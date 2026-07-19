package com.lsp.hypersidebar.ui.fan

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.SystemClock
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
    val selectedIndex: Int,
    val selectedQuickIndex: Int = -1
)

class ComposeFanHost(
    private val context: Context,
    private val prefs: SharedPreferences
) {
    private var wrapperView: View? = null
    private var composeView: ComposeView? = null
    private var windowManager: WindowManager? = null
    private var lifecycleOwner: FanLifecycleOwner? = null
    private var lastSelectedFanIndex = -1
    private var lastSelectedQuickIndex = -1
    private var selectedSince = 0L
    private val DWELL_MS = 150L

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
        val deadZonePx = (geometry.innerRadius * 0.08f).coerceIn(24f, 60f)
        val innerCancelPx = ((geometry.innerRadius * 0.85f - geometry.iconSize * density * 0.5f) * 0.75f)
        val outerCancelPx = ((geometry.outerRadius + geometry.iconSize * density * 0.5f) * 1.25f)
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
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        lastSelectedFanIndex = -1
                        lastSelectedQuickIndex = -1
                        selectedSince = 0L
                        val (fanSel, quickSel) = resolveSelection(
                            x, y, dx, dy, dist, deadZonePx, geometry
                        )
                        if (fanSel != -1 || quickSel != -1) selectedSince = SystemClock.uptimeMillis()
                        touchState.value = FanTouchState(x, y, 0, fanSel, quickSel)
                        Log.d(TAG, "touch DOWN fan=$fanSel quick=$quickSel dist=${dist.toInt()}")
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val prevFan = lastSelectedFanIndex
                        val prevQuick = lastSelectedQuickIndex

                        val inCancelZone = dist < innerCancelPx || dist > outerCancelPx
                        if (inCancelZone && (prevFan != -1 || prevQuick != -1)) {
                            lastSelectedFanIndex = -1
                            lastSelectedQuickIndex = -1
                            touchState.value = FanTouchState(x, y, 0, -1, -1)
                            Log.d(TAG, "touch CLEAR by cancel zone (inner=${innerCancelPx.toInt()}, outer=${outerCancelPx.toInt()})")
                            return true
                        }

                        val (fanSel, quickSel) = resolveSelection(
                            x, y, dx, dy, dist, deadZonePx, geometry
                        )

                        val anySelected = fanSel != -1 || quickSel != -1
                        if (anySelected && (fanSel != prevFan || quickSel != prevQuick)) {
                            selectedSince = SystemClock.uptimeMillis()
                        }

                        touchState.value = FanTouchState(x, y, 0, fanSel, quickSel)
                        Log.d(TAG, "touch MOVE fan=$fanSel quick=$quickSel dist=${dist.toInt()}")
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val (fanSel, quickSel) = resolveSelection(
                            x, y, dx, dy, dist, deadZonePx, geometry
                        )
                        touchState.value = FanTouchState(x, y, 2, -1, -1)
                        Log.d(TAG, "ACTION_UP fan=$fanSel quick=$quickSel dist=${dist.toInt()}")

                        val dwellTime = SystemClock.uptimeMillis() - selectedSince
                        val anySelected = fanSel in geometry.items.indices
                        val anyQuick = quickSel in geometry.quickApps.indices

                        when {
                            !anySelected && !anyQuick -> {
                                handleQuickBarTap(x, y, geometry, config, density)
                            }
                            anySelected && dwellTime < DWELL_MS -> {
                                Log.d(TAG, "dwell too short: ${dwellTime}ms, not launching")
                            }
                            anyQuick && dwellTime < DWELL_MS -> {
                                Log.d(TAG, "dwell too short: ${dwellTime}ms, not launching")
                            }
                            anySelected -> {
                                Log.i(TAG, "selected fan: ${geometry.items[fanSel].app.packageName}")
                                onAppSelected?.invoke(geometry.items[fanSel].app)
                            }
                            anyQuick -> {
                                Log.i(TAG, "selected quick: ${geometry.quickApps[quickSel].packageName}")
                                onQuickAppSelected?.invoke(geometry.quickApps[quickSel])
                            }
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        touchState.value = FanTouchState(x, y, 3, -1, -1)
                        lastSelectedFanIndex = -1
                        lastSelectedQuickIndex = -1
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
        lastSelectedFanIndex = -1
        lastSelectedQuickIndex = -1

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
        geometry: FanGeometry
    ): Pair<Int, Int> {
        if (dist < deadZonePx) {
            lastSelectedFanIndex = -1
            lastSelectedQuickIndex = -1
            return -1 to -1
        }

        val fanCandidate = calcUnifiedFanSelection(dx, dy, dist, deadZonePx, geometry)
        val quickCandidate = calcQuickAppCandidate(x, y, geometry)

        lastSelectedFanIndex = applyFanHysteresis(fanCandidate, dx, dy, geometry)
        lastSelectedQuickIndex = quickCandidate

        return resolveDualSelection(x, y, geometry)
    }

    private fun resolveDualSelection(
        x: Float, y: Float,
        geometry: FanGeometry
    ): Pair<Int, Int> {
        val fanIdx = lastSelectedFanIndex
        val quickIdx = lastSelectedQuickIndex

        return when {
            fanIdx == -1 && quickIdx == -1 -> -1 to -1
            fanIdx == -1 -> -1 to quickIdx
            quickIdx == -1 -> fanIdx to -1
            else -> {
                val touchPos = Offset(x, y)
                val fanItem = geometry.items[fanIdx]
                val fanDist = distance(touchPos, Offset(fanItem.centerX, fanItem.centerY))
                val quickCenter = computeQuickAppCenter(quickIdx, geometry)
                val quickDist = distance(touchPos, quickCenter)
                if (fanDist <= quickDist) fanIdx to -1 else -1 to quickIdx
            }
        }
    }

    private fun computeQuickAppCenter(
        index: Int,
        geometry: FanGeometry
    ): Offset {
        val density = context.resources.displayMetrics.density
        val quickIconPx = geometry.quickIconSize * density
        val pxSpacing = quickIconPx * 0.35f
        val barPadding = quickIconPx * 0.5f
        val cx = geometry.quickBarX + barPadding + index * (quickIconPx + pxSpacing) + quickIconPx / 2f
        val cy = geometry.quickBarY + barPadding + quickIconPx / 2f
        return Offset(cx, cy)
    }

    private fun calcUnifiedFanSelection(
        dx: Float, dy: Float, dist: Float,
        deadZonePx: Float, geometry: FanGeometry
    ): Int {
        if (dist < deadZonePx) return -1
        if (geometry.items.isEmpty()) return -1

        val touchDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val sectorWidth = geometry.spanAngle / geometry.items.size

        val candidates = geometry.items.filter { item ->
            val isEdge = item.index < 2 || item.index >= geometry.items.size - 2
            val angleTolerance = if (isEdge) sectorWidth * 1.3f else sectorWidth
            angleDiffDeg(touchDeg, item.angle) < angleTolerance
        }

        return candidates.minByOrNull { abs(dist - it.radius) }?.index ?: -1
    }

    private fun calcQuickAppCandidate(
        x: Float, y: Float,
        geometry: FanGeometry
    ): Int {
        if (geometry.quickApps.isEmpty()) return -1

        val touchPos = Offset(x, y)
        val density = context.resources.displayMetrics.density
        val quickIconPx = geometry.quickIconSize * density
        val hitRadius = quickIconPx * 0.8f

        var bestIdx = -1
        var bestDist = Float.MAX_VALUE
        geometry.quickApps.take(6).forEachIndexed { index, _ ->
            val center = computeQuickAppCenter(index, geometry)
            val dist = distance(touchPos, center)
            if (dist < hitRadius && dist < bestDist) {
                bestDist = dist
                bestIdx = index
            }
        }

        return bestIdx
    }

    private fun applyFanHysteresis(
        candidate: Int,
        dx: Float, dy: Float,
        geometry: FanGeometry
    ): Int {
        if (candidate == -1) return lastSelectedFanIndex
        if (lastSelectedFanIndex == -1 || lastSelectedFanIndex !in geometry.items.indices) {
            lastSelectedFanIndex = candidate
            return candidate
        }

        val touchDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val currentDiff = angleDiffDeg(touchDeg, geometry.items[lastSelectedFanIndex].angle)
        val candidateDiff = angleDiffDeg(touchDeg, geometry.items[candidate].angle)

        val hysteresisThreshold = if (geometry.items.size > 1) {
            geometry.spanAngle / geometry.items.size * 0.5f
        } else 8f

        return if (candidateDiff < currentDiff - hysteresisThreshold) {
            lastSelectedFanIndex = candidate
            candidate
        } else {
            lastSelectedFanIndex
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

        for (i in quickAppsList.indices) {
            val cx = geometry.quickBarX + barPadding + i * (quickIconPx + pxSpacing) + quickIconPx / 2f
            val cy = geometry.quickBarY + barPadding + quickIconPx / 2f
            val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
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

    private fun buildFanConfig(): FanConfig {
        return FanConfig(
            iconSizeDp = readFloat("iconSize", 48f),
            quickIconSizeDp = 36f,
            innerRadiusDp = readFloat("innerRadius", 150f),
            outerRadiusDp = readFloat("outerRadiusMax", 200f),
            deadZoneDp = readFloat("deadZone", 12f),
            activeZoneDp = readFloat("activeZone", 60f),
            useDualRing = true,
            minRadiusDp = 80f,
            maxAppsOuter = readInt("maxAppsOuter", 7),
            maxAppsInner = readInt("maxAppsInner", 4),
            landscapeIconSizeDp = readFloat("landscapeIconSize", 48f),
            landscapeMaxAppsOuter = readInt("landscapeMaxAppsOuter", 5),
            landscapeMaxAppsInner = readInt("landscapeMaxAppsInner", 3),
            landscapeInnerRadiusDp = readFloat("landscapeInnerRadius", 150f),
            landscapeOuterRadiusDp = readFloat("landscapeOuterRadius", 200f)
        )
    }

    private fun readFloat(key: String, default: Float): Float {
        return try { prefs.getFloat(key, default) } catch (_: Exception) { default }
    }

    private fun readInt(key: String, default: Int): Int {
        return try { prefs.getInt(key, default) } catch (_: Exception) { default }
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
