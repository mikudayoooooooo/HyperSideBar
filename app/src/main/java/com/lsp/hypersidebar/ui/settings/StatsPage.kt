package com.lsp.hypersidebar.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.util.LogCollector
import com.lsp.hypersidebar.util.StatsRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 使用统计页（迭代六 §11.3，PRD §9.2/§9.3）。
 * 数据源=launcher/:ui 两进程 StatsRecorder 聚合（经日志拉取同通道回传），按天合并；
 * 误触率为 0914 重定义版（口径见 StatsRecorder 与页底文案）；支持导出 CSV。
 */
@Composable
internal fun StatsPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) { LogCollector.request(context) }

    // LogCollector.stats 变化即重算（mutableStateMapOf 读感知重组）
    val merged = remember(revision, LogCollector.stats.toMap()) {
        runCatching { StatsRecorder.mergeDumps(LogCollector.stats.values.toList()) }
            .getOrDefault(JSONObject())
    }
    val today = dayCounters(merged, StatsRecorder.dayKey())
    val total = totalCounters(merged)
    val samples = Samples(
        selectMs = intList(merged, "selectMs"),
        responseMs = intList(merged, "responseMs"),
        gapMs = longList(merged, "gapMs")
    )

    Column(
        modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
            .overScrollVertical()
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth()) {
            TextButton(
                text = stringResource(R.string.stats_refresh),
                onClick = {
                    LogCollector.request(context)
                    revision++
                },
                modifier = Modifier.weight(1f)
            )
            TextButton(
                text = if (exporting) stringResource(R.string.logs_exporting)
                else stringResource(R.string.stats_export),
                onClick = {
                    if (exporting) return@TextButton
                    exporting = true
                    scope.launch {
                        val path = withContext(Dispatchers.IO) { exportStatsCsv(context, merged) }
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

        SmallTitle(text = stringResource(R.string.stats_today))
        Card(Modifier.fillMaxWidth()) {
            MetricRow(stringResource(R.string.stats_invocations), today[StatsRecorder.MetricKeys.SHOWS])
            MetricRow(stringResource(R.string.stats_opens), today[StatsRecorder.MetricKeys.OPENS])
            MetricRow(stringResource(R.string.stats_all_apps), today[StatsRecorder.MetricKeys.ALL_APPS])
            MetricRow(stringResource(R.string.stats_shortcuts), today[StatsRecorder.MetricKeys.SHORTCUTS])
            MetricRow(stringResource(R.string.stats_cancels), today[StatsRecorder.MetricKeys.CANCELS])
            MetricRow(
                stringResource(R.string.stats_success_rate),
                successRate(today[StatsRecorder.MetricKeys.LAUNCH_OK], today[StatsRecorder.MetricKeys.LAUNCH_FAIL]), isRate = true
            )
            MetricRow(
                stringResource(R.string.stats_misfire_rate),
                misfireRateText(merged), isRate = true
            )
            MetricRow(stringResource(R.string.stats_avg_select), avgMs(samples.selectMs), suffix = "ms")
            MetricRow(stringResource(R.string.stats_avg_response), avgMs(samples.responseMs), suffix = "ms")
            MetricRow(stringResource(R.string.stats_avg_gap), avgSecs(samples.gapMs), suffix = "s")
        }

        SmallTitle(text = stringResource(R.string.stats_total))
        Card(Modifier.fillMaxWidth()) {
            MetricRow(stringResource(R.string.stats_invocations), total[StatsRecorder.MetricKeys.SHOWS])
            MetricRow(stringResource(R.string.stats_opens), total[StatsRecorder.MetricKeys.OPENS])
            MetricRow(stringResource(R.string.stats_all_apps), total[StatsRecorder.MetricKeys.ALL_APPS])
            MetricRow(
                stringResource(R.string.stats_all_apps_share),
                allAppsShare(total[StatsRecorder.MetricKeys.OPENS], total[StatsRecorder.MetricKeys.ALL_APPS]), isRate = true
            )
            MetricRow(
                stringResource(R.string.stats_success_rate),
                successRate(total[StatsRecorder.MetricKeys.LAUNCH_OK], total[StatsRecorder.MetricKeys.LAUNCH_FAIL]), isRate = true
            )
        }
        Text(
            stringResource(R.string.stats_note),
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSecondaryContainer,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
        )
    }
}

@Composable
private fun MetricRow(label: String, value: Any?, isRate: Boolean = false, suffix: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, fontSize = 14.sp, color = MiuixTheme.colorScheme.onBackground)
        Spacer(Modifier.weight(1f))
        val text = when {
            value == null -> "--"
            isRate -> "$value%"
            suffix != null -> "$value $suffix"
            else -> "$value"
        }
        Text(text, fontSize = 14.sp, color = MiuixTheme.colorScheme.primary)
    }
}

