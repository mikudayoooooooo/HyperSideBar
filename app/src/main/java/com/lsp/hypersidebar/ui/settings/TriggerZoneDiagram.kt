package com.lsp.hypersidebar.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 触发区示意图（§2.3）。绘制以 hook 代码常量为准（不抄 PRD 旧数值）：
 * - 竖屏 = 左右边缘中段 [H/3, 2H/3]（EdgeGestureHook.zoneBounds）
 * - 横屏 = 顶部左右角条（B 路线：隐藏的系统侧边栏触摸条直接收事件，
 *   TurboLayout 横屏条 [0,112dp]；示意图尺寸放大以便辨认）
 */
@Composable
internal fun TriggerZoneDiagramCard(modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    PortraitZoneDiagram(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                    )
                    Text(
                        text = stringResource(R.string.trigger_zone_portrait),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
                Column(
                    modifier = Modifier.weight(1.5f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LandscapeZoneDiagram(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                    )
                    Text(
                        text = stringResource(R.string.trigger_zone_landscape),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
            Text(
                text = stringResource(R.string.trigger_zone_hint),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun PortraitZoneDiagram(modifier: Modifier = Modifier) {
    val outline = MiuixTheme.colorScheme.outline
    val surface = MiuixTheme.colorScheme.surfaceContainerHigh
    val zone = MiuixTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val phoneW = size.width * 0.52f
        val phoneH = size.height * 0.94f
        val left = (size.width - phoneW) / 2f
        val top = (size.height - phoneH) / 2f
        drawRoundRect(
            color = surface,
            topLeft = Offset(left, top),
            size = Size(phoneW, phoneH),
            cornerRadius = CornerRadius(12f)
        )
        drawRoundRect(
            color = outline.copy(alpha = 0.6f),
            topLeft = Offset(left, top),
            size = Size(phoneW, phoneH),
            cornerRadius = CornerRadius(12f),
            style = Stroke(width = 2.dp.toPx())
        )
        // 触发带：左右边缘中段 [H/3, 2H/3]
        val bandTop = top + phoneH / 3f
        val bandH = phoneH / 3f
        val bandW = 5.dp.toPx()
        drawRoundRect(
            color = zone.copy(alpha = 0.85f),
            topLeft = Offset(left, bandTop),
            size = Size(bandW, bandH),
            cornerRadius = CornerRadius(bandW / 2f)
        )
        drawRoundRect(
            color = zone.copy(alpha = 0.85f),
            topLeft = Offset(left + phoneW - bandW, bandTop),
            size = Size(bandW, bandH),
            cornerRadius = CornerRadius(bandW / 2f)
        )
    }
}

@Composable
private fun LandscapeZoneDiagram(modifier: Modifier = Modifier) {
    val outline = MiuixTheme.colorScheme.outline
    val surface = MiuixTheme.colorScheme.surfaceContainerHigh
    val zone = MiuixTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val phoneW = size.width * 0.94f
        val phoneH = size.height * 0.62f
        val left = (size.width - phoneW) / 2f
        val top = (size.height - phoneH) / 2f
        drawRoundRect(
            color = surface,
            topLeft = Offset(left, top),
            size = Size(phoneW, phoneH),
            cornerRadius = CornerRadius(12f)
        )
        drawRoundRect(
            color = outline.copy(alpha = 0.6f),
            topLeft = Offset(left, top),
            size = Size(phoneW, phoneH),
            cornerRadius = CornerRadius(12f),
            style = Stroke(width = 2.dp.toPx())
        )
        // 触发块：横屏顶部左右角条（隐藏触摸条，示意放大）
        val barW = 8.dp.toPx()
        val barH = phoneH * 0.9f
        drawRoundRect(
            color = zone.copy(alpha = 0.85f),
            topLeft = Offset(left, top),
            size = Size(barW, barH),
            cornerRadius = CornerRadius(barW / 2f)
        )
        drawRoundRect(
            color = zone.copy(alpha = 0.85f),
            topLeft = Offset(left + phoneW - barW, top),
            size = Size(barW, barH),
            cornerRadius = CornerRadius(barW / 2f)
        )
    }
}
