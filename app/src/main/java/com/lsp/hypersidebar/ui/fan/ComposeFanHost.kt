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

data class FanTouchState(
    val x: Float,
    val y: Float,
    val touchAction: Int,
    val selectedIndex: Int
)

class ComposeFanHost(
    private val context: Context,
    private val prefs: SharedPreferences
) {
    private var wrapperView: View? = null
    private var composeView: ComposeView? = null
    private var windowManager: WindowManager? = null
    private var lifecycleOwner: FanLifecycleOwner? = null
    private var lastSelectedIndex = -1

    var onAppSelected: ((FanAppInfo) -> Unit)? = null
    var onQuickAppSelected: ((FanAppInfo) -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    fun show(
        anchorX: Float,
        anchorY: Float,
        apps: List<FanAppInfo>,
        quickApps: List<FanAppInfo>,
        isLandscape: Boolean
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

        val geometry = computeFanGeometry(
            anchorOffset, screenSize, apps, quickApps, config, density, isLandscape
        )

        val touchState = mutableStateOf(FanTouchState(0f, 0f, 3, -1))
        val activeZonePx = geometry.activeZonePx
        val deadZonePx = config.deadZoneDp * density
        val ax = anchorX
        val ay = anchorY

        val lcOwner = FanLifecycleOwner()
        this.lifecycleOwner = lcOwner
        lcOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lcOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

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

        val wrapper = object : FrameLayout(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                val x = event.rawX
                val y = event.rawY
                val dx = x - ax
                val dy = y - ay
                val dist = sqrt(dx * dx + dy * dy)
                val iconPx = geometry.iconSize * context.resources.displayMetrics.density
                val outerHitRadius = geometry.outerRadius + iconPx * 1.2f

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        lastSelectedIndex = -1
                        val selected = resolveSelection(x, y, dx, dy, dist, deadZonePx, activeZonePx, outerHitRadius, geometry)
                        touchState.value = FanTouchState(x, y, 0, selected)
                        Log.d(TAG, "touch DOWN sel=$selected dist=${dist.toInt()}")
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val selected = resolveSelection(x, y, dx, dy, dist, deadZonePx, activeZonePx, outerHitRadius, geometry)
                        touchState.value = FanTouchState(x, y, 0, selected)
                        Log.d(TAG, "touch MOVE sel=$selected dist=${dist.toInt()}")
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val selected = resolveSelection(x, y, dx, dy, dist, deadZonePx, activeZonePx, outerHitRadius, geometry)
                        touchState.value = FanTouchState(x, y, 2, -1)
                        Log.d(TAG, "ACTION_UP sel=$selected dist=${dist.toInt()}")
                        if (selected in geometry.items.indices) {
                            Log.i(TAG, "selected: ${geometry.items[selected].app.packageName}")
                            onAppSelected?.invoke(geometry.items[selected].app)
                        } else {
                            handleQuickBarTap(x, y, geometry, config, density)
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        touchState.value = FanTouchState(x, y, 3, -1)
                        lastSelectedIndex = -1
                    }
                }
                return super.dispatchTouchEvent(event)
            }
        }
        wrapper.addView(composeView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
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
        lastSelectedIndex = -1

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

    private fun resolveSelection(
        x: Float, y: Float,
        dx: Float, dy: Float,
        dist: Float,
        deadZonePx: Float,
        activeZonePx: Float,
        outerHitRadius: Float,
        geometry: FanGeometry
    ): Int {
        if (dist < deadZonePx) {
            lastSelectedIndex = -1
            return -1
        }

        return when {
            dist <= activeZonePx -> {
                val candidate = calcSelectedIndexHybrid(dx, dy, dist, deadZonePx, geometry)
                applyHysteresis(candidate, dx, dy, geometry)
            }
            dist <= outerHitRadius -> {
                calcSelectedIndexByPosition(x, y, geometry)
            }
            else -> {
                lastSelectedIndex = -1
                -1
            }
        }
    }

    private fun calcSelectedIndexHybrid(
        dx: Float, dy: Float, dist: Float,
        deadZonePx: Float, geometry: FanGeometry
    ): Int {
        if (dist < deadZonePx) return -1
        if (geometry.items.isEmpty()) return -1

        val touchDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val touchPos = Offset(geometry.anchor.x + dx, geometry.anchor.y + dy)
        val density = context.resources.displayMetrics.density
        val iconPx = geometry.iconSize * density
        val hitRadius = iconPx * 1.2f

        var bestIdx = -1
        var bestScore = Float.MAX_VALUE

        geometry.items.forEachIndexed { index, item ->
            val itemPos = Offset(item.centerX, item.centerY)
            val distancePx = distance(touchPos, itemPos)
            val angleDiff = angleDiffDeg(touchDeg, item.angle)

            val score = distancePx * 0.6f + angleDiff * 3f
            if (score < bestScore && distancePx < hitRadius * 1.5f) {
                bestScore = score
                bestIdx = index
            }
        }

        return bestIdx
    }

    private fun calcSelectedIndexByPosition(
        x: Float, y: Float,
        geometry: FanGeometry
    ): Int {
        if (geometry.items.isEmpty()) return -1

        val touchPos = Offset(x, y)
        val density = context.resources.displayMetrics.density
        val iconPx = geometry.iconSize * density
        val hitRadius = iconPx * 0.8f

        var bestIdx = -1
        var bestDist = Float.MAX_VALUE
        geometry.items.forEachIndexed { index, item ->
            val itemPos = Offset(item.centerX, item.centerY)
            val dist = distance(touchPos, itemPos)
            if (dist < hitRadius && dist < bestDist) {
                bestDist = dist
                bestIdx = index
            }
        }

        return bestIdx
    }

    private fun applyHysteresis(
        candidate: Int,
        dx: Float, dy: Float,
        geometry: FanGeometry
    ): Int {
        if (candidate == -1) return lastSelectedIndex
        if (lastSelectedIndex == -1 || lastSelectedIndex !in geometry.items.indices) {
            lastSelectedIndex = candidate
            return candidate
        }

        val touchDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val currentDiff = angleDiffDeg(touchDeg, geometry.items[lastSelectedIndex].angle)
        val candidateDiff = angleDiffDeg(touchDeg, geometry.items[candidate].angle)

        return if (candidateDiff < currentDiff - 8f) {
            lastSelectedIndex = candidate
            candidate
        } else {
            lastSelectedIndex
        }
    }

    private fun angleDiffDeg(a: Float, b: Float): Float {
        var d = abs(a - b)
        while (d > 180f) d -= 360f
        return abs(d)
    }

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
        val barPadding = quickIconPx * 0.5f

        Log.d(TAG, "quickBarTap: touch=($x,$y) barX=${geometry.quickBarX.toInt()} barY=${geometry.quickBarY.toInt()}")

        if (geometry.quickBarVertical) {
            for (i in quickAppsList.indices) {
                val cy = geometry.quickBarY + barPadding + i * (quickIconPx + pxSpacing) + quickIconPx / 2f
                val cx = geometry.quickBarX + barPadding + quickIconPx / 2f
                val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
                if (d <= quickIconPx * 0.7f) {
                    Log.i(TAG, "quickSelected: ${quickAppsList[i].packageName}")
                    onQuickAppSelected?.invoke(quickAppsList[i])
                    return
                }
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
