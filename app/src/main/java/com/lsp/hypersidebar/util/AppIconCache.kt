package com.lsp.hypersidebar.util

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val TAG = "AppIconCache"

/**
 * 首屏预灌策略（纯逻辑，可 JVM 单测）。
 *
 * 面板打开时只给首屏一个起步量，其余磁贴按需加载——避免 219 个预灌任务与滚动期
 * 解码抢 CPU/IO（2026-11-05 用户拍板“只灌首屏”）。
 */
internal object IconLoadPolicy {
    const val FIRST_SCREEN_MAX = 60

    fun firstScreen(pkgs: List<String>, max: Int = FIRST_SCREEN_MAX): List<String> =
        pkgs.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .take(max.coerceAtLeast(0))
            .toList()
}

/**
 * 应用图标缓存（pkg → 128px Bitmap，进程级 LruCache）。
 *
 * 2026-11-05 重构（高速滑动卡顿）：
 * - `load` 改 suspend：同包并发用 [CompletableDeferred] 合流，等待者只挂起协程，
 *   不再用 `FutureTask.get()` 阻塞 IO 线程；
 * - 可见磁贴与预灌分两条有界通道（2 线程 / 1 线程），预灌不会把滚动请求堵在队列后面；
 * - `preload` 只灌首屏（[IconLoadPolicy.FIRST_SCREEN_MAX]），其余按需。
 */
object AppIconCache {

    private const val MAX_ENTRIES = 320
    private const val ICON_SIZE_PX = 128
    private const val VISIBLE_THREADS = 4

    private val cache = LruCache<String, Bitmap>(MAX_ENTRIES)

    private val visibleExecutor = Executors.newFixedThreadPool(VISIBLE_THREADS) { r ->
        Thread(r, "AppIconVisible").apply { isDaemon = true }
    }
    private val visibleDispatcher = visibleExecutor.asCoroutineDispatcher()

    private val preloadExecutor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "AppIconPreload").apply {
            isDaemon = true
            // 预灌让路给可见磁贴与 UI 线程：只在 CPU 空闲时抢时间片。
            priority = Thread.MIN_PRIORITY
        }
    }
    private val preloadDispatcher = preloadExecutor.asCoroutineDispatcher()

    private val scope = CoroutineScope(SupervisorJob())

    @Volatile private var released = false

    /** 进行中的解码任务（single-flight）：同包同一时刻只解码一次。 */
    private val inflight = ConcurrentHashMap<String, CompletableDeferred<Bitmap?>>()

    /** 仅查缓存，不触发 binder（主线程组合路径用）。 */
    fun peek(pkg: String): Bitmap? = cache.get(pkg)

    /**
     * 查缓存 + miss 时异步解码；同包并发自动合流。
     * 必须在协程中调用（等待时不占线程）。
     */
    suspend fun load(context: Context, pkg: String): Bitmap? =
        loadOn(context.applicationContext ?: context, pkg, visibleDispatcher)

    private suspend fun loadOn(
        context: Context,
        pkg: String,
        dispatcher: CoroutineDispatcher
    ): Bitmap? {
        cache.get(pkg)?.let { return it }
        if (released) return null

        val mine = CompletableDeferred<Bitmap?>()
        val existing = inflight.putIfAbsent(pkg, mine)
        if (existing != null) return existing.await()

        val job = runCatching {
            scope.launch(dispatcher) {
                val bitmap = runCatching { decode(context, pkg) }.getOrNull()
                mine.complete(bitmap)
                inflight.remove(pkg, mine)
            }
        }.getOrNull()
        if (job == null) {
            inflight.remove(pkg, mine)
            mine.complete(null)
        }
        return mine.await()
    }

    /** 真正干活的那一段（不进 inflight 表，避免自等）。 */
    private fun decode(context: Context, pkg: String): Bitmap? {
        cache.get(pkg)?.let { return it }
        val bitmap = runCatching {
            context.packageManager.getApplicationIcon(pkg).toBitmap(ICON_SIZE_PX, ICON_SIZE_PX)
        }.onFailure {
            HLog.w(TAG, "icon load failed: $pkg (${it.javaClass.simpleName}: ${it.message})")
        }.getOrNull()
        if (bitmap != null) cache.put(pkg, bitmap)
        return bitmap
    }

    /**
     * 首屏预灌（后台低优先级通道）。`pkgs` 会被截到 [IconLoadPolicy.FIRST_SCREEN_MAX]；
     * 面板传入全量列表时也只预灌首屏，其余交给磁贴按需加载。
     */
    fun preload(context: Context, pkgs: List<String>) {
        if (released || pkgs.isEmpty()) return
        val appCtx = context.applicationContext ?: context
        val targets = IconLoadPolicy.firstScreen(pkgs)
        if (targets.isEmpty()) return

        val t0 = SystemClock.elapsedRealtime()
        runCatching {
            scope.launch(preloadDispatcher) {
                targets.forEach { pkg ->
                    runCatching { loadOn(appCtx, pkg, preloadDispatcher) }
                }
                val cost = SystemClock.elapsedRealtime() - t0
                val msg = "preload: ${targets.size} icons in ${cost}ms (cap=${IconLoadPolicy.FIRST_SCREEN_MAX})"
                if (cost >= 1000) HLog.w(TAG, "preload slow: $msg") else HLog.i(TAG, msg)
            }
        }.onFailure { HLog.w(TAG, "preload enqueue failed: ${it.message}") }
    }

    /**
     * 内存压力回收（2026-11-11，M17/AllApps 缓存）：320×128×128×4 ≈ 21MB 上限，
     * 低内存时释放一半、COMPLETE 时清空。由 AllAppsActivity.onTrimMemory 调用。
     */
    fun trim(level: Int) {
        when {
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> cache.evictAll()
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ->
                cache.trimToSize(cache.size() / 2)
            else -> Unit
        }
    }

    /**
     * API 102 热重载释放：取消 scope / 唤醒等待者 / 关闭两个 dispatcher / 清缓存。
     * 旧代对象随后不再使用（新代是全新 object）。
     */
    fun releaseForReload() {
        released = true
        runCatching { scope.cancel() }
            .onFailure { HLog.w(TAG, "scope cancel failed: ${it.message}") }
        inflight.values.forEach { it.complete(null) }
        inflight.clear()
        runCatching { visibleDispatcher.close() }
            .onFailure { HLog.w(TAG, "visible dispatcher close failed: ${it.message}") }
        runCatching { preloadDispatcher.close() }
            .onFailure { HLog.w(TAG, "preload dispatcher close failed: ${it.message}") }
        runCatching { cache.evictAll() }
            .onFailure { HLog.w(TAG, "cache evict failed: ${it.message}") }
        HLog.i(TAG, "releaseForReload: done")
    }
}
