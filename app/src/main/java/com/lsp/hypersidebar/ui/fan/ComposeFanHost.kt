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
import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.theme.HyperSidebarTheme
import com.lsp.hypersidebar.theme.ThemeModes
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

private const val TAG = "ComposeFanHost"

/** 分数迟滞余量：仅当最优项显著优于当前项才切换，杜绝预选抖动翻转。 */
private const val HYSTERESIS_MARGIN = 0.15f

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
        Log.i(
            TAG,
            "geometry: anchor=(${geometry.anchor.x.toInt()},${geometry.anchor.y.toInt()}) " +
                "outer=${geometry.outerRadius.toInt()} inner=${geometry.innerRadius.toInt()} " +
                "span=[${geometry.startAngle.toInt()},${geometry.endAngle.toInt()}] " +
                "icon=${geometry.iconSize} quickBar=(${geometry.quickBarX.toInt()},${geometry.quickBarY.toInt()})"
        )

        val touchState = mutableStateOf(FanTouchState(0f, 0f, 3, -1))
        val activeZonePx = geometry.activeZonePx
        // 行为规则 4：实际死区 = max(deadZone×density, innerRadius×0.08)，上限 60px
        val deadZonePx = maxOf(config.deadZoneDp * density, geometry.innerRadius * 0.08f).coerceAtMost(60f)
        val innerCancelPx = ((geometry.innerRadius * 0.85f - geometry.iconSize * density * 0.5f) * 0.75f)
        val outerCancelPx = ((geometry.outerRadius + geometry.iconSize * density * 0.5f) * 1.25f)
        // 选区计算必须用钳制后的圆心（几何层可能平移 anchorY 保屏内），否则触摸映射错位
        val ax = geometry.anchor.x
        val ay = geometry.anchor.y

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
            // 窗口在屏上的原点（每手势 DOWN 刷新）：命中测试必须与渲染同处窗口本地坐标系。
            // 若 overlay 窗口被系统 inset（让出状态栏等），raw 屏幕坐标与本地坐标会差出
            // 一个状态栏高度（实测≈110px），"指到的图标"与"命中的扇区"系统性错一位——
            // 表现为"碰到哪个开隔壁的、扇区两端打不开"
            private val viewOrigin = IntArray(2)
            private var originValid = false

            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                val rawX = event.rawX
                val rawY = event.rawY
                // 窗口原点带有效性持续重试：DOWN 可能早于布局完成（getLocationOnScreen
                // 返回 0），每个事件都重试直到捕获有效原点（attach 且有宽度）
                if (!originValid && isAttachedToWindow && width > 0) {
                    runCatching { getLocationOnScreen(viewOrigin) }
                    originValid = true
                    Log.i(TAG, "fan origin=(${viewOrigin[0]},${viewOrigin[1]}) size=(${width},${height})")
                }
                val x = rawX - viewOrigin[0]
                val y = rawY - viewOrigin[1]
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

                        if (fanSel != prevFan || quickSel != prevQuick) {
                            Log.d(TAG, "sel change: fan $prevFan->$fanSel quick $prevQuick->$quickSel dist=${dist.toInt()}")
                        }

                        val anySelected = fanSel != -1 || quickSel != -1
                        if (anySelected && (fanSel != prevFan || quickSel != prevQuick)) {
                            selectedSince = SystemClock.uptimeMillis()
                        }

                        touchState.value = FanTouchState(x, y, 0, fanSel, quickSel)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val (fanSel, quickSel) = resolveSelection(
                            x, y, dx, dy, dist, deadZonePx, geometry
                        )
                        touchState.value = FanTouchState(x, y, 2, -1, -1)
                        // 取证 dump：本地/原始坐标 + 极坐标 + 命中项全量，选中错位一轮日志定位
                        val deg = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble()))
                        // selectedSince==0 = 手势期间从未预选过（快速扫过即松手），dwell 按 0 计——
                        // 顺带修真 bug：此前 uptime−0 为巨大垃圾值，会让 <150ms 快速松手也通过
                        // 预选时长检查（违反 PRD"预选不足 150ms 松手不启动"）
                        val dwellMs = if (selectedSince == 0L) 0L else SystemClock.uptimeMillis() - selectedSince
                        val hitItem = geometry.items.getOrNull(fanSel)
                        Log.i(
                            TAG,
                            "UP resolve: local=(${x.toInt()},${y.toInt()}) raw=(${rawX.toInt()},${rawY.toInt()}) " +
                                "dist=${dist.toInt()} deg=${"%.1f".format(deg)} fan=$fanSel" +
                                (hitItem?.let {
                                    " [${it.app.packageName} ang=${"%.1f".format(it.angle)} rad=${it.radius.toInt()} ctr=(${it.centerX.toInt()},${it.centerY.toInt()})]"
                                } ?: "") +
                                " quick=$quickSel dwell=$dwellMs"
                        )

                        val dwellTime = if (selectedSince == 0L) 0L else SystemClock.uptimeMillis() - selectedSince
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
            wrapper.post {
                val loc = IntArray(2)
                runCatching { wrapper.getLocationOnScreen(loc) }
                Log.i(TAG, "fan window: origin=(${loc[0]},${loc[1]}) size=(${wrapper.width},${wrapper.height}) anchor=(${ax.toInt()},${ay.toInt()})")
            }
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

        val quickCandidate = calcQuickAppCandidate(x, y, geometry)
        lastSelectedFanIndex = resolveFanSelection(dx, dy, dist, deadZonePx, geometry)
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

    /**
     * 极坐标评分选中（实测轮六重做）：
     * - score = (Δangle/sectorWidth)² + (Δdist/ringGap)²，角度与半径双维度归一化，
     *   治"同角位内外圈项径向竞争翻转"的预选跳变
     * - 可选带门控：|dist − item.radius| ≤ max(ringGap×0.55, iconPx×0.7)——
     *   图标周围有限可选带，环间空隙与锚点近区不再误点亮远处图标；单圈退化为纯角度
     * - 分数迟滞：仅当最优项 score < 当前项 − 0.15 才切换；当前项出带立即切换。
     *   选择稳定后 DWELL 计时自然累积，修"难命中"
     */
    private fun resolveFanSelection(
        dx: Float, dy: Float, dist: Float,
        deadZonePx: Float, geometry: FanGeometry
    ): Int {
        if (dist < deadZonePx || geometry.items.isEmpty()) return -1

        val touchDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        // 扇区宽度按各自环计算（外圈 span/外圈数、内圈 span/内圈数）：此前用全 items 数平摊，
        // 内圈实际扇区更宽却被同一尺度归一化 → 分数系统性偏大，选中偏向外圈、内圈难命中
        val outerCnt = geometry.items.count { it.isOuter }
        val innerCnt = geometry.items.size - outerCnt
        val density = context.resources.displayMetrics.density
        val iconPx = geometry.iconSize * density

        val radii = geometry.items.map { it.radius }.distinct()
        val dualRing = radii.size >= 2
        val ringGap = if (dualRing) abs(radii[0] - radii[1]) else 0f
        val band = if (dualRing) maxOf(ringGap * 0.55f, iconPx * 0.7f) else iconPx * 1.6f

        var bestIdx = -1
        var bestScore = Float.MAX_VALUE
        var curScore = Float.MAX_VALUE
        geometry.items.forEach { item ->
            val dRad = abs(dist - item.radius)
            if (dRad > band) return@forEach
            val ringSector = if (item.isOuter) {
                if (outerCnt > 1) geometry.spanAngle / outerCnt else geometry.spanAngle
            } else {
                if (innerCnt > 1) geometry.spanAngle / innerCnt else geometry.spanAngle
            }
            val aNorm = angleDiffDeg(touchDeg, item.angle) / ringSector
            val rNorm = if (dualRing && ringGap > 0f) dRad / ringGap else 0f
            val score = aNorm * aNorm + rNorm * rNorm
            if (item.index == lastSelectedFanIndex) curScore = score
            if (score < bestScore) {
                bestScore = score
                bestIdx = item.index
            }
        }

        if (bestIdx == -1) return -1
        if (lastSelectedFanIndex !in geometry.items.indices || curScore == Float.MAX_VALUE) {
            return bestIdx
        }
        return if (bestScore < curScore - HYSTERESIS_MARGIN) bestIdx else lastSelectedFanIndex
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
        // 命中半径用几何层生效的快捷图标尺寸（跟随扇形图标收缩），与 computeQuickAppCenter
        // 及渲染保持同一尺寸源；此前用 config 原始值，图标收缩后命中圈偏大错位
        val quickIconPx = geometry.quickIconSize * density
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
        val mode = prefs.getString(PrefKeys.THEME_MODE, ThemeModes.MONET_SYSTEM) ?: ThemeModes.MONET_SYSTEM
        HyperSidebarTheme(colorMode = mode) {
            content()
        }
    }

    private fun buildFanConfig(): FanConfig {
        return FanConfig(
            iconSizeDp = readFloat(PrefKeys.ICON_SIZE, LayoutDefaults.ICON_SIZE),
            quickIconSizeDp = LayoutDefaults.QUICK_ICON_SIZE,
            innerRadiusDp = readFloat(PrefKeys.INNER_RADIUS, LayoutDefaults.INNER_RADIUS),
            outerRadiusDp = readFloat(PrefKeys.OUTER_RADIUS_MAX, LayoutDefaults.OUTER_RADIUS_MAX),
            deadZoneDp = readFloat(PrefKeys.DEAD_ZONE, LayoutDefaults.DEAD_ZONE),
            activeZoneDp = readFloat(PrefKeys.ACTIVE_ZONE, LayoutDefaults.ACTIVE_ZONE),
            useDualRing = true,
            minRadiusDp = 60f,
            maxAppsOuter = readInt(PrefKeys.MAX_APPS_OUTER, LayoutDefaults.MAX_APPS_OUTER),
            maxAppsInner = readInt(PrefKeys.MAX_APPS_INNER, LayoutDefaults.MAX_APPS_INNER),
            landscapeIconSizeDp = readFloat(PrefKeys.LANDSCAPE_ICON_SIZE, LayoutDefaults.LANDSCAPE_ICON_SIZE),
            landscapeMaxAppsOuter = readInt(PrefKeys.LANDSCAPE_MAX_APPS_OUTER, LayoutDefaults.LANDSCAPE_MAX_APPS_OUTER),
            landscapeMaxAppsInner = readInt(PrefKeys.LANDSCAPE_MAX_APPS_INNER, LayoutDefaults.LANDSCAPE_MAX_APPS_INNER),
            landscapeInnerRadiusDp = readFloat(PrefKeys.LANDSCAPE_INNER_RADIUS, LayoutDefaults.LANDSCAPE_INNER_RADIUS),
            landscapeOuterRadiusDp = readFloat(PrefKeys.LANDSCAPE_OUTER_RADIUS, LayoutDefaults.LANDSCAPE_OUTER_RADIUS)
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
        val mode = prefs.getString(PrefKeys.THEME_MODE, ThemeModes.MONET_SYSTEM) ?: ThemeModes.MONET_SYSTEM
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
