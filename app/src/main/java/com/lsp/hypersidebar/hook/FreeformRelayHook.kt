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
import com.lsp.hypersidebar.util.HLog

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

    /**
     * 本进程注册的两个接收器 + 注册用的原始 ctx。
     *
     * ⚠ **必须存字段**（2026-10-16 热重载审计）：原先第一个是局部 `val receiver`（出了函数就
     * 只剩 AMS 的 receiver 表持有）、第二个是匿名对象（连局部变量都没有）——热重载时**无处注销**，
     * 旧代 classloader 被钉住；新代再注册一份就会每次启动转发被处理两遍（fan 启动非幂等：
     * 一次呼出开两个小窗/两次 startShortcut）。
     */
    private var launchReceiver: BroadcastReceiver? = null
    private var resultReceiver: BroadcastReceiver? = null
    private var registeredCtx: Context? = null

    override fun init() {
        HLog.i(TAG, "=== FreeformRelayHook init ===")
        val hooked = MethodFinder.fromClass("android.app.Application")
            .filterByName("attach")
            .filterByParamTypes(Context::class.java)
            .firstOrNull()
            ?.createAfterHook {
                val ctx = it.args[0] as? Context ?: return@createAfterHook
                armHostSide(ctx, "Application.attach")
            }
        if (hooked == null) {
            HLog.e(TAG, "Application.attach hook failed（B 链路不可用，边缘通道选中将无响应）")
        }
    }

    /**
     * 热重载重挂（API 102；与 [releaseForReload] 严格对称）。
     *
     * 为什么需要：:ui 的注册体挂在 `Application.attach` 钩子里，而热重载**不重放 attach**
     * ⇒ 释放后不显式重挂，fan 启动转发 / root 代发结果回告 / 配置同步 / 日志回传会一直死到
     * 宿主重启（症状：边缘手势选中后小窗不开、设置改了不生效、自检报告 [ui] 段空白）。
     * 与 attach 路径**共用同一段实现**（[armHostSide]），没有第二份注册代码。
     */
    override fun rearmHostSide(ctx: Context) = armHostSide(ctx, "hot reload")

    /** 宿主侧装配的唯一实现：attach（冷启动）与热重载重挂都走这里，全部幂等。 */
    private fun armHostSide(ctx: Context, via: String) {
        registerReceiver(ctx, via)
        runCatching {
            com.lsp.hypersidebar.anchor.AnchorResolver.completeWithContext(ctx)
            HLog.i(TAG, "anchor after arm($via): ${com.lsp.hypersidebar.anchor.AnchorResolver.stateLine()}")
        }.onFailure { HLog.w(TAG, "anchor complete failed: ${it.message}") }
        // 配置同步通道（批次 2 同款，:ui 补接）：收设置页全量推送，
        // 总开关/横屏 dwell 等 remotePrefs 读取的实时性不再单靠 LSPosed push
        runCatching { com.lsp.hypersidebar.util.ConfigSync.registerHookSide(ctx) }
            .onFailure { HLog.w(TAG, "config sync register failed: ${it.message}") }
        // 日志拉取回传（§11.2）：模块 App 请求时回传 HLog 缓冲；:ui 的熔断快照
        // 在 TurboLayout（同进程），经其伴生句柄取，未初始化时给空快照
        runCatching {
            com.lsp.hypersidebar.util.LogDumpBridge.register(
                ctx,
                statusProvider = { TurboLayout.breakerSnapshot() },
                statsProvider = { com.lsp.hypersidebar.util.StatsRecorder.dump() }
            )
        }.onFailure { HLog.w(TAG, "log dump register failed: ${it.message}") }
        // 数据记录（§11.3）：注入宿主 context（聚合结构落盘宿主本地 prefs）
        runCatching { com.lsp.hypersidebar.util.StatsRecorder.init(ctx) }
            .onFailure { HLog.w(TAG, "stats init failed: ${it.message}") }
    }

    private fun registerReceiver(context: Context, via: String) {
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
                            // 附带 OS4 侧边栏在场事实：状态卡据此把桌面探针无应答判为
                            // OS4 预期形态（桌面纯 native 无 Java hook）而非通道异常
                            runCatching {
                                val extras = android.os.Bundle()
                                extras.putBoolean("os4", Os4Marker.sidebarPresent)
                                setResultExtras(extras)
                            }
                        }
                        return
                    }
                    // 批次 0 安全修复：本接收器 RECEIVER_EXPORTED 注册（发送端是不同 uid，
                    // 无法用非导出），此前零校验——任意 App 都能发广播借 :ui 特权拉起
                    // 任意应用小窗。改为校验运行期随机令牌（remotePrefs 分发）。
                    // 令牌未配置时按 RelayToken.verifyFan 兼容放行，保证全新安装期主链路可用。
                    if (!RelayToken.verifyFan(intent, RelayToken.read(remotePrefs))) return
                    // 固定应用选择页准入列表请求（设置页 ← :ui，探针同款有序广播信道）：
                    // resultExtras 回带 DataLoader 缓存（同步读，陈旧即触发后台刷新，不阻塞
                    // 应答）。模块进程被 blocklist 拒绝调 getFreeformSuggestionList，只能
                    // 向本进程（:ui，特权宿主）要；空列表也照答（模块侧空判定自行兜底）
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
                        intent.getBooleanExtra(PrefKeys.FAN_EXTRA_OPEN_PANEL, false) -> {
                            HLog.i(TAG, "[${trace ?: "-"}] relay: openPanel")
                            strategy.openNativePanel(ctx)
                        }
                        intent.getBooleanExtra(PrefKeys.FAN_EXTRA_ALL_APPS, false) -> {
                            val source = intent.getStringExtra(PrefKeys.FAN_EXTRA_SOURCE)
                            HLog.i(TAG, "[${trace ?: "-"}] relay: allApps src=${source ?: "-"}")
                            strategy.launchAllApps(ctx, source)
                        }
                        intent.getStringExtra(PrefKeys.FAN_EXTRA_SHORTCUT) != null -> {
                            val json = intent.getStringExtra(PrefKeys.FAN_EXTRA_SHORTCUT) ?: return
                            runCatching {
                                val action = com.lsp.hypersidebar.util.ShortcutAction.fromJson(JSONObject(json))
                                HLog.i(TAG, "[${trace ?: "-"}] relay: shortcut id=${action.id}")
                                strategy.launchShortcut(ctx, action)
                            }.onFailure { HLog.e(TAG, "relay: bad shortcut payload: ${it.message}") }
                        }
                        intent.getStringExtra(PrefKeys.FAN_EXTRA_PKG) != null -> {
                            val pkg = intent.getStringExtra(PrefKeys.FAN_EXTRA_PKG) ?: return
                            val source = intent.getStringExtra(PrefKeys.FAN_EXTRA_SOURCE)
                            HLog.i(TAG, "[${trace ?: "-"}] relay: freeform pkg=$pkg src=${source ?: "-"}")
                            strategy.launchFreeform(ctx, pkg, source)
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
            launchReceiver = receiver
            registeredCtx = context
            HLog.i(TAG, "ACTION_FAN_LAUNCH/REQUEST_SUGGESTIONS receiver registered (via $via)")
            // root 代发结果回告接收器（2026-09-05）：模块 App 执行完代发（su 链）后把
            // 结果发回本进程——失败 toast 到前台（成功静默，只进日志与自检报告）。
            // 校验用 verifyFan 对 :ui 快照（模块 App 是令牌权威，快照即真值）
            val resultRecv = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (!RelayToken.verifyFan(intent, RelayToken.read(remotePrefs))) return
                    val trace = intent.getStringExtra(Trace.EXTRA)
                    val ok = intent.getBooleanExtra(PrefKeys.RELAY_RESULT_EXTRA_OK, false)
                    val label = intent.getStringExtra(PrefKeys.RELAY_RESULT_EXTRA_LABEL) ?: ""
                    val reason = intent.getStringExtra(PrefKeys.RELAY_RESULT_EXTRA_REASON) ?: ""
                    HLog.i(TAG, "[${trace ?: "-"}] relay result: ok=$ok label=$label reason=$reason")
                    if (!ok) {
                        runCatching {
                            android.widget.Toast.makeText(
                                ctx, "快捷方式执行失败：$label（$reason）",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
            context.registerReceiver(
                resultRecv,
                IntentFilter(PrefKeys.ACTION_RELAY_RESULT),
                Context.RECEIVER_EXPORTED
            )
            resultReceiver = resultRecv
            HLog.i(TAG, "ACTION_RELAY_RESULT receiver registered (via $via)")
        } catch (e: Throwable) {
            HLog.e(TAG, "receiver registration failed: ${e.message}", e)
        }
    }

    /**
     * API 102 热重载释放（旧代退休前由 `XposedInit.onHotReloading` 调用；幂等）。
     *
     * 为什么必须：本 hook 在 :ui 进程注册了两个接收器（fan 启动转发端 + root 代发结果回告），
     * 它们由**宿主 AMS** 持有——不注销，旧代 classloader 回收不了，且新代再注册一份就会
     * 把同一次广播处理两遍（启动动作非幂等：会开两个小窗 / 两次 startShortcut）。
     * 同时停掉 :ui 侧的三条共享通道：`ConfigSync`（SYNC 接收器+延迟回推 Runnable）、
     * `LogDumpBridge`（日志回传接收器）、主线程上排队的回调。
     * 复位 `registered` 使**同代内**（如锚点晚解析 re-arm 路径）能重新注册。
     * 任一步失败只 `HLog.w`。
     */
    override fun releaseForReload() {
        runCatching {
            val c = registeredCtx
            if (c != null) {
                launchReceiver?.let { c.unregisterReceiver(it) }
                resultReceiver?.let { c.unregisterReceiver(it) }
            }
        }.onFailure { HLog.w(TAG, "unregister failed: ${it.message}") }
        launchReceiver = null
        resultReceiver = null
        registeredCtx = null
        registered = false
        runCatching { mainHandler.removeCallbacksAndMessages(null) }
            .onFailure { HLog.w(TAG, "handler cleanup failed: ${it.message}") }
        runCatching { com.lsp.hypersidebar.util.ConfigSync.releaseForReload() }
            .onFailure { HLog.w(TAG, "config sync release failed: ${it.message}") }
        runCatching { com.lsp.hypersidebar.util.LogDumpBridge.releaseForReload() }
            .onFailure { HLog.w(TAG, "log dump release failed: ${it.message}") }
        HLog.i(TAG, "releaseForReload: relay receivers + config/log channels released")
    }
}
