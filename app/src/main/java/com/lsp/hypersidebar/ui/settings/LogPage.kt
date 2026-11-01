package com.lsp.hypersidebar.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.lsp.hypersidebar.theme.LocalSemanticColors
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.util.HLog
import com.lsp.hypersidebar.util.LogCollector
import com.lsp.hypersidebar.util.SelfCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 运行日志页（迭代六 §11.2）：三进程 HLog 环形缓冲的汇聚视图。
 * 过滤（进程/级别/关键字）+ 刷新拉取 + 导出 txt + 高频手势日志开关（运行时生效）。
 */
@Composable
internal fun LogPage(
    prefs: android.content.SharedPreferences,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var procFilter by remember { mutableStateOf("all") }      // all / launcher / ui / app
    var warnOnly by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf("") }
    var verbose by remember(prefs) {
        mutableStateOf(
            runCatching { prefs.getBoolean(PrefKeys.DEBUG_VERBOSE_LOGS, false) }.getOrDefault(false)
        )
    }
    var exporting by remember { mutableStateOf(false) }

    // 进入即拉一轮（各 hook 进程回传 + 本进程缓冲直读）
    LaunchedEffect(Unit) { LogCollector.request(context) }

    val entries = remember(procFilter, warnOnly, keyword, LogCollector.logs.toMap()) {
        val all = LogCollector.logs.toMap()
            .filterKeys { procFilter == "all" || it == procFilter }
            .flatMap { it.value }
            .sortedBy { it.wallMs }
        all.filter { e ->
            (!warnOnly || e.level == 'W' || e.level == 'E') &&
                (keyword.isBlank() || e.msg.contains(keyword, ignoreCase = true) ||
                    e.tag.contains(keyword, ignoreCase = true))
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
            .settingsScroll()
            .scrollEndHaptic(),
        contentPadding = PaddingValues(
            start = SettingsDimens.ScreenInset,
            end = SettingsDimens.ScreenInset,
            top = SettingsDimens.ListTop,
            bottom = SettingsDimens.ListBottom
        ),
        verticalArrangement = Arrangement.spacedBy(SettingsDimens.SectionGap)
    ) {
        // 头部控件组作为列表项随之滚走：原先固定头部在横屏/大字体下能把日志区挤成零高
        item {
            Column(Modifier.fillMaxWidth()) {
                // ===== 操作行：刷新 / 导出 / 高频开关 =====
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        text = stringResource(R.string.logs_refresh),
                        onClick = {
                            LogCollector.request(context)
                            Toast.makeText(context, R.string.logs_refreshing, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        text = if (exporting) stringResource(R.string.logs_exporting)
                        else stringResource(R.string.logs_export),
                        onClick = {
                            if (exporting) return@TextButton
                            exporting = true
                            scope.launch {
                                val path = withContext(Dispatchers.IO) {
                                    exportLogs(context, LogCollector.mergedForExport())
                                }
                                exporting = false
                                Toast.makeText(
                                    context, context.getString(R.string.logs_export_done, path),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        enabled = !exporting,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.logs_verbose),
                            color = MiuixTheme.colorScheme.onBackground,
                            style = MiuixTheme.textStyles.body1
                        )
                        Text(
                            stringResource(R.string.logs_verbose_summary),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote1,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Switch(checked = verbose, onCheckedChange = { on ->
                        verbose = on
                        HLog.verboseEnabled = on
                        runCatching { prefs.edit().putBoolean(PrefKeys.DEBUG_VERBOSE_LOGS, on).apply() }
                        // 高频日志开关要热更新到三个进程（hook 侧 ConfigSync.applySync 也会就地刷新）
                        com.lsp.hypersidebar.util.ConfigSync.notifyConfigChanged(PrefKeys.DEBUG_VERBOSE_LOGS)
                    })
                }
                // ===== 进程过滤 chips =====
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SettingsChip(
                        text = stringResource(R.string.log_filter_all),
                        selected = procFilter == "all",
                        onClick = { procFilter = "all" }
                    )
                    SettingsChip(
                        text = stringResource(R.string.log_proc_launcher),
                        selected = procFilter == HLog.PROC_LAUNCHER,
                        onClick = { procFilter = HLog.PROC_LAUNCHER }
                    )
                    SettingsChip(
                        text = stringResource(R.string.log_proc_ui),
                        selected = procFilter == HLog.PROC_UI,
                        onClick = { procFilter = HLog.PROC_UI }
                    )
                    SettingsChip(
                        text = stringResource(R.string.log_proc_app),
                        selected = procFilter == HLog.PROC_APP,
                        onClick = { procFilter = HLog.PROC_APP }
                    )
                    SettingsChip(
                        text = stringResource(
                            if (warnOnly) R.string.log_level_warn else R.string.log_level_all
                        ),
                        selected = warnOnly,
                        onClick = { warnOnly = !warnOnly }
                    )
                }
                TextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    label = stringResource(R.string.log_keyword_hint),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
        // ===== 状态快照（熔断等） =====
            }
        }
        val statuses = LogCollector.statuses.toMap()
        if (statuses.isNotEmpty()) {
            item { SectionTitle(text = stringResource(R.string.log_proc_status)) }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    statuses.forEach { (proc, json) ->
                        Text(
                            text = "$proc: ${statusLine(json)}",
                            style = MiuixTheme.textStyles.footnote1,
                            fontFamily = FontFamily.Monospace,
                            color = MiuixTheme.colorScheme.onBackground,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
        item { SectionTitle(text = stringResource(R.string.log_count_format, entries.size)) }
        // ===== 日志列表 =====
        if (entries.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.logs_empty),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote1,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            }
        }
        items(entries, key = { "${it.wallMs}-${it.tag}-${it.msg.hashCode()}" }) { e ->
            Text(
                text = e.formatLine(),
                style = MiuixTheme.textStyles.footnote2,
                lineHeight = 15.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
                color = when (e.level) {
                    'E' -> MiuixTheme.colorScheme.error
                    'W' -> LocalSemanticColors.current.warning
                    else -> MiuixTheme.colorScheme.onBackground
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            )
        }
    }
}

/** 熔断快照 JSON → 单行人话（open=true 红色由调用方不区分，保持简单） */
private fun statusLine(json: String): String = runCatching {
    val o = JSONObject(json)
    val open = o.optBoolean("open")
    val cons = o.optInt("consecutive")
    val th = o.optInt("threshold", 5)
    val reason = o.optString("lastReason", "")
    buildString {
        append(if (open) "已熔断" else "正常")
        append(" · 失败 $cons/$th")
        if (reason.isNotEmpty()) append(" · $reason")
    }
}.getOrDefault(json.take(80))

/** 导出为分段 txt（复用 SelfCheck 的 MediaStore 路径） */
private suspend fun exportLogs(context: Context, logs: Map<String, List<HLog.Entry>>): String =
    withContext(Dispatchers.IO) {
        val sb = StringBuilder("hyperSidebar 运行日志导出\n时间: ")
            .append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date()))
            .append('\n')
        logs.forEach { (proc, list) ->
            sb.append("\n===== [$proc] ${list.size} 条 =====\n")
            list.forEach { sb.append(it.formatLine()).append('\n') }
        }
        // native 取证段由 `SelfCheck.export()` 统一附加（三条导出路径共用那个收口，
        // 免得按哪个按钮结果不一样——1009 就是踩在这里：日志页导出里没有 hook 状态）
        SelfCheck.export(context, sb.toString())
    }
