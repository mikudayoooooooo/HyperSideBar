package com.lsp.hypersidebar.hook

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.MotionEvent
import com.lsp.hypersidebar.prefs.HostPackages
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.util.HLog
import com.lsp.hypersidebar.util.RelayToken
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createAfterHook

/**
 * B2-1 流回流终点（os4-trigger-final-plan §7，:ui 进程）：接收 SystemUI spy 转发的
 * 触发带内手势流，喂给 TurboLayout 条上状态机——判定逻辑单源（计划 §7 铁律），
 * SystemUI 只是哑管道。
 *
 * 门控与退路（与条上 hook 同款）：isOs4Sidebar + 模块启用 + 未熔断 + 数据未死
 *（owner 实例提供，见 TurboLayout.b2Enabled）。条上 hook 让位纯吞（防同一触摸双喂），
 * 本 sink 是竖屏唯一驱动源（全边缘触发恒开，2026-10-13 起无实验开关）。
 *
 *（2026-10-13 清理，用户 m03028）「抢占指令」只保留**底角线**用途：
 * 条上（竖屏触发带）STALL 后的抢占已删除——那条是给 native 的冗余兜底，真机实证
 * pilfer 一开 launcher Rust 立刻收 CANCEL、native 停顿判定链走不完，而 native 才是
 * 判定单源。底角线仍用 [requestPilfer] 夺流（反向接管模型：系统收到 CANCEL 才不弹
 * recents/home），调用方=TurboLayout.feedCornerGesture 的 SHOW 分支。
 *
 * 注册点：[arm] 由 TurboLayout init 调用（:ui），自挂 Application.attach 注册接收器
 * （与 LogDumpBridge/FreeformRelay 同款生命周期，init 早于 attach 恰好赶上）。
 */
object RelayGestureSink {

    private const val TAG = "SpySink"

    @Volatile private var armed = false
    @Volatile private var registered = false
    @Volatile private var appCtx: Context? = null
    @Volatile private var owner: TurboLayout? = null

    /**
     * spy 流转发接收器：**原先匿名注册（无处注销）**——它由宿主 AMS 持有，热重载下会把
     * 旧代 classloader 钉住；而新代再注册一份就会把同一条转发流喂给两代状态机
     * （条上/底角判定各跑两遍，是"两个扇形"级别的错误）。
     */
    @Volatile private var streamReceiver: BroadcastReceiver? = null

    /** 当前转发流的手势五要素快照（DOWN 时写入；底角线 [requestPilfer] 的身份匹配用） */
    @Volatile private var streamDownTime: Long = 0L
    @Volatile private var streamDeviceId: Int = -1
    @Volatile private var streamSource: Int = 0
    @Volatile private var streamEdgeFlags: Int = 0
    @Volatile private var streamPointerId: Int = 0
    /** 当前流区域（spy DOWN 处判定单源，随流下发；角落流走独立状态机 §14.6） */
    @Volatile private var streamCorner = false

    /** TurboLayout init 调用（:ui）：挂 attach 钩子，attach 时注册接收器 */
    fun arm(ownerInstance: TurboLayout) {
        if (armed) return
        armed = true
        owner = ownerInstance
        runCatching {
            MethodFinder.fromClass(Application::class.java)
                .filterByName("attach")
                .filterByParamTypes(Context::class.java)
                .firstOrNull()
                ?.createAfterHook {
                    val ctx = it.args[0] as? Context ?: return@createAfterHook
                    register(ctx)
                }
                ?: HLog.w(TAG, "Application.attach hook not found — sink inert")
        }.onFailure { HLog.w(TAG, "arm failed: ${it.message}") }
    }

    /**
     * 热重载重挂（API 102；与 [releaseForReload] 严格对称）。
     *
     * 为什么需要：本 sink 的接收器挂在**它自己的** `Application.attach` 钩子里（[arm] 装的），
     * 热重载不重放 attach ⇒ 释放后 :ui 收不到 spy 转发的流，竖屏条上/底角触发整体失效。
     * 由 [TurboLayout.rearmHostSide] 转发（同进程同代，新代的 `arm(this)` 已把 owner 接回来）。
     * [register] 自身幂等（`registered` 守卫），且会重设 [appCtx]（`requestPilfer` 要用）。
     */
    fun rearmHostSide(ctx: Context) {
        register(ctx)
    }

