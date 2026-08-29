package com.lsp.hypersidebar.hook

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.lsp.hypersidebar.prefs.ChannelModes
import com.lsp.hypersidebar.prefs.readChannelMode
import com.lsp.hypersidebar.ui.fan.FanMenuController
import io.github.kyuubiran.ezxhelper.core.finder.ConstructorFinder
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createAfterHook
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createBeforeHook
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.sqrt

private const val TAG = "TurboLayout"

/**
 * 小白条通道（securitycenter:ui）：hook 侧边栏触摸（com.miui.dock.sidebar.f.onTouch），
 * 内滑手势触发 FanMenuController 弹出扇形菜单；执行动作用 DirectLaunchStrategy。
 *
 * EDGE 模式（channelMode pref）：
 * - 视觉隐藏（三层封口）：① `c.draw(Canvas)` before-skip（可见像素唯一出口，C7680c.java:253）；
 *   ② `ImageView.onDraw` 身份过滤置空；③ `View.draw` 身份过滤置空——覆盖熄屏重建/主题切换
 *   换 drawable 类等一切绘制路径。M1/N1 提示一并清理。
 * - 触摸穿透：SidebarCoverView（com.miui.dock.sidebar.b，extends View，唯一触摸入口的窗口根）
 *   EDGE 模式对其窗口加 FLAG_NOT_TOUCHABLE——原小白条区域事件穿透到下层手势桩；
 *   f.onTouch 吞事件分支保留作纵深防御。系统侧边栏开关保持开启 → :ui 常驻 → 活动面板与 B 链路正常。
 */
class TurboLayout(private val remotePrefs: SharedPreferences) : BaseHook() {

    private val sideBar = "com.miui.dock.sidebar.f"
    private val handleBarView = "com.miui.dock.sidebar.RegionSamplingImageView"
    private val coverView = "com.miui.dock.sidebar.b"
    override val name: String = "HookTargetBox"

    private var lastTouchRawX = 0f
    private var lastTouchRawY = 0f
    private var touchDownRawX = 0f
    private var touchDownRawY = 0f
    private var lastTouchView: WeakReference<View>? = null
    private var sidebarWrapperRef: WeakReference<Any>? = null
    private val coverRefs = CopyOnWriteArrayList<WeakReference<View>>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var lastDrawableLogTime = 0L

    private val fanController: FanMenuController by lazy {
        FanMenuController(remotePrefs, DirectLaunchStrategy())
    }

    private var fixedAnchorX = 0f
    private var fixedAnchorY = 0f

