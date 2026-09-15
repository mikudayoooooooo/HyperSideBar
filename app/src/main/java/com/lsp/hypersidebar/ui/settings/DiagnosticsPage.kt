package com.lsp.hypersidebar.ui.settings

import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.util.RemotePrefsBridge
import com.lsp.hypersidebar.util.SelfCheck
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 诊断与统计汇聚页（0909 用户拍板）：诊断类功能的中间层——本页列项目，
 * 点进去才是具体页面（运行日志/使用统计）与动作（导出自检报告）。
 * 入口=关于页「调试」区的单个父条目，调试区不再平铺多个功能行。
 */
@Composable
internal fun DiagnosticsPage(
    service: XposedService?,
    prefs: SharedPreferences,
    onNavigateToLogs: () -> Unit,
    onNavigateToStats: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // D1 同款：绑定晚到时切 bridge.prefs 保读写同源（AboutPage 同法）
    val effectivePrefs = RemotePrefsBridge.prefs ?: prefs
    var selfCheckBusy by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
            .overScrollVertical()
            .padding(horizontal = 16.dp)
    ) {
        SmallTitle(text = stringResource(R.string.diagnostics_section))
        Card(modifier = Modifier.fillMaxWidth()) {
            ArrowPreference(
                title = stringResource(R.string.logs_entry),
                summary = stringResource(R.string.logs_entry_summary),
                onClick = onNavigateToLogs
            )
            ArrowPreference(
                title = stringResource(R.string.stats_entry),
                summary = stringResource(R.string.stats_entry_summary),
                onClick = onNavigateToStats
            )
            ArrowPreference(
                title = stringResource(R.string.selfcheck_export),
                summary = if (selfCheckBusy) {
                    stringResource(R.string.selfcheck_exporting)
                } else {
                    stringResource(R.string.selfcheck_export_summary)
                },
                onClick = {
                    if (selfCheckBusy) return@ArrowPreference
                    selfCheckBusy = true
                    scope.launch {
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
        }
    }
}
