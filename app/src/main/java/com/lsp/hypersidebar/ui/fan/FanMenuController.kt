package com.lsp.hypersidebar.ui.fan

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.view.MotionEvent
import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.util.AppMetaCache
import com.lsp.hypersidebar.util.DataLoader
import com.lsp.hypersidebar.util.ShortcutKind
import com.lsp.hypersidebar.util.ShortcutStore

private const val TAG = "FanMenuController"

/**
 * 扇形菜单编排器：数据组装 + ComposeFanHost 装配 + 选中回调接线。
 * 进程无关（securitycenter:ui 与 com.miui.home 共用），执行动作经 [FanLaunchStrategy] 差异化。
 */
class FanMenuController(
    private val prefs: SharedPreferences,
    private val launchStrategy: FanLaunchStrategy
) {

    @Volatile
    var isShowing = false
        private set
    private var host: ComposeFanHost? = null

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun show(context: Context, anchorX: Float, anchorY: Float) {
        // hook 的触摸回调可能不在主线程（launcher 的 GestureStubView.onTouchEvent 经
        // MiuiMirror 输入线程分发，实测 tid≠主线程）；Compose 生命周期装配必须主线程
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            Log.i(TAG, "show: hopping to main thread (from ${Thread.currentThread().name})")
            mainHandler.post { showInternal(context, anchorX, anchorY) }
        } else {
            showInternal(context, anchorX, anchorY)
        }
    }

    private fun showInternal(context: Context, anchorX: Float, anchorY: Float) {
        // 实测轮七：入口状态遥测——定位 isShowing 被无日志翻转的路径（双开根因）
        Log.i(TAG, "showInternal enter: isShowing=$isShowing host=${host != null} anchor=($anchorX,$anchorY)")
        if (isShowing && host != null) return

        isShowing = true
        // 防御性单窗口不变量：任何状态下不允许两个 fan 窗口并存——
        // 若 isShowing 已被异常翻回 false 而旧 host 仍存活，先拆除再建新
        host?.let { stale ->
            Log.w(TAG, "showInternal: orphan host detected, tearing down")
            runCatching { stale.dismiss() }
        }
        host = null
        Log.i(TAG, "show: anchor=($anchorX, $anchorY)")

        try {
            val isLandscape = context.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE

            val (maxOuter, maxInner) = if (isLandscape) {
                readPref(PrefKeys.LANDSCAPE_MAX_APPS_OUTER, LayoutDefaults.LANDSCAPE_MAX_APPS_OUTER) to
                    readPref(PrefKeys.LANDSCAPE_MAX_APPS_INNER, LayoutDefaults.LANDSCAPE_MAX_APPS_INNER)
            } else {
                readPref(PrefKeys.MAX_APPS_OUTER, LayoutDefaults.MAX_APPS_OUTER) to
                    readPref(PrefKeys.MAX_APPS_INNER, LayoutDefaults.MAX_APPS_INNER)
            }

            val customApps = readStringSetPref(PrefKeys.CUSTOM_APPS, emptySet())

            val allSystemApps = DataLoader.loadApps(context)
            val merged = LinkedHashSet<String>()
            merged.addAll(customApps)
            merged.addAll(allSystemApps)
            // 圈内末位常驻"全部应用"入口（PRD §7.3.2）：合并列表截到（总数-1）留出末位，
            // 哨兵项计入 7+4 参与正常环布局；合并列表为空时扇形单独承载哨兵（不再中止呼出）
            val apps = merged.take((maxOuter + maxInner - 1).coerceAtLeast(0))
                .map { pkg -> FanAppInfo(pkg, AppMetaCache.label(context, pkg)) } +
                FanAppInfo(ALL_APPS_PKG, "全部应用")

            // 快捷栏单一来源：面板占位（第一位，可用时）+ 用户启用的 shortcut_actions（PRD §7.1）
            val runtimeQuick = ShortcutStore.buildRuntimeQuickList(
                prefs,
                ShortcutStore.isToolboxAvailable(context),
                ShortcutStore.getToolboxLabel(context)
            )
            val allQuick = runtimeQuick.map { sa ->
                FanAppInfo(
                    packageName = if (sa.kind == ShortcutKind.TOOLBOX) "__open_panel__" else "shortcut:${sa.id}",
                    appName = sa.label,
                    actionHandle = { ctx ->
                        if (sa.kind == ShortcutKind.TOOLBOX) {
                            launchStrategy.openNativePanel(ctx)
                        } else {
                            launchStrategy.launchShortcut(ctx, sa)
                        }
                    }
                )
            }

            val fanHost = ComposeFanHost(context, prefs).apply {
                onAppSelected = { appInfo ->
                    Log.i(TAG, "onAppSelected: ${appInfo.packageName}")
                    if (appInfo.packageName == ALL_APPS_PKG) {
                        launchStrategy.launchAllApps(context)
                    } else {
                        launchStrategy.launchFreeform(context, appInfo.packageName)
                    }
                    hideAll()
                }

                onQuickAppSelected = { appInfo ->
                    Log.i(TAG, "onQuickAppSelected: ${appInfo.packageName}")
                    if (appInfo.actionHandle != null) {
                        appInfo.actionHandle.invoke(context)
                        dismiss()
                    } else {
                        // 防御分支：快捷项理论上均携带 actionHandle
                        launchStrategy.launchFreeform(context, appInfo.packageName)
                    }
                }

                onDismiss = {
                    Log.d(TAG, "fanMenu onDismiss callback")
                    isShowing = false
                    host = null
                }
            }

            host = fanHost
            fanHost.show(anchorX, anchorY, apps, allQuick, isLandscape)
            Log.i(TAG, "show: compose fan overlay added, ${allQuick.size} quick actions, landscape=$isLandscape")

        } catch (e: Throwable) {
            Log.e(TAG, "show FAILED: ${e.message}", e)
            isShowing = false
            host = null
        }
    }

    fun hideAll() {
        dismiss()
    }

    /** 触摸事件转发给扇形菜单；返回 false 表示当前无菜单。跨线程安全（自动跳主线程）。 */
    fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!isShowing) return false
        // 必须捕获局部引用：跨线程路径下 dismiss() 会在 post 之后立即置空 host，
        // 若 lambda 里读字段，转发的 UP 会被静默丢弃（实测 4 次呼出 0 次 UP 送达）
        val h = host ?: return false
        val copy = MotionEvent.obtain(event).apply { setLocation(event.rawX, event.rawY) }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            try { h.dispatchTouchEvent(copy) } finally { copy.recycle() }
        } else {
            // launcher 的触摸回调在 MiuiMirror 输入线程，Compose 视图操作必须主线程
            mainHandler.post {
                try { h.dispatchTouchEvent(copy) } finally { copy.recycle() }
            }
        }
        return true
    }

    /**
     * 收起扇形。状态机字段（isShowing/host）只允许主线程读写：
     * launcher 输入线程的 UP 若直接清字段，会与正在执行 showInternal 的主线程竞态
     * （冷路径 getFreeformSuggestionList 可阻塞数百 ms）——"装配中"的 show 完成后
     * host 指向孤儿窗口且 isShowing=false，扇形常驻的同时还能再唤出第二个。
     * 改投递语义串行化：show Runnable 先入队、dismiss 后入队 ⇒ 必然先完整展示再拆除
     * （对应 PRD"未预选松手立即收起"），正确性与主线程阻塞时长无关。
     */
    fun dismiss() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            doDismiss("main")
        } else {
            Log.i(TAG, "dismiss: posted from ${Thread.currentThread().name}")
            mainHandler.post { doDismiss("posted") }
        }
    }

    private fun doDismiss(via: String) {
        val h = host
        isShowing = false
        host = null
        if (h == null) return
        Log.i(TAG, "doDismiss($via): tearing down host")
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            h.dismiss()
        } else {
            // 防御：doDismiss 理论上恒在主线程；保留定向拆除兜底
            mainHandler.post { h.dismiss() }
        }
    }

    fun getStats(): String = "fanMenu=${host != null}, isShowing=$isShowing"

    private fun readPref(key: String, default: Float): Float {
        return try { prefs.getFloat(key, default) } catch (_: Exception) { default }
    }

    private fun readPref(key: String, default: Int): Int {
        return try { prefs.getInt(key, default) } catch (_: Exception) { default }
    }

    private fun readStringSetPref(key: String, default: Set<String>): Set<String> {
        return try { prefs.getStringSet(key, default) ?: default } catch (_: Exception) { default }
    }
}