private data class Samples(
    val selectMs: List<Int>,
    val responseMs: List<Int>,
    val gapMs: List<Long>
)

private fun dayCounters(root: JSONObject, day: String): Map<String, Int> =
    root.optJSONObject("days")?.optJSONObject(day)?.let { o ->
        o.keys().asSequence().map { it to o.optInt(it) }.toMap()
    } ?: emptyMap()

private fun totalCounters(root: JSONObject): Map<String, Int> {
    val acc = mutableMapOf<String, Int>()
    root.optJSONObject("days")?.let { ds ->
        ds.keys().forEach { d ->
            ds.getJSONObject(d).let { c ->
                c.keys().forEach { k -> acc[k] = (acc[k] ?: 0) + c.optInt(k) }
            }
        }
    }
    return acc
}

private fun intList(root: JSONObject, key: String): List<Int> =
    root.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: emptyList()

private fun longList(root: JSONObject, key: String): List<Long> =
    root.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optLong(it) } } ?: emptyList()

private fun avgMs(list: List<Int>): String? =
    if (list.isEmpty()) null else (list.sum() / list.size).toString()

private fun avgSecs(list: List<Long>): String? =
    if (list.isEmpty()) null else String.format(java.util.Locale.US, "%.1f", list.sum() / 1000.0 / list.size)

private fun successRate(ok: Int?, fail: Int?): String? {
    val o = ok ?: 0
    val f = fail ?: 0
    if (o + f == 0) return null
    return (o * 100 / (o + f)).toString()
}

private fun allAppsShare(opens: Int?, allApps: Int?): String? {
    val o = opens ?: 0
    val a = allApps ?: 0
    if (o == 0) return null
    return (a * 100 / o).toString()
}

/** 误触率文本：判据与阈值单源 StatsRecorder.misfireRateFrom（0914 重定义版） */
private fun misfireRateText(merged: JSONObject): String? {
    val recent = merged.optJSONArray("recent") ?: return null
    val events = (0 until recent.length()).mapNotNull { recent.optJSONObject(it) }
        .map {
            Triple(
                it.optLong("ts"), it.optString("type"),
                if (it.has("ms")) it.optInt("ms") else null
            )
        }
    return StatsRecorder.misfireRateFrom(events)?.let { (misfires, shows) ->
        (misfires * 100 / shows).toString()
    }
}

/** 导出按天 CSV（下载目录，复用 SelfCheck 的 MediaStore 路径）：表头与行同源 MetricKeys，防漂移 */
private suspend fun exportStatsCsv(context: Context, merged: JSONObject): String =
    withContext(Dispatchers.IO) {
        val keys = listOf(
            StatsRecorder.MetricKeys.SHOWS,
            StatsRecorder.MetricKeys.OPENS,
            StatsRecorder.MetricKeys.ALL_APPS,
            StatsRecorder.MetricKeys.SHORTCUTS,
            StatsRecorder.MetricKeys.CANCELS,
            StatsRecorder.MetricKeys.LAUNCH_OK,
            StatsRecorder.MetricKeys.LAUNCH_FAIL
        )
        val sb = StringBuilder("date," + keys.joinToString(",") + "\n")
        merged.optJSONObject("days")?.let { ds ->
            ds.keys().asSequence().sorted().forEach { d ->
                val c = ds.getJSONObject(d)
                sb.append(d)
                keys.forEach { k -> sb.append(',').append(c.optInt(k)) }
                sb.append('\n')
            }
        }
        com.lsp.hypersidebar.util.SelfCheck.export(context, sb.toString())
    }
