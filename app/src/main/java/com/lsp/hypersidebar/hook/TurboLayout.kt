package com.lsp.hypersidebar.hook

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import android.view.MotionEvent
import android.view.View
import java.lang.ref.WeakReference
import java.util.LinkedHashSet
import com.lsp.hypersidebar.ui.fan.ComposeFanHost
import com.lsp.hypersidebar.ui.fan.FanAppInfo
import com.lsp.hypersidebar.util.DataLoader
import com.lsp.hypersidebar.util.FreeformLauncher
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createAfterHook
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createBeforeHook
import kotlin.math.abs
import kotlin.math.sqrt

class TurboLayout(private val remotePrefs: SharedPreferences) : BaseHook() {

    private val TAG = "TurboLayout"
    private val sideBar = "com.miui.dock.sidebar.f"
    override val name: String = "HookTargetBox"

    private var fanMenu: ComposeFanHost? = null
    private var isFanMenuShowing = false
    private var lastTouchRawX = 0f
    private var lastTouchRawY = 0f
    private var touchDownRawX = 0f
    private var touchDownRawY = 0f
    private var lastTouchView: WeakReference<View>? = null
    private val fanTriggerThresholdPx = 30f
    private var sidebarWrapperRef: WeakReference<Any>? = null

    private var fixedAnchorX = 0f
    private var fixedAnchorY = 0f

    private fun readPref(key: String, default: Float): Float {
        return try { remotePrefs.getFloat(key, default) } catch (_: Exception) { default }
    }

    private fun readPref(key: String, default: Int): Int {
        return try { remotePrefs.getInt(key, default) } catch (_: Exception) { default }
    }

    private fun readStringSetPref(key: String, default: Set<String>): Set<String> {
        return try { remotePrefs.getStringSet(key, default) ?: default } catch (_: Exception) { default }
    }

