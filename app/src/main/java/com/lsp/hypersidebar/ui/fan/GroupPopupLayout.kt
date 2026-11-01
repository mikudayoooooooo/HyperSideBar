package com.lsp.hypersidebar.ui.fan

import com.lsp.hypersidebar.prefs.LayoutDefaults
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 组竖向弹层（2026-10-31 用户走查后拍板"竖向弹出 + 背板"，否决"横向栏内展开"与"沿扇环展开"）。
 *
 * 为什么竖向：组上限 3 个子项，竖列 + 包住它们的背板最能表达"这是组里面的东西"；
 * 横向内联会让子项与同栏的普通快捷项混成一排，层级消失；沿扇环展开则与扇形选中的
 * 极坐标命中互相干扰。
 *
 * 全部坐标为窗口本地 **像素**，与 [FanGeometry] 同一坐标系（锚点/快捷栏未平移）。
 * 纯函数、无 Android 依赖 ⇒ 由 `GroupPopupLayoutTest` 直接覆盖。
 */
internal data class GroupPopup(
    /** 背板外框（px）。x/y 为左上角 */
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val iconPx: Float,
    val gapPx: Float,
    val padPx: Float,
    /** 与组图标同高的子项下标（"第二子项对齐"；子项不足 2 个时取最后一项） */
    val anchorRow: Int,
    /** true=弹层向右展开（组图标在左半屏）；false=向左镜像。名称标签也按它镜像。 */
    val onRight: Boolean,
    /** 每个子项图标中心（px），自上而下 */
    val children: List<Pair<Float, Float>>
)

internal object GroupPopupLayout {

    /** 弹层左缘相对组图标中心的横向偏移（相对快捷图标边长）：让开组图标本身 */
    const val ALIGN_OFFSET_RATIO = 0.35f
    /** 子项命中容差（相对子项图标）：手指粗一点也能点中 */
    const val HIT_TOL_RATIO = 0.2f

    fun compute(
        geometry: FanGeometry,
        groupIndex: Int,
        childCount: Int,
        density: Float
    ): GroupPopup {
        val q = geometry.quickIconSize * density
        val icon = q * LayoutDefaults.GROUP_POPUP_ICON_SCALE
        val gap = icon * LayoutDefaults.GROUP_POPUP_GAP_RATIO
        val pad = icon * LayoutDefaults.GROUP_POPUP_PAD_RATIO
        val w = icon + 2f * pad
        val n = max(1, childCount)
        val h = n * icon + (n - 1) * gap + 2f * pad

        // 组图标中心（与 ComposeFanHost.computeQuickAppCenter / QuickAppsBar 同式）
        val step = q * (1f + QUICK_BAR_GAP_RATIO)
        val groupCx = geometry.quickBarX + 0.5f * q + groupIndex * step + 0.5f * q
        val groupCy = geometry.quickBarY + 0.5f * q + 0.5f * q

        // 竖向（2026-11-01 用户口径）：让"第二个子项"与组图标**同高**——弹层因此横跨快捷栏，
        // 读作"组图标是这列里的第二个"。不足两项时退化为最后一项，绝不出现空锚点。
        val anchorRow = min(1, n - 1)
        val anchorOffset = pad + anchorRow * (icon + gap) + icon / 2f
        val winH = geometry.windowSize.height.toFloat()
        val y = (groupCy - anchorOffset).coerceIn(0f, (winH - h).coerceAtLeast(0f))

        // 横向：**镜像对称**（2026-11-01 用户："位置应做成对称的，避免被手指挡住"）——
        // 组图标在屏幕左半边 ⇒ 弹层向右展开；在右半边 ⇒ 向左展开（左缘 = 组图标中心 −
        // 偏移 − 宽）。两个方向的让开量相同（ALIGN_OFFSET_RATIO×图标），左右手感一致，
        // 且永远朝"离手指/手掌更远的一侧"长，不会被手挡住。
        val maxX = (geometry.windowSize.width - w).coerceAtLeast(0f)
        val offset = ALIGN_OFFSET_RATIO * q
        val toRight = groupCx < geometry.windowSize.width / 2f
        val rawX = if (toRight) groupCx + offset else groupCx - offset - w
        val x = rawX.coerceIn(0f, maxX)

        val centers = (0 until n).map { i ->
            (x + w / 2f) to (y + pad + i * (icon + gap) + icon / 2f)
        }
        return GroupPopup(x, y, w, h, icon, gap, pad, anchorRow, toRight, centers)
    }

    /** 子项命中索引；未命中返回 -1。判定为图标外扩 [HIT_TOL_RATIO] 的方块。 */
    fun hitIndex(p: GroupPopup, x: Float, y: Float, tolPx: Float = p.iconPx * HIT_TOL_RATIO): Int {
        val half = p.iconPx / 2f + tolPx
        p.children.forEachIndexed { i, c ->
            if (abs(x - c.first) <= half && abs(y - c.second) <= half) return i
        }
        return -1
    }

    /** 是否落在背板（外扩 padPx*1.2）内——"点了别处 → 收起"的判据。 */
    fun inside(p: GroupPopup, x: Float, y: Float): Boolean {
        val m = p.padPx * 1.2f
        return x >= p.x - m && x <= p.x + p.width + m &&
            y >= p.y - m && y <= p.y + p.height + m
    }
}
