package com.lsp.hypersidebar.ui.settings

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.lsp.hypersidebar.R
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.util.DefaultLaunchStrategy
import com.lsp.hypersidebar.util.LaunchResult
import com.lsp.hypersidebar.util.ShortcutAction
import com.lsp.hypersidebar.util.ShortcutKind
import com.lsp.hypersidebar.util.ShortcutLauncher
import com.lsp.hypersidebar.util.ShortcutStore
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
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.util.UUID

private const val TAG = "ShortcutSettings"

@Composable
internal fun ShortcutSettingsPage(
    prefs: SharedPreferences,
    modifier: Modifier = Modifier
) {
    var editingShortcut by remember { mutableStateOf<ShortcutAction?>(null) }
    var isNewShortcut by remember { mutableStateOf(false) }
    var showActivityPicker by remember { mutableStateOf(false) }
    var listRevision by remember { mutableStateOf(0) }

    val activity = LocalContext.current as? ComponentActivity

    // 内部返回栈：编辑页按返回 → 回到列表。
    // 选择器页（showActivityPicker）的返回由 ActivityPickerPage 自己的回调处理，
    // 此处用 !showActivityPicker 避免重复拦截。列表层时回调禁用，放行给 MainScreen。
    DisposableEffect(activity, editingShortcut, showActivityPicker) {
        val callback = object : OnBackPressedCallback(editingShortcut != null && !showActivityPicker) {
            override fun handleOnBackPressed() {
                editingShortcut = null
            }
        }
        activity?.onBackPressedDispatcher?.addCallback(callback)
        onDispose { callback.remove() }
    }

        when {
        showActivityPicker -> {
            ActivityPickerPage(
                onSelected = { pkg, act, label ->
                    editingShortcut = editingShortcut?.copy(
                        packageName = pkg,
                        activityName = act,
                        // 仅当当前名称为空时才用 Activity 标签自动填充，避免覆盖用户已输入的名称
                        label = editingShortcut?.label?.ifEmpty { label } ?: label,
                        iconPackageName = pkg
                    )
                    showActivityPicker = false
                },
                onBack = { showActivityPicker = false }
            )
        }
        editingShortcut != null -> {
            val derived = remember(editingShortcut?.packageName, editingShortcut?.activityName) {
                SplitResult(
                    pkg = editingShortcut?.packageName ?: "",
                    act = editingShortcut?.activityName ?: ""
                )
            }
            ShortcutEditPage(
                shortcut = editingShortcut!!,
                isNew = isNewShortcut,
                prefs = prefs,
                initialTargetSpec = derived,
                onSave = { updated ->
                    if (isNewShortcut) {
                        ShortcutStore.addShortcut(prefs, updated)
                    } else {
                        ShortcutStore.updateShortcut(prefs, updated)
                    }
                    listRevision++
                    editingShortcut = null
                },
                onDelete = if (isNewShortcut) null else {
                    {
                        ShortcutStore.removeShortcut(prefs, editingShortcut!!.id)
                        listRevision++
                        editingShortcut = null
                    }
                },
                onPickActivity = { showActivityPicker = true },
                onBack = { editingShortcut = null }
            )
        }
        else -> {
            ShortcutListPage(
                prefs = prefs,
                revision = listRevision,
                onEdit = { shortcut ->
                    isNewShortcut = false
                    editingShortcut = shortcut
                },
                onAdd = { kind ->
                    isNewShortcut = true
                    editingShortcut = ShortcutAction(
                        id = UUID.randomUUID().toString(),
                        kind = kind,
                        label = "",
                        enabled = true
                    )
                },
                modifier = modifier
            )
        }
    }
}

