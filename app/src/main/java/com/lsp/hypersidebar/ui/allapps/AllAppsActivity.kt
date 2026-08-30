package com.lsp.hypersidebar.ui.allapps

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.theme.HyperSidebarTheme
import com.lsp.hypersidebar.theme.ThemeModes
import com.lsp.hypersidebar.util.AppMetaCache
import com.lsp.hypersidebar.util.DataLoader
import com.lsp.hypersidebar.util.FreeformLauncher
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
 * 全部应用面板（PRD §7.3.2"全部应用面板"）：以 freeform 小窗打开的抽屉式 Activity，
 * 上分区 = 用户固定应用，下分区 = 全部可打开应用按字母排序 + 字母索引；
 * 点击目标小窗打开后自动关闭。数据源 = DataLoader（系统准入列表，无资格不展示）。
 *
 * 性能（PRD §9.3 加载 ≤1s）：列表先以包名渲染（DataLoader 缓存即时返回），label 由
 * AppMetaCache 后台线程批量回填渐进刷新——PM binder 查询绝不在首帧路径。
 * 冷缓存时 DataLoader 拉取 ~1s，轮询至多 1.5s 等待首份数据。
 */
class AllAppsActivity : ComponentActivity() {

    companion object {
        /** :ui（system uid）启动时经 intent 传入的准入应用列表——模块进程被 hidden API
         *  blocklist 拒绝（getFreeformSuggestionList denied），自取数据不可行。 */
        const val EXTRA_SUGGESTIONS = "suggestions"
    }

    private var remotePrefs by mutableStateOf<SharedPreferences?>(null)
    private lateinit var fallbackPrefs: SharedPreferences
    private var suggestions by mutableStateOf<List<String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fallbackPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        suggestions = intent.getStringArrayListExtra(EXTRA_SUGGESTIONS)
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                Thread {
                    runCatching { service.getRemotePreferences("hyperSidebar") }
                        .onSuccess { runOnUiThread { remotePrefs = it } }
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
                        if (!FreeformLauncher.launchFromApp(applicationContext, pkg)) {
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

    // 固定应用：prefs 即读即显
    LaunchedEffect(prefs) {
        fixedApps = runCatching {
            prefs.getStringSet(PrefKeys.CUSTOM_APPS, emptySet()).orEmpty().toList()
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
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 字母 → 该组在 LazyColumn 中的 item 下标（每组占 1 header + n items）
    val letterOffsets = remember(groups) {
        var acc = 0
        groups.associate { g ->
            val idx = acc
            acc += 1 + g.items.size
            g.letter to idx
        }
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

        if (fixedLabelled.isNotEmpty()) {
            Text(
                "固定应用",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                fixedLabelled.forEach { (pkg, label) ->
                    AppIconItem(pkg, label) { onLaunch(pkg) }
                }
            }
            Spacer(Modifier.size(6.dp))
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (groups.isEmpty()) {
                // 空态显式占位：避免被误读为黑屏（remote prefs 异步绑定与数据等待期间的过渡态）
                Text(
                    if (fixedApps.isEmpty()) "加载中…" else "暂无更多可打开应用",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                groups.forEach { group ->
                    item(key = "h_${group.letter}") {
                        Text(
                            group.letter,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier
                                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 4.dp)
                        )
                    }
                    items(group.items, key = { it.first }) { (pkg, label) ->
                        AppRow(pkg, label) { onLaunch(pkg) }
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
                groups.forEach { g ->
                    Text(
                        g.letter,
                        fontSize = 10.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .padding(vertical = 1.dp)
                            .clickable {
                                letterOffsets[g.letter]?.let { idx ->
                                    scope.launch { listState.scrollToItem(idx) }
                                }
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(pkg: String, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIconItem(pkg, label, onClick = onClick)
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface)
    }
}

@Composable
private fun AppIconItem(pkg: String, label: String, onClick: () -> Unit) {
    val context = LocalContext.current
    // 图标逐项 PM 查询：LazyColumn 只组合可见行，行级 remember 天然惰性；
    // 固定应用区（横向 Row）全量组合，数量受 7+4 上限约束可接受。
    // Drawable → Bitmap 转写同 QuickAppsBar.rememberIconBitmap（128px 足够 30dp 显示）
    val bitmap = remember(pkg) {
        runCatching {
            context.packageManager.getApplicationIcon(pkg).toBitmap(width = 128, height = 128)
        }.getOrNull()
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                painter = BitmapPainter(bitmap.asImageBitmap()),
                contentDescription = label,
                modifier = Modifier.size(30.dp)
            )
        } else {
            Text(label.take(1), fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
        }
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
