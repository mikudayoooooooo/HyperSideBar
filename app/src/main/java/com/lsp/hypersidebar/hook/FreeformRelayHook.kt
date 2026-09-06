package com.lsp.hypersidebar.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.ui.fan.ACTION_FAN_LAUNCH
import com.lsp.hypersidebar.util.RelayToken
import com.lsp.hypersidebar.util.Trace
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
class FreeformRelayHook(
    /** :ui 的 remotePrefs（只读）：取模块下发的跨进程防伪令牌 */
    private val remotePrefs: SharedPreferences
) : BaseHook() {

    override val name = "FreeformRelay"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val strategy = DirectLaunchStrategy(remotePrefs)
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
                // 配置同步通道（批次 2 同款，:ui 补接）：收设置页全量推送，
                // 总开关/横屏 dwell 等 remotePrefs 读取的实时性不再单靠 LSPosed push
                com.lsp.hypersidebar.util.ConfigSync.registerHookSide(ctx)
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
                    // 链路追踪（util/Trace）：relay 意图自带呼出链 id，写入本进程供代发透传
                    val trace = intent.getStringExtra(Trace.EXTRA)
                    Trace.current = trace
                    // 设置页状态探针（§2.5.4）：短路在一切动作分支之前——只应答，不执行任何动作。
                    // 自检报告（2026-09-05）：probe 附带发送端令牌时先做令牌握手——
                    // 真实启动广播在下方 verifyFan 失败即静默 return（resultCode 留 0），
                    // 与 ":ui 进程死" 同症；探针带令牌握手把这对同症拆开（code 5）
                    if (intent.getBooleanExtra(PrefKeys.PROBE_EXTRA, false)) {
                        if (isOrderedBroadcast) {
                            resultCode = if (intent.hasExtra(PrefKeys.RELAY_LAUNCH_EXTRA_TOKEN) &&
                                !RelayToken.verifyFan(intent, RelayToken.read(remotePrefs))
                            ) {
                                PrefKeys.PROBE_CODE_TOKEN_MISMATCH
                            } else {
                                HookProbeState.uiCode()
                            }
                        }
                        return
                    }
                    // 批次 0 安全修复：本接收器 RECEIVER_EXPORTED 注册（发送端是不同 uid，
                    // 无法用非导出），此前零校验——任意 App 都能发广播借 system uid 拉起
                    // 任意应用小窗。改为校验运行期随机令牌（remotePrefs 分发）。
                    // 令牌未配置时按 RelayToken.verifyFan 兼容放行，保证全新安装期主链路可用。
                    if (!RelayToken.verifyFan(intent, RelayToken.read(remotePrefs))) return
                    // 固定应用选择页准入列表请求（设置页 ← :ui，探针同款有序广播信道）：
                    // resultExtras 回带 DataLoader 缓存（同步读，陈旧即触发后台刷新，不阻塞
                    // 应答）。模块进程被 blocklist 拒绝调 getFreeformSuggestionList，只能
                    // 向本进程（system uid）要；空列表也照答（模块侧空判定自行兜底）
                    if (intent.action == PrefKeys.ACTION_REQUEST_SUGGESTIONS) {
                        if (isOrderedBroadcast) {
                            resultCode = 1
                            resultData = null
                            setResultExtras(android.os.Bundle().apply {
                                putStringArrayList(
                                    PrefKeys.EXTRA_SUGGESTION_LIST,
                                    ArrayList(com.lsp.hypersidebar.util.DataLoader.loadApps(ctx))
                                )
                            })
                        }
                        return
                    }
                    // 有序广播存活探测标记（1C，PRD §9.4 ":ui 不存活→toast"）：launcher 发
                    // ordered broadcast，初始 code=0，本接收器置 1；最终回调读 0 = 本进程
                    // 已死或接收器未注册。普通 sendBroadcast（如 AllAppsActivity 面板内启动）
                    // 里 setResult 会抛 RuntimeException——必须先判 isOrderedBroadcast
                    if (isOrderedBroadcast) resultCode = 1
                    when {
                        intent.getBooleanExtra("openPanel", false) -> {
                            Log.i(TAG, "[${trace ?: "-"}] relay: openPanel")
                            strategy.openNativePanel(ctx)
                        }
                        intent.getBooleanExtra("allApps", false) -> {
                            Log.i(TAG, "[${trace ?: "-"}] relay: allApps")
                            strategy.launchAllApps(ctx)
                        }
                        intent.getStringExtra("shortcut") != null -> {
                            val json = intent.getStringExtra("shortcut") ?: return
                            runCatching {
                                val action = com.lsp.hypersidebar.util.ShortcutAction.fromJson(JSONObject(json))
                                Log.i(TAG, "[${trace ?: "-"}] relay: shortcut id=${action.id}")
                                strategy.launchShortcut(ctx, action)
                            }.onFailure { Log.e(TAG, "relay: bad shortcut payload: ${it.message}") }
                        }
                        intent.getStringExtra("pkg") != null -> {
                            val pkg = intent.getStringExtra("pkg") ?: return
                            Log.i(TAG, "[${trace ?: "-"}] relay: freeform pkg=$pkg")
                            strategy.launchFreeform(ctx, pkg)
                        }
                    }
                }
            }
            context.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(ACTION_FAN_LAUNCH)
                    addAction(PrefKeys.ACTION_REQUEST_SUGGESTIONS)
                },
                Context.RECEIVER_EXPORTED
            )
            Log.i(TAG, "ACTION_FAN_LAUNCH/REQUEST_SUGGESTIONS receiver registered (via Application.attach)")
            // root 代发结果回告接收器（2026-09-05）：模块 App 执行完代发（su 链）后把
            // 结果发回本进程——失败 toast 到前台（成功静默，只进日志与自检报告）。
            // 校验用 verifyFan 对 :ui 快照（模块 App 是令牌权威，快照即真值）
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        if (!RelayToken.verifyFan(intent, RelayToken.read(remotePrefs))) return
                        val trace = intent.getStringExtra(Trace.EXTRA)
                        val ok = intent.getBooleanExtra("ok", false)
                        val label = intent.getStringExtra("label") ?: ""
                        val reason = intent.getStringExtra("reason") ?: ""
                        Log.i(TAG, "[${trace ?: "-"}] relay result: ok=$ok label=$label reason=$reason")
                        if (!ok) {
                            runCatching {
                                android.widget.Toast.makeText(
                                    ctx, "快捷方式执行失败：$label（$reason）",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                },
                IntentFilter(PrefKeys.ACTION_RELAY_RESULT),
                Context.RECEIVER_EXPORTED
            )
            Log.i(TAG, "ACTION_RELAY_RESULT receiver registered (via Application.attach)")
        } catch (e: Throwable) {
            Log.e(TAG, "receiver registration failed: ${e.message}", e)
        }
    }
}
