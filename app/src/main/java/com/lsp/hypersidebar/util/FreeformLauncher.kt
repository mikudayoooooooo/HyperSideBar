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
