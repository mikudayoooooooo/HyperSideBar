package com.lsp.hypersidebar.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.BuildConfig
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.prefs.savePref
import com.lsp.hypersidebar.util.RemotePrefsBridge
import com.lsp.hypersidebar.util.SelfCheck
import com.lsp.hypersidebar.util.UpdateChecker
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun AboutPage(
    service: XposedService?,
    prefs: SharedPreferences,
    prefsRevision: Int,
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
    val deviceModel = remember { Build.MODEL.ifEmpty { Build.DEVICE } }
    val systemVersion = remember { "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）" }
    val frameworkName by produceState(
        initialValue = context.getString(R.string.unknown),
        key1 = service
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching { service?.frameworkName?.toString() }.getOrNull()
                ?: context.getString(R.string.unknown)
        }
    }
    val frameworkVersion by produceState(
        initialValue = "--",
        key1 = service
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching { service?.frameworkVersion?.toString() }.getOrNull() ?: "--"
        }
    }
    val apiVersion by produceState(
        initialValue = "--",
        key1 = service
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching { service?.apiVersion?.toString() }.getOrNull() ?: "--"
        }
    }

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
    val updateSummary = when (val state = updateState) {
        UpdateCheckState.Idle -> stringResource(R.string.update_check_idle)
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

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .overScrollVertical(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = stringResource(R.string.app_icon_content_desc),
                        modifier = Modifier
                            .size(68.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(MiuixTheme.colorScheme.primaryContainer)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.module_description),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }

        item { SmallTitle(text = stringResource(R.string.about_info)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    BasicComponent(
                        title = stringResource(R.string.about_version),
                        summary = versionName
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
                    BasicComponent(
                        title = stringResource(R.string.about_version_code),
                        summary = BuildConfig.VERSION_CODE.toString(),
                        onClick = {
                            val versionCode = BuildConfig.VERSION_CODE.toString()
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("versionCode", versionCode))
                            Toast.makeText(
                                context,
                                context.getString(R.string.version_code_copied),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                    BasicComponent(
                        title = stringResource(R.string.about_based_on),
                        summary = stringResource(
                            R.string.about_based_on_value,
                            BuildConfig.XPOSED_API_VERSION,
                            BuildConfig.EZXHELPER_VERSION
                        )
                    )
                    BasicComponent(
                        title = stringResource(R.string.about_sdk),
                        summary = stringResource(R.string.about_sdk_value)
                    )
                }
            }
        }

        item { SmallTitle(text = stringResource(R.string.framework_info)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    BasicComponent(
                        title = stringResource(R.string.device_model),
                        summary = deviceModel
                    )
                    BasicComponent(
                        title = stringResource(R.string.system_version),
                        summary = systemVersion
                    )
                    BasicComponent(
                        title = stringResource(R.string.framework_name),
                        summary = frameworkName
                    )
                    BasicComponent(
                        title = stringResource(R.string.framework_version),
                        summary = frameworkVersion
                    )
                    BasicComponent(
                        title = stringResource(R.string.api_version),
                        summary = apiVersion
                    )
                }
            }
        }

        item { SmallTitle(text = stringResource(R.string.about_links)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ArrowPreference(
                        title = stringResource(R.string.about_author),
                        summary = AUTHOR_HANDLE,
                        onClick = { openExternalUrl(context, AUTHOR_URL) }
                    )
                    ArrowPreference(
                        title = stringResource(R.string.about_project_url),
                        summary = PROJECT_URL.removePrefix("https://"),
                        onClick = { openExternalUrl(context, PROJECT_URL) }
                    )
                }
            }
        }

        item { SmallTitle(text = stringResource(R.string.debug_section)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                var selfCheckBusy by remember { mutableStateOf(false) }
                BasicComponent(
                    title = stringResource(R.string.selfcheck_export),
                    summary = if (selfCheckBusy) {
                        stringResource(R.string.selfcheck_exporting)
                    } else {
                        stringResource(R.string.selfcheck_export_summary)
                    },
                    onClick = {
                        if (selfCheckBusy) return@BasicComponent
                        selfCheckBusy = true
                        updateScope.launch {
                            val path = runCatching {
                                val content = SelfCheck.generate(context, service, effectivePrefs)
                                SelfCheck.export(context, content)
                            }.getOrElse { context.getString(R.string.unknown) + " (${it.message})" }
                            selfCheckBusy = false
                            runCatching {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.selfcheck_export_done, path),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                )
                SwitchPreference(
                    title = stringResource(R.string.debug_relay_blackhole),
                    summary = stringResource(R.string.debug_relay_blackhole_summary),
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
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = stringResource(R.string.layout_sheet_cancel),
                    onClick = { showBlackholeConfirm = false },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(20.dp))
                TextButton(
                    text = if (countdown > 0) {
                        stringResource(R.string.debug_relay_confirm_action_counting, countdown)
                    } else {
                        stringResource(R.string.debug_relay_confirm_action)
                    },
                    onClick = {
                        relayBlackhole = true
                        effectivePrefs.savePref(PrefKeys.DEBUG_RELAY_BLACKHOLE, true)
                        showBlackholeConfirm = false
                    },
                    enabled = countdown <= 0,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(color = MiuixTheme.colorScheme.error)
                )
            }
        }
    )
}

// 项目地址取自仓库 origin（github.com/mikudayoooooooo/HyperSideBar）——改仓库时同步改这里
private const val AUTHOR_HANDLE = "mikudayoooooooo"
private const val AUTHOR_URL = "https://github.com/mikudayoooooooo"
private const val PROJECT_URL = "https://github.com/mikudayoooooooo/HyperSideBar"

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
