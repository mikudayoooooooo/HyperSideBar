package com.lsp.hypersidebar.util

import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.lsp.hypersidebar.prefs.PrefKeys
import java.util.UUID

/**
 * 跨进程广播防伪令牌（迭代五批次 0 安全修复）。
 *
 * 背景（09-02 安全审查两个严重项）：
 * - FreeformRelayHook（:ui 进程，RECEIVER_EXPORTED 动态注册）此前**零校验**——任意 App
 *   都能发 ACTION_FAN_LAUNCH 广播，借 system uid 拉起任意应用小窗；
 * - ShortcutRelayReceiver（模块进程，manifest exported）用**源码硬编码令牌**校验——
 *   APK 反编译即可读出，等同于零校验，可借模块 root 身份 `am start` 任意非导出组件（提权）。
 *
 * 方案：令牌改为运行期随机生成，存 remotePrefs（模块进程可写、hook 进程只读但可读，
 * 且 XposedInit 已注册变更监听，下发后实时可见），发送端随广播携带，接收端比对。
 *
 * 分档原因：
 * - [verifyFan]（:ui 中继，最坏后果=拉起别的应用小窗）：令牌尚未下发时兼容放行，
 *   避免"全新安装、用户还没打开过模块 App"期间主链路失灵；令牌一旦下发即严格比对。
 * - [verifyRelay]（模块 root 代发，最坏后果=提权）：严格，令牌缺失一律拒绝。
 */
object RelayToken {

    private const val TAG = "RelayToken"

    /**
     * 模块进程内存缓存。必要性：ShortcutRelayReceiver 是 BroadcastReceiver，无法同步
     * 拿 remotePrefs（XposedServiceHelper 为异步绑定），只能复用 Activity 侧同步时的缓存。
     */
    @Volatile
    private var cached: String? = null

    /**
     * 模块进程调用：确保令牌已生成并缓存，返回当前令牌。
     * hook 进程**禁止调用**（remotePrefs 只读，写抛 "Read only implementation"）。
     */
    fun sync(prefs: SharedPreferences): String? = runCatching {
        var token = prefs.getString(PrefKeys.RELAY_TOKEN, null)
        if (token.isNullOrEmpty()) {
            token = UUID.randomUUID().toString()
            prefs.edit().putString(PrefKeys.RELAY_TOKEN, token).commit()
            Log.i(TAG, "relay token provisioned")
        }
        cached = token
        token
    }.getOrElse {
        Log.w(TAG, "relay token sync failed: ${it.message}")
        null
    }

    /** 当前令牌（收发两端共用）；从未同步过时返回 null。 */
    fun current(): String? = cached

    /** hook 进程调用：读取模块下发的令牌（remotePrefs 只读路径）。 */
    fun read(prefs: SharedPreferences): String? =
        runCatching { prefs.getString(PrefKeys.RELAY_TOKEN, null) }.getOrNull()

    /** 发送端：为广播附上令牌。令牌未就绪时不附，由接收端按分档规则处理。 */
    fun attach(intent: Intent, token: String?) {
        if (!token.isNullOrEmpty()) intent.putExtra(PrefKeys.RELAY_LAUNCH_EXTRA_TOKEN, token)
    }

    /**
     * 接收端（:ui 中继）：令牌未下发时兼容放行，下发后严格比对。
     * @param expected 本端从 remotePrefs 读到的期望值（null=尚未下发）
     */
    fun verifyFan(intent: Intent, expected: String?): Boolean {
        if (expected.isNullOrEmpty()) return true
        val got = intent.getStringExtra(PrefKeys.RELAY_LAUNCH_EXTRA_TOKEN)
        if (got.isNullOrEmpty() || got != expected) {
            Log.w(TAG, "fan relay rejected: bad token")
            return false
        }
        return true
    }

    /** 接收端（模块 root 代发）：严格——无有效令牌不得借 root 启动任何组件。 */
    fun verifyRelay(intent: Intent): Boolean {
        val expected = cached
        val got = intent.getStringExtra(PrefKeys.RELAY_LAUNCH_EXTRA_TOKEN)
        if (expected.isNullOrEmpty() || got.isNullOrEmpty() || got != expected) {
            Log.w(TAG, "root relay rejected: bad token (provisioned=${!expected.isNullOrEmpty()})")
            return false
        }
        return true
    }
}
