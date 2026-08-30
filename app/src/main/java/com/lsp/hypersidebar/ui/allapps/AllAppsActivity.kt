package com.lsp.hypersidebar.ui.allapps

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.theme.HyperSidebarTheme
import com.lsp.hypersidebar.theme.ThemeModes
import com.lsp.hypersidebar.ui.fan.ACTION_FAN_LAUNCH
import com.lsp.hypersidebar.util.AppIconCache
import com.lsp.hypersidebar.util.AppMetaCache
import com.lsp.hypersidebar.util.DataLoader
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

private const val TAG = "AllAppsActivity"
private const val PREFS_NAME = "hyperSidebar_prefs"
private const val MAX_DATA_WAIT_MS = 1500L

/** 磁贴圆角提为常量：避免每磁贴每次重组重复分配 Shape。 */
private val TILE_SHAPE = RoundedCornerShape(14.dp)

/**
 * 全部应用面板（PRD §7.3.2，样式参照 assets/image/全部应用.png 的抽屉网格）：
 * freeform 小窗内上下两分区——上=已添加（固定应用）图标磁贴网格，下=全部可打开应用
 * 按字母分组的磁贴网格 + 右侧字母索引条；磁贴=图标在上、应用名在下。
 * 点击目标 → 经 :ui 中继以小窗启动（模块进程被 hidden API blocklist 拒绝，
 * getActivityOptions/getFreeformSuggestionList 均不可用）→ 面板自行关闭。
 */
class AllAppsActivity : ComponentActivity() {

    companion object {
        /** :ui（system uid）启动时经 intent 传入的准入应用列表——模块进程被 hidden API
         *  blocklist 拒绝（getFreeformSuggestionList denied），自取数据不可行。 */
        const val EXTRA_SUGGESTIONS = "suggestions"

        /** 跨实例缓存：XposedServiceHelper 全局只绑定一次，第二个 Activity 实例注册的
         *  监听器可能不再收到回调——静态持有 remotePrefs 供后续实例直接可用
         *  （修"后续打开看不到固定应用"：fallback 本地 prefs 是空壳）。 */
        @Volatile
        var cachedRemotePrefs: SharedPreferences? = null
    }

