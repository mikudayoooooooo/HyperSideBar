package com.lsp.hypersidebar.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.ui.fan.ACTION_FAN_LAUNCH
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createAfterHook
import org.json.JSONObject

private const val TAG = "FreeformRelay"

/**
 * securitycenter:ui 侧的执行转发接收器（B 链路 :ui 端）。
 *
 * launcher 进程（边缘手势通道）的 fan 选中项经 ACTION_FAN_LAUNCH 广播到此，
 * 由 DirectLaunchStrategy 在本进程执行（FreeformLauncher / ShortcutLauncher 仅在 :ui 验证可用）。
 *
 * 注册时机：hook Application.attach 之后立即注册——修 spike 实测的注册延迟问题
 * （原 init 重试/懒注册路径在无人触摸小白条时收不到注册时机，约 1 分钟内广播丢失）。
 */
class FreeformRelayHook : BaseHook() {

    override val name = "FreeformRelay"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val strategy = DirectLaunchStrategy()
    private var registered = false

    override fun init() {
        Log.i(TAG, "=== FreeformRelayHook init ===")
        val hooked = MethodFinder.fromClass("android.app.Application")
            .filterByName("attach")
            .filterByParamTypes(Context::class.java)
            .firstOrNull()
            ?.createAfterHook {
                val ctx = it.args[0] as? Context ?: return@createAfterHook
                registerReceiver(ctx)
            }
        if (hooked == null) {
            Log.e(TAG, "Application.attach hook failed（B 链路不可用，边缘通道选中将无响应）")
        }
    }

    private fun registerReceiver(context: Context) {
        if (registered) return
        registered = true
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    // 设置页状态探针（§2.5.4）：短路在一切动作分支之前——只应答，不执行任何动作
                    if (intent.getBooleanExtra(PrefKeys.PROBE_EXTRA, false)) {
                        if (isOrderedBroadcast) resultCode = HookProbeState.uiCode()
                        return
                    }
                    // 有序广播存活探测标记（1C，PRD §9.4 ":ui 不存活→toast"）：launcher 发
                    // ordered broadcast，初始 code=0，本接收器置 1；最终回调读 0 = 本进程
                    // 已死或接收器未注册。普通 sendBroadcast（如 AllAppsActivity 面板内启动）
                    // 里 setResult 会抛 RuntimeException——必须先判 isOrderedBroadcast
                    if (isOrderedBroadcast) resultCode = 1
                    when {
                        intent.getBooleanExtra("openPanel", false) -> {
                            Log.i(TAG, "relay: openPanel")
                            strategy.openNativePanel(ctx)
                        }
                        intent.getBooleanExtra("allApps", false) -> {
                            Log.i(TAG, "relay: allApps")
                            strategy.launchAllApps(ctx)
                        }
                        intent.getStringExtra("shortcut") != null -> {
                            val json = intent.getStringExtra("shortcut") ?: return
                            runCatching {
                                val action = com.lsp.hypersidebar.util.ShortcutAction.fromJson(JSONObject(json))
                                Log.i(TAG, "relay: shortcut id=${action.id}")
                                strategy.launchShortcut(ctx, action)
                            }.onFailure { Log.e(TAG, "relay: bad shortcut payload: ${it.message}") }
                        }
                        intent.getStringExtra("pkg") != null -> {
                            val pkg = intent.getStringExtra("pkg") ?: return
                            Log.i(TAG, "relay: freeform pkg=$pkg")
                            strategy.launchFreeform(ctx, pkg)
                        }
                    }
                }
            }
            context.registerReceiver(receiver, IntentFilter(ACTION_FAN_LAUNCH), Context.RECEIVER_EXPORTED)
            Log.i(TAG, "ACTION_FAN_LAUNCH receiver registered (via Application.attach)")
        } catch (e: Throwable) {
            Log.e(TAG, "receiver registration failed: ${e.message}", e)
        }
    }
}
