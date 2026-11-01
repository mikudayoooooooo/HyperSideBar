package com.lsp.hypersidebar.ui.settings

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.theme.LocalSemanticColors
import com.lsp.hypersidebar.ui.fan.FanThemeColors
import com.lsp.hypersidebar.util.ShortcutAction
import com.lsp.hypersidebar.util.ShortcutKind
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.pressable

internal enum class ModuleStatus { ACTIVE, INACTIVE }

/**
 * HyperOS 观感的点击目标：miuix 下沉按压反馈（SinkFeedback）替代默认 ripple，
 * 消除"点了没反馈 / 灰圈生硬"的观感（与 AllApps 磁贴同一语系）。
 */
@Composable
fun Modifier.sinkClickable(
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this
        .pressable(
            interactionSource = interactionSource,
            indication = SinkFeedback(),
            // 禁用行不再下沉：原先 pressable 不看 enabled，表现为"看似可按、实际无响应"
            enabled = enabled
        )
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
}

/**
 * 手柄区按下即消费 down：掐死行 combinedClickable 的长按计时——批量选择只能从手柄以外的行体进入。
 * 与拖动排序手柄配对使用（快捷方式列表与组成员两处共用同一仲裁）。
 */
internal fun Modifier.suppressRowLongPress(): Modifier = pointerInput(Unit) {
    awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() }
}

internal fun openLsposedManager(context: Context) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_MAIN).apply {
            setClassName("org.lsposed.manager", "org.lsposed.manager.ui.activity.MainActivity")
        })
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.lsposed_not_found), Toast.LENGTH_SHORT).show()
    }
}

@Composable
internal fun currentFanThemeColors(): FanThemeColors {
    val scheme = MiuixTheme.colorScheme
    return remember(scheme) {
        FanThemeColors(
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
            isDark = scheme.background.luminance() < 0.5f
        )
    }
}

@Composable
internal fun ModuleStatusComponent(
    status: ModuleStatus,
    probe: ModuleProbeState?,
    onRetry: () -> Unit
) {
    val semantic = LocalSemanticColors.current
    val errorColor = MiuixTheme.colorScheme.error

    // 异常端（code 非 1）：黄卡只列异常端，正常态不显示探针细节。
    // OS4 豁免：桌面纯 native（U2 定案）触发端探针恒无应答=预期形态，不算异常
    // （:ui 探针应答回带 os4 旗标；:ui 自身应答正常即代表全链在位）。
    val launcherIssue = probe?.launcher?.takeIf {
        it != PrefKeys.PROBE_CODE_OK && !(probe.os4 && it == PrefKeys.PROBE_CODE_DEAD)
    }
    val executorIssue = probe?.executor?.takeIf { it != PrefKeys.PROBE_CODE_OK }
    // 熔断才可点（手动重试）；降级/无应答无即时动作，未激活纯展示（不跳 LSPosed）
    val anyCircuit = launcherIssue == PrefKeys.PROBE_CODE_CIRCUIT ||
        executorIssue == PrefKeys.PROBE_CODE_CIRCUIT

    val bg: Color
    val dot: Color
    val title: String
    val summary: String
    when {
        status == ModuleStatus.INACTIVE -> {
            bg = errorColor.copy(alpha = 0.12f)
            dot = errorColor
            title = stringResource(R.string.module_inactive)
            summary = stringResource(R.string.deactivation_hint)
        }
        launcherIssue != null || executorIssue != null -> {
            bg = semantic.warningContainer
            dot = semantic.warning
            title = stringResource(R.string.status_abnormal)
            summary = listOfNotNull(
                launcherIssue?.let {
                    stringResource(R.string.probe_end_home) + "：" + probeStateText(it)
                },
                executorIssue?.let {
                    stringResource(R.string.probe_end_ui) + "：" + probeStateText(it)
                }
            ).joinToString("\n")
        }
        else -> {
            bg = semantic.successContainer
            dot = semantic.success
            title = stringResource(R.string.status_running)
            summary = stringResource(R.string.status_normal_summary)
        }
    }

    val retryModifier =
        if (anyCircuit) Modifier.sinkClickable(onClick = onRetry) else Modifier
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(retryModifier),
        colors = CardDefaults.defaultColors(color = bg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dot)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.title2,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = summary,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

@Composable
private fun probeStateText(code: Int): String = when (code) {
    PrefKeys.PROBE_CODE_CIRCUIT -> stringResource(R.string.probe_state_circuit)
    PrefKeys.PROBE_CODE_DATA_DEAD -> stringResource(R.string.probe_state_data_dead)
    PrefKeys.PROBE_CODE_DEGRADED -> stringResource(R.string.probe_state_degraded)
    else -> stringResource(R.string.probe_state_dead)
}

// ===== 设置页共享骨架（2026-10-27 布局改版）=====

/**
 * 设置页间距单一出处（页面禁止裸 dp；豁免：图标尺寸、几何计算值）。
 *
 * 对齐式：滚动容器水平内距 12 + 行组件默认 insideMargin 16 = 文字左缘 28dp；
 * 容器内的 Card 不再加水平外距（卡片边就落在 12dp 上）。
 */
internal object SettingsDimens {
    val ScreenInset = 12.dp
    val TitleInset = 16.dp
    val CardInner = 16.dp
    val ListTop = 8.dp
    val ListBottom = 24.dp
    val SectionGap = 8.dp
    val ButtonGap = 20.dp
}

/** 顶栏 ScrollBehavior 下传通道（MainScreen 提供；页面用它把滚动绑到顶栏收起）。 */
internal val LocalTopAppBarScrollBehavior = staticCompositionLocalOf<ScrollBehavior?> { null }

/** 分组小标题：水平内距 16 ⇒ 文字左缘与卡片内行文字同为 12 + 16 = 28dp。 */
@Composable
internal fun SectionTitle(text: String) {
    SmallTitle(
        text = text,
        insideMargin = PaddingValues(SettingsDimens.TitleInset, SettingsDimens.SectionGap)
    )
}

/** 选中态胶囊：选中 = primary 底 + onPrimary 字（旧实现只换底色不换字色，对比度不足）。
 *  选中态同时写进语义树，读屏能播报"已选中"（原先只有颜色差别）。 */
@Composable
internal fun SettingsChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(
        text = text,
        onClick = onClick,
        modifier = modifier.semantics { this.selected = selected },
        colors = if (selected) {
            ButtonDefaults.textButtonColors(
                color = MiuixTheme.colorScheme.primary,
                textColor = MiuixTheme.colorScheme.onPrimary
            )
        } else {
            ButtonDefaults.textButtonColors()
        }
    )
}

