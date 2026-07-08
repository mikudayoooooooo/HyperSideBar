package com.lsp.hypersidebar

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.state.ToggleableState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.All
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.lsp.hypersidebar.theme.HyperSidebarTheme
import com.lsp.hypersidebar.theme.LocalSemanticColors
import com.lsp.hypersidebar.theme.ThemeModes

private const val TAG = "MainActivity"
private const val PREFS_NAME = "hyperSidebar_prefs"
private const val KEY_THEME_MODE = "themeMode"

class MainActivity : ComponentActivity() {

    private var xposedService: XposedService? = null
    private var remotePrefs: SharedPreferences? = null
    private lateinit var fallbackPrefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fallbackPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                Log.d(TAG, "XposedService bound")
                xposedService = service
                remotePrefs = service.getRemotePreferences("hyperSidebar")
            }

            override fun onServiceDied(service: XposedService) {
                Log.d(TAG, "XposedService died")
                xposedService = null
                remotePrefs = null
            }
        })

        val initialThemeMode = fallbackPrefs.getString(KEY_THEME_MODE, ThemeModes.MONET_SYSTEM)
            ?: ThemeModes.MONET_SYSTEM

        enableEdgeToEdge()
        setContent {
            HyperSidebarTheme(colorMode = initialThemeMode) {
                MainScreen(
                    remotePrefs = { remotePrefs },
                    fallbackPrefs = { fallbackPrefs },
                    getService = { xposedService },
                )
            }
        }
    }
}

private enum class Tab { HOME, SETTINGS, ABOUT }

private enum class ModuleStatus { ACTIVE, INACTIVE, UNKNOWN }

