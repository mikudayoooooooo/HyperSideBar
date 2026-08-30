package com.lsp.hypersidebar.util

import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.UserHandle
import android.util.Log

private const val TAG = "FreeformLauncher"

object FreeformLauncher {

    fun launch(context: Context, packageName: String) {
        val clsName = getMainActivity(context, packageName)
        if (clsName == null) {
            Log.w(TAG, "launch: cannot resolve main activity for $packageName")
            return
        }

        // 唯一路径：MiuiMultiWindowUtils.getActivityOptions + startActivityAsUser（实测验证）。
        // com.miui.freeform.FreeformUtil 在任何进程都 ClassNotFound——旧"优先路径"从未生效，
        // 只贡献每次启动一条异常栈噪音，已移除
        tryMiuiMultiWindow(context, packageName, clsName)
    }

    /**
     * 普通 App 进程用的小窗启动（全部应用面板内点开应用）：
     * 公开 API startActivity(Intent, Bundle) 承载隐藏 options——规避 startActivityAsUser
     * 反射（那是系统进程 :ui 的路径，普通进程未验证）。options 不可得时降级全屏。
     * 返回 false = 两条路径均失败（调用方 toast 兜底，PRD §238）。
     */
    fun launchFromApp(context: Context, packageName: String): Boolean {
        val clsName = getMainActivity(context, packageName) ?: run {
            Log.w(TAG, "launchFromApp: cannot resolve $packageName")
            return false
        }
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setClassName(packageName, clsName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            val cls = Class.forName("android.util.MiuiMultiWindowUtils")
            getActivityOptions(cls, context, packageName)?.let {
                context.startActivity(intent, it.toBundle())
                Log.i(TAG, "app-process freeform ok: $packageName")
                return true
            }
        }.onFailure { Log.w(TAG, "launchFromApp options failed: ${it.message}") }
        return runCatching {
            context.startActivity(intent)
            Log.i(TAG, "app-process plain launch: $packageName")
            true
        }.getOrDefault(false)
    }

    /**
     * 以小窗打开本模块的 Activity（全部应用面板，PRD"实质为一个以 freeform 小窗形式
     * 打开的 activity"）。自身包的小窗资格未经生产验证（B4 验证点）：
     * getActivityOptions 返回 null（无资格）或反射失败时降级普通全屏启动，功能不中断。
     */
    fun launchSelfFreeform(context: Context, activity: Class<*>) {
        val intent = Intent(context, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            val cls = Class.forName("android.util.MiuiMultiWindowUtils")
            val options = getActivityOptions(cls, context, context.packageName)
            if (options != null) {
                val method = context.javaClass.getMethod(
                    "startActivityAsUser",
                    Intent::class.java, android.os.Bundle::class.java, UserHandle::class.java
                )
                method.invoke(context, intent, options.toBundle(), Process.myUserHandle())
                Log.i(TAG, "own activity freeform ok: ${activity.simpleName}")
                return
            }
            Log.w(TAG, "own pkg freeform options null (B4: no eligibility?) → plain launch")
        } catch (e: Throwable) {
            Log.w(TAG, "own activity freeform failed: ${e.message} → plain launch")
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Log.e(TAG, "own activity plain launch failed: ${it.message}") }
    }

    /**
     * MiuiMultiWindowUtils.getActivityOptions + startActivityAsUser（实测可用路径）
     */
    private fun tryMiuiMultiWindow(context: Context, packageName: String, clsName: String) {
        try {
            val cls = Class.forName("android.util.MiuiMultiWindowUtils")
            val options = getActivityOptions(cls, context, packageName) ?: run {
                Log.w(TAG, "MiuiMultiWindow: getActivityOptions returned null")
                return
            }
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setClassName(packageName, clsName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            val method = context.javaClass.getMethod(
                "startActivityAsUser",
                Intent::class.java, android.os.Bundle::class.java, UserHandle::class.java
            )
            method.invoke(context, intent, options.toBundle(), Process.myUserHandle())
            Log.i(TAG, "MiuiMultiWindow fallback success: $packageName/$clsName")
        } catch (e: Exception) {
            Log.e(TAG, "MiuiMultiWindow fallback failed: ${e.message}")
        }
    }

    private fun getActivityOptions(
        cls: Class<*>, context: Context, pkg: String
    ): android.app.ActivityOptions? {
        try {
            val m = cls.getMethod(
                "getActivityOptions",
                Context::class.java, String::class.java, Boolean::class.javaPrimitiveType
            )
            return m.invoke(null, context, pkg, true) as? android.app.ActivityOptions
        } catch (_: NoSuchMethodException) { }
        try {
            val m = cls.getMethod(
                "getActivityOptions",
                Context::class.java, String::class.java
            )
            return m.invoke(null, context, pkg) as? android.app.ActivityOptions
        } catch (_: NoSuchMethodException) { }
        return null
    }

    private fun getMainActivity(context: Context, packageName: String): String? {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }
            context.packageManager.resolveActivity(intent, 0)?.activityInfo?.name
        } catch (_: Exception) { null }
    }
}