    private var remotePrefs by mutableStateOf<SharedPreferences?>(null)
    private lateinit var fallbackPrefs: SharedPreferences
    private var suggestions by mutableStateOf<List<String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fallbackPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        remotePrefs = cachedRemotePrefs
        suggestions = intent.getStringArrayListExtra(EXTRA_SUGGESTIONS)
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                Thread {
                    runCatching { service.getRemotePreferences("hyperSidebar") }
                        .onSuccess { prefs ->
                            cachedRemotePrefs = prefs
                            runOnUiThread { remotePrefs = prefs }
                        }
                }.start()
            }

            override fun onServiceDied(service: XposedService) {
                remotePrefs = null
            }
        })

        setContent {
            HyperSidebarTheme(colorMode = currentThemeMode()) {
                AllAppsScreen(
                    prefs = remotePrefs ?: fallbackPrefs,
                    initialSuggestions = suggestions,
                    onLaunch = { pkg ->
                        // 打开目标一律经 :ui 中继（system uid 才能算小窗 options 并
                        // startActivityAsUser）；本进程直启必降级全屏（blocklist）
                        runCatching {
                            Intent(ACTION_FAN_LAUNCH).apply {
                                setPackage("com.miui.securitycenter")
                                putExtra("pkg", pkg)
                            }.let { applicationContext.sendBroadcast(it) }
                        }.onFailure {
                            Toast.makeText(this, "启动失败：$pkg", Toast.LENGTH_SHORT).show()
                        }
                        // PRD：从"全部应用"打开目标小窗后，列表小窗自动关闭
                        finish()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask 重入（上次未关闭）：刷新传入的建议列表
        suggestions = intent.getStringArrayListExtra(EXTRA_SUGGESTIONS)
    }

    private fun currentThemeMode(): String =
        (remotePrefs ?: fallbackPrefs).getString(PrefKeys.THEME_MODE, ThemeModes.MONET_SYSTEM)
            ?: ThemeModes.MONET_SYSTEM
}

/** 字母索引分组（A-Z + #）；中文经 ICU Han-Latin 转写取首字母，失败落 #。 */
private val transliterator by lazy {
    runCatching { android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII") }.getOrNull()
}

private fun letterFor(label: String): String {
    val c = label.trim().firstOrNull() ?: return "#"
    if (c in 'A'..'Z') return c.toString()
    if (c in 'a'..'z') return c.uppercase()
    val latin = transliterator?.transliterate(c.toString())?.trim()?.uppercase(Locale.ROOT)
    val first = latin?.firstOrNull()
    return if (first in 'A'..'Z') first.toString() else "#"
}

private data class LetterGroup(val letter: String, val items: List<Pair<String, String>>) // pkg to label

private sealed class GridEntry {
    abstract val key: String
    data class Header(val text: String) : GridEntry() {
        override val key: String = "h:$text"
    }
    /** section：同 pkg 可能在"已添加"与字母组重复出现，key 带分区前缀防撞（LazyGrid key 必须唯一） */
    data class App(val pkg: String, val label: String, val section: String) : GridEntry() {
        override val key: String = "$section:$pkg"
    }
}

@Composable
private fun AllAppsScreen(
    prefs: SharedPreferences,
    initialSuggestions: List<String>?,
    onLaunch: (String) -> Unit
) {
    val context = LocalContext.current
    var allPkgs by remember { mutableStateOf<List<String>>(emptyList()) }
    var entries by remember { mutableStateOf<List<GridEntry>>(emptyList()) }
    var hasFixedApps by remember { mutableStateOf(false) }

    // 全部应用：优先用 ：ui 经 intent 传入的列表（首帧可显）；extras 缺失（异常路径）
    // 才退回进程内 DataLoader——模块进程被 hidden API blocklist 拒绝，大概率空结果
    LaunchedEffect(initialSuggestions) {
        if (!initialSuggestions.isNullOrEmpty()) {
            allPkgs = initialSuggestions
        } else {
            var list = DataLoader.loadApps(context)
            val deadline = System.currentTimeMillis() + MAX_DATA_WAIT_MS
            while (list.isEmpty() && System.currentTimeMillis() < deadline) {
                delay(250)
                list = DataLoader.loadApps(context)
            }
            allPkgs = list
        }
    }

    // 数据组装单次 IO 化（1C P1）：label 的 PM binder 与 buildGroups 的逐条 ICU 转写
    // 全部离开主线程（此前 label 回填帧在主线程整段分组，219 项转写卡一帧），
    // 主线程只收最终 entries
    LaunchedEffect(prefs, allPkgs) {
        val pkgs = allPkgs
        val fixed = runCatching {
            prefs.getStringSet(PrefKeys.CUSTOM_APPS, emptySet()).orEmpty()
        }.getOrDefault(emptySet())
        if (pkgs.isEmpty() && fixed.isEmpty()) {
            hasFixedApps = false
            entries = emptyList()
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) {
            val labels = (pkgs + fixed).distinct().associateWith { AppMetaCache.label(context, it) }
            val fixedSorted = fixed.map { it to (labels[it] ?: it) }
                .sortedBy { it.second.lowercase() }
            buildEntries(pkgs, fixedSorted, labels)
        }
        hasFixedApps = fixed.isNotEmpty()
        entries = result
        // 图标全量预灌（后台单线程顺序）：快滑时新磁贴基本同帧命中缓存，消除
        // 逐磁贴 miss 的解码风暴与重组洪水；未及覆盖的格子由 AppTile miss 路径兜底
        AppIconCache.preload(context, pkgs + fixed)
    }

    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // 字母 → 该组 header 在网格条目中的下标
    val letterOffsets = remember(entries) {
        entries.mapIndexedNotNull { idx, e ->
            (e as? GridEntry.Header)?.let { if (it.text != "已添加") it.text to idx else null }
        }.toMap()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
    ) {
        Text(
            "全部应用",
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (entries.isEmpty()) {
                // 空态显式占位：避免被误读为黑屏（remote prefs 异步绑定与数据等待期间的过渡态）
                Text(
                    if (!hasFixedApps) "加载中…" else "暂无更多可打开应用",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                state = gridState,
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(
                    entries,
                    // 稳定 key（此前用索引 key：列表位移全量重组、无法复用）
                    key = { _, entry -> entry.key },
                    // 类型提示：header/磁贴各自复用组合槽，减少快滑时的组合成本
                    contentType = { _, entry -> if (entry is GridEntry.Header) "header" else "app" },
                    span = { _, entry ->
                        if (entry is GridEntry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                    }
                ) { _, entry ->
                    when (entry) {
                        is GridEntry.Header -> Text(
                            entry.text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier
                                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 6.dp)
                        )
                        is GridEntry.App -> AppTile(entry.pkg, entry.label, entry.section) {
                            onLaunch(entry.pkg)
                        }
                    }
                }
            }
            // 字母索引条：贴右缘，点按跳组
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(28.dp)
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                letterOffsets.forEach { (letter, idx) ->
                    Text(
                        letter,
                        fontSize = 10.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .padding(vertical = 1.dp)
                            .clickable { scope.launch { gridState.scrollToItem(idx) } }
                    )
                }
            }
        }
    }
}

/** 抽屉磁贴：图标在上、应用名在下（PRD 参考图样式）。 */
@Composable
private fun AppTile(pkg: String, label: String, section: String, onClick: () -> Unit) {
    val context = LocalContext.current
    // 图标异步加载（1C P1，滑动卡顿主因修复）：此前 remember(pkg) 在主线程组合期
    // 同步做 PM binder + 128px 解码，LazyGrid 滑出即弃、回滑重拉。改为：缓存命中
    // 同帧即显；miss 先显字母占位，IO 线程加载后提交——主线程组合路径零 binder
    var bitmap by remember(section, pkg) { mutableStateOf(AppIconCache.peek(pkg)) }
    LaunchedEffect(section, pkg) {
        if (bitmap == null) {
            val loaded = withContext(Dispatchers.IO) { AppIconCache.load(context, pkg) }
            if (loaded != null) bitmap = loaded
        }
    }
    // Painter 随位图记忆化：否则每次重组（含同位图）都重建 BitmapPainter/asImageBitmap
    val painter = remember(bitmap) { bitmap?.let { BitmapPainter(it.asImageBitmap()) } }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(TILE_SHAPE)
                .background(MiuixTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            // 局部捕获：delegated property 不能 smart cast
            if (painter != null) {
                Image(
                    painter = painter,
                    contentDescription = label,
                    modifier = Modifier.size(40.dp)
                )
            } else {
                Text(label.take(1), fontSize = 18.sp, color = MiuixTheme.colorScheme.onSurface)
            }
        }
        Text(
            label,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** 网格扁平条目组装（IO 线程调用）：header 占满整行，应用为磁贴。 */
private fun buildEntries(
    allPkgs: List<String>,
    fixed: List<Pair<String, String>>,
    labels: Map<String, String>
): List<GridEntry> = buildList {
    if (fixed.isNotEmpty()) {
        add(GridEntry.Header("已添加"))
        fixed.forEach { add(GridEntry.App(it.first, it.second, "fixed")) }
    }
    buildGroups(allPkgs.map { it to (labels[it] ?: it) }).forEach { g ->
        add(GridEntry.Header(g.letter))
        g.items.forEach { add(GridEntry.App(it.first, it.second, "all")) }
    }
}

private fun buildGroups(items: List<Pair<String, String>>): List<LetterGroup> {
    return items
        .groupBy { letterFor(it.second) }
        .toSortedMap(compareBy({ it == "#" }, { it }))
        .map { (letter, list) ->
            LetterGroup(
                letter,
                list.sortedWith(
                    compareBy({ letterFor(it.second) == "#" }, { it.second.lowercase(Locale.ROOT) })
                )
            )
        }
}