    fun hookOnTouch() {
        val hooked = MethodFinder.fromClass(sideBar)
            .filterByName("onTouch")
            .filterByParamTypes(View::class.java, MotionEvent::class.java)
            .filterByReturnType(Boolean::class.java)
            .firstOrNull()
            ?.createBeforeHook {
                val event = it.args[1] as? MotionEvent ?: return@createBeforeHook
                val view = it.args[0] as? View ?: return@createBeforeHook

                // EDGE 模式：吞掉漏到小白条的事件（穿透失效信号），防唤起原生侧边栏/幽灵入口
                if (remotePrefs.readChannelMode() == ChannelModes.EDGE) {
                    it.result = true
                    return@createBeforeHook
                }

                if (fanController.isShowing) {
                    fanController.dispatchTouchEvent(event)
                    if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                        fanController.hideAll()
                    }
                    it.result = true
                    return@createBeforeHook
                }

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        touchDownRawX = event.rawX
                        touchDownRawY = event.rawY
                        lastTouchRawX = event.rawX
                        lastTouchRawY = event.rawY
                        lastTouchView = WeakReference(view)

                        val loc = IntArray(2)
                        view.getLocationOnScreen(loc)
                        val dm = view.context.resources.displayMetrics
                        fixedAnchorX = if (loc[0] <= dm.widthPixels / 2) 0f else dm.widthPixels.toFloat()
                        fixedAnchorY = loc[1] + view.height / 2f
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val wasShowing = fanController.isShowing
                        lastTouchRawX = event.rawX
                        lastTouchRawY = event.rawY
                        maybeTriggerFanMenu()
                        val justCreated = fanController.isShowing && !wasShowing
                        if (fanController.isShowing) {
                            if (justCreated) {
                                val down = MotionEvent.obtain(
                                    event.downTime, event.eventTime,
                                    MotionEvent.ACTION_DOWN, event.rawX, event.rawY, 0
                                )
                                try { fanController.dispatchTouchEvent(down) } finally { down.recycle() }
                            }
                            fanController.dispatchTouchEvent(event)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (fanController.isShowing) fanController.dismiss()
                    }
                }
                it.result = true
            }
        Log.d(TAG, "hookOnTouch: hooked=$hooked")
    }

    fun hookM26633Q() {
        MethodFinder.fromClass("com.miui.dock.sidebar.j")
            .filterByName("Q")
            .filterByParamTypes()
            .firstOrNull()
            ?.createAfterHook {
                sidebarWrapperRef = WeakReference(it.thisObject)
                Log.d(TAG, "Q afterHook: saved wrapper ref, class=${it.thisObject.javaClass.name}")
            }
    }

    private fun maybeTriggerFanMenu() {
        if (fanController.isShowing) return

        val dx = lastTouchRawX - touchDownRawX
        val dy = lastTouchRawY - touchDownRawY
        val moveDistance = sqrt(dx * dx + dy * dy)

        // 1. 最小触发距离
        if (moveDistance < 40f) return

        // 2. 必须向外移动（远离边缘）
        val isOutward = if (fixedAnchorX == 0f) dx > 0 else dx < 0
        if (!isOutward) return

        // 3. 移动方向与水平方向夹角不能太大
        val moveAngle = Math.toDegrees(
            Math.atan2(abs(dy).toDouble(), abs(dx).toDouble())
        ).toFloat()
        if (moveAngle > 60f) return

        val view = lastTouchView?.get()
        val ctx = view?.context
        if (ctx == null) {
            Log.w(TAG, "maybeTriggerFanMenu: view/context null, abort")
            return
        }
        Log.i(TAG, "maybeTriggerFanMenu: dx=$dx dy=$dy moveAngle=$moveAngle anchor=($fixedAnchorX, $fixedAnchorY)")
        fanController.show(ctx, fixedAnchorX, fixedAnchorY)
    }

    fun getStats(): String {
        val wrapper = if (sidebarWrapperRef?.get() != null) "1" else "0"
        return "controller=${fanController.getStats()}, wrapper=$wrapper"
    }

    override fun init() {
        Log.i(TAG, "=== TurboLayout init ===")
        // 各 hook 独立容错：任一失败不中断其余（实测 hookDockLayoutVisibility 的
        // ClassNotFoundException 曾中断 init，导致排在其后的 hook 从未安装）
        listOf(
            { hookOnTouch() },
            { hookM26633Q() },
            { hookCoverPassThrough() },
            { hookHideWhiteBar() },
            { hookHideHints() },
            { hookHandleBarPixelKill() },
            { hookDockLayoutVisibility() }
        ).forEach { step ->
            runCatching { step() }.onFailure { Log.e(TAG, "init step failed: ${it.message}", it) }
        }
        Log.i(TAG, "init done: ${getStats()}")
    }

    // ===== 小白条视觉隐藏（EDGE 模式） =====

    private fun inEdgeMode(): Boolean = remotePrefs.readChannelMode() == ChannelModes.EDGE

    /**
     * 小白条像素出口封口（2026-08-25 反编译取证定稿）：
     * 条的可见像素唯一来源是其 drawable 的 draw(Canvas)（运行时类 com.miui.dock.sidebar.c，
     * 画 Path 处 C7680c.java:253）。before 置空后上层无论设什么 alpha/visibility/Folme，
     * 屏幕输出恒为空白。HANDLE 模式不拦截（条即触发器）；类名漂移时安全降级为可见。
     */
    private fun hookHideWhiteBar() {
        runCatching {
            MethodFinder.fromClass("com.miui.dock.sidebar.c")
                .filterByName("draw")
                .filterByParamTypes(Canvas::class.java)
                .firstOrNull()
                ?.createBeforeHook { if (inEdgeMode()) it.result = null }
                ?.also { Log.i(TAG, "hookHideWhiteBar: c.draw hooked OK") }
                ?: Log.w(TAG, "hookHideWhiteBar: c.draw NOT FOUND（条保持可见，安全降级）")
        }.onFailure { Log.w(TAG, "hookHideWhiteBar failed: ${it.message}（条保持可见）") }
    }

    /**
     * 残留提示清理（EDGE）：n.M1 = 一次性引导弹窗、n.N1 = 小窗 tip 角标——
     * 指向一条被隐藏的条会造成困惑。缺失时打日志跳过，不影响其它功能。
     */
    private fun hookHideHints() {
        listOf("M1" to "引导弹窗", "N1" to "tip 角标").forEach { (method, desc) ->
            runCatching {
                MethodFinder.fromClass("com.miui.dock.sidebar.n")
                    .filterByName(method)
                    .filterByParamTypes()
                    .firstOrNull()
                    ?.createBeforeHook { if (inEdgeMode()) it.result = null }
                    ?: Log.w(TAG, "hookHideHints: n.$method NOT FOUND（$desc 保留）")
            }.onFailure { Log.w(TAG, "hookHideHints[$method] failed: ${it.message}") }
        }
    }

    /**
     * 触摸穿透（EDGE，实测轮七）：SidebarCoverView（com.miui.dock.sidebar.b，extends View，
     * 日志 tag "SidebarCoverView"）是侧边栏层唯一触摸入口的窗口根 view——直接 addView 到 WM，
     * 其 layoutParams 即 WindowManager.LayoutParams（轮五退役的旧机制败因是子 view 的 lp
     * 不是）。EDGE 模式对其窗口加 FLAG_NOT_TOUCHABLE：原小白条区域事件穿透到下层
     * 手势桩/应用（PRD 决策 6），f.onTouch 吞事件分支降级为纵深防御。
     * 看门狗兜底通道切换（EDGE↔HANDLE）与系统重置；HANDLE 模式清除 flag 保原生可用。
     */
    private fun hookCoverPassThrough() {
        runCatching {
            ConstructorFinder.fromClass(coverView).firstOrNull()
                ?.createAfterHook {
                    val view = it.thisObject as? View ?: return@createAfterHook
                    Log.i(TAG, "cover view captured (ctor): $coverView")
                    purgeCoverRefs()
                    coverRefs.add(WeakReference(view))
                    applyCoverFlag(view)
                }
                ?: Log.w(TAG, "hookCoverPassThrough: $coverView ctor NOT FOUND")
        }.onFailure { Log.w(TAG, "hookCoverPassThrough failed: ${it.message}") }
        startCoverWatchdog()
    }

    private fun purgeCoverRefs() {
        coverRefs.removeAll { it.get() == null }
    }

    private fun applyCoverFlag(view: View) {
        if (view.layoutParams == null || !view.isAttachedToWindow) {
            // 构造时尚未 attach：挂一次性监听，attach 后重试
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.removeOnAttachStateChangeListener(this)
                    applyCoverFlag(v)
                }
                override fun onViewDetachedFromWindow(v: View) {}
            })
            return
        }
        runCatching {
            val lp = view.layoutParams as? WindowManager.LayoutParams
                ?: return@runCatching Log.w(TAG, "applyCoverFlag: lp=${view.layoutParams?.javaClass?.name} 非 WM.LayoutParams")
            val want = inEdgeMode()
            val has = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0
            if (want != has) {
                lp.flags = if (want) {
                    lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                } else {
                    lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                }
                (view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    .updateViewLayout(view, lp)
                Log.i(TAG, "cover window NOT_TOUCHABLE ${if (want) "applied" else "cleared"}")
            }
        }.onFailure { Log.w(TAG, "applyCoverFlag failed: ${it.message}") }
    }

    private fun startCoverWatchdog() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                purgeCoverRefs()
                if (!coverRefs.isEmpty()) {
                    coverRefs.forEach { ref -> ref.get()?.let { applyCoverFlag(it) } }
                }
                mainHandler.postDelayed(this, 2000L)
            }
        }, 2000L)
    }

    /**
     * ImageView 渲染级封口（EDGE，实测轮六）：熄屏重建/主题切换时宿主可能给
     * RegionSamplingImageView 换 drawable 实现类，单类封口 c.draw 会漏。
     * 升维到 android.widget.ImageView.onDraw 身份过滤——不管 drawable 是谁，
     * 像素出口恒被置空；与 c.draw 封口互为纵深。:ui 进程 ImageView 数量少，
     * 类名比较开销可忽略。附带 setImageDrawable 探针记录实际 drawable 类名，
     * 用于下轮日志证实"换类"猜想。
     */
    private fun hookHandleBarPixelKill() {
        runCatching {
            MethodFinder.fromClass("android.widget.ImageView")
                .filterByName("onDraw")
                .filterByParamTypes(Canvas::class.java)
                .firstOrNull()
                ?.createBeforeHook {
                    if (!inEdgeMode()) return@createBeforeHook
                    val v = it.thisObjectOrNull ?: return@createBeforeHook
                    if (v.javaClass.name == handleBarView) it.result = null
                }
                ?.also { Log.i(TAG, "hookHandleBarPixelKill: ImageView.onDraw hooked OK") }
                ?: Log.w(TAG, "hookHandleBarPixelKill: ImageView.onDraw NOT FOUND（降级仅靠 c.draw）")
        }.onFailure { Log.w(TAG, "hookHandleBarPixelKill failed: ${it.message}") }

        // 第三层封口（实测轮七）：View.draw 是渲染总入口——身份过滤后置空可覆盖
        // 前景/hardware layer 等一切绘制路径；:ui 进程视图少，类名比较开销可忽略
        runCatching {
            MethodFinder.fromClass("android.view.View")
                .filterByName("draw")
                .filterByParamTypes(Canvas::class.java)
                .firstOrNull()
                ?.createBeforeHook {
                    if (!inEdgeMode()) return@createBeforeHook
                    val v = it.thisObjectOrNull ?: return@createBeforeHook
                    if (v.javaClass.name == handleBarView) it.result = null
                }
                ?.also { Log.i(TAG, "hookHandleBarPixelKill: View.draw (L3) hooked OK") }
                ?: Log.w(TAG, "hookHandleBarPixelKill: View.draw NOT FOUND")
        }.onFailure { Log.w(TAG, "hookHandleBarPixelKill L3 failed: ${it.message}") }

        runCatching {
            MethodFinder.fromClass("android.widget.ImageView")
                .filterByName("setImageDrawable")
                .firstOrNull()
                ?.createAfterHook {
                    if (!inEdgeMode()) return@createAfterHook
                    val v = it.thisObjectOrNull ?: return@createAfterHook
                    if (v.javaClass.name != handleBarView) return@createAfterHook
                    val drawable = it.args.getOrNull(0) ?: return@createAfterHook
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastDrawableLogTime > 2000L) {
                        lastDrawableLogTime = now
                        Log.i(TAG, "handle bar drawable set: ${drawable.javaClass.name}")
                    }
                }
                ?.also { Log.i(TAG, "hookHandleBarPixelKill: setImageDrawable probe hooked OK") }
        }.onFailure { Log.w(TAG, "drawable probe failed: ${it.message}") }
    }

    private fun hookDockLayoutVisibility() {
        // DockLayout 运行时类名可能是 com.miui.gamebooster.windowmanager.newbox.e
        // 或者尝试常见混淆名。注意：MethodFinder.fromClass 对不存在的类直接抛
        // ClassNotFoundException（实测 DockLayout 抛出曾中断 init，连带后续 hook 未安装），
        // 每个候选必须独立捕获
        val classNames = listOf(
            "com.miui.gamebooster.windowmanager.newbox.e",
            "com.miui.gamebooster.windowmanager.newbox.d",
            "com.miui.gamebooster.ui.DockLayout"
        )

        for (className in classNames) {
            Log.d(TAG, "hookDockLayoutVisibility: trying class $className")
            val hooked = runCatching {
                MethodFinder.fromClass(className)
                    .filterByName("setVisibility")
                    .filterByParamTypes(Int::class.javaPrimitiveType)
                    .firstOrNull()
                    ?.createBeforeHook {
                        if (PanelHideState.hidden.get()) {
                            it.args[0] = View.GONE
                            Log.d(TAG, "hookDockLayoutVisibility: intercepted setVisibility, set to GONE")
                        }
                    }
            }.getOrNull()
            if (hooked != null) {
                Log.i(TAG, "hookDockLayoutVisibility: hooked $className")
                return
            } else {
                Log.d(TAG, "hookDockLayoutVisibility: class $className not found or no matching method")
            }
        }
        Log.w(TAG, "hookDockLayoutVisibility: no class matched, skip")
    }
}
