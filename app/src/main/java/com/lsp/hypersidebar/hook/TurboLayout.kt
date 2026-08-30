package com.lsp.hypersidebar.hook

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.ui.fan.FanMenuController
import io.github.kyuubiran.ezxhelper.core.finder.ConstructorFinder
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.xposed.EzXposed
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createAfterHook
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createBeforeHook
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

private const val TAG = "TurboLayout"

/** B 路线横屏固定圆心 Y：条中心（条=[0,112dp]，112/2=56dp；securitycenter 私有资源取常量）。 */
private const val LANDSCAPE_ANCHOR_Y_DP = 56f

/** S1 自动降级门（1C，PRD §9.4）：60s 窗口内穿透失效 ≥3 次 → 恢复原生侧边栏。 */
private const val LEAK_DEGRADE_THRESHOLD = 3

private const val DEFAULT_DEGRADE_TOAST = "扇形侧边栏：边缘穿透持续失效，已恢复原生小白条（重启后恢复扇形）"

/**
 * :ui 进程宿主（securitycenter:ui）。产品形态（PRD §7.1，唯一；channelMode 已废弃删除）：
 *
 * - B 路线横屏触发（1B，PRD §7.1/§7.3.1，反编译取证 2026-08-30）：横屏隐藏条本身即触发器
 *   （HyperCeiler 同款思路），f.onTouch 走锚点圆状态机（EdgeGestureHook 同款：内滑确认
 *   40px/60°、15px 锚点圆 + dwell 停顿、滑回重置）→ 停顿呼出 fan。触摸链路 cover.onTouch →
 *   j.d0() → bar.dispatchTouchEvent → f.onTouch 无方向分支；系统侧 setEnabled 仅在拖动动画/
 *   游戏 turbo 面板/系统隐藏侧边栏时禁用，空闲态恒 enabled。
 * - 触摸穿透（仅竖屏）：flag 施加在窗口生命周期边界——addView 添加期注入（窗口生而
 *   NOT_TOUCHABLE）+ updateViewLayout 更新期重涂（宿主任何重置即时失效）；applyCoverFlag +
 *   2s 看门狗降级为兜底。所有创建/修改 cover 窗口 lp 的路径必经 hook，竞态窗从"最多 2s"
 *   收敛为不存在。f.onTouch 吞事件分支保留作纵深防御 + 失效计数（S1 数据源）。
 * - 视觉隐藏（三层封口）：① `c.draw(Canvas)` before-skip（可见像素唯一出口，C7680c.java:253）；
 *   ② `ImageView.onDraw` 身份过滤置空；③ `View.draw` 身份过滤置空——覆盖熄屏重建/主题切换
 *   换 drawable 类等一切绘制路径。M1/N1 提示一并清理。
 * 系统侧边栏开关保持开启 → :ui 常驻 → 活动面板与 B 链路正常。
 * 非 EDGE 值（HANDLE）为遗留调试通道：条可见可摸、f.onTouch 直呼 fan，产品不暴露。
 * 执行动作用 DirectLaunchStrategy（本进程直执行）。
 * 自动降级（1C，PRD §9.4）：竖屏穿透失效 ≥3 次/分钟 → 恢复原生侧边栏（条可摸/可见/
 * 事件原生流）+ toast + remotePrefs 状态标注；无自动恢复（观测通道已停用），恢复=重启
 * :ui 进程（重启手机/重启模块）。
 */
class TurboLayout(private val remotePrefs: SharedPreferences) : BaseHook() {

    private val sideBar = "com.miui.dock.sidebar.f"
    private val handleBarView = "com.miui.dock.sidebar.RegionSamplingImageView"
    private val coverView = "com.miui.dock.sidebar.b"
    override val name: String = "HookTargetBox"

    private var sidebarWrapperRef: WeakReference<Any>? = null
    private val coverRefs = CopyOnWriteArrayList<WeakReference<View>>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var lastDrawableLogTime = 0L

    private val fanController: FanMenuController by lazy {
        FanMenuController(
            remotePrefs,
            DirectLaunchStrategy(),
            onMechanismResult = { ok, reason ->
                // fan 窗口装配失败 = 机制性失败（熔断数据源）；成功 = 连续失败清零
                if (ok) breaker.recordSuccess() else breaker.recordFailure(reason)
            }
        )
    }