    private fun register(ctx: Context) {
        if (registered) return
        registered = true
        // 存原始 context（attach 传 base ContextImpl）：applicationContext 在 attach 时刻
        // 仍为 null（LoadedApk 未挂完）——1005 实锤 register NPE，SystemUiInputSpy 同款教训
        appCtx = ctx
        runCatching {
            val recv = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    val ev = intent.getParcelableExtra<MotionEvent>(PrefKeys.SPY_EXTRA_EVENT)
                        ?: return
                    try {
                        dispatch(c, intent, ev)
                    } finally {
                        ev.recycle()
                    }
                }
            }
            ctx.registerReceiver(
                recv,
                IntentFilter(PrefKeys.SPY_STREAM_ACTION),
                // 2026-11-11 架构收口：发送方 SystemUI 与接收方 :ui 同为 uid 1000，
                // 用内核 uid 判定（NOT_EXPORTED）替代跨进程字符串令牌，每事件零成本。
                // 若 ROM 的 AMS 不放行同 uid 跨进程，回退 EXPORTED 并记录（见计划 §4a）。
                Context.RECEIVER_NOT_EXPORTED
            )
            streamReceiver = recv
            HLog.i(TAG, "spy stream sink registered (OS4 恒开，b2Enabled 门控)")
        }.onFailure { HLog.w(TAG, "register failed: ${it.message}") }
    }

    /**
     * API 102 热重载释放（由 [TurboLayout.releaseForReload] 调用；幂等）。
     *
     * 为什么必须：本 object 在 :ui 进程注册了一个 spy 流转发接收器——宿主 AMS 持有它，
     * 不注销则旧代 classloader 回收不了；新代再注册一份会让**同一条转发流**同时喂给
     * 两代状态机（§7 铁律"判定逻辑单源"被破坏，症状是重复呼出）。
     * 复位 `armed`/`owner` 使同代内可重新 [arm]（新代由 `installFor(UI)` → TurboLayout.init
     * 重新 arm，或同代重启路径重新挂）。
     * 任一步失败只 `HLog.w`。
     */
    fun releaseForReload() {
        runCatching {
            val r = streamReceiver
            val c = appCtx
            if (r != null && c != null) c.unregisterReceiver(r)
        }.onFailure { HLog.w(TAG, "unregister failed: ${it.message}") }
        streamReceiver = null
        registered = false
        armed = false
        owner = null
        appCtx = null
        // 流身份快照清零：旧代的过期 requestPilfer 不该再撞新代的手势
        streamDownTime = 0L
        streamCorner = false
        HLog.i(TAG, "releaseForReload: spy stream sink released")
    }

    private fun dispatch(c: Context, intent: Intent, ev: MotionEvent) {
        val o = owner ?: return
        val zone = intent.getIntExtra(PrefKeys.SPY_EXTRA_ZONE, PrefKeys.SPY_ZONE_STRIP)
        // 逐区放行（2026-10-31）：
        //  · OS4（b2Enabled）条上/角区恒开；
        //  · OS2/3 只在"底角斜滑兜底"打开且无小白条时接收**角区**流——条上通道属于 OS4
        //    的侧边条几何，不能因为开了兜底就在 OS2/3 一并放开（用户拍板：只兜底底角）。
        val cornerStream = zone == PrefKeys.SPY_ZONE_CORNER
        val allowed = if (cornerStream) o.b2Enabled() || o.cornerFallbackOwned() else o.b2Enabled()
        if (!allowed) return
        val landscape = intent.getBooleanExtra(PrefKeys.SPY_EXTRA_LANDSCAPE, false)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            streamDownTime = ev.downTime
            streamDeviceId = ev.deviceId
            streamSource = ev.source
            streamEdgeFlags = ev.edgeFlags
            streamPointerId = ev.getPointerId(0)
            streamCorner = cornerStream
            HLog.i(TAG, "stream DOWN (${ev.rawX.toInt()},${ev.rawY.toInt()}) land=$landscape" +
                if (streamCorner) " zone=corner" else "")
        }
        if (cornerStream) {
            o.feedCornerGesture(
                c, ev,
                sysPilfered = intent.getBooleanExtra(
                    com.lsp.hypersidebar.prefs.PrefKeys.SPY_EXTRA_SYS_PILFERED, false
                ),
            )
        } else {
            o.feedStripGesture(c, landscape, ev)
        }
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            // 流结束清身份：防后置的过期 requestPilfer 撞旧手势（1005 实锤 mismatch）
            streamDownTime = 0L
        }
    }

    /** 底角线抢占指令：底角锥内确认（TurboLayout feedCornerGesture 的 SHOW）→ 定向广播回
     *  SystemUI，带手势五要素；SystemUI 全匹配才 pilferPointers。条上（竖屏触发带）路径
     *  已于 2026-10-13 删除调用（用户 m03028：那是给 native 的冗余兜底）。 */
    fun requestPilfer() {
        val ctx = appCtx ?: return
        if (streamDownTime == 0L) return
        val o = owner ?: return
        val intent = Intent(PrefKeys.SPY_PILFER_ACTION)
            .setPackage(HostPackages.SYSTEM_UI)
            .putExtra(PrefKeys.SPY_EXTRA_DOWN_TIME, streamDownTime)
            .putExtra(PrefKeys.SPY_EXTRA_DEVICE_ID, streamDeviceId)
            .putExtra(PrefKeys.SPY_EXTRA_SOURCE, streamSource)
            .putExtra(PrefKeys.SPY_EXTRA_EDGE_FLAGS, streamEdgeFlags)
            .putExtra(PrefKeys.SPY_EXTRA_POINTER_ID, streamPointerId)
        RelayToken.attach(intent, RelayToken.read(o.b2Prefs))
        runCatching { ctx.sendBroadcast(intent) }
            .onFailure { HLog.w(TAG, "pilfer request failed: ${it.message}") }
        HLog.i(TAG, "pilfer requested: dt=$streamDownTime dev=$streamDeviceId")
    }
}
