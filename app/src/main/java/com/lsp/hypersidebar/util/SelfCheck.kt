package com.lsp.hypersidebar.util

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.lsp.hypersidebar.prefs.HostPackages
import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.prefs.PrefsFiles
import com.lsp.hypersidebar.prefs.PropNames
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

            val diskVc = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            }.getOrDefault(com.lsp.hypersidebar.BuildConfig.VERSION_CODE.toLong())
            val appVc = com.lsp.hypersidebar.BuildConfig.VERSION_CODE.toLong()
            val acks = ConfigSync.ackSnapshot()
            val staleHosts = ConfigSync.staleHostPackages(diskVc)

            val verdict = when {
                // 配置/令牌/旧 dex 是比探针更上游的失败面，先判（2026-11-11）。
                RemotePrefsBridge.prefs == null ->
                    "LSPosed 服务未绑定：本页写入只落本地回退，hook 进程收不到任何改动。" +
                        "先确认模块已在 LSPosed 中启用、作用域包含三个宿主，再重启本应用。"
                !RelayToken.authorityReady() ->
                    "令牌权威未就绪（仅镜像或完全未就绪）：root 代发/解冻/恢复可能被拒。" +
                        "打开一次设置页等桥绑定，或检查 LSPosed 服务；镜像只保首次可用性。"
                staleHosts.isNotEmpty() || appVc != diskVc ->
                    "检测到宿主/本进程仍在跑旧 dex（运行 vc=$appVc，磁盘 vc=$diskVc，陈旧宿主=$staleHosts）" +
                        "——更新 APK 后 SystemUI 功能不生效（含点空白忽略键盘）通常就是这个；" +
                        "重启对应宿主或设备，不要先改逻辑。"
                acks.isEmpty() ->
                    "本次启动后没有任何 hook 应答配置同步（SYNC_ACK）：hook 未注入/宿主冻结/作用域缺失。" +
                        "先看 LSPosed 是否注入（modules_*.log 有 hypersidebar），再重启宿主。"
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
                appendLine("== 配置通道 ==")
                appendLine(
                    "LSPosed 绑定: " + if (RemotePrefsBridge.prefs != null) {
                        "已绑定"
                    } else {
                        "未绑定（写入只落本地回退，hook 收不到）"
                    }
                )
                appendLine(
                    "令牌: " + when {
                        RelayToken.authorityReady() -> "已就绪（权威）"
                        RelayToken.isReady() -> "仅镜像（bind 未完成）"
                        else -> "未就绪（root 代发会拒绝）"
                    }
                )
                if (acks.isEmpty()) {
                    appendLine("配置同步回执: 无（本次启动后没有 hook 应答 SYNC_ACK）")
                } else {
                    acks.values.forEach { a ->
                        appendLine(
                            "配置同步回执: ${a.proc ?: "?"} keys=${a.keys} vc=${a.vc}" +
                                "（${(android.os.SystemClock.elapsedRealtime() - a.at) / 1000}s 前）"
                        )
                    }
                }
                appendLine(
                    "进程版本: " + (if (acks.isEmpty()) "无" else acks.values.joinToString {
                        "${it.proc ?: "?"} vc=${it.vc}"
                    }) + "（磁盘 vc=$diskVc）"
                )
                if (staleHosts.isNotEmpty() || appVc != diskVc) {
                    appendLine("陈旧宿主: $staleHosts（运行旧 dex，需重启对应宿主/应用）")
                }
                appendLine()
                appendLine("== 运行环境 ==")
                runCatching {
                    val launcherVc = context.packageManager
                        .getPackageInfo(HostPackages.HOME, 0).longVersionCode
                    SelfCheckEnv.lines(
                        rom = Build.VERSION.INCREMENTAL ?: "?",
                        os4 = com.lsp.hypersidebar.hook.Os4Marker.romIsOs4(),
                        launcherVc = launcherVc,
                        moduleVc = diskVc,
                        pilferProp = SysProps.get(PropNames.CORNER_PILFER),
                        sysjudgeProp = SysProps.get(PropNames.CORNER_SYSJUDGE),
                        gateMarker = NativeGateBridge.markerContent(),
                        acks = acks,
                        nowElapsedMs = android.os.SystemClock.elapsedRealtime()
                    ).forEach { appendLine(it) }
                }.onFailure { appendLine("运行环境读取失败: ${it.message}") }
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
                val localPrefs = context.getSharedPreferences(PrefsFiles.APP_LOCAL, Context.MODE_PRIVATE)
                appendLine(
                    "缓存: labels=${localPrefs.getString(PrefKeys.CACHED_LABELS, null)?.length ?: 0}B" +
                        " suggestions=${localPrefs.getString(PrefKeys.CACHED_SUGGESTIONS, null)?.length ?: 0}B" +
                        " sectionLetters=${localPrefs.getString(PrefKeys.CACHED_SECTION_LETTERS, null)?.length ?: 0}B"
                )
                appendLine()
                appendLine("== native 配置 ==")
                appendLine("hsb.conf: " + (ConfigFileBridge.confPath() ?: "路径构造失败"))
                val conf = runCatching { ConfigFileBridge.readConf() }.getOrNull()
                if (conf.isNullOrBlank()) {
                    appendLine("（文件不存在或为空 ⇒ native 回落属性/缺省）")
                } else {
                    conf.lineSequence().take(40).forEach { appendLine("  $it") }
                }
                appendLine(
                    "persist.hsb.native.hook=" +
                        SysProps.get(NativeGateBridge.PROP_HOOK).ifEmpty { "<unset>" }
                )
                appendLine(
                    "persist.hsb.corner.supp=" +
                        SysProps.get(CornerGeomBridge.PROP_CORNER_SUPP).ifEmpty { "<unset>" }
                )
                appendLine(
                    "debug.hsb.corner.pilfer=" +
                        SysProps.get(PropNames.CORNER_PILFER).ifEmpty { "<unset>" }
                )
                appendLine()
                appendLine("== 模块安装 ==")
                runCatching {
                    val pi = context.packageManager.getPackageInfo(context.packageName, 0)
                    appendLine("APK: ${pi.applicationInfo?.sourceDir}")
                    appendLine(
                        "安装时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            .format(Date(pi.lastUpdateTime))
                    )
                    appendLine("versionCode: ${pi.longVersionCode}")
                }.onFailure { appendLine("安装信息读取失败: ${it.message}") }
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

    /**
     * 紧凑环境头（2026-11-25，用户提问「不同位置导出的日志有差异吗」的落地）。
     *
     * 背景：三条导出路径（自检报告 / 日志页 / 使用统计）共用 [export] 挂 native 取证段，
     * 但**只有自检报告**带环境头。用户点「日志页导出」时，拿到的文件里没有桌面
     * versionCode、没有 2a 分档判决、没有回执年龄、没有 native gate marker ——
     * 而这些恰是 issue #13 定性必需的信息，于是同一台设备按不同按钮导出，结论不一样
     * （与 1009「日志页导出没有 hook 状态」同一类问题）。
     *
     * 本函数只放**判读必需且廉价**的项：版本族 / ROM / 桌面分档 / native 总闸 / 三宿主回执。
     * 配置快照、探针双路等重项仍只在完整自检报告里（导出体积与耗时考虑）。
     */
    fun compactHeader(context: Context): String = buildString {
        appendLine("== 环境与状态（三条导出路径共用；缺这段就无法判读版本/分档/回执）==")
        runCatching {
            val diskVc = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            appendLine("模块: ${com.lsp.hypersidebar.BuildConfig.VERSION_NAME}" +
                " 运行vc=${com.lsp.hypersidebar.BuildConfig.VERSION_CODE} 磁盘vc=$diskVc")
        }.onFailure { appendLine("模块版本: 读取失败 ${it.message}") }
        runCatching {
            appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) / ${Build.MODEL}" +
                " / ${Build.VERSION.INCREMENTAL}")
        }
        runCatching {
            val launcherVc = context.packageManager.getPackageInfo(HostPackages.HOME, 0).longVersionCode
            val acks = ConfigSync.ackSnapshot()
            SelfCheckEnv.lines(
                rom = Build.VERSION.INCREMENTAL ?: "?",
                os4 = com.lsp.hypersidebar.hook.Os4Marker.romIsOs4(),
                launcherVc = launcherVc,
                moduleVc = com.lsp.hypersidebar.BuildConfig.VERSION_CODE.toLong(),
                pilferProp = SysProps.get(PropNames.CORNER_PILFER),
                sysjudgeProp = SysProps.get(PropNames.CORNER_SYSJUDGE),
                gateMarker = NativeGateBridge.markerContent(),
                acks = acks,
                nowElapsedMs = android.os.SystemClock.elapsedRealtime()
            ).forEach { appendLine(it) }
        }.onFailure { appendLine("运行环境: 读取失败 ${it.message}") }
    }
    /** 导出到公共下载目录（MediaStore，无需存储权限）；返回展示用路径描述 */
    suspend fun export(
        context: Context,
        content: String,
        includeHeader: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        // 日志页/统计页导出默认补环境头（2026-11-25）：同一台设备按不同按钮导出，
        // 结论不该不一样。自检报告自己已带完整环境段，故传 false 避免重复。
        val head = if (includeHeader) compactHeader(context) + "\n" else ""
        // 三条导出路径（诊断页 / 日志页 / 使用数据页）都收口在这里 ⇒ native 取证段保证带得上。
        // 桌面进程无模块 Java 半侧（导出永远不会有 [launcher] 段），hook 的活性与命中只能经
        // root 抓一拍；1009 的教训是只加在自检报告里，用户按"日志页导出"就一个字都没拿到。
        val withNative = head + content + "\n\n== native root 取证（spike 分支）==\n" +
            runCatching { NativeRootProbe.snapshot(context) }
                .getOrElse { "取证失败：${it.message}" } + "\n"
        val fileName = "hypersidebar_selfcheck_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".txt"
        DownloadsWriter.write(context, fileName, "text/plain", withNative)
    }
}