    /** 熔断器（1C 轮二）：本进程机制性失败连续 5 次 → 恢复原生侧边栏（走降级动作） */
    private val breaker = CircuitBreaker(PrefKeys.CIRCUIT_OPEN_UI, remotePrefs)

    fun hookOnTouch() {
        val hooked = MethodFinder.fromClass(sideBar)
            .filterByName("onTouch")
            .filterByParamTypes(View::class.java, MotionEvent::class.java)
            .filterByReturnType(Boolean::class.java)
            .firstOrNull()
            ?.createBeforeHook {
                val event = it.args[1] as? MotionEvent ?: return@createBeforeHook
                val view = it.args[0] as? View ?: return@createBeforeHook

                // 竖屏=吞掉漏到小白条的事件（穿透失效信号，防唤起原生侧边栏/
                // 幽灵入口）；横屏=B 路线状态机接管（隐藏条即触发器）。
                // 已降级（1C）：竖屏事件放行原生流（不吞不计数）；
                // 已熔断（1C 轮二）：两侧全放行，本进程停止一切侵入
                if (!isLandscape(view)) {
                    if (passthroughDegraded || breaker.open) return@createBeforeHook
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) onCoverTouchLeak()
                    it.result = true
                    return@createBeforeHook
                }
                if (breaker.open) return@createBeforeHook
                handleStripGesture(view, event)
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

    // ===== B 路线横屏状态机（1B，PRD §7.1/§7.3.1） =====
    // 移植 EdgeGestureHook 的锚点圆法 v2（生产验证），适配 :ui 条上触摸：
    // - 内滑轴=就近角落的对角线（反编译定稿：横屏条固定在短轴顶部角落——lp 恒为
    //   START/END|TOP + y=0 + 88×308px，m29488M 含 !isLandscape 使 y 取本地默认 0），
    //   60° 锥同时容纳纯竖直/纯水平内滑；轴不依赖 view 边界——f.onTouch 的 arg0 是 bar，
    //   面板关闭时未挂窗口，getLocationOnScreen 不可靠
    // - 条上事件一律消费：原生侧边栏逻辑（拖动/呼面板）不得在隐藏条上运行
    private var sDownX = 0f
    private var sDownY = 0f
    private var sInwardUx = 0f
    private var sInwardUy = 1f
    private var sSwipeConfirmed = false
    private var sAnchorX = 0f
    private var sAnchorY = 0f
    private var sAnchorT = -1L
    private var sStallFired = false
    private var sGestureSeq = 0
    private var sFanSeen = false
    private var sPendingShow: Runnable? = null

    private fun handleStripGesture(view: View, ev: MotionEvent) {
        // fan 展示中：转发驱动；手势中途落地先合成 DOWN 起始选择状态（边缘通道同款：
        // showInternal 主线程阻塞期间丢 DOWN 会导致窗口原点/选中起点全部失效）
        if (fanController.isShowing) {
            if (!sFanSeen) {
                val down = MotionEvent.obtain(
                    ev.downTime, ev.eventTime, MotionEvent.ACTION_DOWN, ev.rawX, ev.rawY, 0
                )
                try { sFanSeen = fanController.dispatchTouchEvent(down) } finally { down.recycle() }
            }
            fanController.dispatchTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                fanController.dismiss()
                resetStripGesture()
            }
            return
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sDownX = ev.rawX
                sDownY = ev.rawY
                sGestureSeq++
                sSwipeConfirmed = false
                sStallFired = false
                sAnchorT = -1L
                sFanSeen = false
                // 内滑轴：DOWN 点就近角落的对角线（指向屏幕内部）
                val dm = view.context.resources.displayMetrics
                val inv = 1f / sqrt(2f)
                sInwardUx = (if (sDownX < dm.widthPixels / 2f) 1f else -1f) * inv
                sInwardUy = (if (sDownY < dm.heightPixels / 2f) 1f else -1f) * inv
                Log.i(
                    TAG,
                    "s#$sGestureSeq DOWN raw=(${ev.rawX.toInt()},${ev.rawY.toInt()}) " +
                        "axis=(${"%.2f".format(sInwardUx)},${"%.2f".format(sInwardUy)})"
                )
            }

            MotionEvent.ACTION_MOVE -> {
                // 停顿已触发（fan 装配中或已展示前）：消费所有事件
                if (sStallFired) return

                val dx = ev.rawX - sDownX
                val dy = ev.rawY - sDownY
                val inward = dx * sInwardUx + dy * sInwardUy
                val perp = abs(dx * sInwardUy - dy * sInwardUx)

                // 滑回条：整体重置（PRD 状态机"滑回边缘→待触发"）
                if (sSwipeConfirmed && inward < GestureThresholds.SWIPE_CONFIRM_PX) {
                    Log.i(TAG, "s#$sGestureSeq RESET slide-back (inward=${inward.toInt()}px < ${GestureThresholds.SWIPE_CONFIRM_PX.toInt()})")
                    resetStripGesture()
                    return
                }

                if (!sSwipeConfirmed) {
                    // PRD §9.5：内滑距离 ≥40px 且与内滑轴夹角 ≤60°（atan2 点积/叉积形式）
                    val angle = Math.toDegrees(
                        Math.atan2(perp.toDouble(), inward.toDouble())
                    ).toFloat()
                    if (inward >= GestureThresholds.SWIPE_CONFIRM_PX && angle <= GestureThresholds.MAX_SWIPE_ANGLE_DEG) {
                        sSwipeConfirmed = true
                        sAnchorX = ev.rawX
                        sAnchorY = ev.rawY
                        sAnchorT = ev.eventTime
                        Log.i(TAG, "s#$sGestureSeq swipe confirmed: inward=${inward.toInt()}px (>= ${GestureThresholds.SWIPE_CONFIRM_PX.toInt()}) angle=${angle.toInt()}")
                    }
                }

                if (sSwipeConfirmed && !sStallFired) {
                    if (hypot(ev.rawX - sAnchorX, ev.rawY - sAnchorY) > GestureThresholds.STALL_RADIUS_PX) {
                        // 显著位移：锚点随动重置计时
                        sAnchorX = ev.rawX
                        sAnchorY = ev.rawY
                        sAnchorT = ev.eventTime
                    } else if (ev.eventTime - sAnchorT >= stripDwellMs()) {
                        sStallFired = true
                        Log.i(TAG, "s#$sGestureSeq STALL ${stripDwellMs()}ms anchor=(${sAnchorX.toInt()},${sAnchorY.toInt()})")
                        postShowStripFan(view)
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                Log.i(TAG, "s#$sGestureSeq UP stallFired=$sStallFired shown=${fanController.isShowing}")
                if (sStallFired) {
                    cancelPendingStripShow()
                    // fan 已落地而手指未预选即松手 → 立即收起（PRD"未预选松手→立即收起"）
                    if (fanController.isShowing) fanController.dismiss()
                }
                resetStripGesture()
            }
        }
    }

    /**
     * 停顿触发 → 弹 fan。圆心 X=呼出起始侧屏幕边缘（与边缘通道一致）；
     * 圆心 Y 横屏固定为条中心（条=[0,112dp] → 56dp，PRD §9.5 修订 2026-08-30：
     * 触发范围本就小，圆心不随触摸 Y，配合几何层展开角自适应向下方倾斜展开）。
     */
    private fun postShowStripFan(view: View) {
        val ctx = view.context
        val dm = ctx.resources.displayMetrics
        val anchorX = if (sDownX < dm.widthPixels / 2f) 0f else dm.widthPixels.toFloat()
        val anchorY = LANDSCAPE_ANCHOR_Y_DP * dm.density
        Log.i(TAG, "s#$sGestureSeq showFan: anchor=($anchorX, $anchorY) downY=${sDownY.toInt()} dwell=${stripDwellMs()}ms")
        val r = Runnable {
            sPendingShow = null
            fanController.show(ctx, anchorX, anchorY)
        }
        sPendingShow = r
        mainHandler.post(r)
    }

    private fun cancelPendingStripShow() {
        sPendingShow?.let { mainHandler.removeCallbacks(it) }
        sPendingShow = null
    }

    private fun resetStripGesture() {
        sSwipeConfirmed = false
        sStallFired = false
        sAnchorT = -1L
        sFanSeen = false
    }

    private fun stripDwellMs(): Long = try {
        remotePrefs.getInt(PrefKeys.TRIGGER_DWELL_MS, LayoutDefaults.TRIGGER_DWELL_MS).toLong()
    } catch (_: Exception) {
        LayoutDefaults.TRIGGER_DWELL_MS.toLong()
    }

    private fun isLandscape(view: View): Boolean =
        view.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun getStats(): String {
        val wrapper = if (sidebarWrapperRef?.get() != null) "1" else "0"
        return "controller=${fanController.getStats()}, wrapper=$wrapper"
    }

    override fun init() {
        Log.i(TAG, "=== TurboLayout init ===")
        // 熔断器：发布本进程新鲜状态（清掉上进程生命周期遗留的熔断键）+ 熔断动作
        breaker.forceReset()
        breaker.onTripped = { reason ->
            enterDegradedMode(
                "熔断：$reason",
                "扇形连续失败，已熔断保护：恢复原生小白条，重启手机或在设置页重试"
            )
        }
        // 各 hook 独立容错：任一失败不中断其余（实测 hookDockLayoutVisibility 的
        // ClassNotFoundException 曾中断 init，导致排在其后的 hook 从未安装）
        listOf(
            { hookOnTouch() },
            { hookM26633Q() },
            { hookCoverPassThrough() },
            { hookCoverLifecycleFlags() },
            { hookHideWhiteBar() },
            { hookHideHints() },
            { hookHandleBarPixelKill() },
            { hookDockLayoutVisibility() }
        ).forEach { step ->
            runCatching { step() }.onFailure { Log.e(TAG, "init step failed: ${it.message}", it) }
        }
        Log.i(TAG, "init done: ${getStats()}")
        // 预热推荐列表缓存（:ui 侧 B 路线横屏呼出共用 DataLoader；反射 ~1s 不进呼出关键路径）。
        // :ui 的 appContext 一般立即可用；带重试防未就绪（与边缘通道同款）
        com.lsp.hypersidebar.util.DataLoader.prewarmWithRetry(
            provider = { runCatching { EzXposed.appContext }.getOrNull() }
        )
    }

    // ===== 小白条视觉隐藏（EDGE 模式） =====

    /**
     * 小白条像素出口封口（2026-08-25 反编译取证定稿）：
     * 条的可见像素唯一来源是其 drawable 的 draw(Canvas)（运行时类 com.miui.dock.sidebar.c，
     * 画 Path 处 C7680c.java:253）。before 置空后上层无论设什么 alpha/visibility/Folme，
     * 屏幕输出恒为空白。已降级（1C）放行（条恢复可见）；类名漂移时安全降级为可见。
     */
    private fun hookHideWhiteBar() {
        runCatching {
            MethodFinder.fromClass("com.miui.dock.sidebar.c")
                .filterByName("draw")
                .filterByParamTypes(Canvas::class.java)
                .firstOrNull()
                ?.createBeforeHook {
                    if (!passthroughDegraded) it.result = null
                }
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
                    ?.createBeforeHook { it.result = null }
                    ?: Log.w(TAG, "hookHideHints: n.$method NOT FOUND（$desc 保留）")
            }.onFailure { Log.w(TAG, "hookHideHints[$method] failed: ${it.message}") }
        }
    }

