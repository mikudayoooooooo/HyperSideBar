package com.lsp.hypersidebar.ui.settings

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.ui.fan.AppIconImage
import com.lsp.hypersidebar.ui.fan.FanAppInfo
import com.lsp.hypersidebar.ui.fan.rememberAppIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class AppItem(
    val label: String,
    val packageName: String,
    val isSystem: Boolean
)

private sealed interface AppLoadState {
    data object Loading : AppLoadState
    data class Loaded(val apps: List<AppItem>) : AppLoadState
    data object Failed : AppLoadState
}

@Volatile
private var cachedApps: List<AppItem>? = null

@Composable
internal fun AppSelectionPage(
    prefs: SharedPreferences,
    prefsKey: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var selectedApps by remember(prefs, prefsKey) {
        mutableStateOf(prefs.getStringSet(prefsKey, emptySet()).orEmpty().toSet())
    }
    val loadState by produceState<AppLoadState>(
        initialValue = cachedApps?.let(AppLoadState::Loaded) ?: AppLoadState.Loading,
        key1 = context.applicationContext
    ) {
        val cached = cachedApps
        if (cached != null) {
            value = AppLoadState.Loaded(cached)
            return@produceState
        }
        value = runCatching {
            withContext(Dispatchers.IO) { loadInstalledApps(context.applicationContext) }
        }.fold(
            onSuccess = { apps ->
                cachedApps = apps
                AppLoadState.Loaded(apps)
            },
            onFailure = { AppLoadState.Failed }
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        SearchBar(
            inputField = {
                InputField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onSearch = { searchExpanded = false },
                    expanded = searchExpanded,
                    onExpandedChange = { searchExpanded = it },
                    label = stringResource(R.string.search_apps_hint)
                )
            },
            expanded = searchExpanded,
            onExpandedChange = { searchExpanded = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) { }

        Text(
            text = stringResource(R.string.selected_apps_count, selectedApps.size),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
        )

        when (val state = loadState) {
            AppLoadState.Loading -> LoadingApps()
            AppLoadState.Failed -> MessageState(stringResource(R.string.apps_load_failed))
            is AppLoadState.Loaded -> {
                val filteredApps = remember(searchQuery, state.apps) {
                    if (searchQuery.isBlank()) {
                        state.apps
                    } else {
                        state.apps.filter { app ->
                            app.label.contains(searchQuery, ignoreCase = true) ||
                                app.packageName.contains(searchQuery, ignoreCase = true)
                        }
                    }
                }
                AppList(
                    apps = filteredApps,
                    showGroups = searchQuery.isBlank(),
                    selectedApps = selectedApps,
                    onToggle = { packageName ->
                        selectedApps = selectedApps.toMutableSet().apply {
                            if (!remove(packageName)) add(packageName)
                        }.toSet()
                        prefs.savePref(prefsKey, selectedApps)
                    }
                )
            }
        }
    }
}

@Composable
private fun LoadingApps() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.loading_apps),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun MessageState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(24.dp)
        )
    }
}

@Composable
private fun AppList(
    apps: List<AppItem>,
    showGroups: Boolean,
    selectedApps: Set<String>,
    onToggle: (String) -> Unit
) {
    if (apps.isEmpty()) {
        MessageState(stringResource(R.string.no_apps_found))
        return
    }
    val firstSystemIndex = remember(apps) { apps.indexOfFirst { it.isSystem } }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(apps.size, key = { apps[it].packageName }) { index ->
            val app = apps[index]
            if (showGroups) {
                if (index == 0 && !app.isSystem) {
                    SmallTitle(text = stringResource(R.string.user_apps))
                } else if (index == firstSystemIndex && app.isSystem) {
                    SmallTitle(text = stringResource(R.string.system_apps))
                }
            }
            AppSelectionRow(
                app = app,
                isChecked = app.packageName in selectedApps,
                onToggle = { onToggle(app.packageName) }
            )
        }
    }
}

@Composable
private fun AppSelectionRow(
    app: AppItem,
    isChecked: Boolean,
    onToggle: () -> Unit
) {
    val context = LocalContext.current
    val appInfo = remember(app.packageName, app.label) {
        FanAppInfo(packageName = app.packageName, appName = app.label)
    }
    val (drawable, fallbackColor) = rememberAppIcon(context, appInfo)
    val colors = currentFanThemeColors()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIconImage(
            drawable = drawable,
            fallbackColor = fallbackColor,
            appName = app.label,
            size = 36f,
            colors = colors
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface
            )
            Text(
                text = app.packageName,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        Checkbox(
            state = if (isChecked) ToggleableState.On else ToggleableState.Off,
            onClick = onToggle
        )
    }
}

private fun loadInstalledApps(context: Context): List<AppItem> {
    val packageManager = context.packageManager
    val installed = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        packageManager.getInstalledApplications(0)
    }
    return installed
        .asSequence()
        .filter { it.enabled }
        .map { info ->
            AppItem(
                label = packageManager.getApplicationLabel(info).toString(),
                packageName = info.packageName,
                isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
            )
        }
        .sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
        .toList()
}
