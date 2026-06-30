package com.lsp.hypersidebar

import android.content.Intent
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
            TopAppBar(title = tabTitle(selectedTab))
        },
        bottomBar = {
            NavigationBar(mode = NavigationBarDisplayMode.IconAndText) {
                NavigationBarItem(
                    selected = selectedTab == Tab.HOME,
                    onClick = { selectedTab = Tab.HOME },
                    icon = MiuixIcons.All,
                    label = "首页"
                )
                NavigationBarItem(
                    selected = selectedTab == Tab.SETTINGS,
                    onClick = { selectedTab = Tab.SETTINGS },
                    icon = MiuixIcons.Settings,
                    label = "设置"
                )
                NavigationBarItem(
                    selected = selectedTab == Tab.ABOUT,
                    onClick = { selectedTab = Tab.ABOUT },
                    icon = MiuixIcons.Info,
                    label = "关于"
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
                            title = "选择应用",
                            onBack = { showAppSelection = false }
                        )
                        showShortcutSelection -> AppSelectionPage(
                            prefs = prefs.value,
                            savePref = ::savePref,
                            prefsKey = "shortcutApps",
                            title = "选择快捷应用",
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

private fun tabTitle(tab: Tab): String = when (tab) {
    Tab.HOME -> "hyperSidebar"
    Tab.SETTINGS -> "设置"
    Tab.ABOUT -> "关于"
}

private fun openLsposedManager(context: android.content.Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_MAIN).apply {
            setClassName("org.lsposed.manager", "org.lsposed.manager.ui.activity.MainActivity")
        })
    } catch (_: Exception) {
        Toast.makeText(context, "未找到 LSPosed Manager", Toast.LENGTH_SHORT).show()
    }
}

// ─── Home Page ──────────────────────────────────────────────────

@Composable
private fun HomePage(service: XposedService?, status: ModuleStatus) {
    val context = LocalContext.current

    val frameworkName by remember(service) {
        derivedStateOf {
            try { service?.frameworkName?.toString() } catch (_: Exception) { null } ?: "未知"
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
            SmallTitle(text = "框架信息")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ArrowPreference(title = "框架名称", summary = frameworkName, onClick = { })
                    ArrowPreference(title = "框架版本", summary = frameworkVersion, onClick = { })
                    ArrowPreference(title = "API 版本", summary = apiVersion, onClick = { })
                }
            }
        }

        item {
            SmallTitle(text = "作用域")
            Card(modifier = Modifier.fillMaxWidth()) {
                if (scopeList.isEmpty()) {
                    Text(
                        text = "等待框架连接…",
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
    val (dotColor, bgColor, statusText, hintText) = when (status) {
        ModuleStatus.ACTIVE -> Quad(
            semantic.success,
            semantic.successContainer,
            "模块已激活",
            "作用域已包含 com.miui.securitycenter"
        )
        ModuleStatus.UNKNOWN -> Quad(
            semantic.warning,
            semantic.warningContainer,
            "等待框架连接",
            "LSPosed 框架正在连接，请稍候…"
        )
        ModuleStatus.INACTIVE -> Quad(
            error,
            error.copy(alpha = 0.12f),
            "模块未激活",
            "请在 LSPosed Manager 中启用本模块并勾选作用域"
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
        item { SmallTitle(text = "基本设置") }
        item {
            SwitchPreference(
                title = "启用超级侧边栏",
                summary = if (moduleActive) "替换原生侧边栏为扇形菜单"
                else "请在首页启用模块后再调整此设置",
                checked = enabled,
                enabled = moduleActive,
                onCheckedChange = {
                    enabled = it
                    savePref("enabled", it)
                }
            )
        }

        item { SmallTitle(text = "主题") }
        item {
            ArrowPreference(
                title = "主题模式",
                summary = ThemeModes.toDisplayName(currentThemeMode),
                onClick = onNavigateToThemeSelection
            )
        }

        item { SmallTitle(text = "扇形菜单设置") }
        item {
            SettingsSliderItem(
                title = "图标大小",
                summary = "${iconSize.toInt()} dp",
                value = iconSize,
                valueRange = 32f..80f,
                onValueChange = { iconSize = it; savePref("iconSize", it) }
            )
        }
        item {
            SettingsSliderItem(
                title = "内圈半径",
                summary = "${innerRadius.toInt()} dp",
                value = innerRadius,
                valueRange = 100f..200f,
                steps = 9,
                onValueChange = { innerRadius = it; savePref("innerRadius", it) }
            )
        }
        item {
            SettingsSliderItem(
                title = "外圈最大半径",
                summary = "${outerRadiusMax.toInt()} dp",
                value = outerRadiusMax,
                valueRange = 150f..300f,
                steps = 14,
                onValueChange = { outerRadiusMax = it; savePref("outerRadiusMax", it) }
            )
        }

        item { SmallTitle(text = "数量限制") }
        item {
            SettingsSliderItem(
                title = "外圈应用数",
                summary = "${maxAppsOuter.toInt()} 个",
                value = maxAppsOuter,
                valueRange = 4f..12f,
                steps = 7,
                onValueChange = { maxAppsOuter = it; savePref("maxAppsOuter", it.toInt()) }
            )
        }
        item {
            SettingsSliderItem(
                title = "内圈应用数",
                summary = "${maxAppsInner.toInt()} 个",
                value = maxAppsInner,
                valueRange = 2f..8f,
                steps = 5,
                onValueChange = { maxAppsInner = it; savePref("maxAppsInner", it.toInt()) }
            )
        }
        item {
            SettingsSliderItem(
                title = "摇杆灵敏度",
                summary = "${activeZone.toInt()} dp（越小越灵敏）",
                value = activeZone,
                valueRange = 30f..120f,
                steps = 8,
                onValueChange = { activeZone = it; savePref("activeZone", it) }
            )
        }

        item { SmallTitle(text = "自定义应用") }
        item {
            ArrowPreference(
                title = "选择应用",
                summary = "在扇形区域显示的已安装应用",
                onClick = onNavigateToAppSelection
            )
        }

        item { SmallTitle(text = "快捷应用栏") }
        item {
            ArrowPreference(
                title = "选择快捷应用",
                summary = "在扇形上方显示的快捷启动 app",
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
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知"
        } catch (_: Exception) { "未知" }
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
                        contentDescription = "应用图标",
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MiuixTheme.colorScheme.primaryContainer)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "hyperSidebar",
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "替换原生 MIUI 侧边栏为扇形摇杆菜单",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        item {
            SmallTitle(text = "信息")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ArrowPreference(title = "版本", summary = versionName, onClick = { })
                    ArrowPreference(title = "基于", summary = "LSPosed API 101 · EzXHelper", onClick = { })
                    ArrowPreference(title = "编译 SDK", summary = sdkVersion, onClick = { })
                }
            }
        }

        item {
            SmallTitle(text = "管理")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ArrowPreference(
                        title = "模块作用域",
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
                            contentDescription = "返回"
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
                        label = "搜索应用…"
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
                title = "主题模式",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回"
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            currentThemeMode = mode
                            savePref(KEY_THEME_MODE, mode)
                            Toast.makeText(context, "重启应用后生效", Toast.LENGTH_SHORT).show()
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
                            contentDescription = "已选择",
                            tint = MiuixTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
