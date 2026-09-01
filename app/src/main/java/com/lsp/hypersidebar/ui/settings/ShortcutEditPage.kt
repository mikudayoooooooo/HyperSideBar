package com.lsp.hypersidebar.ui.settings

import android.content.SharedPreferences
import android.util.Log
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.util.DefaultLaunchStrategy
import com.lsp.hypersidebar.util.LaunchResult
import com.lsp.hypersidebar.util.ShortcutAction
import com.lsp.hypersidebar.util.ShortcutKind
import com.lsp.hypersidebar.util.ShortcutLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

private const val TAG = "ShortcutSettings"

@Composable
internal fun ShortcutEditPage(
    shortcut: ShortcutAction,
    isNew: Boolean,
    prefs: SharedPreferences,
    initialTargetSpec: SplitResult = SplitResult("", ""),
    onSave: (ShortcutAction) -> Unit,
    onDelete: (() -> Unit)?,
    onPickActivity: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var label by remember(shortcut.id) { mutableStateOf(shortcut.label) }
    var targetSpec by remember(shortcut.id) { mutableStateOf(initialTargetSpec.spec) }
    var serviceName by remember(shortcut.id) { mutableStateOf(shortcut.serviceName ?: "") }
    var intentUri by remember(shortcut.id) { mutableStateOf(shortcut.intentUri ?: "") }
    var packageName by remember(shortcut.id) { mutableStateOf(shortcut.packageName ?: "") }
    var enabled by remember(shortcut.id) { mutableStateOf(shortcut.enabled) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    // 已安装包名列表：IO 线程一次性预取（原实现把 getInstalledPackages 放在组合期派生状态里）
    val installedPkgs by produceState<List<String>>(emptyList(), shortcut.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getInstalledPackages(0)
                    .mapNotNull { it.packageName }
            }.getOrDefault(emptyList())
        }
    }

    // 从 targetSpec 推导 pkg/act（纯函数，随输入与包列表变化重算）
    val resolved = remember(targetSpec, installedPkgs) {
        splitTargetSpec(targetSpec, installedPkgs)
    }

    LaunchedEffect(testResult) {
        if (testResult != null) {
            delay(3000)
            testResult = null
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            BasicComponent(
                title = stringResource(if (isNew) R.string.shortcut_new else R.string.shortcut_edit),
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Back,
                        contentDescription = stringResource(R.string.back),
                        modifier = Modifier.size(20.dp),
                        tint = MiuixTheme.colorScheme.primary
                    )
                },
                onClick = onBack
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    title = stringResource(R.string.shortcut_enabled),
                    checked = enabled,
                    onCheckedChange = { enabled = it }
                )
            }
        }

        item { SmallTitle(text = stringResource(R.string.shortcut_basic_info)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = stringResource(R.string.shortcut_label), style = MiuixTheme.textStyles.body1)
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = label,
                        onValueChange = { label = it },
                        label = stringResource(R.string.shortcut_label_hint)
                    )
                }
            }
        }

        item { SmallTitle(text = stringResource(R.string.shortcut_target_config)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    when (shortcut.kind) {
                        ShortcutKind.COMPONENT -> {
                            Text(text = stringResource(R.string.shortcut_target_component), style = MiuixTheme.textStyles.body1)
                            Spacer(Modifier.height(8.dp))
                            TextField(
                                value = targetSpec,
                                onValueChange = { targetSpec = it },
                                label = stringResource(R.string.shortcut_component_hint)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.shortcut_component_desc),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                style = MiuixTheme.textStyles.footnote1
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = onPickActivity,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.shortcut_pick_activity))
                            }
                        }
                        ShortcutKind.ACTIVITY -> {
                            Text(text = stringResource(R.string.shortcut_package_activity), style = MiuixTheme.textStyles.body1)
                            Spacer(Modifier.height(8.dp))
                            TextField(
                                value = targetSpec,
                                onValueChange = { targetSpec = it },
                                label = stringResource(R.string.shortcut_component_hint)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.shortcut_package_activity_hint),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                style = MiuixTheme.textStyles.footnote1
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = onPickActivity,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.shortcut_pick_activity))
                            }
                        }
                        ShortcutKind.INTENT_URI -> {
                            Text(text = stringResource(R.string.shortcuts_add_intent_uri), style = MiuixTheme.textStyles.body1)
                            Spacer(Modifier.height(8.dp))
                            TextField(
                                value = intentUri,
                                onValueChange = { intentUri = it },
                                label = stringResource(R.string.shortcut_intent_uri_hint)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.shortcut_intent_uri_desc),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                style = MiuixTheme.textStyles.footnote1
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(text = stringResource(R.string.shortcut_package_optional), style = MiuixTheme.textStyles.body1)
                            Spacer(Modifier.height(8.dp))
                            TextField(
                                value = packageName,
                                onValueChange = { packageName = it },
                                label = stringResource(R.string.shortcut_package_optional_hint)
                            )
                        }
                        ShortcutKind.TOOLBOX -> {
                            Text(text = stringResource(R.string.shortcut_toolbox_desc))
                        }
                        ShortcutKind.SERVICE -> {
                            Text(text = stringResource(R.string.shortcut_package_optional), style = MiuixTheme.textStyles.body1)
                            Spacer(Modifier.height(8.dp))
                            TextField(
                                value = packageName,
                                onValueChange = { packageName = it },
                                label = "com.example.app"
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(text = stringResource(R.string.shortcut_service_name), style = MiuixTheme.textStyles.body1)
                            Spacer(Modifier.height(8.dp))
                            TextField(
                                value = serviceName,
                                onValueChange = { serviceName = it },
                                label = stringResource(R.string.shortcut_service_name_hint)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.shortcut_service_root_note),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                style = MiuixTheme.textStyles.footnote1
                            )
                        }
                    }
                }
            }
        }

        item { SmallTitle(text = stringResource(R.string.shortcut_test_section)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            testing = true
                            testResult = null
                            val testShortcut = shortcut.copy(
                                label = label,
                                packageName = when (shortcut.kind) {
                                    ShortcutKind.COMPONENT, ShortcutKind.ACTIVITY -> resolved.pkg.ifEmpty { null }
                                    ShortcutKind.SERVICE -> packageName.ifEmpty { null }
                                    else -> packageName.ifEmpty { null }
                                },
                                activityName = when (shortcut.kind) {
                                    ShortcutKind.COMPONENT, ShortcutKind.ACTIVITY -> resolved.act.ifEmpty { null }
                                    else -> null
                                },
                                serviceName = when (shortcut.kind) {
                                    ShortcutKind.SERVICE -> serviceName.ifEmpty { null }
                                    else -> null
                                },
                                intentUri = intentUri.ifEmpty { null }
                            )
                            scope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        ShortcutLauncher.launch(
                                            context, testShortcut, DefaultLaunchStrategy()
                                        )
                                    }
                                    testResult = when (result) {
                                        is LaunchResult.Success ->
                                            context.getString(R.string.shortcut_test_success, result.componentName?.flattenToShortString() ?: "OK")
                                        is LaunchResult.Failure ->
                                            context.getString(R.string.shortcut_test_failure, result.reason, result.detail)
                                    }
                                } catch (e: Exception) {
                                    testResult = context.getString(R.string.shortcut_test_error, e.message ?: "Unknown error")
                                    Log.e(TAG, "Test launch failed", e)
                                } finally {
                                    testing = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (testing) stringResource(R.string.shortcut_testing) else stringResource(R.string.shortcut_test))
                    }
                    testResult?.let { result ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = result,
                            color = if (result.startsWith(context.getString(R.string.shortcut_test_success, "").substringBefore(":")))
                                MiuixTheme.colorScheme.primary
                            else
                                MiuixTheme.colorScheme.error,
                            style = MiuixTheme.textStyles.footnote1
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.shortcut_test_note),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.footnote1
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val updated = shortcut.copy(
                        label = label.ifEmpty { context.getString(R.string.shortcut_unnamed) },
                        packageName = when (shortcut.kind) {
                            ShortcutKind.COMPONENT, ShortcutKind.ACTIVITY -> resolved.pkg.ifEmpty { null }
                            else -> packageName.ifEmpty { null }
                        },
                        activityName = when (shortcut.kind) {
                            ShortcutKind.COMPONENT, ShortcutKind.ACTIVITY -> resolved.act.ifEmpty { null }
                            else -> null
                        },
                        serviceName = when (shortcut.kind) {
                            ShortcutKind.SERVICE -> serviceName.ifEmpty { null }
                            else -> null
                        },
                        intentUri = intentUri.ifEmpty { null },
                        enabled = enabled,
                        iconPackageName = when (shortcut.kind) {
                            ShortcutKind.COMPONENT, ShortcutKind.ACTIVITY -> resolved.pkg.ifEmpty { shortcut.iconPackageName }
                            ShortcutKind.SERVICE -> packageName.ifEmpty { shortcut.iconPackageName }
                            else -> shortcut.iconPackageName
                        }
                    )
                    onSave(updated)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.shortcut_save))
            }
        }

        // 删除入口：仅编辑已有快捷方式时显示
        if (onDelete != null) {
            item {
                Spacer(Modifier.height(4.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.shortcut_delete),
                        color = MiuixTheme.colorScheme.error,
                        style = MiuixTheme.textStyles.body1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onDelete!!)
                            .padding(vertical = 12.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * 拆分结果：从用户输入的完整类名推导出 packageName + activityName。
 * 优先匹配已安装包中最长的包名前缀；匹配失败时 act 取原值，pkg 为空。
 */
data class SplitResult(
    val pkg: String,
    val act: String,
    val spec: String = if (pkg.isNotEmpty()) "$pkg/$act" else act
)

/**
 * 将用户输入（完整类名或 pkg/act 混合格式）拆分为 pkg + act。
 * 通过已安装包列表匹配最长前缀，确保即使输入不精确也能正确拆分。
 *
 * @param installedPkgs 已预取的包名列表（IO 线程加载），本函数保持纯函数
 */
private fun splitTargetSpec(input: String, installedPkgs: List<String>): SplitResult {
    if (input.isBlank()) return SplitResult("", "")
    val cleaned = input.replace(Regex("[\n\r\t\u0000-\u001f\u007f-\u009f]"), "")
    val trimmed = cleaned.trim()
    // 已包含 / 分隔符：直接按 / 拆（取第一段作为包名候选）
    if (trimmed.contains('/')) {
        val parts = trimmed.split('/', limit = 2)
        return SplitResult(pkg = parts[0].trim(), act = parts[1].trim())
    }
    // 单段完整类名：匹配已安装包列表中最长的包名前缀
    var bestPkg = ""
    for (pkgName in installedPkgs) {
        if (trimmed.startsWith("$pkgName.") && pkgName.length > bestPkg.length) {
            bestPkg = pkgName
        }
    }
    val act = if (bestPkg.isNotEmpty()) trimmed.substring(bestPkg.length) else trimmed
    return SplitResult(pkg = bestPkg, act = act)
}
