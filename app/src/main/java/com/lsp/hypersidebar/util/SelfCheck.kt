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

private const val TAG = "SelfCheck"

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
            val home = probe(context, Intent(PrefKeys.PROBE_ACTION_HOME))
            val relayToken = RelayToken.read(prefs)
            val executor = probe(
                context,
                Intent(ACTION_FAN_LAUNCH).putExtra(PrefKeys.PROBE_EXTRA, true),
                relayToken
            )
            val debugSwitch = runCatching {
                prefs.getBoolean(PrefKeys.DEBUG_RELAY_BLACKHOLE, false)
            }.getOrDefault(false)
            val framework = runCatching {
                "${service?.frameworkName} ${service?.frameworkVersion} (api=${service?.apiVersion})"
            }.getOrDefault("未绑定（LSPosed 服务不可达）")
            val customApps = runCatching {
                prefs.getStringSet(PrefKeys.CUSTOM_APPS, emptySet()).orEmpty()
            }.getOrDefault(emptySet())
            val shortcuts = runCatching { ShortcutStore.loadUserShortcuts(prefs) }.getOrDefault(emptyList())
            val kindCounts = shortcuts.groupBy { it.kind.name }
                .entries.joinToString { "${it.key}=${it.value.size}" }
                .ifEmpty { "无" }

            val verdict = when {
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
                appendLine()
                appendLine("== 探针双路 ==")
                appendLine("触发端 (com.miui.home): ${decodeProbe(home)} (code=$home)")
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
                    " / 死区: ${f(prefs, PrefKeys.DEAD_ZONE, LayoutDefaults.DEAD_ZONE)} dp")
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
                appendLine("== 判读建议 ==")
                appendLine(verdict)
            }
        }

    /** 导出到公共下载目录（MediaStore，无需存储权限）；返回展示用路径描述 */
    suspend fun export(context: Context, content: String): String = withContext(Dispatchers.IO) {
        val fileName = "hypersidebar_selfcheck_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".txt"
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert returned null")
            context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                ?: error("openOutputStream returned null")
            "下载/$fileName"
        }.getOrElse { e ->
            Log.w(TAG, "MediaStore export failed, fallback to app dir: ${e.message}")
            // 兜底：应用私有外部目录（文件管理器可达性差但至少能取到）
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: context.filesDir
            java.io.File(dir, fileName).writeText(content)
            "应用目录/${dir.name}/$fileName"
        }
    }
}
