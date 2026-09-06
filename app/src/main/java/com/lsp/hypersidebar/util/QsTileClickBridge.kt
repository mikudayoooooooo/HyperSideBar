package com.lsp.hypersidebar.util

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lsp.hypersidebar.prefs.PrefKeys
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * QS 磁贴 SystemUI 直点桥的发送端（批次 3，配对 hook/SystemUiHook）。
 *
 * 有序广播进 SystemUI 进程，hook 接收器从数据层直点磁贴并回 resultCode：
 * 1=已点击；0=hook 不在（未加作用域/ROM 漂移）/磁贴不在当前 QS/异常。
 * 发送端据结果决定是否走 root 三连兜底（expand-settings，QS 闪现）。
 */
object QsTileClickBridge {

    private const val TAG = "QsTileClick"
    private const val RESULT_TIMEOUT_MS = 2000L

    /**
     * 发送点击指令并阻塞等待 SystemUI 侧应答（快路径 ~10ms；hook 不在时立即返回 false）。
     * 须在后台线程调用。token = 调用方进程各自读取的 remotePrefs 令牌
     * （:ui=RelayToken.read(remotePrefs)，模块=RelayToken.current()）。
     */
    fun sendBlocking(context: Context, componentName: String, token: String?): Boolean {
        val latch = CountDownLatch(1)
        var delivered = false
        val intent = Intent(PrefKeys.QS_TILE_CLICK_ACTION)
            .putExtra(PrefKeys.QS_TILE_CLICK_EXTRA, componentName)
        RelayToken.attach(intent, token)
        try {
            context.sendOrderedBroadcast(
                intent,
                null,
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        delivered = resultCode == SystemUiHookResult.RESULT_CLICKED
                        latch.countDown()
                    }
                },
                Handler(Looper.getMainLooper()),
                Activity.RESULT_OK, null, null
            )
        } catch (e: Exception) {
            Log.w(TAG, "send failed: ${e.message}")
            return false
        }
        latch.await(RESULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        Log.i(TAG, "click sent: cn=$componentName delivered=$delivered")
        return delivered
    }
}

/** SystemUiHook 的 resultCode 语义（隔离对象避免 util 反向依赖 hook 包）。 */
object SystemUiHookResult {
    const val RESULT_CLICKED = 1
}