    fun hookOnTouch() {
        val hooked = MethodFinder.fromClass(sideBar)
            .filterByName("onTouch")
            .filterByParamTypes(View::class.java, MotionEvent::class.java)
            .filterByReturnType(Boolean::class.java)
            .firstOrNull()
            ?.createBeforeHook {
                val event = it.args[1] as? MotionEvent ?: return@createBeforeHook
                val view = it.args[0] as? View ?: return@createBeforeHook

                if (isFanMenuShowing) {
                    val copy = MotionEvent.obtain(event).apply { setLocation(event.rawX, event.rawY) }
                    try { fanMenu?.dispatchTouchEvent(copy) } finally { copy.recycle() }
                    if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                        fanMenu?.let { hideAll() }
                    }
                    it.result = true
                    return@createBeforeHook
                }

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        touchDownRawX = event.rawX
                        touchDownRawY = event.rawY
                        lastTouchRawX = event.rawX
                        lastTouchRawY = event.rawY
                        lastTouchView = WeakReference(view)

                        val loc = IntArray(2)
                        view.getLocationOnScreen(loc)
                        val dm = view.context.resources.displayMetrics
                        fixedAnchorX = if (loc[0] <= dm.widthPixels / 2) 0f else dm.widthPixels.toFloat()
                        fixedAnchorY = loc[1] + view.height / 2f

                        Log.d(TAG, "onTouch: DOWN raw=(${event.rawX}, ${event.rawY}) viewLoc=(${loc[0]}, ${loc[1]}) viewSize=(${view.width}x${view.height}) screen=(${dm.widthPixels}x${dm.heightPixels}) anchor=($fixedAnchorX, $fixedAnchorY)")
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val wasShowing = isFanMenuShowing
                        lastTouchRawX = event.rawX
                        lastTouchRawY = event.rawY
                        maybeTriggerFanMenu()
                        val justCreated = isFanMenuShowing && !wasShowing
                        if (isFanMenuShowing) {
                            if (justCreated) {
                                val down = MotionEvent.obtain(
                                    event.downTime, event.eventTime,
                                    MotionEvent.ACTION_DOWN, event.rawX, event.rawY, 0
                                )
                                try { fanMenu?.dispatchTouchEvent(down) } finally { down.recycle() }
                            }
                            val copy = MotionEvent.obtain(event).apply { setLocation(event.rawX, event.rawY) }
                            try { fanMenu?.dispatchTouchEvent(copy) } finally { copy.recycle() }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isFanMenuShowing) dismissFanMenu()
                    }
                }
                it.result = true
            }
        Log.d(TAG, "hookOnTouch: hooked=$hooked")
    }

    private fun hookM26633Q() {
        MethodFinder.fromClass("com.miui.dock.sidebar.j")
            .filterByName("Q")
            .filterByParamTypes()
            .firstOrNull()
            ?.createAfterHook {
                sidebarWrapperRef = WeakReference(it.thisObject)
                Log.d(TAG, "Q afterHook: saved wrapper ref")
            }
    }

    private fun showFanMenu(context: Context, anchorX: Float, anchorY: Float) {
        if (isFanMenuShowing) return
        isFanMenuShowing = true
        Log.i(TAG, "showFanMenu: anchor=($anchorX, $anchorY)")

        try {
            val maxOuter = readPref("maxAppsOuter", 7)
            val maxInner = readPref("maxAppsInner", 4)
            val customApps = readStringSetPref("customApps", emptySet())

            val allSystemApps = DataLoader.loadApps(context)
            val merged = LinkedHashSet<String>()
            merged.addAll(customApps)
            merged.addAll(allSystemApps)
            val apps = merged.take(maxOuter + maxInner)
                .map { pkg ->
                    val label = runCatching {
                        context.packageManager.getApplicationLabel(
                            context.packageManager.getApplicationInfo(pkg, 0)
                        ).toString()
                    }.getOrDefault(pkg)
                    FanAppInfo(pkg, label)
                }

            if (apps.isEmpty()) {
                Log.w(TAG, "showFanMenu: no apps available, aborting")
                isFanMenuShowing = false
                return
            }

            val allQuick = mutableListOf<FanAppInfo>()

            allQuick.add(FanAppInfo(
                "__open_panel__", getPanelLabel(context),
                actionHandle = { ctx -> openNativePanel(ctx) }
            ))

            val systemActions = DataLoader.loadQuickActions(context)
            systemActions.forEach { qa ->
                allQuick.add(FanAppInfo(
                    qa.packageName.ifEmpty { qa.id },
                    qa.name.ifEmpty { qa.action },
                    actionHandle = { ctx -> executeQuickAction(ctx, qa) }
                ))
            }

            val shortcutPkgs = readStringSetPref("shortcutApps", emptySet())
            shortcutPkgs.forEach { pkg ->
                try {
                    val label = context.packageManager.getApplicationLabel(
                        context.packageManager.getApplicationInfo(pkg, 0)
                    ).toString()
                    allQuick.add(FanAppInfo(pkg, label))
                } catch (_: Exception) { }
            }

            val isLandscape = context.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE

            val host = ComposeFanHost(context, remotePrefs).apply {
                onAppSelected = { appInfo ->
                    Log.i(TAG, "onAppSelected: ${appInfo.packageName}")
                    FreeformLauncher.launch(context, appInfo.packageName)
                    hideAll()
                }

                onQuickAppSelected = { appInfo ->
                    Log.i(TAG, "onQuickAppSelected: ${appInfo.packageName}")
                    if (appInfo.actionHandle != null) {
                        appInfo.actionHandle.invoke(context)
                        dismissFanMenu()
                    } else {
                        when (appInfo.packageName) {
                            "com.miui.notes" -> {
                                dismissFanMenu()
                                context.startActivity(Intent(Intent.ACTION_MAIN).apply {
                                    addCategory(Intent.CATEGORY_LAUNCHER)
                                    setPackage("com.miui.notes")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                            }
                            else -> FreeformLauncher.launch(context, appInfo.packageName)
                        }
                    }
                }

                onDismiss = {
                    Log.d(TAG, "fanMenu onDismiss callback")
                    isFanMenuShowing = false
                    fanMenu = null
                }
            }

            fanMenu = host
            host.show(anchorX, anchorY, apps, allQuick, isLandscape)
            Log.i(TAG, "showFanMenu: compose fan overlay added, ${allQuick.size} quick actions, landscape=$isLandscape")

        } catch (e: Throwable) {
            Log.e(TAG, "showFanMenu FAILED: ${e.message}", e)
            isFanMenuShowing = false
            fanMenu = null
        }
    }

    private fun getPanelLabel(context: Context): String {
        val cr = context.contentResolver
        return when {
            android.provider.Settings.Secure.getInt(cr, "gb_boosting", 0) == 1 -> "游戏工具箱"
            android.provider.Settings.Secure.getInt(cr, "vtb_boosting", 0) == 1 -> "视频工具箱"
            else -> "打开面板"
        }
    }

    private fun openNativePanel(context: Context) {
        val wrapper = sidebarWrapperRef?.get()
        if (wrapper != null) {
            try {
                wrapper.javaClass.getMethod("Q").invoke(wrapper)
                Log.i(TAG, "openNativePanel: invoked Q()")
                return
            } catch (e: Exception) {
                Log.w(TAG, "openNativePanel: Q() failed: ${e.message}")
            }
        }
        for (cn in listOf("C0406n", "D4.n")) {
            try {
                val cls = Class.forName(cn)
                val m = cls.getMethod("o0", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                m.invoke(cls.getMethod("getInstance").invoke(null), true, true)
                Log.i(TAG, "openNativePanel: invoked $cn.o0(true,true)")
                return
            } catch (_: ClassNotFoundException) { }
            catch (_: Exception) { }
        }
        Log.w(TAG, "openNativePanel: all methods failed")
    }

    private fun executeQuickAction(context: Context, qa: DataLoader.QuickAction) {
        when (qa.action) {
            "native" -> {
                if (qa.className.isNotEmpty()) {
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        setClassName(qa.packageName, qa.className)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } else if (qa.packageName.isNotEmpty()) {
                    FreeformLauncher.launch(context, qa.packageName)
                }
            }
            "deeplink", "h5" -> {
                val uri = android.net.Uri.parse(qa.uri)
                if (qa.packageName.isNotEmpty()) {
                    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        data = uri
                        setPackage(qa.packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } else {
                    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        data = uri
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            }
            "quickapp" -> {
                val intent = Intent("android.intent.action.VIEW").apply {
                    data = android.net.Uri.parse(qa.uri)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try { context.startActivity(intent) } catch (_: Exception) { }
            }
        }
    }

    private fun maybeTriggerFanMenu() {
        if (isFanMenuShowing) return

        val dx = lastTouchRawX - touchDownRawX
        val dy = lastTouchRawY - touchDownRawY
        val moveDistance = sqrt(dx * dx + dy * dy)

        // 1. 最小触发距离
        if (moveDistance < 40f) return

        // 2. 必须向外移动（远离边缘）
        val isOutward = if (fixedAnchorX == 0f) dx > 0 else dx < 0
        if (!isOutward) return

        // 3. 移动方向与水平方向夹角不能太大
        val moveAngle = Math.toDegrees(
            Math.atan2(abs(dy).toDouble(), abs(dx).toDouble())
        ).toFloat()
        if (moveAngle > 60f) return

        val view = lastTouchView?.get()
        val ctx = view?.context
        if (ctx == null) {
            Log.w(TAG, "maybeTriggerFanMenu: view/context null, abort")
            return
        }
        Log.i(TAG, "maybeTriggerFanMenu: dx=$dx dy=$dy moveAngle=$moveAngle anchor=($fixedAnchorX, $fixedAnchorY)")
        showFanMenu(ctx, fixedAnchorX, fixedAnchorY)
    }

    private fun dismissFanMenu() {
        Log.d(TAG, "dismissFanMenu")
        isFanMenuShowing = false
        fanMenu?.dismiss()
        fanMenu = null
    }

    private fun hideAll() {
        Log.d(TAG, "hideAll")
        dismissFanMenu()
    }

    fun getStats(): String {
        val wrapper = if (sidebarWrapperRef?.get() != null) "1" else "0"
        return "fanMenu=${fanMenu != null}, isShowing=$isFanMenuShowing, wrapper=$wrapper"
    }

    override fun init() {
        Log.i(TAG, "=== TurboLayout init ===")
        hookOnTouch()
        hookM26633Q()
        Log.i(TAG, "init done: ${getStats()}")
    }
}
