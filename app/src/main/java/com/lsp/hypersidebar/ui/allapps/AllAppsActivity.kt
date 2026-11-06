package com.lsp.hypersidebar.ui.allapps

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.prefs.HostPackages
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.prefs.PrefsFiles
import com.lsp.hypersidebar.theme.HyperSidebarTheme
import com.lsp.hypersidebar.theme.ThemeModes
import com.lsp.hypersidebar.ui.fan.ACTION_FAN_LAUNCH
import com.lsp.hypersidebar.util.AppIconCache
import com.lsp.hypersidebar.util.AppMetaCache
import com.lsp.hypersidebar.util.DataLoader
import com.lsp.hypersidebar.util.IconLoadPolicy
import com.lsp.hypersidebar.util.RemotePrefsBridge
import com.lsp.hypersidebar.util.RelayToken
import com.lsp.hypersidebar.util.HLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.utils.pressable
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.util.Locale

private const val TAG = "AllAppsActivity"
private const val PREFS_NAME = PrefsFiles.APP_LOCAL

/** 磁贴圆角（A3：14→18dp，MIUI 抽屉近亲）。squircle 直接吃 Dp，无 Shape 分配。 */
private val TILE_CORNER = 18.dp

/** 磁贴与骨架屏共用的格内尺寸：两处必须同源，否则加载完成时整格回流。 */
private val TILE_CELL_HPAD = 6.dp
private val TILE_CELL_VPAD = 8.dp
private val TILE_BOX = 56.dp
private val TILE_ICON = 48.dp
private val TILE_LABEL_GAP = 4.dp

/** 字母索引条宽度：网格末列给它让位（否则最后一列压在索引条下面，点击也抢事件）。 */
private val INDEX_BAR_WIDTH = 28.dp

/** 索引气泡圆角（A2）：MIUI 抽屉同款圆角方，非整圆。 */
private val BUBBLE_CORNER = 24.dp

/**
 * 全部应用面板（PRD §7.3.2，样式参照 assets/image/全部应用.png 的抽屉网格）：
 * freeform 小窗内上下两分区——上=已添加（固定应用）图标磁贴网格，下=全部可打开应用
 * 按字母分组的磁贴网格 + 右侧字母索引条；磁贴=图标在上、应用名在下。
 * 点击目标 → 经 :ui 中继以小窗启动（模块进程被 hidden API blocklist 拒绝，
 * getActivityOptions/getFreeformSuggestionList 均不可用）→ 面板自行关闭。
 */
class AllAppsActivity : ComponentActivity() {

    companion object {
        /** :ui（特权宿主）启动时经 intent 传入的准入应用列表——模块进程被 hidden API
         *  blocklist 拒绝（getFreeformSuggestionList denied），自取数据不可行。 */
        const val EXTRA_SUGGESTIONS = "suggestions"
    }

    private var remotePrefs by mutableStateOf<SharedPreferences?>(null)
    private lateinit var fallbackPrefs: SharedPreferences
    private var suggestions by mutableStateOf<List<String>?>(null)
    /** 呼出扇形时的前台应用（source 三跳第二跳：面板内选择后随回传广播到 :ui 记录点） */
    private var frozenSource: String? = null