    /**
     * 触摸穿透（EDGE，实测轮七）：SidebarCoverView（com.miui.dock.sidebar.b，extends View，
     * 日志 tag "SidebarCoverView"）是侧边栏层唯一触摸入口的窗口根 view——直接 addView 到 WM，
     * 其 layoutParams 即 WindowManager.LayoutParams（轮五退役的旧机制败因是子 view 的 lp
     * 不是）。EDGE 模式对其窗口加 FLAG_NOT_TOUCHABLE（仅竖屏，横屏 B 路线条需收事件）：
     * 原小白条区域事件穿透到下层手势桩/应用（PRD 决策 6），f.onTouch 吞事件分支降级为纵深防御。
     * 看门狗兜底通道切换、系统重置与旋转后残留收敛；非 EDGE 清除 flag 保原生可用。
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

    /**
     * 穿透机制升级（迭代一 v2 §4）：flag 施加挪到窗口生命周期边界。
     * - addView 添加期注入（仅竖屏）：cover 窗口生而 NOT_TOUCHABLE（宿主从未见过无 flag 状态）；
     * - updateViewLayout 更新期重涂：宿主任何带新 lp 的重置即时失效（仅在 flag 被清掉时
     *   重涂并记日志——该日志即"宿主重置频率"的取证数据）；
     * - B 路线横屏（1B）：方向感知反置——横屏条要收事件，不加 flag 且清掉旋转前竖屏
     *   注入的残留（旋转重建 lp 走 addView、位置更新走 updateViewLayout，两条边界都在此收口）。
     * hook android.view.WindowManagerImpl（框架类，:ui 内全局身份过滤，每次仅一次类名比较）。
     */
    private fun hookCoverLifecycleFlags() {
        runCatching {
            MethodFinder.fromClass("android.view.WindowManagerImpl")
                .filterByName("addView")
                .firstOrNull()
                ?.createBeforeHook {
                    val view = it.args.getOrNull(0) as? View ?: return@createBeforeHook
                    if (view.javaClass.name != coverView) return@createBeforeHook
                    val lp = it.args.getOrNull(1) as? WindowManager.LayoutParams
                        ?: return@createBeforeHook
                    // lp 位置/尺寸随日志输出：cover=白条触摸条，pos 即白条当前实际位置
                    // （横屏 B 路线的定位数据源）。带 rotation/orientation 标签自描述——
                    // 实测竖屏 y=用户自定义位置（[871,1179] 中心 1025），横屏 y=0 固定于
                    // 短轴顶部（lp 构建器无方向分支，START/END|TOP + 32×112dp）
                    applyCoverFlagAtBoundary(view, lp, "addView")
                    val rot = runCatching { view.display?.rotation ?: -1 }.getOrDefault(-1)
                    val orient = view.resources.configuration.orientation
                    Log.i(
                        TAG,
                        "cover addView: pos=(${lp.x},${lp.y}) size=(${lp.width}x${lp.height}) " +
                            "gravity=${lp.gravity} rot=$rot orient=$orient " +
                            "touchable=${lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0}"
                    )
                }
                ?: Log.w(TAG, "hookCoverLifecycleFlags: WindowManagerImpl.addView NOT FOUND")
        }.onFailure { Log.w(TAG, "hookCoverLifecycleFlags[addView] failed: ${it.message}") }
        runCatching {
            MethodFinder.fromClass("android.view.WindowManagerImpl")
                .filterByName("updateViewLayout")
                .firstOrNull()
                ?.createBeforeHook {
                    val view = it.args.getOrNull(0) as? View ?: return@createBeforeHook
                    if (view.javaClass.name != coverView) return@createBeforeHook
                    val lp = it.args.getOrNull(1) as? WindowManager.LayoutParams
                        ?: return@createBeforeHook
                    applyCoverFlagAtBoundary(view, lp, "updateViewLayout")
                }
                ?: Log.w(TAG, "hookCoverLifecycleFlags: updateViewLayout NOT FOUND")
        }.onFailure { Log.w(TAG, "hookCoverLifecycleFlags[updateViewLayout] failed: ${it.message}") }
    }