@Composable
private fun MainScreen(
    remotePrefs: () -> SharedPreferences?,
    fallbackPrefs: () -> SharedPreferences,
    getService: () -> XposedService?,
) {
    var selectedTab by remember { mutableStateOf(Tab.HOME) }
    var showAppSelection by remember { mutableStateOf(false) }
    var showShortcutSelection by remember { mutableStateOf(false) }
    var showThemeSelection by remember { mutableStateOf(false) }
    val service = remember { derivedStateOf { getService() } }
    val prefs = remember { derivedStateOf { remotePrefs() ?: fallbackPrefs() } }

    val context = LocalContext.current
    val moduleStatus by remember(service) {
        derivedStateOf {
            val svc = service.value
            when {
                svc != null -> {
                    val scope = try { svc.scope } catch (_: Exception) { emptyList<String>() }
                    if (scope.contains("com.miui.securitycenter")) ModuleStatus.ACTIVE
                    else ModuleStatus.INACTIVE
                }
                else -> try {
                    context.packageManager.getPackageInfo("org.lsposed.manager", 0)
                    ModuleStatus.UNKNOWN
                } catch (_: Exception) {
                    ModuleStatus.INACTIVE
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun savePref(key: String, value: Any) {
        val p = prefs.value
        p.edit().apply {
            when (value) {
                is Boolean -> putBoolean(key, value)
                is Float -> putFloat(key, value)
                is Int -> putInt(key, value)
                is String -> putString(key, value)
                is Set<*> -> putStringSet(key, value as Set<String>)
            }
        }.apply()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = tabTitle(selectedTab, context))
        },
        bottomBar = {
            NavigationBar(mode = NavigationBarDisplayMode.IconAndText) {
    NavigationBarItem(
        selected = selectedTab == Tab.HOME,
        onClick = { selectedTab = Tab.HOME },
        icon = MiuixIcons.All,
        label = stringResource(R.string.tab_home)
    )
    NavigationBarItem(
        selected = selectedTab == Tab.SETTINGS,
        onClick = { selectedTab = Tab.SETTINGS },
        icon = MiuixIcons.Settings,
        label = stringResource(R.string.tab_settings)
    )
    NavigationBarItem(
        selected = selectedTab == Tab.ABOUT,
        onClick = { selectedTab = Tab.ABOUT },
        icon = MiuixIcons.Info,
        label = stringResource(R.string.tab_about)
    )
            }
        }
    ) { padding ->
        AnimatedContent(
            targetState = selectedTab,
            modifier = Modifier.padding(padding),
            transitionSpec = {
                fadeIn() togetherWith fadeOut()
            }
        ) { tab ->
            when (tab) {
                Tab.HOME -> HomePage(service.value, moduleStatus)
                Tab.SETTINGS -> {
                    when {
                        showThemeSelection -> ThemeSelectionPage(
                            prefs = prefs.value,
                            savePref = ::savePref,
                            onBack = { showThemeSelection = false }
                        )
                        showAppSelection -> AppSelectionPage(
                            prefs = prefs.value,
                            savePref = ::savePref,
                            prefsKey = "customApps",
                            title = stringResource(R.string.select_apps),
                            onBack = { showAppSelection = false }
                        )
                        showShortcutSelection -> AppSelectionPage(
                            prefs = prefs.value,
                            savePref = ::savePref,
                            prefsKey = "shortcutApps",
                            title = stringResource(R.string.select_shortcut_apps),
                            onBack = { showShortcutSelection = false }
                        )
                        else -> SettingsPage(
                            prefs = prefs.value,
                            savePref = ::savePref,
                            moduleStatus = moduleStatus,
                            onNavigateToAppSelection = { showAppSelection = true },
                            onNavigateToShortcutSelection = { showShortcutSelection = true },
                            onNavigateToThemeSelection = { showThemeSelection = true }
                        )
                    }
                }
                Tab.ABOUT -> AboutPage()
            }
        }
    }
}

private fun tabTitle(tab: Tab, context: android.content.Context): String = when (tab) {
    Tab.HOME -> context.getString(R.string.app_name)
    Tab.SETTINGS -> context.getString(R.string.tab_settings)
    Tab.ABOUT -> context.getString(R.string.tab_about)
}

private fun openLsposedManager(context: android.content.Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_MAIN).apply {
            setClassName("org.lsposed.manager", "org.lsposed.manager.ui.activity.MainActivity")
        })
    } catch (_: Exception) {
        Toast.makeText(context, context.getString(R.string.lsposed_not_found), Toast.LENGTH_SHORT).show()
    }
}

// ─── Home Page ──────────────────────────────────────────────────