    // 绑定桥监听存字段（2026-10-26 审查 B3）：onDestroy 用**同一引用**注销（面板是
    // singleTask 高频重入，匿名闭包每次 onCreate 泄漏一份捕获本 Activity）
    private val bridgeListener: (SharedPreferences) -> Unit = { prefs ->
        runOnUiThread {
            remotePrefs = prefs
            Log.i(
                TAG,
                "bridge bound: customApps=" +
                    runCatching { prefs.getStringSet(PrefKeys.CUSTOM_APPS, emptySet()) }
                        .getOrNull()?.size
            )
        }
        RemotePrefsBridge.registerConfigSync(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        verifyCaller()
        fallbackPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        // D7 修复：改走进程级绑定桥。原自注册 listener 在"设置页先完成绑定后，
        // 本 Activity 二次注册收不到回调"路径下 remotePrefs 永远为 null——本地
        // 空壳 prefs 读不到 CUSTOM_APPS，固定应用消失（logcat 实证 2026-09-04）
        remotePrefs = RemotePrefsBridge.prefs
        suggestions = intent.getStringArrayListExtra(EXTRA_SUGGESTIONS)
        frozenSource = intent.getStringExtra(PrefKeys.FAN_EXTRA_SOURCE)
        RemotePrefsBridge.addListener(bridgeListener)

        setContent {
            HyperSidebarTheme(colorMode = currentThemeMode()) {
                AllAppsScreen(
                    prefs = remotePrefs ?: fallbackPrefs,
                    prefsIsFallback = remotePrefs == null,
                    initialSuggestions = suggestions,
                    onLaunch = { pkg ->
                        // 打开目标一律经 :ui 中继（特权进程才能算小窗 options 并
                        // startActivityAsUser）；本进程直启必降级全屏（blocklist）
                        runCatching {
                            Intent(ACTION_FAN_LAUNCH).apply {
                                setPackage(HostPackages.UI_HOST)
                                putExtra(PrefKeys.FAN_EXTRA_PKG, pkg)
                                // source 三跳第三跳：回传呼出时冻结的前台应用到记录点
                                frozenSource?.let { putExtra(PrefKeys.FAN_EXTRA_SOURCE, it) }
                                // 跨进程防伪令牌（:ui 侧 FreeformRelayHook 校验）
                                RelayToken.attach(this, RelayToken.current())
                            }.let { applicationContext.sendBroadcast(it) }
                        }.onFailure {
                            Toast.makeText(this, "启动失败：$pkg", Toast.LENGTH_SHORT).show()
                        }
                        // PRD：从"全部应用"打开目标小窗后，列表小窗自动关闭。
                        // finishAndRemoveTask 兜底：面板已配独立 taskAffinity（正常 finish
                        // 即任务空、窗口即收），此处确保任务无条件移除，防异常归置残留
                        finishAndRemoveTask()
                    }
                )
            }
        }
    }

    /**
     * 调用方校验（2026-10-18 体检 P1）：本 Activity 是 exported 组件（:ui 跨进程拉起它），
     * 按仓库 ACL 规则必须验调用方 —— 用与 ShortcutRelayReceiver 同款的跨进程防伪令牌
     * （`RelayToken.verifyFan` 的宽松分档语义：令牌未下发时放行，下发后严格比对）。
     *
     * 冷启动细节：模块进程刚拉起时 `RelayToken.current()` 可能还没值（令牌由 remotePrefs
     * 绑定后同步）⇒ 等桥绑定就绪（复用 `awaitPrefsBind`，3s）再用 remotePrefs 里的令牌复验一次；
     * 桥始终不可用时按宽松语义放行（与面板自身的 prefs 回落一致：桥不在也不能让面板不可用）。
     * 校验不通过 ⇒ 立即结束，且**绝不读入参**（伪造的 suggestions 列表一律不采信）。
     */
    private fun verifyCaller() {
        val got = intent.getStringExtra(PrefKeys.RELAY_LAUNCH_EXTRA_TOKEN)
        val cached = RelayToken.current()
        if (cached != null) {
            if (!RelayToken.verifyFan(intent, cached)) rejectPanel()
            return
        }
        Thread {
            RemotePrefsBridge.awaitPrefsBind(3)
            val expected = RemotePrefsBridge.prefs?.let { RelayToken.read(it) }
            if (!expected.isNullOrEmpty() &&
                !RelayToken.verifyFan(intent, expected)
            ) {
                runOnUiThread { rejectPanel() }
            }
        }.start()
    }

    private fun rejectPanel() {
        HLog.w(TAG, "panel launch rejected: bad token")
        finishAndRemoveTask()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTask 重入（上次未关闭）：同样先过令牌闸，再刷新传入的建议列表
        verifyCaller()
        suggestions = intent.getStringArrayListExtra(EXTRA_SUGGESTIONS)
    }

    override fun onDestroy() {
        // B3：注销绑定桥监听（同引用），阻断 Activity 实例经进程级 listeners 表泄漏
        RemotePrefsBridge.removeListener(bridgeListener)
        super.onDestroy()
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
    /** isFixed：固定区标题不是字母分组，不进字母索引（2026-11-03 起不再靠文案判断）。 */
    data class Header(val text: String, val isFixed: Boolean = false) : GridEntry() {
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
    prefsIsFallback: Boolean,
    initialSuggestions: List<String>?,
    onLaunch: (String) -> Unit
) {
    val context = LocalContext.current
    var allPkgs by remember { mutableStateOf<List<String>>(emptyList()) }
    var entries by remember { mutableStateOf<List<GridEntry>>(emptyList()) }
    var hasFixedApps by remember { mutableStateOf(false) }

    // ===== 加载三态（迭代六 §11.4）：加载中（骨架屏）/ 空 / 失败可重试 =====
    // Loading=数据未到（骨架占位，区别于黑屏）；Error=等待超时且无任何数据（缓存/
    // extras/固定应用全空）→ 显式失败 + 重试；Empty=数据就绪但无更多应用（有固定区
    // 时列表非空，不会落 Empty）。retryKey 变化重跑加载 effect。
    var retryKey by remember { mutableStateOf(0) }
    var loadFailed by remember { mutableStateOf(false) }
    // entries 非空即视为加载完成（组装 effect 是数据的最终出口）
    val loaded = entries.isNotEmpty() || hasFixedApps

    // 全部应用：优先用 ：ui 经 intent 传入的列表（首帧可显）。A4 冷启动 hydrate：
    // extras 缺失（:ui 冷进程缓存空，>5s 空窗来源）时先灌 remotePrefs 缓存
    // （CACHED_SUGGESTIONS，上次会话准入列表，与 AppSelectionPage 同键同格式）
    // 立即出列表，等待循环只负责新数据覆盖；extras 齐全时后台写回保鲜。
    // 模块进程被 hidden API blocklist 拒绝，DataLoader 只是最后兜底（大概率空）
    LaunchedEffect(prefs, initialSuggestions, retryKey) {
        if (!initialSuggestions.isNullOrEmpty()) {
            allPkgs = initialSuggestions
            // 后台写回保鲜：内容没变不写（AllApps 高频打开，实际写盘趋近于零）
            withContext(Dispatchers.IO) {
                val json = org.json.JSONArray(initialSuggestions).toString()
                val current = runCatching { prefs.getString(PrefKeys.CACHED_SUGGESTIONS, null) }.getOrNull()
                if (current != json) {
                    runCatching {
                        prefs.edit().putString(PrefKeys.CACHED_SUGGESTIONS, json).apply()
                    }
                }
            }
        } else {
            // 1) 先灌缓存：remotePrefs 未绑定（fallbackPrefs 空壳）时读到空，
            //    绑定完成后 LaunchedEffect(prefs) 重跑本段补灌
            val cached = runCatching {
                prefs.getString(PrefKeys.CACHED_SUGGESTIONS, null)?.let { json ->
                    val arr = org.json.JSONArray(json)
                    (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
                }
            }.getOrNull().orEmpty()
            if (cached.isNotEmpty()) {
                allPkgs = cached
            }
            // 2) 最后兜底 DataLoader（模块进程反射被 hidden API blocklist 拒绝 ⇒ 恒空）。
            //    2026-10-30：删掉原来的 1.5s、每 250ms 一次的等待循环——那条路在本进程
            //    从来没成功过，冷开白等最多 1.5s，还会让 loadFailed 三态延迟落定。
            val list = DataLoader.loadApps(context)
            if (list.isNotEmpty()) {
                allPkgs = list
                withContext(Dispatchers.IO) {
                    runCatching {
                        prefs.edit().putString(PrefKeys.CACHED_SUGGESTIONS, org.json.JSONArray(list).toString()).apply()
                    }
                }
            }
            // 3) 全部落空且无缓存：allPkgs 保持空 → 空态文案兜底
            else if (allPkgs.isEmpty()) {
                // §11.4：无任何数据（缓存/extras 全空）→ 显式失败态
                //（固定应用也空才算失败——有固定区时列表仍可用）
                loadFailed = true
            }
        }
    }

    // 数据组装单次 IO 化（1C P1）：label 的 PM binder 与 buildGroups 的逐条 ICU 转写
    // 全部离开主线程（此前 label 回填帧在主线程整段分组，219 项转写卡一帧），
    // 主线程只收最终 entries。
    // 2026-10-30：key 从 `prefs` 实例改成 `prefsIsFallback` 布尔 —— remotePrefs 绑定完成会换
    // prefs 对象（并连带 allPkgs 重设），旧 key 让本 effect 连环重启（代码原注释实测 3 次、
    // 冷组装 769ms 被顶到 1376ms）。现在绑定只触发**一次**重跑（布尔翻转），数据到达也不再重启。
    // 固定区分组标题走资源（2026-11-03）：此前是硬编码字面量，且 letterOffsets 靠
    // 这个字面量做字符串比较——改文案会静默破坏字母索引，现改为结构标记 isFixed。
    val addedHeader = stringResource(R.string.all_apps_added_header)
    LaunchedEffect(allPkgs, prefsIsFallback) {
        val pkgs = allPkgs
        val t0 = System.currentTimeMillis()
        // 主线程不再读 prefs / 碰 PM：固定区与顺序键都在 IO 里读（私有 prefs 的首次 XML 解析
        // 也是毫秒级，放主线程没有必要）
        var labels: Map<String, String> = emptyMap()
        val meta = withContext(Dispatchers.IO) {
            val fixed = runCatching {
                prefs.getStringSet(PrefKeys.CUSTOM_APPS, emptySet()).orEmpty()
            }.getOrDefault(emptySet())
            // D7 诊断：prefs 实例来源 + fixed 实读数量（绑定切换前后各一条）
            Log.i(TAG, "assemble: prefsIsFallback=$prefsIsFallback, fixed=${fixed.size}, allPkgs=${pkgs.size}")
            if (pkgs.isEmpty() && fixed.isEmpty()) return@withContext null
            // 固定区按用户拖动排序展示（§2.4），无序键时按 label 字母序兜底
            val fixedOrder = runCatching {
                prefs.getString(PrefKeys.CUSTOM_APPS_ORDER, null)?.let { s ->
                    val arr = org.json.JSONArray(s)
                    (0 until arr.length()).map { arr.optString(it) }
                }
            }.getOrNull() ?: emptyList()
            // 冷启动盘灌：上次会话的 label 快照先顶住，miss 才回 PM（实测冷组装 769ms 的主构成）
            AppMetaCache.warmFromDisk(context)
            // 分区首字母也走盘缓存：没有缓存时才跑 ICU（262 个标签 ~1.2s）
            SectionLetterCache.warm(context)
            val knownLetters = SectionLetterCache.snapshot()
            // ===== 第一段：只用缓存/盘灌 label（零 binder）先出一版 =====
            // 旧实现等全量 PM label 回来才发布 entries，骨架屏因此要挡到整表组装完。
            val peeked = (pkgs + fixed).distinct().associateWith { AppMetaCache.peek(it) ?: it }
            val built = buildEntries(pkgs, sortFixed(fixed, fixedOrder, peeked), peeked, addedHeader, knownLetters)
            SectionLetterCache.persist(context, built.newLetters)
            if (built.newLetters.isNotEmpty()) {
                Log.w(TAG, "section letters computed: ${built.newLetters.size} (known=${knownLetters.size})")
            }
            Triple(fixed, fixedOrder, built.entries)
        }
        if (meta == null) {
            hasFixedApps = false
            entries = emptyList()
            return@LaunchedEffect
        }
        val (fixed, fixedOrder, stage1) = meta
        hasFixedApps = fixed.isNotEmpty()
        entries = stage1
        val cost1 = System.currentTimeMillis() - t0
        Log.i(TAG, "assemble stage1: entries=${stage1.size} cost=${cost1}ms")
        if (cost1 >= 500) Log.w(TAG, "assemble stage1 SLOW: entries=${stage1.size} cost=${cost1}ms")
        // ===== 第二段：全量 label（PM 兜底）覆盖发布 =====
        val result = withContext(Dispatchers.IO) {
            // 绑定/数据到达窗口内本 effect 会连环重启（实测 3 次）：被取消的跑次逐包
            // ensureActive 立即终止，避免多份冷 PM label 循环并发互抢（769ms 被顶到 1376ms）
            val targets = (pkgs + fixed).distinct()
            // 一次 getInstalledApplications 批量取标签，替代 219 次逐包 PM binder（2026-11-05）
            AppMetaCache.warmAll(context, targets)
            val fetched = mutableMapOf<String, String>()
            for (pkg in targets) {
                ensureActive()
                fetched[pkg] = AppMetaCache.label(context, pkg)
            }
            labels = fetched
            val knownLetters = SectionLetterCache.snapshot()
            val built = buildEntries(pkgs, sortFixed(fixed, fixedOrder, fetched), fetched, addedHeader, knownLetters)
            SectionLetterCache.persist(context, built.newLetters)
            if (built.newLetters.isNotEmpty()) {
                Log.w(TAG, "section letters computed: ${built.newLetters.size} (known=${knownLetters.size})")
            }
            built
        }
        entries = result.entries
        val cost2 = System.currentTimeMillis() - t0
        Log.i(TAG, "assemble done: entries=${result.entries.size} cost=${cost2}ms")
        if (cost2 >= 800) Log.w(TAG, "assemble done SLOW: entries=${result.entries.size} cost=${cost2}ms")
        // 本地镜像（IO）：CACHED_SUGGESTIONS/CUSTOM_APPS/CUSTOM_APPS_ORDER 写入模块本地
        // prefs——冷进程 remotePrefs 未绑定时读端（remotePrefs ?: fallbackPrefs）可直接
        // 出列表，绑定完成后 remotePrefs 为权威、镜像只作快速路径预览（旧值短暂展示可接受）。
        // 没变化不写（AllApps 高频打开）
        withContext(Dispatchers.IO) {
            runCatching {
                val p = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                val editor = p.edit()
                var dirty = false
                val sugJson = org.json.JSONArray(pkgs).toString()
                if (pkgs.isNotEmpty() && p.getString(PrefKeys.CACHED_SUGGESTIONS, null) != sugJson) {
                    editor.putString(PrefKeys.CACHED_SUGGESTIONS, sugJson); dirty = true
                }
                if (p.getStringSet(PrefKeys.CUSTOM_APPS, null) != fixed) {
                    editor.putStringSet(PrefKeys.CUSTOM_APPS, fixed); dirty = true
                }
                val orderJson = org.json.JSONArray(fixedOrder).toString()
                if (p.getString(PrefKeys.CUSTOM_APPS_ORDER, null) != orderJson) {
                    editor.putString(PrefKeys.CUSTOM_APPS_ORDER, orderJson); dirty = true
                }
                if (dirty) editor.apply()
            }
            AppMetaCache.persistToDisk(context, labels)
        }
    }

    val gridState = rememberLazyGridState()

    // 首屏图标预灌（2026-11-05）：只灌 entries 前 N 个应用磁贴（固定区自然排在最前），
    // 其余由磁贴按需加载，避免 219 个预灌任务与滚动抢 CPU/IO。
    LaunchedEffect(entries) {
        if (entries.isEmpty()) return@LaunchedEffect
        val first = entries.asSequence()
            .filterIsInstance<GridEntry.App>()
            .take(IconLoadPolicy.FIRST_SCREEN_MAX)
            .map { it.pkg }
            .toList()
        AppIconCache.preload(context, first)
    }

    Scaffold(
        topBar = {
            // freeform 小窗纵向空间有限：用小标题形态（不含 largeTitle 占位）。
            // 此前用 TopAppBar(largeTitle = "") 仍在顶部留出大标题高度——顶部空白根因
            SmallTopAppBar(
                title = stringResource(R.string.all_apps_title)
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (entries.isEmpty()) {
                // §11.4 加载三态：失败（重试）/ 加载中（骨架屏）/ 空（明确文案），
                // 取代旧「加载中…/暂无」二值文案——加载期不再被误读为黑屏或空数据
                when {
                    loadFailed && !loaded -> Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            stringResource(R.string.all_apps_load_failed),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        Spacer(Modifier.padding(top = 12.dp))
                        TextButton(
                            text = stringResource(R.string.all_apps_retry),
                            onClick = {
                                loadFailed = false
                                retryKey++
                            }
                        )
                    }
                    !loaded -> SkeletonGrid(modifier = Modifier.align(Alignment.Center).fillMaxSize())
                    !hasFixedApps -> Text(
                        stringResource(R.string.no_apps_found),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                state = gridState,
                contentPadding = PaddingValues(end = INDEX_BAR_WIDTH),
                modifier = Modifier
                    .fillMaxSize()
                    .scrollEndHaptic()
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
                        // MIUI 抽屉的字母 header 就是一行小灰字：无通栏色带、无加粗
                        is GridEntry.Header -> Text(
                            entry.text,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 6.dp)
                        )
                        is GridEntry.App -> AppTile(entry.pkg, entry.label, entry.section) {
                            onLaunch(entry.pkg)
                        }
                    }
                }
            }
            AllAppsLetterBar(gridState = gridState, entries = entries)
        }
    }
}

/** 索引条 y → 字母（纯函数，均匀槽位；与视觉布局同源）。 */
internal fun letterAt(y: Float, barHeightPx: Float, letters: List<String>): String? {
    if (barHeightPx <= 0f || letters.isEmpty()) return null
    val index = ((y / barHeightPx) * letters.size).toInt().coerceIn(0, letters.lastIndex)
    return letters.getOrNull(index)
}

/**
 * 字母索引条 + 中央气泡（2026-11-05 重组作用域隔离）：
 * activeLetter/currentLetter 只在本 composable 内读取，跨字母/拖动不再重组整个网格。
 */
@Composable
private fun AllAppsLetterBar(
    gridState: LazyGridState,
    entries: List<GridEntry>
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val letterOffsets = remember(entries) {
        entries.mapIndexedNotNull { idx, entry ->
            (entry as? GridEntry.Header)?.let { if (!it.isFixed) it.text to idx else null }
        }.toMap()
    }
    val letters = remember(letterOffsets) { letterOffsets.keys.toList() }

    var activeLetter by remember { mutableStateOf<String?>(null) }
    var dragActive by remember { mutableStateOf(false) }
    var barHeightPx by remember { mutableStateOf(0f) }
    var jumpJob by remember { mutableStateOf<Job?>(null) }

    // 跨字母震动：structuralEqualityPolicy 保证同字母重复赋值不触发 effect 重启。
    LaunchedEffect(activeLetter) {
        if (activeLetter != null) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            delay(600)
            if (!dragActive) activeLetter = null
        }
    }

    // 当前组高亮：firstVisibleItemIndex 反查最近的字母 header。
    // derivedStateOf 只在跨组时产生新值；读取点已经下沉到本 composable，不再驱动网格重组。
    val currentLetter by remember(letterOffsets) {
        derivedStateOf {
            val first = gridState.firstVisibleItemIndex
            var best: String? = null
            var bestIdx = -1
            letterOffsets.forEach { (letter, idx) ->
                if (idx <= first && idx > bestIdx) { best = letter; bestIdx = idx }
            }
            best
        }
    }

    fun jumpTo(letter: String?) {
        // 拖动事件高频：同字母直接返回；不同字母也只保留一个在途 scrollToItem。
        if (letter == null || letter == activeLetter) return
        val idx = letterOffsets[letter] ?: return
        activeLetter = letter
        jumpJob?.cancel()
        jumpJob = scope.launch { gridState.scrollToItem(idx) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(INDEX_BAR_WIDTH)
                .padding(vertical = 8.dp)
                .onSizeChanged { barHeightPx = it.height.toFloat() }
                .pointerInput(letterOffsets) {
                    detectTapGestures { offset -> jumpTo(letterAt(offset.y, barHeightPx, letters)) }
                }
                .pointerInput(letterOffsets) {
                    detectVerticalDragGestures(
                        onDragStart = { dragActive = true },
                        onDragEnd = { dragActive = false; activeLetter = null },
                        onDragCancel = { dragActive = false; activeLetter = null },
                        onVerticalDrag = { change, _ ->
                            dragActive = true
                            jumpTo(letterAt(change.position.y, barHeightPx, letters))
                        }
                    )
                },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 每字母 weight(1f) 均分条高：与 letterAt 的均匀映射同源。
            letters.forEach { letter ->
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        letter,
                        style = MiuixTheme.textStyles.footnote2,
                        // 优先级：拖动/点按中的字母 > 当前组字母 > 普通态
                        color = if (letter == (activeLetter ?: currentLetter)) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        }
                    )
                }
            }
        }
        // 中央大字气泡：拖动/点按中实时跟随，松手即收。
        AnimatedVisibility(
            visible = activeLetter != null,
            enter = fadeIn() + scaleIn(initialScale = 0.6f),
            exit = scaleOut(targetScale = 0.6f) + fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .squircleSurface(
                        color = MiuixTheme.colorScheme.primary,
                        cornerRadius = BUBBLE_CORNER
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    activeLetter.orEmpty(),
                    style = MiuixTheme.textStyles.title1,
                    color = MiuixTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

/** 抽屉磁贴：图标在上、应用名在下（PRD 参考图样式）。 */
/**
 * 骨架屏（§11.4 加载态）：与网格同构的 4 列占位磁贴，呼吸透明度脉动——
 * 明确表达"数据在路上"，区别于空数据与黑屏。
 */
@Composable
private fun SkeletonGrid(modifier: Modifier = Modifier) {
    val alpha = rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.25f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeletonAlpha"
    )
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        userScrollEnabled = false,
        contentPadding = PaddingValues(end = INDEX_BAR_WIDTH),
        modifier = modifier
    ) {
        items(12) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = TILE_CELL_HPAD, vertical = TILE_CELL_VPAD)
            ) {
                // 与 AppTile 同构：56dp 盒 + 48dp 图形（行高只由盒与标签行决定）
                Box(modifier = Modifier.size(TILE_BOX), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(TILE_ICON)
                            .alpha(alpha.value)
                            .clip(RoundedCornerShape(TILE_CORNER))
                            .background(MiuixTheme.colorScheme.secondaryContainer)
                    )
                }
                // 透明文字占住真实标签的行高（footnote2），上面叠示意条：
                // 数据落地时行高不变，网格不回流
                Box(
                    modifier = Modifier.padding(top = TILE_LABEL_GAP),
                    contentAlignment = Alignment.Center
                ) {
                    Text(" ", style = MiuixTheme.textStyles.footnote2, maxLines = 1)
                    Box(
                        Modifier
                            .width(40.dp)
                            .height(10.dp)
                            .alpha(alpha.value)
                            .clip(RoundedCornerShape(5.dp))
                            .background(MiuixTheme.colorScheme.secondaryContainer)
                    )
                }
            }
        }
    }
}

