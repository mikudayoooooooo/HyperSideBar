package com.lsp.hypersidebar.hook

import android.content.SharedPreferences
import android.util.Log
import com.lsp.hypersidebar.prefs.PrefKeys

private const val TAG = "CircuitBreaker"

/**
 * 进程级熔断器（1C 轮二，PRD §9.4"连续失败 5 次 → 进程生命周期内熔断"）。
 *
 * 只计**机制性失败**（:ui 执行端失联、fan 窗口装配失败）——单应用启动失败（无小窗
 * 资格等）是数据面问题，不算。连续 ≥[THRESHOLD] 次 → 本进程停止一切侵入：
 * - launcher：触摸全透传（原生返回零干扰，PRD"原生返回优先原则"）；
 * - :ui：恢复原生侧边栏（宿主经 [onTripped] 注入，走降级动作）。
 * 同时 toast + remotePrefs 写状态键（设置页状态卡数据源）。键按进程分离
 * （[processKey]）：一端重启只清自己的键，另一端的熔断状态不被误清。
 *
 * 不自愈："连续"语义由成功清零（[recordSuccess]）承载；熔断后的恢复=重启本进程
 * 或设置页手动重试（写 [PrefKeys.CIRCUIT_RESET_AT]，[maybeManualReset] 比较
 * resetAt > 本端熔断时刻即解除）。
 */
class CircuitBreaker(
    private val processKey: String,
    private val remotePrefs: SharedPreferences
) {

    @Volatile
    var open = false
        private set
    private var consecutive = 0
    @Volatile private var trippedAtMs = 0L

    /** 熔断动作由宿主 hook 注入（launcher=toast；:ui=enterDegradedMode） */
    var onTripped: ((reason: String) -> Unit)? = null

    fun recordFailure(reason: String) {
        if (open) return
        consecutive++
        Log.w(TAG, "mechanism failure #$consecutive/$THRESHOLD ($processKey): $reason")
        if (consecutive < THRESHOLD) return
        open = true
        trippedAtMs = System.currentTimeMillis()
        Log.e(TAG, "CIRCUIT OPEN ($processKey): $reason — 停止侵入，等待重启或手动重试")
        runCatching {
            remotePrefs.edit().putBoolean(processKey, true).commit()
        }.onFailure { Log.w(TAG, "circuit status write failed: ${it.message}") }
        onTripped?.invoke(reason)
    }

    fun recordSuccess() {
        if (open || consecutive == 0) return
        consecutive = 0
        Log.i(TAG, "success resets consecutive failures ($processKey)")
    }

    /** 廉价路径：仅在熔断期间被调用（launcher=每次边缘 DOWN；:ui=2s 看门狗循环）。 */
    fun maybeManualReset() {
        if (!open) return
        val resetAt = runCatching { remotePrefs.getLong(PrefKeys.CIRCUIT_RESET_AT, 0L) }
            .getOrDefault(0L)
        if (resetAt > trippedAtMs) {
            Log.i(TAG, "manual reset accepted ($processKey): resetAt=$resetAt > trippedAt=$trippedAtMs")
            forceReset()
        }
    }

    /** 解除熔断并发布状态（init 时也用它发布"本进程未熔断"，清掉陈旧键） */
    fun forceReset() {
        open = false
        consecutive = 0
        trippedAtMs = 0L
        runCatching { remotePrefs.edit().putBoolean(processKey, false).commit() }
    }

    companion object {
        const val THRESHOLD = 5
    }
}
