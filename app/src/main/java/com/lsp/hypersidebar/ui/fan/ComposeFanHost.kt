package com.lsp.hypersidebar.ui.fan

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.lsp.hypersidebar.theme.HyperSidebarTheme
import com.lsp.hypersidebar.theme.ThemeModes
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

private const val TAG = "ComposeFanHost"

/**
 * 触摸状态，由 dispatchTouchEvent 覆写更新，Compose UI 读取做视觉渲染。
 * touchAction: 0=DOWN, 1=MOVE, 2=UP, 3=CANCEL
 */
data class FanTouchState(
    val x: Float,
    val y: Float,
    val touchAction: Int,
    val selectedIndex: Int
)

/**
 * 在 WindowManager 中承载 Compose 扇形菜单。
 *
 * 关键：ComposeView 是 final 类不能继承，且作为 ViewGroup 其
 * setOnTouchListener 无效。因此用 FrameLayout 包裹 ComposeView，
 * 覆写 FrameLayout.dispatchTouchEvent 在 super 调用之前拦截事件。
 */
class ComposeFanHost(
    private val context: Context,
    private val prefs: SharedPreferences
) {
    private var wrapperView: View? = null
    private var composeView: ComposeView? = null
    private var windowManager: WindowManager? = null
    private var lifecycleOwner: FanLifecycleOwner? = null

    var onAppSelected: ((FanAppInfo) -> Unit)? = null
    var onQuickAppSelected: ((FanAppInfo) -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    fun show(
        anchorX: Float,
        anchorY: Float,
        apps: List<FanAppInfo>,
        quickApps: List<FanAppInfo>
    ) {
        if (wrapperView != null) return

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val density = context.resources.displayMetrics.density
        val screenSize = IntSize(
            context.resources.displayMetrics.widthPixels,
            context.resources.displayMetrics.heightPixels
        )
        val config = buildFanConfig()
        val anchorOffset = Offset(anchorX, anchorY)

        // 预计算 geometry（纯数学，不依赖 Compose）
        val geometry = computeFanGeometry(
            anchorOffset, screenSize, apps, quickApps, config, density
        )

        val touchState = mutableStateOf(FanTouchState(0f, 0f, 3, -1))
        val activeZonePx = geometry.activeZonePx
        val deadZonePx = config.deadZoneDp * density
        val ax = anchorX
        val ay = anchorY

        // ── Lifecycle ──
        val lcOwner = FanLifecycleOwner()
        this.lifecycleOwner = lcOwner
        lcOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lcOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        // ── ComposeView（常规创建，不继承） ──
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lcOwner)
            setViewTreeSavedStateRegistryOwner(lcOwner)
            setContent {
                FanMenuWithTheme {
                    val themeColors = extractFanThemeColors()
                    FanMenuCompose(
                        geometry = geometry,
                        touchState = touchState,
                        colors = themeColors,
                        onAppSelected = { app -> onAppSelected?.invoke(app) },
                        onQuickAppSelected = { app -> onQuickAppSelected?.invoke(app) },
                        onDismiss = { onDismiss?.invoke() }
                    )
                }
            }
        }
        this.composeView = composeView

        // ── FrameLayout 包裹层：覆写 dispatchTouchEvent 拦截触摸 ──
        // ComposeView 是 final 不能继承，所以用 FrameLayout 包一层
        val wrapper = object : FrameLayout(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                val x = event.rawX
                val y = event.rawY
                val dx = x - ax
                val dy = y - ay
                val dist = sqrt(dx * dx + dy * dy)

                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        if (dist <= activeZonePx) {
                            val selIdx = calcSelectedIndex(
                                dx, dy, dist, deadZonePx, activeZonePx, geometry
                            )
                            touchState.value = FanTouchState(x, y, 0, selIdx)
                            Log.d(TAG, "touch MOVE sel=$selIdx dist=${dist.toInt()}")
                            return true  // 消费，不传给 ComposeView
                        } else {
                            touchState.value = FanTouchState(x, y, 0, -1)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        touchState.value = FanTouchState(x, y, 2, -1)
                        Log.d(TAG, "ACTION_UP dist=${dist.toInt()} zone=${activeZonePx.toInt()}")
                        if (dist <= activeZonePx) {
                            val idx = calcSelectedIndex(
                                dx, dy, dist, deadZonePx, activeZonePx, geometry
                            )
                            if (idx in geometry.items.indices) {
                                Log.i(TAG, "selected: ${geometry.items[idx].app.packageName}")
                                onAppSelected?.invoke(geometry.items[idx].app)
                            }
                        } else {
                            handleQuickBarTap(x, y, geometry, config, density)
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        touchState.value = FanTouchState(x, y, 3, -1)
                    }
                }
                return super.dispatchTouchEvent(event)
            }
        }
        wrapper.addView(composeView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        // ComposeView 在 onAttachedToWindow 时会从窗口根 View 向上查找
        // LifecycleOwner，所以 wrapper 上也必须设置
        wrapper.setViewTreeLifecycleOwner(lcOwner)
        wrapper.setViewTreeSavedStateRegistryOwner(lcOwner)
        this.wrapperView = wrapper

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            wm.addView(wrapper, params)
            Log.i(TAG, "Compose fan host added, ${apps.size} apps, ${quickApps.size} quick")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to add compose fan host", e)
            dismiss()
        }
    }

    fun dispatchTouchEvent(event: MotionEvent): Boolean {
        return wrapperView?.dispatchTouchEvent(event) ?: false
    }

    fun dismiss() {
        val wv = wrapperView ?: return
        wrapperView = null
        val cv = composeView
        composeView = null

        try {
            windowManager?.removeViewImmediate(wv)
        } catch (e: Throwable) {
            Log.w(TAG, "removeView failed", e)
        }

        try {
            cv?.disposeComposition()
        } catch (e: Throwable) {
            Log.w(TAG, "disposeComposition failed", e)
        }

        lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        lifecycleOwner = null

        onDismiss?.invoke()
    }

    // ── 同步计算选中索引（角度 + 环区分） ──
    private fun calcSelectedIndex(
        dx: Float, dy: Float, dist: Float,
        deadZonePx: Float, activeZonePx: Float, geometry: FanGeometry
    ): Int {
        if (dist < deadZonePx) return -1
        val touchDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val start = geometry.startAngle
        val end = start + geometry.spanAngle
        var ta = touchDeg
        while (ta < start) ta += 360f
        while (ta > start + 360f) ta -= 360f
        if (ta < start || ta > end) return -1

        // 用推动比例区分内外圈：轻推（< 50%）→ 内圈，重推（≥ 50%）→ 外圈
        val pushRatio = ((dist - deadZonePx) / (activeZonePx - deadZonePx)).coerceIn(0f, 1f)
        val preferOuter = pushRatio >= 0.5f

        var bestIdx = -1
        var bestDiff = Float.MAX_VALUE
        // 先只在目标环中找
        geometry.items.forEachIndexed { index, item ->
            if (item.isOuter == preferOuter) {
                var ia = item.angle
                while (ia < start) ia += 360f
                while (ia > start + 360f) ia -= 360f
                val diff = abs(ta - ia)
                if (diff < bestDiff) {
                    bestDiff = diff
                    bestIdx = index
                }
            }
        }
        // 目标环没东西时回退到全部
        if (bestIdx == -1) {
            bestDiff = Float.MAX_VALUE
            geometry.items.forEachIndexed { index, item ->
                var ia = item.angle
                while (ia < start) ia += 360f
                while (ia > start + 360f) ia -= 360f
                val diff = abs(ta - ia)
                if (diff < bestDiff) {
                    bestDiff = diff
                    bestIdx = index
                }
            }
        }
        return bestIdx
    }

    // ── 快捷栏点击检测 ──
    private fun handleQuickBarTap(
        x: Float, y: Float,
        geometry: FanGeometry, config: FanConfig, density: Float
    ) {
        val quickAppsList = geometry.quickApps.take(6)
        if (quickAppsList.isEmpty()) {
            Log.d(TAG, "quickBarTap: no quick apps")
            return
        }
        val quickIconPx = config.quickIconSizeDp * density
        val pxSpacing = quickIconPx * 0.35f
        val totalWidth = quickAppsList.size * quickIconPx +
            (quickAppsList.size - 1) * pxSpacing
        val barStartX = geometry.quickBarCenterX - totalWidth / 2f
        Log.d(TAG, "quickBarTap: touch=($x,$y) barCenter=(${geometry.quickBarCenterX},${geometry.quickBarCenterY}) totalW=${totalWidth.toInt()}")
        for (i in quickAppsList.indices) {
            val cx = barStartX + i * (quickIconPx + pxSpacing) + quickIconPx / 2f
            val d = sqrt((x - cx) * (x - cx) + (y - geometry.quickBarCenterY) * (y - geometry.quickBarCenterY))
            if (d <= quickIconPx * 0.7f) {
                Log.i(TAG, "quickSelected: ${quickAppsList[i].packageName}")
                onQuickAppSelected?.invoke(quickAppsList[i])
                return
            }
        }
    }

    @Composable
    private fun FanMenuWithTheme(content: @Composable () -> Unit) {
        val mode = prefs.getString("themeMode", ThemeModes.MONET_SYSTEM) ?: ThemeModes.MONET_SYSTEM
        HyperSidebarTheme(colorMode = mode) {
            content()
        }
    }

    /** 非 @Composable，可在 View 层调用 */
    private fun buildFanConfig(): FanConfig {
        return FanConfig(
            iconSizeDp = readFloat("iconSize", 48f),
            quickIconSizeDp = 36f,
            innerRadiusDp = readFloat("innerRadius", 150f),
            outerRadiusDp = readFloat("outerRadiusMax", 200f),
            deadZoneDp = readFloat("deadZone", 12f),
            activeZoneDp = readFloat("activeZone", 60f),
            useDualRing = true,
            minRadiusDp = 80f
        )
    }

    private fun readFloat(key: String, default: Float): Float {
        return try { prefs.getFloat(key, default) } catch (_: Exception) { default }
    }

    @Composable
    private fun extractFanThemeColors(): FanThemeColors {
        val scheme = MiuixTheme.colorScheme
        val mode = prefs.getString("themeMode", ThemeModes.MONET_SYSTEM) ?: ThemeModes.MONET_SYSTEM
        val isDark = mode == ThemeModes.DARK || mode == ThemeModes.MONET_DARK ||
            (mode == ThemeModes.SYSTEM || mode == ThemeModes.MONET_SYSTEM) &&
            context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

        return FanThemeColors(
            primary = scheme.primary,
            onPrimary = scheme.onPrimary,
            primaryContainer = scheme.primaryContainer,
            onPrimaryContainer = scheme.onPrimaryContainer,
            surface = scheme.surface,
            surfaceContainer = scheme.surfaceContainer,
            surfaceContainerHigh = scheme.surfaceContainerHigh,
            onSurface = scheme.onSurface,
            onSurfaceVariant = scheme.onSurfaceVariantSummary,
            outline = scheme.outline,
            background = scheme.background,
            isDark = isDark
        )
    }

    private class FanLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

        init {
            savedStateRegistryController.performAttach()
            savedStateRegistryController.performRestore(null)
        }

        fun handleLifecycleEvent(event: Lifecycle.Event) {
            lifecycleRegistry.handleLifecycleEvent(event)
        }
    }
}