@Composable
private fun AppTile(pkg: String, label: String, section: String, onClick: () -> Unit) {    val context = LocalContext.current
    // 图标异步加载（1C P1，滑动卡顿主因修复）：此前 remember(pkg) 在主线程组合期
    // 同步做 PM binder + 128px 解码，LazyGrid 滑出即弃、回滑重拉。改为：缓存命中
    // 同帧即显；miss 先显字母占位，IO 线程加载后提交——主线程组合路径零 binder
    var bitmap by remember(section, pkg) { mutableStateOf(AppIconCache.peek(pkg)) }
    LaunchedEffect(section, pkg) {
        if (bitmap == null) {
            val loaded = AppIconCache.load(context, pkg)
            if (loaded != null) bitmap = loaded
        }
    }
    // Painter 随位图记忆化：否则每次重组（含同位图）都重建 BitmapPainter/asImageBitmap
    val painter = remember(bitmap) { bitmap?.let { BitmapPainter(it.asImageBitmap()) } }
    // A3 按压反馈：miuix pressable + SinkFeedback 下沉反馈（无 ripple，贴合 MIUI 磁贴观感）。
    // 2026-11-01 试过把两个指针 modifier 合成一个 `clickable(indication=SinkFeedback())`：
    // 冷启无可测收益（872ms vs 862ms，噪声内），而按压下沉明显变弱 ⇒ 回滚，保留原写法。
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .pressable(
                interactionSource = interactionSource,
                indication = SinkFeedback()
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = TILE_CELL_HPAD, vertical = TILE_CELL_VPAD)
    ) {
        // A3 去托底：正常态图标 48dp 直接展示（MIUI 抽屉样式，图标自带形状边界）；
        // miss 占位态保留托底 + 首字母。外层恒定 56dp 盒，防图标加载完成后行高跳变
        Box(modifier = Modifier.size(TILE_BOX), contentAlignment = Alignment.Center) {
            if (painter != null) {
                Image(
                    painter = painter,
                    contentDescription = label,
                    modifier = Modifier.size(TILE_ICON)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(TILE_CORNER))
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label.take(1), style = MiuixTheme.textStyles.title4, color = MiuixTheme.colorScheme.onSurface)
                }
            }
        }
        Text(
            label,
            style = MiuixTheme.textStyles.footnote2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = TILE_LABEL_GAP)
        )
    }
}