/** 弹窗双按钮行：等宽取消/确认 + 统一间距（原来三页各写一份）。 */
@Composable
internal fun ConfirmCancelRow(
    confirmText: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    confirmEnabled: Boolean = true,
    cancelEnabled: Boolean = true,
    danger: Boolean = false,
    primary: Boolean = false
) {
    Row(horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(
            text = stringResource(R.string.layout_sheet_cancel),
            onClick = onCancel,
            enabled = cancelEnabled,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(SettingsDimens.ButtonGap))
        TextButton(
            text = confirmText,
            onClick = onConfirm,
            enabled = confirmEnabled,
            modifier = Modifier.weight(1f),
            colors = when {
                danger -> ButtonDefaults.textButtonColors(textColor = MiuixTheme.colorScheme.error)
                primary -> ButtonDefaults.textButtonColorsPrimary()
                else -> ButtonDefaults.textButtonColors()
            }
        )
    }
}

/**
 * 设置页滚动容器统一入口：overscroll 在前、顶栏 nestedScroll 在后（顺序反了回弹会抖，
 * miuix utils 文档明示）。被 [top.yukonga.miuix.kmp.basic.PullToRefresh] 包裹的列表不要用本函数，
 * 改由 PullToRefresh 的 topAppBarScrollBehavior 参数协调。
 */
@Composable
internal fun Modifier.settingsScroll(): Modifier {
    val behavior = LocalTopAppBarScrollBehavior.current
    return this.overScrollVertical().let { mod ->
        if (behavior != null) mod.nestedScroll(behavior.nestedScrollConnection) else mod
    }
}

/**
 * 顶栏刷新桥：App 选择 / 组件选择 / QS 磁贴选择三页共用（同一时刻只有一个在栈顶）。
 * 这三页的数据源都在 App 之外（PM / LauncherApps / :ui 准入列表），才值得主动刷新。
 */
internal class RefreshBar {
    var onRefresh: (() -> Unit)? = null
}

/** 快捷方式类型的中文标签（旧实现直接上屏 `kind.name` 英文枚举）。 */
internal fun shortcutKindLabelRes(kind: ShortcutKind): Int = when (kind) {
    ShortcutKind.COMPONENT -> R.string.shortcut_kind_component
    ShortcutKind.ACTIVITY -> R.string.shortcut_kind_activity
    ShortcutKind.INTENT_URI -> R.string.shortcut_kind_intent_uri
    ShortcutKind.TOOLBOX -> R.string.shortcut_kind_toolbox
    ShortcutKind.SERVICE -> R.string.shortcut_kind_service
    ShortcutKind.QS_TILE -> R.string.shortcut_kind_qs_tile
    ShortcutKind.SHORTCUT_ID -> R.string.shortcut_kind_shortcut_id
    ShortcutKind.GROUP -> R.string.shortcut_kind_group
}

/**
 * 组图标：借用图标优先（与扇形端一致），否则上 1 下 2 三角拼贴（仅启用成员，与展开一致）。
 *
 * 组条目没有 packageName/iconPackageName（建组只写 children），所以列表行不渲染它会
 * 留下一格空白、文字左缘比普通行偏 32+8dp —— 列表与组详情共用本函数。
 */
@Composable
internal fun GroupIconPreview(
    group: ShortcutAction,
    members: List<ShortcutAction>,
    name: String,
    size: Float = 56f
) {
    val borrowed = group.iconPackageName
    if (borrowed != null) {
        SettingsAppIcon(packageName = borrowed, appName = name, size = size)
        return
    }
    val cell = size
    val child = cell * 0.46f
    val gap = cell * 0.04f
    val enabledMembers = members.filter { it.enabled }.take(3)
    Box(modifier = Modifier.size(cell.dp)) {
        enabledMembers.forEachIndexed { i, m ->
            val mod = when (i) {
                0 -> Modifier.offset(x = ((cell - child) / 2f).dp, y = gap.dp)
                1 -> Modifier.offset(x = gap.dp, y = (cell - child - gap).dp)
                else -> Modifier.offset(x = (cell - child - gap).dp, y = (cell - child - gap).dp)
            }
            Box(modifier = mod) {
                SettingsAppIcon(
                    packageName = m.iconPackageName ?: m.packageName ?: "",
                    appName = m.label,
                    size = child
                )
            }
        }
    }
}
