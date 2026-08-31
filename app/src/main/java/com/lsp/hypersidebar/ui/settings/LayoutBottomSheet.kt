package com.lsp.hypersidebar.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.prefs.SettingsRepository
import com.lsp.hypersidebar.ui.fan.effectiveIconSizeDp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class LayoutOrientation { PORTRAIT, LANDSCAPE }

/**
 * 布局参数编辑 BottomSheet（§2.2 本迭代核心交互）。
 *
 * 草稿/提交语义：滑条只写 [SettingsRepository.putDraft]（实时预览跟随 revision 通道），
 * 保存=commitDraft 单 editor 批量落盘（一次 revision 跳变、hook 侧一次 LSPosed 推送），
 * 取消/下滑/返回关闭=discardDraft 整体丢弃（丢弃推迟到 [onDismissFinished]——
 * 退出动画期间内容保持组合，跟着 sheet 一起滑下）。竖/横屏各 5 项、完全独立（行为规则 6）。
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
    val isPortrait = orientation == LayoutOrientation.PORTRAIT

    OverlayBottomSheet(
        show = show,
        title = stringResource(
            if (isPortrait) R.string.portrait_layout else R.string.landscape_layout
        ),
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

@Composable
private fun LayoutSheetContent(
    orientation: LayoutOrientation,
    repo: SettingsRepository
) {
    val isPortrait = orientation == LayoutOrientation.PORTRAIT

    // 下面的 remember(repo.revision) 在组合期读 revision 建立订阅：
    // 任何 putDraft（revision++）都驱动本组合重读草稿值
    val density = LocalDensity.current.density

    val iconSize = if (isPortrait) repo.iconSize() else repo.landscapeIconSize()
    val innerRadius = if (isPortrait) repo.innerRadius() else repo.landscapeInnerRadius()
    val outerRadius = if (isPortrait) repo.outerRadiusMax() else repo.landscapeOuterRadius()
    val outerCount = if (isPortrait) repo.maxAppsOuter() else repo.landscapeMaxAppsOuter()
    val innerCount = if (isPortrait) repo.maxAppsInner() else repo.landscapeMaxAppsInner()

    fun putIcon(v: Float) = repo.putDraft(if (isPortrait) PrefKeys.ICON_SIZE else PrefKeys.LANDSCAPE_ICON_SIZE, v)
    fun putInner(v: Float) = repo.putDraft(if (isPortrait) PrefKeys.INNER_RADIUS else PrefKeys.LANDSCAPE_INNER_RADIUS, v)
    fun putOuter(v: Float) = repo.putDraft(if (isPortrait) PrefKeys.OUTER_RADIUS_MAX else PrefKeys.LANDSCAPE_OUTER_RADIUS, v)
    fun putOuterCount(v: Int) = repo.putDraft(if (isPortrait) PrefKeys.MAX_APPS_OUTER else PrefKeys.LANDSCAPE_MAX_APPS_OUTER, v)
    fun putInnerCount(v: Int) = repo.putDraft(if (isPortrait) PrefKeys.MAX_APPS_INNER else PrefKeys.LANDSCAPE_MAX_APPS_INNER, v)

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

    // 行为规则 1：显示弦长收缩后的实际生效尺寸；目标过大只提示不削减数量
    val effectiveIcon = effectiveIconSizeDp(
        outerCount, innerCount, if (isPortrait) 150f else 75f,
        outerRadius, innerRadius, iconSize, density
    )

    val config = remember(repo.revision) { buildPreviewConfig(repo) }

    // 数值嵌入标题（省副标题行高）；仅图标超限需提示时给副标题
    val iconSummary = if (effectiveIcon < iconSize) {
        stringResource(R.string.icon_size_limited, iconSize.toInt(), effectiveIcon.toInt())
    } else {
        stringResource(R.string.icon_size_effective, iconSize.toInt(), effectiveIcon.toInt())
    }

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 430.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // 实时预览：按内容宽高比收紧、无快捷栏（快捷栏不受这 5 个滑条影响）
        FanStaticPreview(
            config = config,
            isLandscape = !isPortrait,
            includeQuickBar = false,
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .padding(bottom = 4.dp)
        )

        Column {
            SettingsSliderItem(
                title = stringResource(R.string.icon_size),
                summary = iconSummary,
                value = iconSize,
                valueRange = 32f..80f,
                onValueChange = { putIcon(it) },
                onValueChangeFinished = {},
                sliderHorizontalPadding = 0.dp,
                compact = true
            )
            SettingsSliderItem(
                title = stringResource(R.string.inner_radius),
                summary = stringResource(R.string.inner_radius_summary, innerRadius.toInt()),
                value = innerRadius,
                valueRange = 80f..160f,
                steps = 7,
                onValueChange = { putInnerWithClamp(it) },
                onValueChangeFinished = {},
                sliderHorizontalPadding = 0.dp,
                compact = true
            )
            SettingsSliderItem(
                title = stringResource(R.string.outer_radius_max),
                summary = stringResource(R.string.outer_radius_summary, outerRadius.toInt()),
                value = outerRadius,
                valueRange = 110f..220f,
                steps = 10,
                onValueChange = { putOuterWithClamp(it) },
                onValueChangeFinished = {},
                sliderHorizontalPadding = 0.dp,
                compact = true
            )
            SettingsSliderItem(
                title = stringResource(R.string.outer_apps_count),
                summary = stringResource(R.string.outer_apps_summary, outerCount),
                value = outerCount.toFloat(),
                valueRange = if (isPortrait) 4f..12f else 3f..8f,
                steps = if (isPortrait) 7 else 4,
                onValueChange = { putOuterCount(it.toInt()) },
                onValueChangeFinished = {},
                sliderHorizontalPadding = 0.dp,
                compact = true
            )
            SettingsSliderItem(
                title = stringResource(R.string.inner_apps_count),
                summary = stringResource(R.string.inner_apps_summary, innerCount),
                value = innerCount.toFloat(),
                valueRange = if (isPortrait) 2f..8f else 0f..6f,
                steps = if (isPortrait) 5 else 5,
                onValueChange = { putInnerCount(it.toInt()) },
                onValueChangeFinished = {},
                sliderHorizontalPadding = 0.dp,
                compact = true
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
                    // 本方向 5 键写入草稿（恢复默认也走草稿，可取消）
                    putIcon(if (isPortrait) LayoutDefaults.ICON_SIZE else LayoutDefaults.LANDSCAPE_ICON_SIZE)
                    putInner(if (isPortrait) LayoutDefaults.INNER_RADIUS else LayoutDefaults.LANDSCAPE_INNER_RADIUS)
                    putOuter(if (isPortrait) LayoutDefaults.OUTER_RADIUS_MAX else LayoutDefaults.LANDSCAPE_OUTER_RADIUS)
                    putOuterCount(if (isPortrait) LayoutDefaults.MAX_APPS_OUTER else LayoutDefaults.LANDSCAPE_MAX_APPS_OUTER)
                    putInnerCount(if (isPortrait) LayoutDefaults.MAX_APPS_INNER else LayoutDefaults.LANDSCAPE_MAX_APPS_INNER)
                    clampedHint = false
                }
            )
        }
    }
}

/** sheet 紧凑滑条行：上下边距 16→4dp，约省 40% 行高。 */
internal val SheetSliderInsideMargin = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
