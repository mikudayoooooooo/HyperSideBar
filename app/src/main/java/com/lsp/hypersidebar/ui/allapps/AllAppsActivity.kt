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
import androidx.core.graphics.drawable.toBitmap
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.theme.HyperSidebarTheme
import com.lsp.hypersidebar.theme.ThemeModes
import com.lsp.hypersidebar.ui.fan.ACTION_FAN_LAUNCH
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
    data class Header(val text: String) : GridEntry()
    data class App(val pkg: String, val label: String) : GridEntry()
}

@Composable
private fun AllAppsScreen(
    prefs: SharedPreferences,
    initialSuggestions: List<String>?,
    onLaunch: (String) -> Unit
) {
    val context = LocalContext.current
    var fixedApps by remember { mutableStateOf<List<String>>(emptyList()) }
    var allPkgs by remember { mutableStateOf<List<String>>(emptyList()) }
    var labels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    // 固定应用：prefs 即读即显（StringSet 无序，按 label 排序保证显示确定）
    LaunchedEffect(prefs) {
        fixedApps = runCatching {
            prefs.getStringSet(PrefKeys.CUSTOM_APPS, emptySet()).orEmpty()
                .map { it to AppMetaCache.label(context, it) }
                .sortedBy { it.second.lowercase() }
                .map { it.first }
        }.getOrDefault(emptyList())
    }

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

    // label 批量回填：IO 线程走 AppMetaCache（miss 才有 PM binder，重复触发近乎免费），
    // 主线程只做 state 提交
    LaunchedEffect(allPkgs, fixedApps) {
        val wanted = (allPkgs + fixedApps).distinct()
        if (wanted.isEmpty()) return@LaunchedEffect
        val map = withContext(Dispatchers.IO) {
            wanted.associateWith { AppMetaCache.label(context, it) }
        }
        labels = map
    }

    val fixedLabelled = fixedApps.map { it to (labels[it] ?: it) }
    val groups = remember(allPkgs, labels) {
        buildGroups(allPkgs.map { it to (labels[it] ?: it) })
    }
    // 网格扁平条目：header 占满整行，应用为磁贴
    val entries = remember(fixedLabelled, groups) {
        buildList {
            if (fixedLabelled.isNotEmpty()) {
                add(GridEntry.Header("已添加"))
                fixedLabelled.forEach { add(GridEntry.App(it.first, it.second)) }
            }
            groups.forEach { g ->
                add(GridEntry.Header(g.letter))
                g.items.forEach { add(GridEntry.App(it.first, it.second)) }
            }
        }
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
                    if (fixedApps.isEmpty()) "加载中…" else "暂无更多可打开应用",
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
                    key = { idx, _ -> idx },
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
                        is GridEntry.App -> AppTile(entry.pkg, entry.label) { onLaunch(entry.pkg) }
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
private fun AppTile(pkg: String, label: String, onClick: () -> Unit) {
    val context = LocalContext.current
    // Drawable → Bitmap 转写同 QuickAppsBar.rememberIconBitmap（128px 足够 40dp 显示）
    val bitmap = remember(pkg) {
        runCatching {
            context.packageManager.getApplicationIcon(pkg).toBitmap(width = 128, height = 128)
        }.getOrNull()
    }
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
                .clip(RoundedCornerShape(14.dp))
                .background(MiuixTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    painter = BitmapPainter(bitmap.asImageBitmap()),
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
