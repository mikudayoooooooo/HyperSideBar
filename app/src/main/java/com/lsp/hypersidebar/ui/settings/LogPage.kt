package com.lsp.hypersidebar.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Color
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
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
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

    Column(
        modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.padding(top = 8.dp))
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
                    fontSize = 15.sp
                )
                Text(
                    stringResource(R.string.logs_verbose_summary),
                    color = MiuixTheme.colorScheme.onSecondaryContainer,
                    fontSize = 12.sp,
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
            Chip("全部", procFilter == "all") { procFilter = "all" }
            Chip("桌面", procFilter == HLog.PROC_LAUNCHER) { procFilter = HLog.PROC_LAUNCHER }
            Chip("执行端", procFilter == HLog.PROC_UI) { procFilter = HLog.PROC_UI }
            Chip("本应用", procFilter == HLog.PROC_APP) { procFilter = HLog.PROC_APP }
            Chip(if (warnOnly) "警告+" else "全级别", warnOnly) { warnOnly = !warnOnly }
        }
        TextField(
            value = keyword,
            onValueChange = { keyword = it },
            label = "关键字过滤（tag 或内容）",
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )
        // ===== 状态快照（熔断等） =====
        val statuses = LogCollector.statuses.toMap()
        if (statuses.isNotEmpty()) {
            SmallTitle(text = "进程状态")
            Card(modifier = Modifier.fillMaxWidth()) {
                statuses.forEach { (proc, json) ->
                    Text(
                        text = "$proc: ${statusLine(json)}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MiuixTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
        SmallTitle(text = "日志（${entries.size} 条）")
        // ===== 日志列表 =====
        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.logs_empty),
                color = MiuixTheme.colorScheme.onSecondaryContainer,
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 24.dp)
            )
        }
        LazyColumn(
            Modifier
                .weight(1f)
                .overScrollVertical()
                .scrollEndHaptic()
        ) {
            items(entries, key = { "${it.wallMs}-${it.tag}-${it.msg.hashCode()}" }) { e ->
                Text(
                    text = e.formatLine(),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                    color = when (e.level) {
                        'E' -> Color(0xFFE0533D)
                        'W' -> Color(0xFFC88719)
                        else -> MiuixTheme.colorScheme.onBackground
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                )
            }
            item { Spacer(Modifier.padding(bottom = 16.dp)) }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(
        text = label,
        onClick = onClick,
        colors = if (selected) ButtonDefaults.textButtonColors(
            color = MiuixTheme.colorScheme.primary
        ) else ButtonDefaults.textButtonColors(),
        modifier = Modifier.padding(vertical = 2.dp)
    )
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
        SelfCheck.export(context, sb.toString())
    }