@Composable
private fun HomePage(service: XposedService?, status: ModuleStatus) {
    val context = LocalContext.current

    val frameworkName by remember(service) {
        derivedStateOf {
            try { service?.frameworkName?.toString() } catch (_: Exception) { null } ?: context.getString(R.string.unknown)
        }
    }
    val frameworkVersion by remember(service) {
        derivedStateOf {
            try { service?.frameworkVersion?.toString() } catch (_: Exception) { null } ?: "--"
        }
    }
    val apiVersion by remember(service) {
        derivedStateOf {
            try { service?.apiVersion?.toString() } catch (_: Exception) { null } ?: "--"
        }
    }
    val scopeList by remember(service) {
        derivedStateOf<List<String>> {
            try { service?.scope ?: emptyList() } catch (_: Exception) { emptyList() }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { StatusCard(status, context) }

        item {
            SmallTitle(text = stringResource(R.string.framework_info))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ArrowPreference(title = stringResource(R.string.framework_name), summary = frameworkName, onClick = { })
                    ArrowPreference(title = stringResource(R.string.framework_version), summary = frameworkVersion, onClick = { })
                    ArrowPreference(title = stringResource(R.string.api_version), summary = apiVersion, onClick = { })
                }
            }
        }

        item {
            SmallTitle(text = stringResource(R.string.scope))
            Card(modifier = Modifier.fillMaxWidth()) {
                if (scopeList.isEmpty()) {
                    Text(
                        text = stringResource(R.string.waiting_for_scope),
                        modifier = Modifier.padding(16.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                } else {
                    ScopeChips(scopeList)
                }
            }
        }
    }
}

@Composable
private fun StatusCard(status: ModuleStatus, context: android.content.Context) {
    val semantic = LocalSemanticColors.current
    val error = MiuixTheme.colorScheme.error
    val scopePkg = "com.miui.securitycenter"
    val (dotColor, bgColor, statusText, hintText) = when (status) {
        ModuleStatus.ACTIVE -> Quad(
            semantic.success,
            semantic.successContainer,
            stringResource(R.string.module_active),
            stringResource(R.string.scope_contains, scopePkg)
        )
        ModuleStatus.UNKNOWN -> Quad(
            semantic.warning,
            semantic.warningContainer,
            stringResource(R.string.module_waiting),
            stringResource(R.string.waiting_hint)
        )
        ModuleStatus.INACTIVE -> Quad(
            error,
            error.copy(alpha = 0.12f),
            stringResource(R.string.module_inactive),
            stringResource(R.string.deactivation_hint)
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = if (status != ModuleStatus.ACTIVE) {
            { openLsposedManager(context) }
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = statusText, fontWeight = FontWeight.Bold)
                Text(
                    text = hintText,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScopeChips(packages: List<String>) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        packages.forEach { pkg ->
            val label = pkg.substringAfterLast('.')
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(MiuixTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = label,
                    color = MiuixTheme.colorScheme.onPrimaryContainer,
                    style = MiuixTheme.textStyles.body2
                )
            }
        }
    }
}

// ─── Settings Page ──────────────────────────────────────────────

@Composable
private fun SettingsPage(
    prefs: SharedPreferences?,
    savePref: (String, Any) -> Unit,
    moduleStatus: ModuleStatus,
    onNavigateToAppSelection: () -> Unit,
    onNavigateToShortcutSelection: () -> Unit,
    onNavigateToThemeSelection: () -> Unit
) {
    val context = LocalContext.current
    val moduleActive = moduleStatus == ModuleStatus.ACTIVE

    var enabled by remember { mutableStateOf(prefs?.getBoolean("enabled", true) ?: true) }
    var iconSize by remember { mutableFloatStateOf(prefs?.getFloat("iconSize", 48f) ?: 48f) }
    var innerRadius by remember { mutableFloatStateOf(prefs?.getFloat("innerRadius", 150f) ?: 150f) }
    var outerRadiusMax by remember { mutableFloatStateOf(prefs?.getFloat("outerRadiusMax", 200f) ?: 200f) }
    var maxAppsOuter by remember { mutableFloatStateOf((prefs?.getInt("maxAppsOuter", 8) ?: 8).toFloat()) }
    var maxAppsInner by remember { mutableFloatStateOf((prefs?.getInt("maxAppsInner", 4) ?: 4).toFloat()) }
    var activeZone by remember { mutableFloatStateOf(prefs?.getFloat("activeZone", 60f) ?: 60f) }
    var currentThemeMode by remember {
        mutableStateOf(
            prefs?.getString(KEY_THEME_MODE, ThemeModes.MONET_SYSTEM) ?: ThemeModes.MONET_SYSTEM
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item { SmallTitle(text = stringResource(R.string.basic_settings)) }
        item {
            SwitchPreference(
                title = stringResource(R.string.module_enabled),
                summary = if (moduleActive) stringResource(R.string.module_enabled_summary)
                else stringResource(R.string.enable_module_first),
                checked = enabled,
                enabled = moduleActive,
                onCheckedChange = {
                    enabled = it
                    savePref("enabled", it)
                }
            )
        }

        item { SmallTitle(text = stringResource(R.string.theme)) }
        item {
            ArrowPreference(
                title = stringResource(R.string.theme_mode),
                summary = ThemeModes.toDisplayName(currentThemeMode),
                onClick = onNavigateToThemeSelection
            )
        }

        item { SmallTitle(text = stringResource(R.string.fan_menu_settings)) }
        item {
            SettingsSliderItem(
                title = stringResource(R.string.icon_size),
                summary = stringResource(R.string.icon_size_summary, iconSize.toInt()),
                value = iconSize,
                valueRange = 32f..80f,
                onValueChange = { iconSize = it; savePref("iconSize", it) }
            )
        }
        item {
            SettingsSliderItem(
                title = stringResource(R.string.inner_radius),
                summary = stringResource(R.string.inner_radius_summary, innerRadius.toInt()),
                value = innerRadius,
                valueRange = 100f..200f,
                steps = 9,
                onValueChange = { innerRadius = it; savePref("innerRadius", it) }
            )
        }
        item {
            SettingsSliderItem(
                title = stringResource(R.string.outer_radius_max),
                summary = stringResource(R.string.outer_radius_summary, outerRadiusMax.toInt()),
                value = outerRadiusMax,
                valueRange = 150f..300f,
                steps = 14,
                onValueChange = { outerRadiusMax = it; savePref("outerRadiusMax", it) }
            )
        }

        item { SmallTitle(text = stringResource(R.string.quantity_limit)) }
        item {
            SettingsSliderItem(
                title = stringResource(R.string.outer_apps_count),
                summary = stringResource(R.string.outer_apps_summary, maxAppsOuter.toInt()),
                value = maxAppsOuter,
                valueRange = 4f..12f,
                steps = 7,
                onValueChange = { maxAppsOuter = it; savePref("maxAppsOuter", it.toInt()) }
            )
        }
        item {
            SettingsSliderItem(
                title = stringResource(R.string.inner_apps_count),
                summary = stringResource(R.string.inner_apps_summary, maxAppsInner.toInt()),
                value = maxAppsInner,
                valueRange = 2f..8f,
                steps = 5,
                onValueChange = { maxAppsInner = it; savePref("maxAppsInner", it.toInt()) }
            )
        }
        item {
            SettingsSliderItem(
                title = stringResource(R.string.stick_sensitivity),
                summary = stringResource(R.string.stick_sensitivity_summary, activeZone.toInt()),
                value = activeZone,
                valueRange = 30f..120f,
                steps = 8,
                onValueChange = { activeZone = it; savePref("activeZone", it) }
            )
        }

        item { SmallTitle(text = stringResource(R.string.custom_apps)) }
        item {
            ArrowPreference(
                title = stringResource(R.string.select_apps),
                summary = stringResource(R.string.custom_apps_summary),
                onClick = onNavigateToAppSelection
            )
        }

        item { SmallTitle(text = stringResource(R.string.quick_apps_bar)) }
        item {
            ArrowPreference(
                title = stringResource(R.string.select_shortcut_apps),
                summary = stringResource(R.string.quick_apps_bar_summary),
                onClick = onNavigateToShortcutSelection
            )
        }
    }
}

@Composable
private fun SettingsSliderItem(
    title: String,
    summary: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit
) {
    val animatedValue by animateFloatAsState(
        targetValue = value,
        animationSpec = tween(150)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title)
                Text(
                    text = summary,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
                Text(
                    text = "${value.toInt()}",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Slider(
            value = animatedValue,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ─── About Page ─────────────────────────────────────────────────

@Composable
private fun AboutPage() {
    val context = LocalContext.current
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                ?: context.getString(R.string.unknown)
        } catch (_: Exception) {
            context.getString(R.string.unknown)
        }
    }
    val sdkVersion = remember { android.os.Build.VERSION.SDK_INT.toString() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = stringResource(R.string.app_icon_content_desc),
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MiuixTheme.colorScheme.primaryContainer)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.module_description),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        item {
            SmallTitle(text = stringResource(R.string.about_info))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ArrowPreference(title = stringResource(R.string.about_version), summary = versionName, onClick = { })
                    ArrowPreference(title = stringResource(R.string.about_version_code), summary = BuildConfig.VERSION_CODE.toString(), onClick = {
                        val versionCodeStr = BuildConfig.VERSION_CODE.toString()
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText("versionCode", versionCodeStr))
                        Toast.makeText(context, context.getString(R.string.version_code_copied), Toast.LENGTH_SHORT).show()
                    })
                    ArrowPreference(title = stringResource(R.string.about_based_on), summary = stringResource(R.string.about_based_on_value, BuildConfig.XPOSED_API_VERSION, BuildConfig.EZXHELPER_VERSION), onClick = { })
                    ArrowPreference(title = stringResource(R.string.about_sdk), summary = sdkVersion, onClick = { })
                }
            }
        }

        item {
            SmallTitle(text = stringResource(R.string.about_manage))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ArrowPreference(
                        title = stringResource(R.string.about_module_scope),
                        summary = "com.miui.securitycenter",
                        onClick = { openLsposedManager(context) }
                    )
                }
            }
        }
    }
}