    /**
     * 窗口边界处的 flag 期望态收敛（addView/updateViewLayout 共用）：
     * 竖屏 EDGE=注入（穿透），横屏 EDGE=清除（B 路线收事件）；仅在偏离期望时改写并记日志。
     * 已降级（1C）：竖屏期望态翻为"可触摸"（原生侧边栏恢复），任何窗口重建不再注入。
     */
    private fun applyCoverFlagAtBoundary(view: View, lp: WindowManager.LayoutParams, via: String) {
        val wantFlag = !isLandscape(view) && !passthroughDegraded
        val hasFlag = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0
        when {
            wantFlag && !hasFlag -> {
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                Log.i(TAG, "cover $via: FLAG_NOT_TOUCHABLE injected (host lp was clean)")
            }
            !wantFlag && hasFlag -> {
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                Log.i(TAG, "cover $via: FLAG_NOT_TOUCHABLE cleared (B-route landscape needs touchable strip)")
            }
        }
    }

    // 穿透失效计数（S1 数据源）：EDGE 下事件本应穿透 cover 窗口，漏到 f.onTouch 即 flag 失效。
    // 每次手势计一次（仅 DOWN），按分钟窗口滚动；达阈值自动降级（1C 接线）
    private var leakWindowStartMs = 0L
    private var leakCountInWindow = 0

