package com.lsp.hypersidebar.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.lsp.hypersidebar.prefs.HostPackages
import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.ui.fan.ACTION_FAN_LAUNCH
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import com.lsp.hypersidebar.util.HLog

private const val TAG = "SelfCheck"

/** 自检报告附带的每进程日志条数上限（2026-10-04 P0-2 改为全量：导出写文件，无渲染理由截断） */
private const val LOG_TAIL_PER_PROC = Int.MAX_VALUE

/**
 * 自检报告（2026-09-05 用户拍板：探针双路 + 调试开关实值 + 脱敏配置快照，导出为文本文件）。
 *
 * 背景：远程排障时 hook 进程日志（FanLaunch/CircuitBreaker）第三方 App 因 READ_LOGS
 * 特权墙读不到，但模块 App 能拿到三样决定性信息——探针双路应答（区分"hook 没挂"与
 * "挂了但执行端死"）、DEBUG_RELAY_BLACKHOLE 存储实值（绕开开关 UI 状态陈旧坑，
 * cja 与远程用户先后中招）、脱敏配置快照。
 *
 * 脱敏红线：RelayToken 绝不进入报告（root 代发防伪令牌，导出=广播可伪造）；
 * 快捷方式只出 kind 计数不出 intentUri（载荷含站外令牌）。
 */
object SelfCheck {

