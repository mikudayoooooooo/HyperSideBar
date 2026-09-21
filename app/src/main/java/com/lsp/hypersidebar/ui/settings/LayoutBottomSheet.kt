package com.lsp.hypersidebar.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.prefs.SettingsRepository
import com.lsp.hypersidebar.ui.fan.CORNER_SPAN_DEG
import com.lsp.hypersidebar.ui.fan.FanVisuals
import com.lsp.hypersidebar.ui.fan.effectiveIconSizeDp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class LayoutOrientation { PORTRAIT, LANDSCAPE, CORNER }

/**
 * 布局参数编辑 BottomSheet（§2.2 本迭代核心交互）。
 *
 * 草稿/提交语义：滑条只写 [SettingsRepository.putDraft]（实时预览跟随 revision 通道），
 * 保存=commitDraft 单 editor 批量落盘（一次 revision 跳变、hook 侧一次 LSPosed 推送），
 * 取消/下滑/返回关闭=discardDraft 整体丢弃（丢弃推迟到 [onDismissFinished]——
 * 退出动画期间内容保持组合，跟着 sheet 一起滑下）。竖/横屏/底角各 5 项样式、完全独立（行为规则 6）；
 * 底角变体额外承载「底角斜滑」触发开关（同为草稿键，✕ 连带撤销）。
 * 约束联动（行为规则 2）：内圈半径 ≤ 外圈×80%，调外圈时内圈上限同步、超限钳制并提示。
 */
@Composable
internal fun LayoutBottomSheet(
    show: Boolean,
    orientation: LayoutOrientation,
    repo: SettingsRepository,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit
) {
    OverlayBottomSheet(
        show = show,
        title = stringResource(layoutSpec(orientation).titleRes),
        startAction = {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = MiuixIcons.Close,
                    contentDescription = stringResource(R.string.layout_sheet_cancel),
                    tint = MiuixTheme.colorScheme.onSurface
                )
            }
        },
        endAction = {
            IconButton(onClick = {
                repo.commitDraft()
                onDismiss()
            }) {
                Icon(
                    imageVector = MiuixIcons.Basic.Check,
                    contentDescription = stringResource(R.string.layout_sheet_save),
                    tint = MiuixTheme.colorScheme.primary
                )
            }
        },
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished,
        content = {
            // 不设 if(show) 门：退出动画期间内容保持组合随 sheet 滑下
            LayoutSheetContent(orientation = orientation, repo = repo)
        }
    )
}

/**
 * 单个方向的布局参数契约：键组 / 默认值 / 数量量程 / 弦长口径的唯一来源。
 * 三种方向（竖屏 / 横屏 / 底角）同构，禁止在各处再散写 if(isPortrait)。
 */
private class LayoutSpec(
    val titleRes: Int,
    val iconKey: String,
    val innerKey: String,
    val outerKey: String,
    val outerCountKey: String,
    val innerCountKey: String,
    val defIcon: Float,
    val defInner: Float,
    val defOuter: Float,
    val defOuterCount: Int,
    val defInnerCount: Int,
    /** 弦长收缩口径的张角：竖 150 / 横 75 / 底角 76（=CORNER_SPAN_DEG） */
    val capSpanDeg: Float,
    /** 内/外圈半径滑条量程：底角默认半径(175/250)远大于竖/横屏(110/150)，量程须按方向分开，
     *  否则默认值落在量程外滑条显示不对。步进统一 10dp，steps=(max-min)/step-1。 */
    val innerRadiusRange: ClosedFloatingPointRange<Float>,
    val innerRadiusSteps: Int,
    val outerRadiusRange: ClosedFloatingPointRange<Float>,
    val outerRadiusSteps: Int,
    val innerCountRange: ClosedFloatingPointRange<Float>,
    val innerCountSteps: Int,
    val outerCountRange: ClosedFloatingPointRange<Float>,
    val outerCountSteps: Int
)

