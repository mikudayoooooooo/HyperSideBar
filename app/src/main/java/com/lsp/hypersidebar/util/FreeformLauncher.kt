package com.lsp.hypersidebar.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.os.UserHandle

object FreeformLauncher {

    fun launch(context: Context, packageName: String) {
        tryMiuiFreeform(context, packageName)
    }

    private fun tryMiuiFreeform(context: Context, packageName: String) {
        try {
            val clsName = getMainActivity(context, packageName) ?: return
            val cls = Class.forName("android.util.MiuiMultiWindowUtils")
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setClassName(packageName, clsName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            val options = getOptions(cls, context, packageName) ?: return
            val method = context.javaClass.getMethod(
                "startActivityAsUser",
                Intent::class.java, Bundle::class.java, UserHandle::class.java
            )
            method.invoke(context, intent, options.toBundle(), Process.myUserHandle())
        } catch (_: Exception) { }
    }

    private fun getOptions(cls: Class<*>, context: Context, pkg: String): android.app.ActivityOptions? {
        try {
            val m = cls.getMethod("getActivityOptions", Context::class.java, String::class.java, Boolean::class.javaPrimitiveType)
            return m.invoke(null, context, pkg, true) as? android.app.ActivityOptions
        } catch (_: NoSuchMethodException) { }
        try {
            val m = cls.getMethod("getActivityOptions", Context::class.java, String::class.java)
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