/** 网格扁平条目组装（IO 线程调用）：header 占满整行，应用为磁贴。 */
private data class BuiltEntries(
    val entries: List<GridEntry>,
    /** 本次新算出的 label→首字母（供 [SectionLetterCache] 落盘）。 */
    val newLetters: Map<String, String>
)

private fun buildEntries(
    allPkgs: List<String>,
    fixed: List<Pair<String, String>>,
    labels: Map<String, String>,
    fixedHeader: String,
    knownLetters: Map<String, String>
): BuiltEntries {
    val (groups, newLetters) = buildGroups(
        allPkgs.map { it to (labels[it] ?: it) },
        knownLetters
    )
    val entries = buildList {
        if (fixed.isNotEmpty()) {
            add(GridEntry.Header(fixedHeader, isFixed = true))
            fixed.forEach { add(GridEntry.App(it.first, it.second, "fixed")) }
        }
        groups.forEach { g ->
            add(GridEntry.Header(g.letter))
            g.items.forEach { add(GridEntry.App(it.first, it.second, "all")) }
        }
    }
    return BuiltEntries(entries, newLetters)
}

/** 固定区按用户拖动顺序排序，未在顺序表里的排后面（两段发布共用同一规则）。 */
private fun sortFixed(
    fixed: Set<String>,
    order: List<String>,
    labels: Map<String, String>
): List<Pair<String, String>> = fixed
    .sortedBy { pkg -> order.indexOf(pkg).let { if (it >= 0) it else Int.MAX_VALUE } }
    .map { it to (labels[it] ?: it) }

