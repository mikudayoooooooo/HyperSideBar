package com.lsp.hypersidebar.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.lsp.hypersidebar.BuildConfig
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.prefs.SettingsRepository
import com.lsp.hypersidebar.prefs.savePref
import com.lsp.hypersidebar.util.ConfigBackup
import com.lsp.hypersidebar.util.RemotePrefsBridge
import com.lsp.hypersidebar.util.UpdateChecker
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun AboutPage(
    service: XposedService?,
    prefs: SharedPreferences,
    prefsRevision: Int,
    onNavigateToDiagnostics: () -> Unit = {},
    onNavigateToTechInfo: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // D1 同款（SettingsPage）：绑定晚到时参数 prefs 可能是 nav3 entry 固化的本地空壳，
    // 读写都落不到 hook 可见的 remote store——页内切到 bridge.prefs 保读写同源
    val effectivePrefs = RemotePrefsBridge.prefs ?: prefs
    var relayBlackhole by remember(effectivePrefs, prefsRevision) {
        mutableStateOf(
            runCatching { effectivePrefs.getBoolean(PrefKeys.DEBUG_RELAY_BLACKHOLE, false) }
                .getOrDefault(false)
        )
    }
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: context.getString(R.string.unknown)
    }
    // 一键重置（0922 从首页「重置」分区迁入）：首页不再常驻 SettingsRepository，本页按需
    // 自建自弃；仍以 effectivePrefs 构造，保证写进 hook 可见的那一份
    val repo = remember(effectivePrefs) { SettingsRepository(effectivePrefs) }
    DisposableEffect(repo) { onDispose { repo.dispose() } }
    var showResetConfirm by remember { mutableStateOf(false) }

    val updateScope = rememberCoroutineScope()
    var updateState by remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle) }
    fun checkForUpdate() {
        if (updateState is UpdateCheckState.Checking) return
        updateState = UpdateCheckState.Checking
        updateScope.launch {
            updateState = try {
                val release = UpdateChecker.fetchLatestRelease()
                when {
                    release.version.isEmpty() -> UpdateCheckState.Failed
                    UpdateChecker.isNewer(release.version, versionName) ->
                        UpdateCheckState.Available(release.version, release.pageUrl)
                    else -> UpdateCheckState.UpToDate
                }
            } catch (_: Exception) {
                UpdateCheckState.Failed
            }
        }
    }
    // Idle 不给 summary（2026-11-03 文案瘦身）：没有状态就不占一行
    val updateSummary: String? = when (val state = updateState) {
        UpdateCheckState.Idle -> null
        UpdateCheckState.Checking -> stringResource(R.string.update_checking)
        UpdateCheckState.UpToDate -> stringResource(R.string.update_up_to_date)
        is UpdateCheckState.Available -> stringResource(R.string.update_available, state.version)
        UpdateCheckState.Failed -> stringResource(R.string.update_check_failed)
    }

    // 调试开关确认弹窗（用户 2026-09-05 拍板）：开启需 5 秒倒计时——该开关开启后
    // 症状与真实 :ui 死亡完全一致（toast"服务不可用"+5 次真熔断），且存 remotePrefs
    // 跨卸载重装存活，作者本人都曾被它误伤；关闭路径保持即时（不给出恢复障碍）
    var showBlackholeConfirm by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(5) }
    LaunchedEffect(showBlackholeConfirm) {
        if (!showBlackholeConfirm) return@LaunchedEffect
        countdown = 5
        while (countdown > 0) {
            delay(1000)
            countdown--
        }
    }

    // 重启 hook 宿主（0912）：两段式确认——勾选只改选择，点「重启」才真正执行；
    // 执行中禁用取消/再触发（WindowDialog 的 onDismissRequest 同步上锁）
    var showHostRestart by remember { mutableStateOf(false) }
    var selectedRestarts by remember { mutableStateOf(emptySet<String>()) }
    var hostRestartBusy by remember { mutableStateOf(false) }

    // 配置备份（2026-11-11）：导入/导出都走 IO；busy 只在真正读写时置位，
    // 打开文件选择器期间不锁按钮（用户可能停很久）。
    val backupScope = rememberCoroutineScope()
    var backupBusy by remember { mutableStateOf(false) }
    var showBackupMenu by remember { mutableStateOf(false) }
    var importPreview by remember { mutableStateOf<ConfigBackup.Snapshot?>(null) }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            backupBusy = true
            backupScope.launch {
                val parsed = runCatching {
                    withContext(Dispatchers.IO) {
                        ConfigBackup.decode(ConfigBackup.readFromUri(context, uri))
                    }
                }
                backupBusy = false
                parsed.onSuccess { importPreview = it }
                    .onFailure { e ->
                        Toast.makeText(
                            context,
                            context.getString(R.string.backup_import_failed, e.message ?: ""),
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
        }
    }

    // 备份入口共用：导出动作（菜单里点“导出配置备份”）。
    val exportBackup: () -> Unit = {
        if (!backupBusy) {
            backupBusy = true
            backupScope.launch {
                val exported = runCatching {
                    val json = withContext(Dispatchers.IO) {
                        ConfigBackup.encodeFromPrefs(effectivePrefs, versionName)
                    }
                    ConfigBackup.exportToDownloads(context, json)
                }
                backupBusy = false
                exported.onSuccess { path ->
                    Toast.makeText(
                        context,
                        context.getString(R.string.backup_export_done, path),
                        Toast.LENGTH_LONG
                    ).show()
                }.onFailure { e ->
                    Toast.makeText(
                        context,
                        context.getString(R.string.backup_export_failed, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // hook 回执/旧 dex 提示均已移除（2026-11-13 用户要求）。

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .settingsScroll(),
        contentPadding = PaddingValues(
            start = SettingsDimens.ScreenInset,
            end = SettingsDimens.ScreenInset,
            top = SettingsDimens.ListTop,
            bottom = SettingsDimens.ListBottom
        ),
        verticalArrangement = Arrangement.spacedBy(SettingsDimens.SectionGap)
    ) {
        item {
            AboutHero(
                cs = MiuixTheme.colorScheme,
                modifier = Modifier.padding(bottom = SettingsDimens.SectionGap)
            )
        }

        item { SectionTitle(text = stringResource(R.string.about_info)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    // 版本号与内部版本合并成一行（点击复制内部版本），不再另占一行
                    BasicComponent(
                        title = stringResource(R.string.about_version),
                        endActions = {
                            Text(
                                text = "$versionName · ${BuildConfig.VERSION_CODE}",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        },
                        onClick = {
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(
                                    ClipData.newPlainText(
                                        "versionCode",
                                        BuildConfig.VERSION_CODE.toString()
                                    )
                                )
                            Toast.makeText(
                                context,
                                context.getString(R.string.version_code_copied),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                    BasicComponent(
                        title = stringResource(R.string.update_check),
                        summary = updateSummary,
                        onClick = {
                            when (val state = updateState) {
                                is UpdateCheckState.Available ->
                                    openExternalUrl(context, state.pageUrl.ifEmpty { PROJECT_URL })
                                else -> checkForUpdate()
                            }
                        }
                    )
                    ArrowPreference(
                        title = stringResource(R.string.about_project_url),
                        endActions = {
                            Text(
                                text = "GitHub",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        },
                        onClick = { openExternalUrl(context, PROJECT_URL) }
                    )
                    ArrowPreference(
                        title = stringResource(R.string.about_tech_info),
                        onClick = onNavigateToTechInfo
                    )
                }
            }
        }

        item { SectionTitle(text = stringResource(R.string.about_more)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                // 配置备份（2026-11-11；2026-11-13 合并为一个入口）：
                // 点击后二选一“导出 / 恢复”，不再平铺两行占位。
                ArrowPreference(
                    title = stringResource(R.string.backup_menu_title),
                    summary = when {
                        backupBusy -> stringResource(R.string.backup_busy)
                        RemotePrefsBridge.prefs == null -> stringResource(R.string.backup_unbound)
                        else -> stringResource(R.string.backup_menu_summary)
                    },
                    onClick = { if (!backupBusy) showBackupMenu = true }
                )
                // 恢复默认值（0922 从首页迁入）：低频破坏性操作不再占首页一个分区；
                // 只回写布局/手势/背景参数，应用选择与快捷方式不受影响（确认 dialog 里说清）
                BasicComponent(
                    title = stringResource(R.string.restore_defaults),
                    onClick = { showResetConfirm = true }
                )
                // 诊断与统计（0909 拍板）：只留一个父入口，点进去再选
                // 运行日志/使用统计/导出自检报告——本页不再平铺诊断功能行
                ArrowPreference(
                    title = stringResource(R.string.diagnostics_entry),
                    onClick = onNavigateToDiagnostics
                )
                // 重启 hook 宿主不限 debug（0912 拍板：仅失联开关 debug 专属）——
                // 远程排障/模块更新后重启三宿主是运维规程，release 用户同样需要
                ArrowPreference(
                    title = stringResource(R.string.debug_host_restart),
                    onClick = { showHostRestart = true }
                )
                if (com.lsp.hypersidebar.BuildConfig.DEBUG) SwitchPreference(
                    title = stringResource(R.string.debug_relay_blackhole),
                    checked = relayBlackhole,
                    onCheckedChange = {
                        if (it) {
                            // 开启走 5 秒倒计时确认弹窗；关闭保持即时（恢复路径不加障碍）
                            showBlackholeConfirm = true
                        } else {
                            // 乐观本地更新：开关样式即时翻转（写入→revision→重组的异步链
                            // 不保证触发，曾实测样式滞留旧态）；写 remotePrefs，
                            // launcher 侧每次执行广播时读取（binder 缓存实时同步）
                            relayBlackhole = false
                            effectivePrefs.savePref(PrefKeys.DEBUG_RELAY_BLACKHOLE, false)
                        }
                    }
                )
            }
        }
    }

    // 确认弹窗：样式对齐 ResetConfirmDialog（miuix WindowDialog + 双等宽 TextButton），
    // 醒目化=后果行染 error 色 + 确认按钮倒计时期间禁用（变暗）且按钮染 error 色
    WindowDialog(
        show = showBlackholeConfirm,
        title = stringResource(R.string.debug_relay_confirm_title),
        onDismissRequest = { showBlackholeConfirm = false },
        content = {
            Text(
                text = stringResource(R.string.debug_relay_confirm_summary),
                color = MiuixTheme.colorScheme.error,
                style = MiuixTheme.textStyles.body2
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (countdown > 0) {
                    stringResource(R.string.debug_relay_confirm_countdown, countdown)
                } else {
                    stringResource(R.string.debug_relay_confirm_ready)
                },
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1
            )
            Spacer(Modifier.height(14.dp))
            ConfirmCancelRow(
                confirmText = if (countdown > 0) {
                    stringResource(R.string.debug_relay_confirm_action_counting, countdown)
                } else {
                    stringResource(R.string.debug_relay_confirm_action)
                },
                onConfirm = {
                    relayBlackhole = true
                    effectivePrefs.savePref(PrefKeys.DEBUG_RELAY_BLACKHOLE, true)
                    showBlackholeConfirm = false
                },
                onCancel = { showBlackholeConfirm = false },
                confirmEnabled = countdown <= 0,
                danger = true
            )
        }
    )

    // 重启 hook 宿主弹窗：样式对齐黑hole 确认弹窗（WindowDialog + 双等宽 TextButton）。
    // 勾选行=Row 整体 clickable + Checkbox 仅作状态显示（触摸行内任意处均可切换）；
    // 确认按钮无选中时禁用（变暗），执行中两键同锁防重复 force-stop
    WindowDialog(
        show = showHostRestart,
        title = stringResource(R.string.debug_host_restart),
        onDismissRequest = { if (!hostRestartBusy) showHostRestart = false },
        content = {
            Text(
                text = stringResource(R.string.debug_host_restart_dialog_summary),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1
            )
            Spacer(Modifier.height(10.dp))
            HostRestarter.HOSTS.forEach { host ->
                val checked = host.pkg in selectedRestarts
                val toggle = {
                    if (!hostRestartBusy) {
                        selectedRestarts = if (checked) selectedRestarts - host.pkg
                        else selectedRestarts + host.pkg
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .sinkClickable(enabled = !hostRestartBusy, onClick = toggle)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(host.label, style = MiuixTheme.textStyles.body2)
                        Text(
                            host.summary,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                    // 行与勾选框共用同一 toggle：点行/点框都切换（checkbox 消费自身触摸，
                    // 不会与 Row 的 clickable 叠加二次触发）
                    Checkbox(ToggleableState(checked), toggle)
                }
            }
            Spacer(Modifier.height(14.dp))
            ConfirmCancelRow(
                confirmText = stringResource(
                    if (hostRestartBusy) R.string.debug_host_restart_busy
                    else R.string.debug_host_restart_action
                ),
                onConfirm = {
                    val targets = HostRestarter.HOSTS.filter { it.pkg in selectedRestarts }
                    if (targets.isNotEmpty() && !hostRestartBusy) {
                        hostRestartBusy = true
                        updateScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                HostRestarter.restart(targets)
                            }
                            hostRestartBusy = false
                            selectedRestarts = emptySet()
                            showHostRestart = false
                            val msg = buildString {
                                if (result.ok.isNotEmpty()) append("已重启：").append(result.ok.joinToString("、"))
                                if (result.failed.isNotEmpty()) {
                                    if (isNotEmpty()) append("；")
                                    append("失败：").append(result.failed.joinToString("、")).append("（检查 root 授权）")
                                }
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onCancel = {
                    selectedRestarts = emptySet()
                    showHostRestart = false
                },
                confirmEnabled = !hostRestartBusy && selectedRestarts.isNotEmpty(),
                cancelEnabled = !hostRestartBusy,
                danger = true
            )
        }
    )

    // 备份与恢复（2026-11-13）：一个入口，弹窗内二选一“导出/恢复”。
    WindowDialog(
        show = showBackupMenu,
        title = stringResource(R.string.backup_menu_title),
        onDismissRequest = { if (!backupBusy) showBackupMenu = false },
        content = {
            BasicComponent(
                title = stringResource(R.string.backup_export),
                summary = if (backupBusy) stringResource(R.string.backup_busy) else null,
                onClick = {
                    if (!backupBusy) {
                        showBackupMenu = false
                        exportBackup()
                    }
                }
            )
            BasicComponent(
                title = stringResource(R.string.backup_import),
                summary = if (backupBusy) stringResource(R.string.backup_busy) else null,
                onClick = {
                    if (!backupBusy) {
                        showBackupMenu = false
                        importPicker.launch(arrayOf("*/*"))
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            TextButton(
                text = stringResource(R.string.layout_sheet_cancel),
                onClick = { if (!backupBusy) showBackupMenu = false },
                modifier = Modifier.fillMaxWidth()
            )
        }
    )

    // 备份恢复确认（2026-11-11）：解析成功才弹；确认后 IO 写、主线程 toast。
    ImportConfirmDialog(
        snapshot = importPreview,
        onConfirm = { snapshot ->
            importPreview = null
            backupBusy = true
            backupScope.launch {
                val pm = context.packageManager
                val result = withContext(Dispatchers.IO) {
                    ConfigBackup.apply(effectivePrefs, snapshot) { pkg ->
                        runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
                    }
                }
                backupBusy = false
                val msg = when (result) {
                    is ConfigBackup.ApplyResult.Success -> context.getString(
                        R.string.backup_import_done,
                        result.appsWritten,
                        result.appsSkipped,
                        result.settingsWritten,
                        result.shortcutsWritten
                    )
                    is ConfigBackup.ApplyResult.Rejected -> result.reason
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        },
        onDismiss = { importPreview = null }
    )

    // 一键重置确认 dialog（0922 随功能行自首页迁入，样式未变）
    ResetConfirmDialog(
        show = showResetConfirm,
        onConfirm = {
            repo.restoreAllDefaults()
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

/** 备份恢复确认（结构同 [ResetConfirmDialog]；snapshot=null 即不显示）。 */
@Composable
private fun ImportConfirmDialog(
    snapshot: ConfigBackup.Snapshot?,
    onConfirm: (ConfigBackup.Snapshot) -> Unit,
    onDismiss: () -> Unit
) {
    WindowDialog(
        show = snapshot != null,
        title = stringResource(R.string.backup_import_confirm_title),
        summary = snapshot?.let {
            stringResource(
                R.string.backup_import_confirm_summary,
                it.settings.size,
                it.customApps?.size ?: 0,
                it.shortcuts?.size ?: 0
            )
        } ?: "",
        onDismissRequest = onDismiss,
        content = {
            ConfirmCancelRow(
                confirmText = stringResource(R.string.backup_import_confirm_action),
                onConfirm = { snapshot?.let(onConfirm) },
                onCancel = onDismiss,
                primary = true
            )
        }
    )
}

/** miuix WindowDialog（窗口级）：自带居中 title/summary 与 insideMargin；
 *  按钮行照官方 DialogSection 模式——两等宽 TextButton 两端分布，确认染主色 */
@Composable
private fun ResetConfirmDialog(
    show: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    WindowDialog(
        show = show,
        title = stringResource(R.string.restore_defaults),
        summary = stringResource(R.string.restore_defaults_confirm),
        onDismissRequest = onDismiss,
        content = {
            ConfirmCancelRow(
                confirmText = stringResource(R.string.reset_confirm),
                onConfirm = onConfirm,
                onCancel = onDismiss,
                primary = true
            )
        }
    )
}

// 项目地址（0912 收口）：仓库名单源 UpdateChecker.REPO_PATH——改仓库只改那里
private const val PROJECT_URL = UpdateChecker.REPO_URL

/** 检查更新 UI 态；Failed 可重试，Available 点击跳 Release 页 */
private sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data object UpToDate : UpdateCheckState
    data class Available(val version: String, val pageUrl: String) : UpdateCheckState
    data object Failed : UpdateCheckState
}

private fun openExternalUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.open_link_failed), Toast.LENGTH_SHORT).show()
    }
}

// ===== About hero：动态渐变 + 星河背景（2026-11-04 去掉玻璃牌：星空下那块"不大不小"的磨砂面很突兀）=====

private val HERO_HEIGHT = 200.dp

/** 一次完整旋转周期；6s 转一圈偏快，9s 更像"呼吸"。 */
private const val HERO_STAGE_PERIOD_MS = 9000

/**
 * hero 专用取色：**只借 base 的色相**，饱和度与明度由 hero 自己给。
 *
 * 不能对主题色做 satScale/valScale：Monet（TonalSpot + Spec2025）的 primary 本身低彩度，
 * 放大 1.1 倍依旧是灰蓝，三段渐变会糊成一片；锁死 S/V 才能保证
 * 深浅模式 × 莫奈/非莫奈 四种组合都够"重"。
 */
private fun heroColor(base: Color, hueDelta: Float, saturation: Float, value: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(base.toArgb(), hsv)
    hsv[0] = (hsv[0] + hueDelta + 360f) % 360f
    hsv[1] = saturation.coerceIn(0f, 1f)
    hsv[2] = value.coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private fun DrawScope.drawHeroGradient(cs: Colors, stage: Float, isDark: Boolean) {
    val a = stage * 2f * PI.toFloat()
    val w = size.width
    val h = size.height
    val center = Offset(w / 2f, h / 2f)
    // 覆盖整卡（含旋转后的四角）
    val radius = hypot(w.toDouble(), h.toDouble()).toFloat() / 2f

    // 三段蓝紫：hero 自定的色相/饱和，再按比例混入主题容器色（莫奈开启时这些是
    // 由 seed 生成的调色板色，混进去 hero 才有"跟主题同一套"的观感）
    val deep = lerp(
        heroColor(cs.primary, hueDelta = -16f, saturation = 1f, value = if (isDark) 0.44f else 0.72f),
        cs.primaryContainer,
        0.28f
    )
    val mid = lerp(
        heroColor(cs.primary, hueDelta = 8f, saturation = 0.92f, value = if (isDark) 0.60f else 0.86f),
        cs.primaryVariant,
        0.22f
    )
    val glow = lerp(
        heroColor(cs.primary, hueDelta = 42f, saturation = 0.82f, value = if (isDark) 0.82f else 0.96f),
        cs.tertiaryContainer,
        0.22f
    )

    // 主运动：锥形渐变整卡旋转（一圈 = 一个周期）。只靠漂移色斑在缩略尺寸下几乎看不出动，
    // 旋转锥面才让"在动"这件事一眼可见
    withTransform({ rotate(stage * 360f, center) }) {
        drawCircle(
            brush = Brush.sweepGradient(
                colors = listOf(deep, mid, glow, mid, deep),
                center = center
            ),
            radius = radius,
            center = center
        )
    }

    // 两团漂移色斑补充层次（同样 SrcOver：backdrop 录制层起始透明，
    // Screen/Plus 在"透明底"与"屏上"结果不同，会让玻璃牌糊到的颜色与观感不一致）
    fun blob(cx: Float, cy: Float, r: Float, color: Color) {
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(color, Color.Transparent),
                center = Offset(cx, cy),
                radius = r
            )
        )
    }
    val breathe = 1f + 0.12f * sin(a * 0.9f)
    blob(
        w * (0.24f + 0.30f * cos(a)),
        h * (0.32f + 0.26f * sin(a)),
        w * 0.42f * breathe,
        heroColor(cs.primary, 72f, 0.62f, 1f).copy(alpha = if (isDark) 0.50f else 0.38f)
    )
    blob(
        w * (0.74f + 0.22f * sin(a * 1.15f)),
        h * (0.74f + 0.20f * cos(a * 0.85f)),
        w * 0.34f * breathe,
        cs.onPrimary.copy(alpha = if (isDark) 0.26f else 0.30f)
    )

    // 星河：固定种子的星点 + 随 stage 各自呼吸，hero 与图标形成同一套星空语系
    val starPx = 1.dp.toPx()
    for (star in HERO_STARS) {
        val twinkle = 0.45f + 0.30f * sin(a + star.phase)
        drawCircle(
            color = Color.White.copy(alpha = twinkle * if (isDark) 0.95f else 0.75f),
            radius = star.radiusDp * starPx,
            center = Offset(star.x * w, star.y * h)
        )
    }
}

/** hero 卡里的固定星点（种子固定 ⇒ 每次重组/录 backdrop 位置一致，不会闪跳） */
private data class HeroStar(
    val x: Float,
    val y: Float,
    val radiusDp: Float,
    val phase: Float
)

private val HERO_STARS: List<HeroStar> = kotlin.random.Random(20261104).let { r ->
    List(90) {
        HeroStar(
            x = r.nextFloat(),
            y = r.nextFloat(),
            radiusDp = 0.35f + r.nextFloat() * 1.0f,
            phase = r.nextFloat() * 2f * PI.toFloat()
        )
    }
}

/**
 * hero 卡：动态渐变 + 固定种子星点，logo 与标题直接落在星野上。
 * 渐变/星点只在 draw 相位读取，9s 周期内不会触发整页重组。
 */
@Composable
private fun AboutHero(cs: Colors, modifier: Modifier = Modifier) {
    val stage = remember { Animatable(0f) }
    val isDark = cs.surface.luminance() < 0.5f
    LaunchedEffect(Unit) {
        while (true) {
            stage.animateTo(
                targetValue = stage.value + 1f,
                animationSpec = tween(HERO_STAGE_PERIOD_MS, easing = LinearEasing)
            )
        }
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(HERO_HEIGHT)
                .drawBehind { drawHeroGradient(cs, stage.value, isDark) }
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_module_logo),
                    contentDescription = stringResource(R.string.app_icon_content_desc),
                    modifier = Modifier
                        .size(64.dp)
                        .squircleSurface(
                            color = cs.onPrimary.copy(alpha = 0.18f),
                            cornerRadius = 17.dp
                        )
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MiuixTheme.textStyles.title3.copy(
                        // 标题用渐变字（白 → 淡紫），与系统「关于本机」那种发光字一致；
                        // 底色是蓝紫渐变，文字不能用 onBackground
                        brush = Brush.linearGradient(
                            listOf(cs.onPrimary, heroColor(cs.primary, 70f, 0.55f, 1f))
                        )
                    ),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
