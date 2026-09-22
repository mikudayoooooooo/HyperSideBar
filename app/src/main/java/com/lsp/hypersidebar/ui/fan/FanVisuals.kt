package com.lsp.hypersidebar.ui.fan

import androidx.compose.ui.unit.dp

/**
 * 扇形 / 快捷栏的**视觉调参唯一来源**（0921 抽离）。
 *
 * 为什么单独成文件：这些比例、透明度、描边宽度散布在 `FanBoard` / `FanMenuCompose` /
 * `QuickAppsBar`，而设置页预览（`FanPreview`）只能**手抄一份**——同一套观感两处各写一次，
 * 滑条一动或改一处就走样（`PreviewQuickBar` 曾手抄 0.35/0.5/10dp，`take(4)` 又让预览包围盒
 * 比实机窄）。凡"真机与预览必须一致"的数字都放这里，两边引用同一常量。
 *
 * 口径约定：
 * - 比例类（`*_RATIO`）相对**图标边长**，与 `FanBoard` 的 `quickCapsuleMetrics` 同族；
 * - 尺寸类用 `Dp`（Compose 侧），仅在 `Canvas`/px 运算里才 `* density`；
 * - 用户可调项**不进本文件**——那些在 `LayoutDefaults`（如雾化浓度、模糊半径）。
 */
internal object FanVisuals {

    // ===== 图标（扇形与快捷栏必须同观感：B1「圆角 mask 统一」）=====

    /** 圆角 / 图标边长。扇形图标、快捷栏图标、预览占位块共用。 */
    const val ICON_CORNER_RATIO = 0.25f

    /**
     * 图标位图占格比例：0.92 = 几乎占满、留 8% 呼吸。
     * 历史 0.7（圆形托底时代）→ 1.0（快捷栏）→ 统一 0.92（与 AllApps 去托底口径一致）；
     * 快捷栏曾按 1.0 绘制，同 iconSize 下肉眼比扇形大一圈。
     */
    const val ICON_FILL_RATIO = 0.92f

    /** 选中放大倍数（PRD §7.3.2「图标放大 1.25 倍」）；标签避让计算同源。 */
    const val SELECTED_ICON_SCALE = 1.25f

    /** 选中态描边宽度（绘于图标之上）。 */
    val SELECTION_BORDER_WIDTH = 2.dp

    /** 选中高亮板填充透明度（primaryContainer × 此值）。 */
    const val SELECTION_PLATE_ALPHA = 0.9f

    // ===== 应用名标签（扇形 SelectedLabel 与快捷栏标签逐项同参）=====

    val LABEL_CORNER = 12.dp
    val LABEL_PADDING_H = 10.dp
    val LABEL_PADDING_V = 4.dp
    /** 底填充透明度：板材质与标签同色系，低于此值标签会融进板里（0914 真机「看不到框选效果」）。 */
    const val LABEL_FILL_ALPHA = 0.95f
    val LABEL_BORDER_WIDTH = 1.dp
    const val LABEL_BORDER_ALPHA = 0.65f
    /** 标签底边与（放大后）图标顶边的间距。 */
    val LABEL_GAP = 10.dp

    // ===== 板材质：磨砂弧带与图标群的关系 =====

    /** 弧带内外缘相对图标外沿的外扩量（px 口径 ×density）：让图标坐在带子里。 */
    const val BAND_EDGE_PAD_DP = 12f

    /** 内缘半径下限（px 口径 ×density）：极窄扇形下防止内缘塌成负值。 */
    const val BAND_MIN_INNER_RADIUS_DP = 24f

    // ===== ③ 玻璃描边层（不参与模糊、始终清晰）=====

    /** 极淡柔光弧（假 bloom）宽度与透明度。 */
    val BAND_BLOOM_STROKE_WIDTH = 3.dp
    const val BAND_BLOOM_ALPHA = 0.18f
    /** 最外层单条细弧宽度。 */
    val BAND_STROKE_WIDTH = 1.5.dp
    /** 弧线竖向渐变：深色主题用白、浅色主题用 outline；顶亮底暗（玻璃受顶光）。 */
    const val BAND_STROKE_TOP_ALPHA_DARK = 0.50f
    const val BAND_STROKE_TOP_ALPHA_LIGHT = 0.45f
    const val BAND_STROKE_BOTTOM_ALPHA_DARK = 0.06f
    const val BAND_STROKE_BOTTOM_ALPHA_LIGHT = 0.10f
    val CAPSULE_STROKE_WIDTH = 1.25.dp
    /** 胶囊框线**恒定可见色**：不蹭弧带那支竖向渐变（底角档胶囊落在渐变区间外会被 clamp 到最暗端）。 */
    const val CAPSULE_STROKE_ALPHA_DARK = 0.35f
    const val CAPSULE_STROKE_ALPHA_LIGHT = 0.55f

    /** 入场扫开小于此值不画描边（省掉首帧空画）。 */
    const val SWEEP_VISIBLE_EPSILON = 0.01f

    /**
     * 弧带渐进模糊的衰减曲线（miuix `ProgressiveBlur.curve`）。
     * shader 实证：`radius = maxRadius × (1 − smoothstep(raw)^curve)`
     * —— `raw=0` 端（startFraction 侧）最糊、`raw=1` 端（endFraction 侧）降为 0；
     * 1.0 = 平滑过渡；>1 让"清晰区"更靠外、过渡更集中在弧缘。
     */
    const val PROGRESSIVE_BLUR_CURVE = 1.0f

    // ===== 入场动画：图标从锚点沿半径飞出 =====

    /** 起飞起点缩放与飞行区间（终态 1.0）。 */
    const val ICON_FLY_START_SCALE = 0.4f
    const val ICON_FLY_SCALE_SPAN = 0.6f

    // ===== 设置页抽象预览（真机口径 + 预览专用示意）=====

    /** 预览虚拟系密度：预览框是 3:4/4:3 缩略图，用半密度让几何接近真机观感。 */
    const val PREVIEW_DENSITY = 0.5f

    /** 占位图标填充（primaryContainer × 此值）。 */
    const val PREVIEW_PLACEHOLDER_ALPHA = 0.55f
    /** 预览视口在内容外再留的边距（× 图标边长）。 */
    const val PREVIEW_VIEWPORT_PAD_RATIO = 0.55f
    /** 弧带剪影（抽象平涂，替代旧填充饼 + 双轨道）。 */
    const val PREVIEW_BAND_FILL_ALPHA = 0.92f
    /** 预览框：圆角、描边宽度、描边透明度。 */
    val PREVIEW_FRAME_CORNER = 12.dp
    val PREVIEW_FRAME_BORDER_WIDTH = 1.dp
    const val PREVIEW_FRAME_BORDER_ALPHA = 0.35f
    /** 预览里快捷栏示意盒子（真机材质不参与预览，故给一层示意底）。 */
    const val PREVIEW_CAPSULE_FILL_ALPHA = 0.94f
    const val PREVIEW_CAPSULE_BORDER_ALPHA = 0.30f
    /** 布局 sheet 内实时预览的高度。 */
    val PREVIEW_SHEET_HEIGHT = 150.dp
}