    /** 有序 ping 可等待版（ModuleProbe 的同步变体）；3s 超时按无应答兜底。
     *  token 非空时随探针附带——:ui 接收器做令牌握手（code 5 = 不匹配），
     *  用于拆开 ":ui 进程死" 与 ":ui 活但持旧令牌拒收" 这对同症 */
    private suspend fun probe(context: Context, intent: Intent, token: String? = null): Int =
        withTimeout(3_000L) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    RelayToken.attach(intent, token)
                    context.sendOrderedBroadcast(
                    intent, null,
                    object : android.content.BroadcastReceiver() {
                        override fun onReceive(c: Context?, i: Intent?) {
                            if (cont.isActive) cont.resume(resultCode)
                        }
                    },
                    Handler(Looper.getMainLooper()), PrefKeys.PROBE_CODE_DEAD, null, null
                )
            }.onFailure {
                if (cont.isActive) cont.resume(PrefKeys.PROBE_CODE_DEAD)
            }
        }
    }

    private fun decodeProbe(code: Int): String = when (code) {
        PrefKeys.PROBE_CODE_OK -> "正常"
        PrefKeys.PROBE_CODE_DEGRADED -> "已降级"
        PrefKeys.PROBE_CODE_CIRCUIT -> "已熔断"
        PrefKeys.PROBE_CODE_DATA_DEAD -> "数据源死亡"
        PrefKeys.PROBE_CODE_TOKEN_MISMATCH -> "令牌不匹配（:ui 持有旧令牌快照）"
        else -> "无应答"
    }

    private fun f(prefs: SharedPreferences, key: String, default: Float) =
        runCatching { prefs.getFloat(key, default) }.getOrDefault(default)

    private fun i(prefs: SharedPreferences, key: String, default: Int) =
        runCatching { prefs.getInt(key, default) }.getOrDefault(default)

    suspend fun generate(context: Context, service: XposedService?, prefs: SharedPreferences): String =
        withContext(Dispatchers.IO) {
            // 探针显式定向宿主包（接收器 RECEIVER_EXPORTED；executor 附带令牌，
            // 隐式发送可被任意 App 截收——0912 审查，与 sendSync 同类修复）
            val home = probe(context, Intent(PrefKeys.PROBE_ACTION_HOME).setPackage(HostPackages.HOME))
            // 桌面形态：OS4 起 MiuiHome 是 Rust/Flutter 纯 native（APK 里没有 dex，也没有模块
            // Java 半侧）⇒ `com.miui.home` 里**从来没有**能应答 PROBE_ACTION_HOME 的接收器。
            // 不区分形态就会把"结构上不可能有应答"读成"桌面侧 hook 未加载"——2026-11-07
            // OTA 后用户就是被这一行带偏的（真因在 native 锚点，自检正文看不出）。
            val os4NativeHome = runCatching { NativeGateBridge.isOs4NativeLauncher() }.getOrDefault(false)
            val relayToken = RelayToken.read(prefs)
            val executor = probe(
                context,
                Intent(ACTION_FAN_LAUNCH)
                    .setPackage(HostPackages.UI_HOST)
                    .putExtra(PrefKeys.PROBE_EXTRA, true),
                relayToken
            )
            // 与 hook 消费端同门控：release 构建该开关不生效（存量毒值压死）
            val debugSwitch = com.lsp.hypersidebar.BuildConfig.DEBUG && runCatching {
                prefs.getBoolean(PrefKeys.DEBUG_RELAY_BLACKHOLE, false)
            }.getOrDefault(false)
            val framework = runCatching {
                // 自检报告的「框架身份」：名字/版本/API 版本 + 版本号（数值，便于比较构建新旧）
                // + 能力位（PROP_CAP_SYSTEM / PROP_CAP_REMOTE / PROP_RT_API_PROTECTION…）。
                // 尝鲜用户回传时，这一条直接告诉我们他跑的是哪个 LSPosed 构建 —— 云适配最缺的信息。
                val props = service?.frameworkProperties ?: 0L
                "${service?.frameworkName} ${service?.frameworkVersion} " +
                    "(api=${service?.apiVersion}, vc=${service?.frameworkVersionCode}, props=0x${props.toString(16)})"
            }.getOrDefault("未绑定（LSPosed 服务不可达）")
            val customApps = runCatching {
                prefs.getStringSet(PrefKeys.CUSTOM_APPS, emptySet()).orEmpty()
            }.getOrDefault(emptySet())
            val shortcuts = runCatching { ShortcutStore.loadUserShortcuts(prefs) }.getOrDefault(emptyList())
            val kindCounts = shortcuts.groupBy { it.kind.name }
                .entries.joinToString { "${it.key}=${it.value.size}" }
                .ifEmpty { "无" }

            // §11.2 自检 v2：拉一轮三进程日志（广播请求 + 等待回传），报告附
            // 熔断快照与各进程日志尾——远程排障不再依赖 LSPosed 日志页导出
            LogCollector.requestAndAwait(context)
            val procLogs = LogCollector.mergedForExport()
            val statuses = LogCollector.statuses.toMap()

            val verdict = when {
                // 先判形态再判应答：OS4 桌面没有 Java 半侧 ⇒ 触发端必然无应答，
                // 这时"无应答"是常态而不是故障（真因去 native 取证段看 hook installed/refused）。
                os4NativeHome && home == PrefKeys.PROBE_CODE_DEAD -> {
                    val uiOk = if (executor == PrefKeys.PROBE_CODE_OK) "执行端(:ui)正常。" else ""
                    "桌面是 OS4 纯 native 形态（MiuiHome.apk 内含 libapp_launcher.so，无 dex、无模块 Java 半侧）" +
                        "⇒ 触发端探针天然无应答，不代表 hook 没装上。" +
                        "请改看报告末尾「native root 取证」段或 HSBNative 日志：有 hook installed / hooks ready 才算装上；" +
                        "出现 hook refused（cand rejected … prologue / sigscan hits=N）说明桌面构建变了、锚点没跟上。" +
                        uiOk
                }
                home == PrefKeys.PROBE_CODE_DEAD ->
                    "桌面侧 hook 未加载：检查 LSPosed 模块总开关与作用域（系统桌面），重启桌面或手机后重测"
                debugSwitch ->
                    "调试开关（模拟执行端失联）当前开启：所有点击会被人为拦截并最终熔断，请在本模块关于页关闭"
                home == PrefKeys.PROBE_CODE_CIRCUIT ->
                    "触发端已熔断：设置页手动重试或重启手机；反复出现请用 LSPosed 管理器日志搜 mechanism failure"
                executor == PrefKeys.PROBE_CODE_TOKEN_MISMATCH ->
                    "执行端令牌不匹配：:ui 进程持有旧令牌快照而拒收启动广播（探针正常但点击必死）——" +
                        "重启安全中心或重启手机即可恢复"
                executor == PrefKeys.PROBE_CODE_DEAD ->
                    "执行端（安全中心 :ui）未应答：检查作用域（安全中心）与系统全局侧边栏开关"
                home == PrefKeys.PROBE_CODE_OK && executor == PrefKeys.PROBE_CODE_OK ->
                    "探针双路正常、调试开关关闭，配置层面未见异常；若问题仍存在请导出 LSPosed 管理器完整日志"
                else -> "存在降级/数据源死亡等异常状态，请结合 LSPosed 管理器日志进一步定位"
            }

            buildString {
                appendLine("hyperSidebar 自检报告")
                appendLine("生成时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                appendLine("包名: ${context.packageName}")
                appendLine("模块版本: " + runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull() ?: "?")
                appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) / ${Build.MODEL}")
                appendLine("框架: $framework")
                // 换包陈旧（2026-10-31）：LSPosed 的模块 dex 跨安装可能陈旧 ⇒ "新代码不生效、重启才好"
                runCatching { com.lsp.hypersidebar.util.ModuleStaleness.diagnose(context) }
                    .getOrNull()?.let { appendLine("★ $it") }
                // 熄屏归因（2026-10-31）：模块侧没有 WakeLock / FLAG_KEEP_SCREEN_ON（编译期结论），
                // 偶发无法熄屏只可能是宿主或其他模块；这两行让报告里永远带着这个前提。
                appendLine("唤醒锁: 无（本模块不使用 PowerManager/WakeLock/KEEP_SCREEN_ON）")
                appendLine()
                appendLine("== 探针双路 ==")
                appendLine(
                    "桌面形态: " + (if (os4NativeHome) {
                        "OS4 纯 native（Rust/Flutter，无 dex/无 Java 半侧 ⇒ 触发端探针不适用）"
                    } else {
                        "OS2/OS3 Java 桌面（触发端探针有效）"
                    })
                )
                appendLine(
                    "触发端 (com.miui.home): " + (if (os4NativeHome && home == PrefKeys.PROBE_CODE_DEAD) {
                        "不适用（OS4 native 桌面，结构上无接收器）"
                    } else {
                        decodeProbe(home)
                    }) + " (code=$home)"
                )
                appendLine("执行端 (com.miui.securitycenter:ui): ${decodeProbe(executor)} (code=$executor)")
                appendLine()
                appendLine("== 开关与状态 ==")
                appendLine("模块启用开关(应用内): ${runCatching { prefs.getBoolean(PrefKeys.ENABLED, true) }.getOrDefault(true)}")
                appendLine("★ 调试开关(模拟执行端失联): $debugSwitch")
                appendLine()
                appendLine("== 配置快照 ==")
                appendLine("图标大小: ${f(prefs, PrefKeys.ICON_SIZE, LayoutDefaults.ICON_SIZE)} dp" +
                    " / 内圈半径: ${f(prefs, PrefKeys.INNER_RADIUS, LayoutDefaults.INNER_RADIUS)}" +
                    " / 外圈半径: ${f(prefs, PrefKeys.OUTER_RADIUS_MAX, LayoutDefaults.OUTER_RADIUS_MAX)}")
                appendLine("应用数: 外圈 ${i(prefs, PrefKeys.MAX_APPS_OUTER, LayoutDefaults.MAX_APPS_OUTER)}" +
                    " / 内圈 ${i(prefs, PrefKeys.MAX_APPS_INNER, LayoutDefaults.MAX_APPS_INNER)}")
                appendLine("横屏: 图标 ${f(prefs, PrefKeys.LANDSCAPE_ICON_SIZE, LayoutDefaults.LANDSCAPE_ICON_SIZE)} dp" +
                    " / 外圈 ${i(prefs, PrefKeys.LANDSCAPE_MAX_APPS_OUTER, LayoutDefaults.LANDSCAPE_MAX_APPS_OUTER)}" +
                    " / 内圈 ${i(prefs, PrefKeys.LANDSCAPE_MAX_APPS_INNER, LayoutDefaults.LANDSCAPE_MAX_APPS_INNER)}")
                appendLine("呼出停顿: ${i(prefs, PrefKeys.TRIGGER_DWELL_MS, LayoutDefaults.TRIGGER_DWELL_MS)} ms" +
                    " / 呼出距离: ${com.lsp.hypersidebar.hook.GestureThresholds.SWIPE_CONFIRM_DP.toInt()} dp（固定）" +
                    " / 死区: ${f(prefs, PrefKeys.DEAD_ZONE, LayoutDefaults.DEAD_ZONE)} dp")
                appendLine("底角斜滑: ${runCatching {
                    prefs.getBoolean(PrefKeys.CORNER_SWIPE_ENABLED, LayoutDefaults.CORNER_SWIPE_ENABLED)
                }.getOrDefault(LayoutDefaults.CORNER_SWIPE_ENABLED)}" +
                    " / 兜底(底栏收起时): ${runCatching {
                        prefs.getBoolean(PrefKeys.CORNER_SPY_FALLBACK, LayoutDefaults.CORNER_SPY_FALLBACK)
                    }.getOrDefault(LayoutDefaults.CORNER_SPY_FALLBACK)}")
                appendLine("扇形雾化: ${f(prefs, PrefKeys.FAN_FOG_INTENSITY, LayoutDefaults.FAN_FOG_INTENSITY)}" +
                    " / 背景压暗: ${runCatching { prefs.getBoolean(PrefKeys.FAN_DIM_ENABLED, LayoutDefaults.FAN_DIM_ENABLED) }.getOrDefault(LayoutDefaults.FAN_DIM_ENABLED)}")
                appendLine("主题: ${runCatching { prefs.getString(PrefKeys.THEME_MODE, "?") }.getOrNull() ?: "?"}")
                appendLine("固定应用 (${customApps.size}): ${customApps.joinToString()}")
                appendLine("快捷方式: 共 ${shortcuts.size} 个 / 启用 ${shortcuts.count { it.enabled }} 个" +
                    " (类型分布: $kindCounts)")
                appendLine("上次 root 代发结果: " + runCatching {
                    prefs.getString(PrefKeys.LAST_RELAY_RESULT, "无记录")
                }.getOrNull() ?: "无记录")
                appendLine()
                appendLine()
                appendLine("== 进程状态快照 ==")
                if (statuses.isEmpty()) {
                    appendLine("无应答（hook 进程未回传状态——进程死或接收器未注册）")
                } else {
                    statuses.forEach { (proc, json) -> appendLine("$proc: $json") }
                }
                appendLine()
                appendLine("== 最近日志（各进程全量，时间升序；头部附缓冲元信息）==")
                if (procLogs.isEmpty()) {
                    appendLine("无（各进程缓冲未回传）")
                } else {
                    val metas = LogCollector.metas.toMap()
                    procLogs.forEach { (proc, list) ->
                        appendLine("----- [$proc] -----")
                        metas[proc]?.let { appendLine("(缓冲: $it)") }
                        list.takeLast(LOG_TAIL_PER_PROC).forEach { appendLine(it.formatLine()) }
                    }
                }
                appendLine()
                appendLine("== 判读建议 ==")
                appendLine(verdict)
            }
        }

    /** 导出到公共下载目录（MediaStore，无需存储权限）；返回展示用路径描述 */
    suspend fun export(context: Context, content: String): String = withContext(Dispatchers.IO) {
        // 三条导出路径（诊断页 / 日志页 / 使用数据页）都收口在这里 ⇒ native 取证段保证带得上。
        // 桌面进程无模块 Java 半侧（导出永远不会有 [launcher] 段），hook 的活性与命中只能经
        // root 抓一拍；1009 的教训是只加在自检报告里，用户按"日志页导出"就一个字都没拿到。
        val withNative = content + "\n\n== native root 取证（spike 分支）==\n" +
            runCatching { NativeRootProbe.snapshot(context) }
                .getOrElse { "取证失败：${it.message}" } + "\n"
        val fileName = "hypersidebar_selfcheck_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".txt"
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert returned null")
            context.contentResolver.openOutputStream(uri)?.use { it.write(withNative.toByteArray()) }
                ?: error("openOutputStream returned null")
            "下载/$fileName"
        }.getOrElse { e ->
            HLog.w(TAG, "MediaStore export failed, fallback to app dir: ${e.message}")
            // 兜底：应用私有外部目录（文件管理器可达性差但至少能取到）
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: context.filesDir
            java.io.File(dir, fileName).writeText(withNative)
            "应用目录/${dir.name}/$fileName"
        }
    }
}
