package com.lsp.hypersidebar.util

import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.UserHandle
import android.util.Log
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder

private const val TAG = "FreeformLauncher"

object FreeformLauncher {

    fun launch(context: Context, packageName: String) {
        val clsName = getMainActivity(context, packageName)
        if (clsName == null) {
            Log.w(TAG, "launch: cannot resolve main activity for $packageName")
            return
        }

        // 优先使用 MIUI 内部 FreeformUtil.U()
        if (tryFreeformUtilU(context, packageName, clsName)) return

        // 回退：MiuiMultiWindowUtils + startActivityAsUser
        tryMiuiMultiWindow(context, packageName, clsName)
    }

    private fun tryFreeformUtilU(context: Context, packageName: String, clsName: String): Boolean {
        return try {
            val cls = Class.forName("com.miui.freeform.FreeformUtil")
            val method = MethodFinder.fromClass(cls)
                .filterByName("U")
                .filterByParamTypes(
                    Context::class.java,
                    String::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType
                )
                .firstOrNull()
            if (method == null) {
                Log.w(TAG, "FreeformUtil.U method not found")
                return false
            }
            method.invoke(null, context, packageName, clsName, 0)
            Log.i(TAG, "FreeformUtil.U success: $packageName/$clsName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "FreeformUtil.U failed for $packageName", e)
            false
        }
    }

    /**
     * 回退方案：MiuiMultiWindowUtils.getActivityOptions + startActivityAsUser
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
