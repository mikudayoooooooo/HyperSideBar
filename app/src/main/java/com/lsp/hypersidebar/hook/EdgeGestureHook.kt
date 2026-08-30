package com.lsp.hypersidebar.hook

import android.content.SharedPreferences
import android.util.DisplayMetrics
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.ui.fan.ACTION_FAN_LAUNCH
import com.lsp.hypersidebar.ui.fan.FanMenuController
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.xposed.EzXposed
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
 * - 触发区（仅竖屏）：[H/3, 2H/3]，DOWN 时过滤，区外完全透传；横屏触发已整体移交
 *   B 路线（:ui 的 cover 通道直接收事件，见 TurboLayout），launcher 侧横屏区置空防双触发
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
    private var gestureSeq = 0   // 手势取证 id：贯穿 DOWN/确认/停顿/拦截/UP 日志（S 门数据源）
    private var gestureInZone = false  // DOWN 判定的触发区归属；区外手势整条透传

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
        Log.i(TAG, "hooks installed: onTouchEvent=$okTouch onSwipeStop=$okStop")
        // 预热推荐列表缓存：launcher 进程 init 时 EzXposed.appContext 可能尚未就绪
        // （实测 getAppContext 直接抛 NPE 而非返回 null，首轮 prewarm skipped 是
        // "首次呼出只有固定应用"的根因）——prewarmWithRetry 每 5s 重试直到就绪
        com.lsp.hypersidebar.util.DataLoader.prewarmWithRetry(
            provider = { runCatching { EzXposed.appContext }.getOrNull() }
        )
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
                        Log.i(TAG, "g#$gestureSeq onSwipeStop INTERCEPTED: shouldBack=true -> false")
                    }
                }
            }
            ?: Log.e(TAG, "onSwipeStop NOT FOUND on $CALLBACK_CLASS（混淆名漂移，需 GesturesBackCallback 接口扫描兜底）")
    }

    private fun handleTouch(ev: MotionEvent, stub: View?): Boolean {
        // fan 展示中：事件转发给 fan 并消费；UP/CANCEL 收起（未预选立即收起，PRD §7.1）
        if (fanController.isShowing) {
            if (!fanSeenThisGesture) {
                // fan 在手势中途异步落地：先合成 DOWN 起始选择状态（同 :ui 条上通道）。
                // 仅在真正送达时才置位——showInternal 主线程阻塞期间 host==null 会静默丢事件，
                // 实测合成 DOWN 被丢后整条手势再无 DOWN：窗口原点捕获、选中起点全部失效，
                // 整条手势退回 94px 窗口 inset 偏差（g#6 实锤）
                val down = MotionEvent.obtain(
                    ev.downTime, ev.eventTime, MotionEvent.ACTION_DOWN, ev.rawX, ev.rawY, 0
                )
                try { fanSeenThisGesture = fanController.dispatchTouchEvent(down) } finally { down.recycle() }
            }
            fanController.dispatchTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                fanController.dismiss()
                resetGesture()
            }
            return true
        }

        // 区外手势整条透传（修 1A 缺陷：此前仅 DOWN 过滤，其后 MOVE 仍会进状态机确认/停顿，
        // 触发区外也能呼出——实测 y 超 2/3 界多处 inZone=false 仍 STALL）
        if (!gestureInZone && ev.actionMasked != MotionEvent.ACTION_DOWN) return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                gestureSeq++
                swipeConfirmed = false
                stallFired = false
                anchorT = -1L
                // 触发区外：完全透传（原生返回正常走）；DOWN 全量记录（A2/A7/A8 数据源）
                val inZone = isInTriggerZone(ev.rawX, ev.rawY, stub)
                gestureInZone = inZone
                Log.i(TAG, "g#$gestureSeq DOWN raw=(${ev.rawX.toInt()},${ev.rawY.toInt()}) inZone=$inZone")
                if (!inZone) return false
            }

            MotionEvent.ACTION_MOVE -> {
                // 停顿已触发（fan 装配中或已展示前）：
                // 消费所有后续事件，避免喂给原生状态机
                if (stallFired) return true

                val inward = inwardDx(ev.rawX)

                // 滑回边缘：整体重置（PRD 状态机"滑回边缘→待触发"；修 spike 锁存 bug）
                if (swipeConfirmed && inward < GestureThresholds.SWIPE_CONFIRM_PX) {
                    Log.i(TAG, "g#$gestureSeq RESET slide-back (inward=${inward.toInt()}px < ${GestureThresholds.SWIPE_CONFIRM_PX.toInt()})")
                    resetGesture()
                    return false
                }

                if (!swipeConfirmed) {
                    val dy = abs(ev.rawY - downY)
                    // PRD §9.5：距离 ≥40px 且与水平方向夹角 ≤60°（atan2，弃用 0.x 的 dx>dy/2 近似）
                    val angle = Math.toDegrees(
                        Math.atan2(dy.toDouble(), inward.toDouble())
                    ).toFloat()
                    if (inward >= GestureThresholds.SWIPE_CONFIRM_PX && angle <= GestureThresholds.MAX_SWIPE_ANGLE_DEG) {
                        swipeConfirmed = true
                        anchorX = ev.rawX
                        anchorY = ev.rawY
                        anchorT = ev.eventTime
                        Log.i(TAG, "g#$gestureSeq swipe confirmed: inward=${inward.toInt()}px (>= ${GestureThresholds.SWIPE_CONFIRM_PX.toInt()}) angle=${angle.toInt()}")
                    }
                }

                if (swipeConfirmed && !stallFired) {
                    if (hypot(ev.rawX - anchorX, ev.rawY - anchorY) > GestureThresholds.STALL_RADIUS_PX) {
                        // 显著位移：锚点随动重置计时
                        anchorX = ev.rawX
                        anchorY = ev.rawY
                        anchorT = ev.eventTime
                    } else if (ev.eventTime - anchorT >= dwellMs()) {
                        stallFired = true
                        Log.i(TAG, "g#$gestureSeq STALL ${dwellMs()}ms 达标 anchor=(${anchorX.toInt()},${anchorY.toInt()})")
                        cancelNativeGesture(stub, ev)
                        postShowFan(ev, stub)
                        return true
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                Log.i(TAG, "g#$gestureSeq UP stallFired=$stallFired shown=${fanController.isShowing}")
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
        // 横屏区已置空（B 路线），空区间时跳过钳制防 coerceIn 抛异常（横屏本就到不了这里）
        val (zoneTop, zoneBottom) = zoneBounds(dm)
        val anchorX = if (downX < dm.widthPixels / 2f) 0f else dm.widthPixels.toFloat()
        val anchorY = if (zoneTop < zoneBottom) downY.coerceIn(zoneTop, zoneBottom) else downY
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

    /** PRD 触发区（§9.5 行为规则 3 / §7.3.1）：竖屏 [H/3, 2H/3]；横屏=原小白条位置带。 */
    private fun isInTriggerZone(x: Float, y: Float, stub: View?): Boolean {
        val dm = (stub?.context ?: EzXposed.appContext)?.resources?.displayMetrics ?: return false
        val (top, bottom) = zoneBounds(dm)
        return y in top..bottom
    }

    /** 触发区唯一定义源（DOWN 过滤与 postShowFan 钳制共用，避免两处各写一份漂移）。 */
    private fun zoneBounds(dm: DisplayMetrics): Pair<Float, Float> =
        if (dm.widthPixels > dm.heightPixels) {
            // B 路线（1B）：横屏触发整体移交 :ui cover 通道（隐藏条直接收事件，整条可达
            // 含条顶 [0,108px) 死区），launcher 侧横屏区置空防双触发；原生返回带恢复系统默认
            1f to 0f
        } else {
            (dm.heightPixels / 3f) to (dm.heightPixels * 2f / 3f)
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
}
