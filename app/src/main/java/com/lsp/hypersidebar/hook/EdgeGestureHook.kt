package com.lsp.hypersidebar.hook

import android.content.SharedPreferences
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.lsp.hypersidebar.prefs.ChannelModes
import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.prefs.readChannelMode
import com.lsp.hypersidebar.ui.fan.ACTION_FAN_LAUNCH
import com.lsp.hypersidebar.ui.fan.FanMenuController
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.xposed.EzXposed
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createAfterHook
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createBeforeHook
import kotlin.math.abs
import kotlin.math.hypot

private const val TAG = "EdgeGesture"

/**
 * 边缘手势通道（com.miui.home / GestureStubView）——spike 验证方案的生产化。
 *
 * 设计（PRD §7.1/§7.2，全部实测背书）：
 * - 与全面屏返回手势共享同一次触摸：快速滑动松手 = 原生返回（零干扰透传）；
 *   内滑确认后位移停滞 triggerDwellMs（默认 250ms，锚点圆法 v2）= 呼出 fan 并消费后续事件
 * - 触发区：竖屏 [H/4, 3H/4]、横屏 [0, H/2]，DOWN 时过滤，区外完全透传
 * - 滑回边缘（inward 回落至确认阈值下）→ 整体重置手势状态（PRD"滑回边缘→待触发"）
 * - fan 选中项经 BroadcastLaunchStrategy 广播到 :ui 执行（B 链路实测 2-3ms）
 * - 拦截层：GestureStubView$3.onSwipeStop 翻转首参为 false（消费路径漏事件时的兜底）
 */