private fun layoutSpec(orientation: LayoutOrientation): LayoutSpec = when (orientation) {
    LayoutOrientation.PORTRAIT -> LayoutSpec(
        titleRes = R.string.portrait_layout,
        iconKey = PrefKeys.ICON_SIZE,
        innerKey = PrefKeys.INNER_RADIUS,
        outerKey = PrefKeys.OUTER_RADIUS_MAX,
        outerCountKey = PrefKeys.MAX_APPS_OUTER,
        innerCountKey = PrefKeys.MAX_APPS_INNER,
        defIcon = LayoutDefaults.ICON_SIZE,
        defInner = LayoutDefaults.INNER_RADIUS,
        defOuter = LayoutDefaults.OUTER_RADIUS_MAX,
        defOuterCount = LayoutDefaults.MAX_APPS_OUTER,
        defInnerCount = LayoutDefaults.MAX_APPS_INNER,
        capSpanDeg = 150f,
        innerRadiusRange = 80f..160f,
        innerRadiusSteps = 7,
        outerRadiusRange = 110f..220f,
        outerRadiusSteps = 10,
        innerCountRange = 2f..6f,
        innerCountSteps = 3,
        outerCountRange = 4f..12f,
        outerCountSteps = 7
    )
    LayoutOrientation.LANDSCAPE -> LayoutSpec(
        titleRes = R.string.landscape_layout,
        iconKey = PrefKeys.LANDSCAPE_ICON_SIZE,
        innerKey = PrefKeys.LANDSCAPE_INNER_RADIUS,
        outerKey = PrefKeys.LANDSCAPE_OUTER_RADIUS,
        outerCountKey = PrefKeys.LANDSCAPE_MAX_APPS_OUTER,
        innerCountKey = PrefKeys.LANDSCAPE_MAX_APPS_INNER,
        defIcon = LayoutDefaults.LANDSCAPE_ICON_SIZE,
        defInner = LayoutDefaults.LANDSCAPE_INNER_RADIUS,
        defOuter = LayoutDefaults.LANDSCAPE_OUTER_RADIUS,
        defOuterCount = LayoutDefaults.LANDSCAPE_MAX_APPS_OUTER,
        defInnerCount = LayoutDefaults.LANDSCAPE_MAX_APPS_INNER,
        capSpanDeg = 75f,
        innerRadiusRange = 80f..160f,
        innerRadiusSteps = 7,
        outerRadiusRange = 110f..220f,
        outerRadiusSteps = 10,
        innerCountRange = 0f..6f,
        innerCountSteps = 5,
        outerCountRange = 3f..8f,
        outerCountSteps = 4
    )
    // 底角：量程/钳制口径与竖屏一致，仅键组与弦长张角（70°）不同
    LayoutOrientation.CORNER -> LayoutSpec(
        titleRes = R.string.corner_layout,
        iconKey = PrefKeys.CORNER_ICON_SIZE,
        innerKey = PrefKeys.CORNER_INNER_RADIUS,
        outerKey = PrefKeys.CORNER_OUTER_RADIUS,
        outerCountKey = PrefKeys.CORNER_MAX_APPS_OUTER,
        innerCountKey = PrefKeys.CORNER_MAX_APPS_INNER,
        defIcon = LayoutDefaults.CORNER_ICON_SIZE,
        defInner = LayoutDefaults.CORNER_INNER_RADIUS,
        defOuter = LayoutDefaults.CORNER_OUTER_RADIUS,
        defOuterCount = LayoutDefaults.CORNER_MAX_APPS_OUTER,
        defInnerCount = LayoutDefaults.CORNER_MAX_APPS_INNER,
        capSpanDeg = CORNER_SPAN_DEG,
        innerRadiusRange = 180f..360f,
        innerRadiusSteps = 17,
        outerRadiusRange = 240f..520f,
        outerRadiusSteps = 27,
        innerCountRange = 2f..6f,
        innerCountSteps = 3,
        outerCountRange = 4f..12f,
        outerCountSteps = 7
    )
}

