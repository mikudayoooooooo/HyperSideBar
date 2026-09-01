package com.lsp.hypersidebar.ui.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.ui.fan.ACTION_FAN_LAUNCH

/** 探针结果（code 取 [PrefKeys.PROBE_CODE_*]；null = 检测中） */
internal data class ModuleProbeState(
    val launcher: Int?,   // 触发端（com.miui.home，边缘手势 + 扇形窗口）
    val executor: Int?    // 执行端（com.miui.securitycenter:ui，启动执行 + 横屏通道）
)

/**
 * hook 状态探针（§2.5.4）：对两个宿主进程各发一次有序 ping，hook 侧接收器应答
 * resultCode（0 无应答/1 正常/2 降级/3 熔断）。无匹配接收器时系统立即回初始码 0——
 * 恰好就是"无应答"语义。调用时机：设置页打开（组合进入）+ 手动重试后。
 */
internal class ModuleProbe(private val context: Context) {

    var state by mutableStateOf(ModuleProbeState(null, null))
        private set

    fun probe() {
        state = ModuleProbeState(null, null)
        // 执行端：复用 :ui 的 ACTION_FAN_LAUNCH 接收器 + 探针标记 extra
        //（接收器探针分支短路在动作分发之前，只应答不执行）
        sendProbe(Intent(ACTION_FAN_LAUNCH).putExtra(PrefKeys.PROBE_EXTRA, true)) { code ->
            state = state.copy(executor = code)
        }
        // 触发端：home 进程的独立探针 action（EdgeGestureHook 经 Application.attach 注册）
        sendProbe(Intent(PrefKeys.PROBE_ACTION_HOME)) { code ->
            state = state.copy(launcher = code)
        }
    }

    private fun sendProbe(intent: Intent, onCode: (Int) -> Unit) {
        runCatching {
            context.sendOrderedBroadcast(
                intent, null,
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) = onCode(resultCode)
                },
                Handler(Looper.getMainLooper()), PrefKeys.PROBE_CODE_DEAD, null, null
            )
        }.onFailure { onCode(PrefKeys.PROBE_CODE_DEAD) }
    }
}