class EdgeGestureHook(
    private val remotePrefs: SharedPreferences
) : BaseHook() {

    override val name = "EdgeGesture"

    companion object {
        const val STUB_CLASS = "com.miui.home.recents.GestureStubView"
        const val CALLBACK_CLASS = "com.miui.home.recents.GestureStubView\$3"

        const val SWIPE_CONFIRM_PX = 20f    // 与原生状态机一致的滑动确认阈值（dx≥20px 且水平占优）
        const val STALL_RADIUS_PX = 15f     // 锚点圆半径：dwell 期间位移不超此值视为停顿
    }

    private val fanController: FanMenuController by lazy {
        FanMenuController(remotePrefs, BroadcastLaunchStrategy(ACTION_FAN_LAUNCH))
    }

    // ===== 单指手势状态（DOWN 重置） =====
    private var downX = 0f
    private var downY = 0f
    private var swipeConfirmed = false
    private var anchorX = 0f
    private var anchorY = 0f
    private var anchorT = -1L
    private var stallFired = false

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingShow: Runnable? = null
    private var fanSeenThisGesture = false

    override fun init() {
        Log.i(TAG, "=== EdgeGestureHook init, pid=${android.os.Process.myPid()} ===")
        var okTouch = false
        runCatching { hookOnTouchEvent(); okTouch = true }
            .onFailure { Log.e(TAG, "A FAILED hookOnTouchEvent: ${it.message}", it) }
        var okStop = false
        runCatching { hookOnSwipeStop(); okStop = true }
            .onFailure { Log.e(TAG, "C FAILED hookOnSwipeStop: ${it.message}", it) }
        var okBand = false
        runCatching { hookLandscapeBand(); okBand = true }
            .onFailure { Log.e(TAG, "D FAILED hookLandscapeBand: ${it.message}", it) }
        Log.i(TAG, "hooks installed: onTouchEvent=$okTouch onSwipeStop=$okStop landscapeBand=$okBand")
    }

    /** 记录层：触摸流入口，BeforeHook。返回 true = 消费（拦截原生处理）。 */
    private fun hookOnTouchEvent() {
        MethodFinder.fromClass(STUB_CLASS)
            .filterByName("onTouchEvent")
            .filterByParamTypes(MotionEvent::class.java)
            .filterByReturnType(Boolean::class.java)
            .firstOrNull()
            ?.createBeforeHook {
                val ev = it.args[0] as? MotionEvent ?: return@createBeforeHook
                val stub = it.thisObject as? View
                if (handleTouch(ev, stub)) it.result = true
            }
            ?: Log.e(TAG, "onTouchEvent NOT FOUND on $STUB_CLASS")
    }

    /**
     * 拦截层：停顿标志命中时翻转 shouldBack → 原生走 onBackCancelled 复位分支。
     */
    private fun hookOnSwipeStop() {
        MethodFinder.fromClass(CALLBACK_CLASS)
            .filterByName("onSwipeStop")
            .firstOrNull()
            ?.createBeforeHook {
                if (stallFired || fanController.isShowing) {
                    val shouldBack = it.args[0] as? Boolean ?: return@createBeforeHook
                    if (shouldBack) {
                        it.args[0] = false
                        Log.i(TAG, "onSwipeStop INTERCEPTED: shouldBack=true -> false")
                    }
                }
            }
            ?: Log.e(TAG, "onSwipeStop NOT FOUND on $CALLBACK_CLASS（混淆名漂移，需 GesturesBackCallback 接口扫描兜底）")
    }

    /**
     * 横屏手势带放宽（实测轮六，反编译取证定稿）：
     * 原生 updateGestureTouchHeight 硬编码 0.6 → 横屏可触摸带居中 60% [0.2S,0.8S]，
     * 而竖屏走 expansion 分支≈全高——这就是"横屏难呼出"的结构性根因。
     * AfterHook 仅在横屏（rotation 1/3）+EDGE 模式把字段覆写为短轴×0.8
     * → 带 [0.1S,0.9S]，配合逻辑区 [0,H/2] 实际可用 [0.1S,0.5S]；
     * 原生返回仅两端各多 10%，副作用最小。窗口本就 MATCH_PARENT，单点足够。
     * 反射失败静默保留原值 = 安全降级。旋转时原方法必被重调，覆写随之刷新。
     */
    private fun hookLandscapeBand() {
        MethodFinder.fromClass(STUB_CLASS)
            .filterByName("updateGestureTouchHeight")
            .filterByParamTypes()
            .firstOrNull()
            ?.createAfterHook { param ->
                if (remotePrefs.readChannelMode() != ChannelModes.EDGE) return@createAfterHook
                val v = param.thisObject as? View ?: return@createAfterHook
                runCatching {
                    val cls = v.javaClass
                    val rotationField = cls.getDeclaredField("mRotation").apply { isAccessible = true }
                    val rotation = rotationField.getInt(v)
                    if (rotation == 1 || rotation == 3) {
                        val screenW = cls.getDeclaredField("mScreenWidth")
                            .apply { isAccessible = true }.getInt(v)
                        cls.getDeclaredField("mGestureTouchHeight")
                            .apply { isAccessible = true }.setInt(v, (screenW * 0.8f).toInt())
                    }
                }.onFailure { Log.w(TAG, "landscapeBand: ${it.message}") }
            } ?: Log.w(TAG, "updateGestureTouchHeight NOT FOUND（横屏带维持原生 60%）")
    }

    private fun handleTouch(ev: MotionEvent, stub: View?): Boolean {
        if (remotePrefs.readChannelMode() != ChannelModes.EDGE) {
            resetGesture()
            return false   // HANDLE 模式：零干预透传
        }

        // fan 展示中：事件转发给 fan 并消费；UP/CANCEL 收起（未预选立即收起，PRD §7.1）
        if (fanController.isShowing) {
            if (!fanSeenThisGesture) {
                // fan 在手势中途异步落地：先合成 DOWN 起始选择状态（同小白条通道）
                fanSeenThisGesture = true
                val down = MotionEvent.obtain(
                    ev.downTime, ev.eventTime, MotionEvent.ACTION_DOWN, ev.rawX, ev.rawY, 0
                )
                try { fanController.dispatchTouchEvent(down) } finally { down.recycle() }
            }
            fanController.dispatchTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                fanController.dismiss()
                resetGesture()
            }
            return true
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                swipeConfirmed = false
                stallFired = false
                anchorT = -1L
                // 触发区外：完全透传（原生返回正常走）
                if (!isInTriggerZone(ev.rawX, ev.rawY, stub)) return false
            }

            MotionEvent.ACTION_MOVE -> {
                // 停顿已触发（fan 装配中或已展示前）：
                // 消费所有后续事件，避免喂给原生状态机
                if (stallFired) return true

                val inward = inwardDx(ev.rawX)

                // 滑回边缘：整体重置（PRD 状态机"滑回边缘→待触发"；修 spike 锁存 bug）
                if (swipeConfirmed && inward < SWIPE_CONFIRM_PX) {
                    resetGesture()
                    return false
                }

                if (!swipeConfirmed) {
                    val dy = abs(ev.rawY - downY)
                    if (inward >= SWIPE_CONFIRM_PX && inward > dy / 2f) {
                        swipeConfirmed = true
                        anchorX = ev.rawX
                        anchorY = ev.rawY
                        anchorT = ev.eventTime
                    }
                }

                if (swipeConfirmed && !stallFired) {
                    if (hypot(ev.rawX - anchorX, ev.rawY - anchorY) > STALL_RADIUS_PX) {
                        // 显著位移：锚点随动重置计时
                        anchorX = ev.rawX
                        anchorY = ev.rawY
                        anchorT = ev.eventTime
                    } else if (ev.eventTime - anchorT >= dwellMs()) {
                        stallFired = true
                        vibrate()
                        cancelNativeGesture(stub, ev)
                        postShowFan(ev, stub)
                        return true
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (stallFired) {
                    cancelPendingShow()
                    // 实测轮七：fan 已落地而手指未预选即松手 → 立即收起。
                    // 此前只重置手势状态——fan 失去唯一驱动源后常驻（PRD"未预选松手→立即收起"）
                    if (fanController.isShowing) fanController.dismiss()
                    resetGesture()
                    return true
                }
                resetGesture()
            }
        }
        return false
    }

    /** 停顿触发 → 主线程弹 fan（launcher 触摸回调在 MiuiMirror 输入线程，Compose 需主线程装配）。 */
    private fun postShowFan(ev: MotionEvent, stub: View?) {
        val ctx = EzXposed.appContext ?: stub?.context ?: run {
            Log.w(TAG, "postShowFan: no context available")
            return
        }
        val dm = ctx.resources.displayMetrics
        // 圆心：X 固定在呼出起始侧的屏幕边缘，Y = 手指初始触碰边缘时的高度（downY，
        // DOWN 已被触发区过滤保证在带内；实测轮六定稿——停顿过程中的滑动漂移不再
        // 影响呼出位置）。展开方向由 computeFanGeometry 按锚点半边自动确定。
        // 圆心 Y 若超出几何层可行带会被 computeFanGeometry 二次钳制（保形恒在屏内）。
        val zoneTop: Float
        val zoneBottom: Float
        if (dm.widthPixels > dm.heightPixels) {
            zoneTop = 0f
            zoneBottom = dm.heightPixels / 2f
        } else {
            zoneTop = dm.heightPixels / 4f
            zoneBottom = dm.heightPixels * 3f / 4f
        }
        val anchorX = if (downX < dm.widthPixels / 2f) 0f else dm.widthPixels.toFloat()
        val anchorY = downY.coerceIn(zoneTop, zoneBottom)
        Log.i(TAG, "showFan: anchor=($anchorX, $anchorY) downY=$downY dwell=${dwellMs()}ms")
        val r = Runnable {
            pendingShow = null
            fanController.show(ctx, anchorX, anchorY)
        }
        pendingShow = r
        mainHandler.post(r)
    }

    /**
     * 停顿触发后事件被消费，原生状态机收不到 UP——返回箭头/预测式返回效果残留。
     * GestureStubView.onBackCancelled() 只复位预测式回调（反编译实锤），箭头退出动画
     * 在 $3.onSwipeStop 完整路径里。修法：给原生触摸处理器喂合成 UP——走完整原生收尾
     * （$3.onSwipeStop 拦截层把 shouldBack 翻为 false → 箭头动画退出 + 预测式返回复位 +
     * 状态机复位），在与原生相同的线程（本 hook 的输入线程）同步调用。
     */
    private fun cancelNativeGesture(stub: View?, ev: MotionEvent) {
        if (stub == null) return
        runCatching {
            val field = stub.javaClass.getDeclaredField("mGesturesBackTouchProcessor")
                .apply { isAccessible = true }
            val processor = field.get(stub) ?: return
            val method = processor.javaClass.getMethod(
                "onPointerEvent", MotionEvent::class.java, stub.javaClass
            )
            method.isAccessible = true
            val up = MotionEvent.obtain(
                ev.downTime, ev.eventTime, MotionEvent.ACTION_UP, ev.rawX, ev.rawY, 0
            )
            try { method.invoke(processor, up, stub) } finally { up.recycle() }
            Log.i(TAG, "native gesture teardown via synthetic UP")
        }.onFailure { Log.w(TAG, "cancelNativeGesture failed: ${it.message}") }
    }

    private fun cancelPendingShow() {
        pendingShow?.let { mainHandler.removeCallbacks(it) }
        pendingShow = null
    }

    /** PRD 触发区：竖屏四等分第二、三块 [H/4, 3H/4]；横屏靠上 [0, H/2]。 */
    private fun isInTriggerZone(x: Float, y: Float, stub: View?): Boolean {
        val dm = (stub?.context ?: EzXposed.appContext)?.resources?.displayMetrics ?: return false
        return if (dm.widthPixels > dm.heightPixels) {
            y in 0f..(dm.heightPixels / 2f)
        } else {
            y in (dm.heightPixels / 4f)..(dm.heightPixels * 3f / 4f)
        }
    }

    private fun inwardDx(x: Float): Float =
        if (downX < screenHalfWidth()) x - downX else downX - x

    private fun screenHalfWidth(): Float =
        EzXposed.appContext?.resources?.displayMetrics?.let { it.widthPixels / 2f }
            ?: downX  // 上下文不可用时以自身为界（保守：按左边缘处理）

    private fun resetGesture() {
        swipeConfirmed = false
        stallFired = false
        anchorT = -1L
        fanSeenThisGesture = false
    }

    private fun dwellMs(): Long = try {
        remotePrefs.getInt(PrefKeys.TRIGGER_DWELL_MS, LayoutDefaults.TRIGGER_DWELL_MS).toLong()
    } catch (_: Exception) {
        LayoutDefaults.TRIGGER_DWELL_MS.toLong()
    }

    private fun vibrate() {
        val enabled = try {
            remotePrefs.getBoolean(PrefKeys.VIBRATE, LayoutDefaults.VIBRATE)
        } catch (_: Exception) { LayoutDefaults.VIBRATE }
        if (!enabled) return
        runCatching {
            EzXposed.appContext?.getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