@Composable
private fun ShortcutListPage(
    prefs: SharedPreferences,
    revision: Int,
    onEdit: (ShortcutAction) -> Unit,
    onAdd: (ShortcutKind) -> Unit,
    modifier: Modifier = Modifier
) {
    val shortcuts = remember(prefs, revision) {
        ShortcutStore.loadUserShortcuts(prefs)
    }
    val isFull = shortcuts.size >= ShortcutStore.MAX_USER_SHORTCUTS

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .overScrollVertical(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            SmallTitle(text = stringResource(R.string.shortcut_count_format, shortcuts.size, ShortcutStore.MAX_USER_SHORTCUTS))
        }

        if (shortcuts.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.shortcuts_empty_hint),
                        modifier = Modifier.padding(16.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }

        if (shortcuts.isNotEmpty()) {
            items(shortcuts, key = { it.id }) { shortcut ->
                val iconPkg = shortcut.iconPackageName ?: shortcut.packageName
                val status = if (shortcut.enabled) "" else stringResource(R.string.shortcut_disabled_tag)
                ArrowPreference(
                    title = shortcut.label.ifEmpty { stringResource(R.string.shortcut_unnamed) },
                    summary = buildShortcutSummary(
                        shortcut,
                        stringResource(R.string.shortcut_uri_unset),
                        stringResource(R.string.shortcut_toolbox_desc)
                    ) + status,
                    startAction = if (iconPkg != null) {
                        { SettingsAppIcon(packageName = iconPkg, appName = shortcut.label, size = 32f) }
                    } else null,
                    onClick = { onEdit(shortcut) }
                )
            }
        }

        item {
            SmallTitle(text = stringResource(R.string.shortcuts_add_section))
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ArrowPreference(
                        title = stringResource(R.string.shortcuts_add_component),
                        summary = stringResource(R.string.shortcuts_add_component_summary),
                        onClick = { onAdd(ShortcutKind.COMPONENT) },
                        enabled = !isFull
                    )
                    ArrowPreference(
                        title = stringResource(R.string.shortcuts_add_intent_uri),
                        summary = stringResource(R.string.shortcuts_add_intent_uri_summary),
                        onClick = { onAdd(ShortcutKind.INTENT_URI) },
                        enabled = !isFull
                    )
                }
            }
        }

        if (isFull) {
            item {
                Text(
                    text = stringResource(R.string.shortcuts_at_limit, ShortcutStore.MAX_USER_SHORTCUTS),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

private fun buildShortcutSummary(shortcut: ShortcutAction, unsetUri: String, toolboxLabel: String) = when (shortcut.kind) {
    ShortcutKind.COMPONENT, ShortcutKind.ACTIVITY -> {
        val pkg = shortcut.packageName ?: "?"
        val act = shortcut.activityName ?: "?"
        "$pkg/$act"
    }
    ShortcutKind.INTENT_URI -> shortcut.intentUri ?: unsetUri
    ShortcutKind.TOOLBOX -> toolboxLabel
    ShortcutKind.SERVICE -> {
        val pkg = shortcut.packageName ?: "?"
        val svc = shortcut.serviceName ?: "?"
        "$pkg/$svc"
    }
}

@Composable
private fun ShortcutEditPage(
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

data class ComponentInfo(
    val packageName: String,
    val className: String,
    val label: String,
    val appLabel: String,
    val exported: Boolean,
    val isService: Boolean
)

private data class AppInfo(
    val packageName: String,
    val appLabel: String,
    val components: List<ComponentInfo>
)

@Composable
private fun ActivityPickerPage(
    onSelected: (packageName: String, activityName: String, label: String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val apps by produceState<List<AppInfo>>(
        initialValue = emptyList(),
        key1 = context.applicationContext
    ) {
        value = withContext(Dispatchers.IO) {
            loadAppsByPackage(context)
        }
    }

    var selectedPackage by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val activity = LocalContext.current as? ComponentActivity

    // 选择器内部返回栈：ActivityList → AppList → 关闭选择器（回到编辑页）。
    // 该回调在 ShortcutSettingsPage 的回调之后注册，优先级更高，
    // 因此选择器显示期间由它拦截返回。
    DisposableEffect(activity, selectedPackage) {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selectedPackage != null) {
                    selectedPackage = null
                } else {
                    onBack()
                }
            }
        }
        activity?.onBackPressedDispatcher?.addCallback(callback)
        onDispose { callback.remove() }
    }

    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) apps
        else apps.filter {
            it.appLabel.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    when (selectedPackage) {
        null -> AppList(
            apps = filteredApps,
            searchQuery = searchQuery,
            onSearchChange = { searchQuery = it },
            onSelectApp = { selectedPackage = it },
            onBack = onBack
        )
        else -> {
            val app = apps.find { it.packageName == selectedPackage }
            if (app != null) {
                ActivityList(
                    app = app,
                    onSelected = onSelected,
                    onBack = { selectedPackage = null }
                )
            }
        }
    }
}

@Composable
private fun AppList(
    apps: List<AppInfo>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onSelectApp: (String) -> Unit,
    onBack: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            BasicComponent(
                title = stringResource(R.string.activity_picker_title),
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
                TextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    label = stringResource(R.string.activity_picker_search),
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        if (apps.isEmpty() && searchQuery.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.activity_picker_loading),
                        modifier = Modifier.padding(16.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }

        item {
            Text(
                text = stringResource(R.string.activity_picker_count, apps.size),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        items(apps, key = { it.packageName }) { app ->
            ArrowPreference(
                title = app.appLabel,
                summary = stringResource(R.string.shortcut_component_count, app.packageName, app.components.size),
                startAction = { SettingsAppIcon(packageName = app.packageName, appName = app.appLabel, size = 28f) },
                onClick = { onSelectApp(app.packageName) }
            )
        }
    }
}

@Composable
private fun ActivityList(
    app: AppInfo,
    onSelected: (packageName: String, activityName: String, label: String) -> Unit,
    onBack: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            BasicComponent(
                title = app.appLabel,
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
            Text(
                text = stringResource(
                    R.string.shortcut_components_info,
                    app.components.size,
                    app.components.count { !it.exported }
                ),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        items(app.components, key = { it.className + (if (it.isService) "#s" else "#a") }) { info ->
            val typeTag = if (info.isService) "[S] " else ""
            val lockTag = if (!info.exported) " 🔒" else ""
            ArrowPreference(
                title = typeTag + info.label.ifEmpty { info.className.substringAfterLast('.') } + lockTag,
                summary = info.className,
                onClick = { onSelected(info.packageName, info.className, info.label) }
            )
        }
    }
}

private fun loadAppsByPackage(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val apps = mutableMapOf<String, MutableList<ComponentInfo>>()
    val labels = mutableMapOf<String, String>()

    try {
        val packages = pm.getInstalledPackages(0)
        for (pkg in packages) {
            val pkgName = pkg.packageName ?: continue
            val pkgInfo = runCatching {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkgName, PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES)
            }.getOrNull() ?: continue

            val components = mutableListOf<ComponentInfo>()

            // Activities（含非导出）
            pkgInfo.activities?.forEach { ai ->
                components.add(ComponentInfo(
                    packageName = pkgName,
                    className = ai.name,
                    label = runCatching { ai.loadLabel(pm).toString() }.getOrNull() ?: "",
                    appLabel = "",
                    exported = ai.exported,
                    isService = false
                ))
            }

            // Services（含非导出）
            pkgInfo.services?.forEach { si ->
                components.add(ComponentInfo(
                    packageName = pkgName,
                    className = si.name,
                    label = runCatching { si.loadLabel(pm).toString() }.getOrNull() ?: "",
                    appLabel = "",
                    exported = si.exported,
                    isService = true
                ))
            }

            if (components.isNotEmpty()) {
                labels[pkgName] = pkgInfo.applicationInfo?.let {
                    pm.getApplicationLabel(it).toString()
                } ?: pkgName
                apps[pkgName] = components
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "loadAppsByPackage failed", e)
    }

    return apps.map { (pkgName, components) ->
        AppInfo(
            packageName = pkgName,
            appLabel = labels[pkgName] ?: pkgName,
            components = components.sortedWith(compareBy({ !it.exported }, { it.isService }, { it.label.ifEmpty { it.className } }))
        )
    }.sortedBy { it.appLabel.lowercase(Locale.ROOT) }
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

/**
 * 设置页专用的应用图标组件。
 * 经 IconLoader 异步加载（LruCache + IO 线程），不在组合期做 PackageManager IPC。
 */
@Composable
private fun SettingsAppIcon(
    packageName: String,
    appName: String,
    size: Float
) {
    val context = LocalContext.current
    var bitmap by remember(packageName) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(packageName) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(width = 128, height = 128)
            }.getOrNull()
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            painter = BitmapPainter(bmp.asImageBitmap()),
            contentDescription = appName,
            modifier = Modifier.size(size.dp)
        )
    } else {
        FallbackSettingsIcon(appName, size)
    }
}

@Composable
private fun FallbackSettingsIcon(appName: String, size: Float) {
    val text = appName.take(1).ifEmpty { "?" }
    val colors = listOf(0xFF5C6BC0, 0xFF26A69A, 0xFFEF5350, 0xFFFF7043, 0xFFAB47BC)
    val colorIndex = remember(appName) { (appName.hashCode() and 0x7FFFFFFF) % colors.size }
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color(colors[colorIndex])),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MiuixTheme.textStyles.body1
        )
    }
}