// ─── App Selection Page ────────────────────────────────────────

private data class AppSelectionItem(val label: String, val packageName: String)

@Composable
private fun AppSelectionPage(
    prefs: SharedPreferences?,
    savePref: (String, Any) -> Unit,
    prefsKey: String,
    title: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var selectedApps by remember { mutableStateOf(
        prefs?.getStringSet(prefsKey, emptySet()) ?: emptySet()
    ) }

    val allApps = remember {
        val pm = context.packageManager
        pm.getInstalledApplications(android.content.pm.PackageManager.ApplicationInfoFlags.of(0))
            .filter { it.enabled }
            .map { info ->
                val label = pm.getApplicationLabel(info).toString()
                AppSelectionItem(label, info.packageName)
            }
            .sortedBy { it.label.lowercase() }
    }

    val filteredApps = remember(searchQuery, allApps) {
        if (searchQuery.isBlank()) allApps
        else allApps.filter {
            it.label.contains(searchQuery, ignoreCase = true) ||
            it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = title,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
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

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filteredApps, key = { it.packageName }) { app ->
                    val isChecked = app.packageName in selectedApps
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val newSet = buildSet {
                                    addAll(selectedApps)
                                    if (isChecked) remove(app.packageName) else add(app.packageName)
                                }
                                selectedApps = newSet
                                savePref(prefsKey, newSet)
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            state = if (isChecked) ToggleableState.On else ToggleableState.Off,
                            onClick = {
                                val newSet = buildSet {
                                    addAll(selectedApps)
                                    if (isChecked) remove(app.packageName) else add(app.packageName)
                                }
                                selectedApps = newSet
                                savePref(prefsKey, newSet)
                            }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = app.label, fontWeight = FontWeight.Medium)
                            Text(
                                text = app.packageName,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Theme Selection Page ───────────────────────────────────────

@Composable
private fun ThemeSelectionPage(
    prefs: SharedPreferences?,
    savePref: (String, Any) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var currentThemeMode by remember {
        mutableStateOf(
            prefs?.getString(KEY_THEME_MODE, ThemeModes.MONET_SYSTEM) ?: ThemeModes.MONET_SYSTEM
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.theme_mode),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            items(ThemeModes.ALL, key = { it }) { mode ->
                val isSelected = mode == currentThemeMode
                val themeApplyHint = stringResource(R.string.theme_apply_hint)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            currentThemeMode = mode
                            savePref(KEY_THEME_MODE, mode)
                            Toast.makeText(context, themeApplyHint, Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = ThemeModes.toDisplayName(mode),
                        modifier = Modifier.weight(1f),
                        style = MiuixTheme.textStyles.body1
                    )
                    if (isSelected) {
                        Icon(
                            imageVector = MiuixIcons.Ok,
                            contentDescription = stringResource(R.string.ok_selected),
                            tint = MiuixTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
