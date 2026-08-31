package com.lsp.hypersidebar.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.util.Locale

private const val TAG = "ShortcutSettings"

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
internal fun ActivityPickerPage(
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