@Composable
private fun LayoutSheetContent(
    orientation: LayoutOrientation,
    repo: SettingsRepository
) {
    val spec = layoutSpec(orientation)
    val isCorner = orientation == LayoutOrientation.CORNER
    val isLandscape = orientation == LayoutOrientation.LANDSCAPE

    // 下面的 remember(repo.revision) 在组合期读 revision 建立订阅：
    // 任何 putDraft（revision++）都驱动本组合重读草稿值
    val density = LocalDensity.current.density

    val iconSize = when (orientation) {
        LayoutOrientation.PORTRAIT -> repo.iconSize()
        LayoutOrientation.LANDSCAPE -> repo.landscapeIconSize()
        LayoutOrientation.CORNER -> repo.cornerIconSize()
    }
    val innerRadius = when (orientation) {
        LayoutOrientation.PORTRAIT -> repo.innerRadius()
        LayoutOrientation.LANDSCAPE -> repo.landscapeInnerRadius()
        LayoutOrientation.CORNER -> repo.cornerInnerRadius()
    }
    val outerRadius = when (orientation) {
        LayoutOrientation.PORTRAIT -> repo.outerRadiusMax()
        LayoutOrientation.LANDSCAPE -> repo.landscapeOuterRadius()
        LayoutOrientation.CORNER -> repo.cornerOuterRadius()
    }
    val outerCount = when (orientation) {
        LayoutOrientation.PORTRAIT -> repo.maxAppsOuter()
        LayoutOrientation.LANDSCAPE -> repo.landscapeMaxAppsOuter()
        LayoutOrientation.CORNER -> repo.cornerMaxAppsOuter()
    }
    val innerCount = when (orientation) {
        LayoutOrientation.PORTRAIT -> repo.maxAppsInner()
        LayoutOrientation.LANDSCAPE -> repo.landscapeMaxAppsInner()
        LayoutOrientation.CORNER -> repo.cornerMaxAppsInner()
    }

    fun putIcon(v: Float) = repo.putDraft(spec.iconKey, v)
    fun putInner(v: Float) = repo.putDraft(spec.innerKey, v)
    fun putOuter(v: Float) = repo.putDraft(spec.outerKey, v)
    fun putOuterCount(v: Int) = repo.putDraft(spec.outerCountKey, v)
    fun putInnerCount(v: Int) = repo.putDraft(spec.innerCountKey, v)

    // 约束联动（行为规则 2）：内圈 ≤ 外圈×80%
    var clampedHint by remember { mutableStateOf(false) }
    fun putInnerWithClamp(v: Float) {
        val capped = v.coerceAtMost(outerRadius * 0.8f)
        clampedHint = capped < v
        putInner(capped)
    }
    fun putOuterWithClamp(v: Float) {
        putOuter(v)
        val capped = innerRadius.coerceAtMost(v * 0.8f)
        if (capped < innerRadius) {
            clampedHint = true
            putInner(capped)
        } else {
            clampedHint = false
        }
    }

    // 行为规则 1：显示弦长收缩后的实际生效尺寸；目标过大只提示不削减数量。
    // 取「纯弦长上限」的写法：请求值传 Float.MAX_VALUE，返回的就是未被请求值钳过的几何上限
    val iconCapDp = effectiveIconSizeDp(
        outerCount, innerCount, spec.capSpanDeg,
        outerRadius, innerRadius, Float.MAX_VALUE, density
    )
    // 实际生效 = 几何结果：max(硬下限, min(弦长上限, 目标值))（fitIconSize 的 coerceIn 口径）
    val effectiveIcon = iconCapDp.coerceAtMost(iconSize).coerceAtLeast(LayoutDefaults.ICON_SIZE_HARD_MIN)

    // 滑条量程跟着几何上限走（0915 用户报"图标大小滑条调大不生效"）：
    // 量程曾写死 32..80，而弦长收缩后的生效尺寸可以低于任何 UI 下限——实测外圈 10 个图标
    // （半径 110/160、满张角 150°）时上限仅 31.5dp，**整条滑条落进死区**。
    // 现在：上限 = min(UI 上限, 弦长上限)，下限 = 几何硬下限（与 fitIconSize 同源，禁止内联）。
    // 弦长上限低于硬下限时（底角 70° 弧 + 竖屏默认 7+4 即 20.2dp < 24dp）量程无法成立，
    // 几何也已把它钳在硬下限 ⇒ 本滑条必为死区：给合法 range 兜底并禁用
    //（miuix Slider 强制 start < end，等值量程会直接抛 IllegalArgumentException）。
    // 用户调小数量/调大半径后上限回升，滑条随之自动恢复可用。
    val iconMin = LayoutDefaults.ICON_SIZE_HARD_MIN
    val iconCapClamped = iconCapDp.coerceIn(iconMin, LayoutDefaults.ICON_SIZE_UI_MAX)
    val iconSliderActive = iconCapClamped > iconMin
    val iconMax = if (iconSliderActive) iconCapClamped else LayoutDefaults.ICON_SIZE_UI_MAX

    val config = remember(repo.revision) { buildPreviewConfig(repo) }

    // 数值嵌入标题（省副标题行高）；仅图标超限需提示时给副标题
    val iconSummary = if (effectiveIcon < iconSize) {
        stringResource(R.string.icon_size_limited, iconSize.toInt(), effectiveIcon.toInt())
    } else {
        stringResource(R.string.icon_size_effective, iconSize.toInt(), effectiveIcon.toInt())
    }

    // 内容区上限 = 屏高 3/4 − 标题栏（用户定值 2026-08-31：sheet 整体约 3/4 屏）
    val contentMaxHeight = (LocalConfiguration.current.screenHeightDp * 3 / 4 - 56).dp

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = contentMaxHeight)
            .verticalScroll(rememberScrollState())
    ) {
        // 底角：触发开关与样式同 sheet、同草稿（✕ 全撤 / ✓ 全存，语义与滑条一致）
        if (isCorner) {
            Card(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    title = stringResource(R.string.corner_swipe_title),
                    summary = stringResource(R.string.corner_swipe_summary),
                    checked = repo.cornerSwipeEnabled(),
                    onCheckedChange = { repo.putDraft(PrefKeys.CORNER_SWIPE_ENABLED, it) }
                )
            }
        }

        // 实时预览：含快捷栏（扇形+胶囊完整构图，拖滑条时整体反馈更直观）
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = if (isCorner) 8.dp else 0.dp)
        ) {
            FanStaticPreview(
                config = config,
                isLandscape = isLandscape,
                prefs = repo.prefs,
                corner = isCorner,
                includeQuickBar = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(FanVisuals.PREVIEW_SHEET_HEIGHT)
            )
        }

        // 5 项样式：miuix SliderPreference（标题左 / 数值右 / 滑条下，HyperOS 标准长相），
        // 收进一张 Card（不加显式分割线——HyperOS 现行观感不用），取代旧手搓 SettingsSliderItem 裸堆
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            SliderPreference(
                title = stringResource(R.string.icon_size),
                summary = iconSummary,
                valueText = "${iconSize.toInt()} dp",
                // 存量值可能落在新量程之外（如旧值 48 而上限 31.5）→ 显示时钳进量程
                value = iconSize.coerceIn(iconMin, iconMax),
                valueRange = iconMin..iconMax,
                enabled = iconSliderActive,
                onValueChange = { putIcon(it) },
                onValueChangeFinished = {}
            )
            SliderPreference(
                title = stringResource(R.string.inner_radius),
                valueText = "${innerRadius.toInt()} dp",
                value = innerRadius.coerceIn(spec.innerRadiusRange),
                valueRange = spec.innerRadiusRange,
                steps = spec.innerRadiusSteps,
                onValueChange = { putInnerWithClamp(it) },
                onValueChangeFinished = {}
            )
            SliderPreference(
                title = stringResource(R.string.outer_radius_max),
                valueText = "${outerRadius.toInt()} dp",
                value = outerRadius.coerceIn(spec.outerRadiusRange),
                valueRange = spec.outerRadiusRange,
                steps = spec.outerRadiusSteps,
                onValueChange = { putOuterWithClamp(it) },
                onValueChangeFinished = {}
            )
            // 内圈应用数（用户 2026-09-04：顺序置于外圈应用数之前）。实际可摆数受"外圈先挑走
            // 应用"约束：内圈=总数−外圈，外圈调大内圈跟着变小
            SliderPreference(
                title = stringResource(R.string.inner_apps_count),
                valueText = innerCount.toString(),
                value = innerCount.toFloat(),
                valueRange = spec.innerCountRange,
                steps = spec.innerCountSteps,
                onValueChange = { putInnerCount(it.toInt()) },
                onValueChangeFinished = {}
            )
            SliderPreference(
                title = stringResource(R.string.outer_apps_count),
                valueText = outerCount.toString(),
                value = outerCount.toFloat(),
                valueRange = spec.outerCountRange,
                steps = spec.outerCountSteps,
                onValueChange = { putOuterCount(it.toInt()) },
                onValueChangeFinished = {}
            )
        }

        if (clampedHint) {
            Text(
                text = stringResource(R.string.layout_inner_clamped),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                text = stringResource(R.string.layout_sheet_restore_default),
                onClick = {
                    // 本方向键组写入草稿（恢复默认也走草稿，可取消）；
                    // 底角含触发开关（默认关），与"本 sheet 全部设置"的语义一致
                    putIcon(spec.defIcon)
                    putInner(spec.defInner)
                    putOuter(spec.defOuter)
                    putOuterCount(spec.defOuterCount)
                    putInnerCount(spec.defInnerCount)
                    if (isCorner) {
                        repo.putDraft(PrefKeys.CORNER_SWIPE_ENABLED, LayoutDefaults.CORNER_SWIPE_ENABLED)
                    }
                    clampedHint = false
                }
            )
        }
    }
}