    // ===== 穿透失效自动降级（1C，PRD §9.4"功能让位于可用性"） =====
    // 降级 = 恢复原生侧边栏全部能力（条可摸/可见/事件走原生流），本进程 hook 让位。
    // 无自动恢复：降级期间事件走原生流，"是否已愈合"无法观测（观测通道=leak 本身
    // 已停用），盲恢复只会反复横跳——恢复路径 = 重启 :ui 进程（重启手机/重启模块）。
    @Volatile private var passthroughDegraded = false

    private fun onCoverTouchLeak() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - leakWindowStartMs >= 60_000L) {
            leakWindowStartMs = now
            leakCountInWindow = 0
        }
        leakCountInWindow++
        Log.w(TAG, "EDGE touch leak: DOWN reached f.onTouch (穿透失效) count=$leakCountInWindow/min")
        if (leakCountInWindow >= LEAK_DEGRADE_THRESHOLD) {
            enterDegradedMode("leak $leakCountInWindow/min >= $LEAK_DEGRADE_THRESHOLD")
        }
    }

    private fun enterDegradedMode(reason: String, toast: String = DEFAULT_DEGRADE_TOAST) {
        if (passthroughDegraded) return
        passthroughDegraded = true
        Log.e(TAG, "PASSTHROUGH DEGRADED ($reason)：恢复原生侧边栏；恢复扇形=重启手机或重启模块")
        // 三条恢复线：条可摸（applyCoverFlag 在 degraded 态反向清 flag，立即 updateViewLayout
        // 生效）、条可见（三层封口 hook 内放行）、事件不吞（hookOnTouch 分支放行原生流）
        coverRefs.forEach { ref -> ref.get()?.let { v -> applyCoverFlag(v) } }
        // 状态标注（设置页读）：remotePrefs 跨进程写，尽力而为
        runCatching {
            remotePrefs.edit().putBoolean(PrefKeys.PASSTHROUGH_DEGRADED, true).commit()
        }.onFailure { Log.w(TAG, "degrade status write failed: ${it.message}") }
        toastOnMain(safeAppContext(), toast)
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
            // B 路线（1B）：横屏条要收事件——仅竖屏 EDGE 期望穿透 flag；旋转后本方法
            // （看门狗 2s 周期）负责收敛残留。已降级（1C）：竖屏反向清 flag 恢复可摸
            val want = !isLandscape(view) && !passthroughDegraded
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
                // 手动重试检查（2s 粒度）：设置页写 circuitResetAt > 本端熔断时刻即解除
                if (breaker.open) breaker.maybeManualReset()
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
                    val v = it.thisObjectOrNull ?: return@createBeforeHook
                    // 已降级（1C）放行：条恢复可见
                    if (v.javaClass.name == handleBarView && !passthroughDegraded) it.result = null
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
                    val v = it.thisObjectOrNull ?: return@createBeforeHook
                    // 已降级（1C）放行：条恢复可见
                    if (v.javaClass.name == handleBarView && !passthroughDegraded) it.result = null
                }
                ?.also { Log.i(TAG, "hookHandleBarPixelKill: View.draw (L3) hooked OK") }
                ?: Log.w(TAG, "hookHandleBarPixelKill: View.draw NOT FOUND")
        }.onFailure { Log.w(TAG, "hookHandleBarPixelKill L3 failed: ${it.message}") }

        runCatching {
            MethodFinder.fromClass("android.widget.ImageView")
                .filterByName("setImageDrawable")
                .firstOrNull()
                ?.createAfterHook {
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