private fun buildGroups(
    items: List<Pair<String, String>>,
    knownLetters: Map<String, String>
): Pair<List<LetterGroup>, Map<String, String>> {
    // letterFor 是 ICU 转写（首字符 → 字母）：旧实现把它放进 groupBy **和**排序比较器里，
    // 每次比较都重算一遍（200+ 项 ≈ n log n 次转写）。先算一次进 Map，再分组/排序。
    // 2026-11-05：label→首字母 跨会话稳定，优先命中盘缓存；只有 miss 才跑 ICU。
    val letterOf = HashMap<String, String>(items.size * 2)
    val newLetters = HashMap<String, String>()
    items.forEach { item ->
        val label = item.second
        if (letterOf.containsKey(label)) return@forEach
        val known = knownLetters[label]
        if (known != null) {
            letterOf[label] = known
        } else {
            val letter = letterFor(label)
            letterOf[label] = letter
            newLetters[label] = letter
        }
    }
    val groups = items
        .groupBy { letterOf.getValue(it.second) }
        .toSortedMap(compareBy({ it == "#" }, { it }))
        .map { (letter, list) ->
            // 排序键同样预计算（lowercase 每次比较都分配一份新串）
            val decorated = list.map { it to (letterOf.getValue(it.second) to it.second.lowercase(Locale.ROOT)) }
            LetterGroup(
                letter,
                decorated
                    .sortedWith(compareBy({ it.second.first == "#" }, { it.second.second }))
                    .map { it.first }
            )
        }
    return groups to newLetters
}
