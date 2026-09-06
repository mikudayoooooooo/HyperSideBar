package com.lsp.hypersidebar.ui.settings

import com.lsp.hypersidebar.prefs.savePref
import com.lsp.hypersidebar.prefs.SettingsRepository
import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.prefs.PrefKeys
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.theme.ThemeMode
import com.lsp.hypersidebar.theme.ThemeModes
import com.lsp.hypersidebar.util.RemotePrefsBridge
import com.lsp.hypersidebar.util.ShortcutStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlin.math.roundToInt

@Composable
internal fun SettingsPage(
    prefs: SharedPreferences,
    status: ModuleStatus,
    currentThemeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onNavigateToAppSelection: () -> Unit,
    onNavigateToShortcutSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    // D1：绑定晚到时参数 prefs 仍是本地空壳（remember 首读即 0 且 revision 通道只覆盖
    // 本地写入）。bridge.prefs 是 Compose state——绑定完成强制本组合重组并切换读取源，
    // 不依赖上游参数链（navigation3 entry 可能固化旧参数捕获）
    val effectivePrefs = RemotePrefsBridge.prefs ?: prefs
    // D1 写端残留（2026-09-04 logcat 实证：remote store 有 innerRadius/outerRadiusMax/
    // 数量键=nav3 迁移前落盘的老值，唯独没有 iconSize）：repo 参数同样是 entry 固化的
    // 绑定前捕获（包着本地 fallbackPrefs），布局 sheet commitDraft / 一键重置 / 预览卡
    // 的读写全落在 hook 永远不可见的本地文件上。以 effectivePrefs 页内重建，读写同源。
    val effectiveRepo = remember(effectivePrefs) { SettingsRepository(effectivePrefs) }
    DisposableEffect(effectiveRepo) { onDispose { effectiveRepo.dispose() } }
    var enabled by remember(effectivePrefs, effectiveRepo.revision) {
        mutableStateOf(effectivePrefs.getBoolean(PrefKeys.ENABLED, true))
    }
    val selectedApps = remember(effectivePrefs, effectiveRepo.revision) {
        effectivePrefs.getStringSet(PrefKeys.CUSTOM_APPS, emptySet()).orEmpty().size
    }
    val shortcutStats = remember(effectivePrefs, effectiveRepo.revision) {
        val all = ShortcutStore.loadUserShortcuts(effectivePrefs)
        all.size to all.count { it.enabled }
    }
    val themeOptions = listOf(
        stringResource(R.string.theme_follow_system),
        stringResource(R.string.theme_light),
        stringResource(R.string.theme_dark)
    )
    // 主题开关实时性（2026-09-04 用户反馈"开关要重开应用才刷新"）：currentThemeMode
    // 参数链会被 navigation3 entry 固化（D1 同病）——开关自持 listener 直读 prefs，
    // THEME_MODE 键值写入即时驱动本页重组（主题全局切换由 MainActivity 的键级
    // 监听负责，此处只管开关自身的 checked 呈现）
    var themeModeLive by remember(effectivePrefs) {
        mutableStateOf(
            effectivePrefs.getString(PrefKeys.THEME_MODE, ThemeModes.MONET_SYSTEM)
                ?: ThemeModes.MONET_SYSTEM
        )
    }
    DisposableEffect(effectivePrefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == PrefKeys.THEME_MODE) {
                themeModeLive = p.getString(PrefKeys.THEME_MODE, ThemeModes.MONET_SYSTEM)
                    ?: ThemeModes.MONET_SYSTEM
            }
        }
        effectivePrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { effectivePrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val baseMode = ThemeModes.baseMode(themeModeLive)
    val selectedThemeIndex = ThemeModes.BASE_MODES.indexOf(baseMode).coerceAtLeast(0)
    val useSystemColors = ThemeModes.usesSystemColors(themeModeLive)

    // hook 状态探针（§2.5.4）：设置页组合进入时双路 ping（切 Tab 返回会重新组合=顺带刷新）。
    // 旧通路（hook→app 经 remotePrefs 回写熔断/降级）在 LSPosed 下是死路：hook 进程 prefs 只读
    val context = LocalContext.current
    val probe = remember { ModuleProbe(context) }
    val probeScope = rememberCoroutineScope()
    LaunchedEffect(probe) { probe.probe() }

    fun manualRetry() {
        // 手动重试：写时间戳，hook 侧比较 resetAt > 本端熔断时刻即解除
        //（launcher=下次边缘呼出，:ui=2s 看门狗内）；3s 后复测刷新状态行
        effectivePrefs.edit()
            .putLong(PrefKeys.CIRCUIT_RESET_AT, System.currentTimeMillis())
            .commit()
        probeScope.launch {
            delay(3000)
            probe.probe()
        }
    }

    // 布局编辑 BottomSheet：入口 = 布局预览卡双缩略点击（§2.2）
    var sheetOrientation by remember { mutableStateOf<LayoutOrientation?>(null) }
    var sheetVisible by remember { mutableStateOf(false) }

    // 一键重置确认 sheet（反馈轮：先确认后执行）
    var showResetConfirm by remember { mutableStateOf(false) }

    fun openLayoutSheet(orientation: LayoutOrientation) {
        effectiveRepo.discardDraft() // 兜底清残留（上次关闭未走 onDismissFinished 的极端路径）
        sheetOrientation = orientation
        sheetVisible = true
    }

    // 草稿守卫：sheet 关闭或页面离开组合（含切 Tab 丢 sheet 状态）时兜底丢弃，
    // 防止残留草稿持续泄漏进预览卡的草稿优先读（"没保存却生效"的观感来源）
    DisposableEffect(sheetOrientation) {
        onDispose { effectiveRepo.discardDraft() }
    }

    SettingsList(modifier = modifier) {
        item { SmallTitle(text = stringResource(R.string.module_section)) }
        item {
            // 大色块状态卡（§2.5 反馈轮）：绿=正常 / 黄=通道异常（明细拼进卡内，熔断可点重试）
            // / 红=未激活；探针两端行不常显，异常才有存在感
            ModuleStatusComponent(
                status = status,
                probe = probe.state,
                onRetry = { manualRetry() }
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    title = stringResource(R.string.module_enabled),
                    summary = stringResource(R.string.module_enabled_summary),
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        effectivePrefs.savePref(PrefKeys.ENABLED, it)
                    }
                )
            }
        }

        item { SmallTitle(text = stringResource(R.string.effect_preview)) }
        item {
            LayoutPreviewCard(
                repo = effectiveRepo,
                onPortraitClick = { openLayoutSheet(LayoutOrientation.PORTRAIT) },
                onLandscapeClick = { openLayoutSheet(LayoutOrientation.LANDSCAPE) }
            )
        }

        item { SmallTitle(text = stringResource(R.string.apps_section)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ArrowPreference(
                        title = stringResource(R.string.select_apps),
                        summary = stringResource(R.string.selected_apps_summary, selectedApps),
                        onClick = onNavigateToAppSelection
                    )
                    ArrowPreference(
                        title = stringResource(R.string.select_shortcut_apps),
                        summary = stringResource(
                            R.string.shortcut_entry_summary, shortcutStats.first, shortcutStats.second
                        ),
                        onClick = onNavigateToShortcutSelection
                    )
                }
            }
        }

        item { SmallTitle(text = stringResource(R.string.appearance)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    OverlayDropdownMenu(
                        title = stringResource(R.string.theme_mode),
                        options = themeOptions,
                        selectedIndex = selectedThemeIndex,
                        onSelectedIndexChange = { index ->
                            val selectedBase = ThemeModes.BASE_MODES[index]
                            onThemeModeChange(ThemeModes.compose(selectedBase, useSystemColors))
                        }
                    )
                    SwitchPreference(
                        title = stringResource(R.string.system_colors),
                        summary = stringResource(R.string.system_colors_summary),
                        checked = useSystemColors,
                        onCheckedChange = { enabled ->
                            onThemeModeChange(ThemeModes.compose(baseMode, enabled))
                        }
                    )
                }
            }
        }

        // D2（批次 4，用户 2026-09-04 定稿）：呼出停顿滑条 150~350ms 步进 50 默认 250——
        // 取消 0 档（极易误触）、上限 500→350 收窄；150ms 快松预选锁死是独立硬编码守卫不受影响。
        // 拖动只改本地 state，松手才落盘（=一次 ConfigSync 广播）；restoreDefaults 后经
        // revision 通道回读默认值（D4 复原审计 ✓）
        // 路线 C 视觉（用户 2026-09-06 拍板）：扇形雾化浓度滑条（0=关闭填充可在线 A/B）
        // + 背景压暗 opt-in 开关；同 Card 同款"拖动暂存、松手落盘"节奏
        item { SmallTitle(text = stringResource(R.string.interaction_section)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    var dwellMs by remember(effectivePrefs, effectiveRepo.revision) {
                        mutableStateOf(effectiveRepo.triggerDwellMs())
                    }
                    SettingsSliderItem(
                        title = stringResource(R.string.trigger_dwell_title),
                        summary = stringResource(R.string.trigger_dwell_summary, dwellMs),
                        value = dwellMs.toFloat(),
                        valueRange = 150f..350f,
                        steps = 3,
                        onValueChange = {
                            dwellMs = ((it / 50f).roundToInt() * 50).coerceIn(150, 350)
                        },
                        onValueChangeFinished = {
                            effectiveRepo.save(PrefKeys.TRIGGER_DWELL_MS, dwellMs)
                        },
                        compact = true
                    )
                    var fog by remember(effectivePrefs, effectiveRepo.revision) {
                        mutableStateOf(effectiveRepo.fanFogIntensity())
                    }
                    SettingsSliderItem(
                        title = stringResource(R.string.fan_fog_title),
                        summary = stringResource(R.string.fan_fog_summary, (fog * 100).roundToInt()),
                        value = fog,
                        valueRange = 0f..0.70f,
                        steps = 13,
                        onValueChange = { fog = (it * 20f).roundToInt() / 20f },
                        onValueChangeFinished = {
                            effectiveRepo.save(PrefKeys.FAN_FOG_INTENSITY, fog)
                        },
                        compact = true
                    )
                    var dimOn by remember(effectivePrefs, effectiveRepo.revision) {
                        mutableStateOf(effectiveRepo.fanDimEnabled())
                    }
                    SwitchPreference(
                        title = stringResource(R.string.fan_dim_title),
                        summary = stringResource(R.string.fan_dim_summary),
                        checked = dimOn,
                        onCheckedChange = {
                            dimOn = it
                            effectiveRepo.save(PrefKeys.FAN_DIM_ENABLED, it)
                        }
                    )
                }
            }
        }

        // 一键重置（§2.5.3，PRD"默认值且可重置"）：全部布局/交互参数，不动应用与快捷方式；
        // 先确认后执行（反馈轮拍板），确认 sheet 与布局 sheet 同款图标按钮
        item { SmallTitle(text = stringResource(R.string.defaults_section)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                BasicComponent(
                    title = stringResource(R.string.restore_defaults),
                    summary = stringResource(R.string.restore_defaults_summary),
                    onClick = { showResetConfirm = true }
                )
            }
        }
    }

    // 布局编辑 sheet 常驻组合（show 控制显隐）；
    // 关闭走两段：onDismiss 收 show（内容随 sheet 滑下）→ onDismissFinished 清理草稿与方向
    LayoutBottomSheet(
        show = sheetVisible,
        orientation = sheetOrientation ?: LayoutOrientation.PORTRAIT,
        repo = effectiveRepo,
        onDismiss = { sheetVisible = false },
        onDismissFinished = {
            // 保存路径 commit 已清空草稿，此处为无操作；取消/滑掉/返回=丢弃
            effectiveRepo.discardDraft()
            sheetOrientation = null
        }
    )

    // 一键重置确认 dialog（反馈轮二：弃 sheet 用 dialog）
    ResetConfirmDialog(
        show = showResetConfirm,
        onConfirm = {
            effectiveRepo.restoreAllDefaults()
            showResetConfirm = false
            Toast.makeText(
                context,
                context.getString(R.string.restore_defaults_done),
                Toast.LENGTH_SHORT
            ).show()
        },
        onDismiss = { showResetConfirm = false }
    )
}

@Composable
private fun ResetConfirmDialog(
    show: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    // miuix WindowDialog（窗口级）：自带居中 title/summary 与 insideMargin；
    // 按钮行照官方 DialogSection 模式——两等宽 TextButton 两端分布，确认染主色
    WindowDialog(
        show = show,
        title = stringResource(R.string.restore_defaults),
        summary = stringResource(R.string.restore_defaults_confirm),
        onDismissRequest = onDismiss,
        content = {
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = stringResource(R.string.layout_sheet_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.reset_confirm),
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    )
}

@Composable
private fun SettingsList(
    modifier: Modifier,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .overScrollVertical(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
internal fun SettingsSliderItem(
    title: String,
    summary: String? = null,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    steps: Int = 0,
    sliderHorizontalPadding: Dp = 16.dp,
    compact: Boolean = false
) {
    BasicComponent(
        title = title,
        summary = summary,
        insideMargin = if (compact) SheetSliderInsideMargin else BasicComponentDefaults.InsideMargin,
        bottomAction = {
            Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = sliderHorizontalPadding)
            )
        }
    )
}
