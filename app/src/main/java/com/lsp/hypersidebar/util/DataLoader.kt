package com.lsp.hypersidebar.util

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object DataLoader {

    private const val TAG = "DataLoader"
    private const val PROVIDER_URI = "content://com.miui.securitycenter.remoteprovider"
    private const val KEY = "global_dock_apps"
    private const val CACHE_TTL_MS = 30_000L

    private var cachedResult: List<String>? = null
    private var cachedQuickActions: List<QuickAction>? = null
    private var lastFetchTime = 0L

    data class QuickAction(
        val id: String,
        val name: String,
        val action: String,
        val uri: String,
        val packageName: String,
        val className: String
    )

    fun loadApps(context: Context): List<String> {
        return try {
            loadAppsInternal(context)
        } catch (e: Throwable) {
            cachedResult ?: emptyList()
        }
    }

    fun loadQuickActions(context: Context): List<QuickAction> {
        return try {
            loadDockData(context)
            cachedQuickActions ?: emptyList()
        } catch (e: Throwable) {
            cachedQuickActions ?: emptyList()
        }
    }

    private fun loadAppsInternal(context: Context): List<String> {
        val now = System.currentTimeMillis()
        if (cachedResult != null && (now - lastFetchTime) < CACHE_TTL_MS) {
            return cachedResult!!
        }

        loadDockData(context)
        val suggestion = loadSuggestionApps(context)
        val merged = linkedSetOf<String>()
        merged.addAll(cachedResult ?: emptyList())
        merged.addAll(suggestion)

        cachedResult = merged.toList()
        lastFetchTime = now
        return cachedResult!!
    }

    private fun loadDockData(context: Context) {
        try {
            val bundle = Bundle().apply {
                putString("key", KEY)
                putString("default", "")
            }
            val result = context.contentResolver.call(
                Uri.parse(PROVIDER_URI),
                "callPreference",
                "GET",
                bundle
            ) ?: return
            val jsonStr = result.getString(KEY, "") ?: return
            if (jsonStr.isEmpty()) return

            val jsonArray = JSONArray(jsonStr)
            val apps = mutableListOf<String>()
            val actions = mutableListOf<QuickAction>()

            for (i in 0 until jsonArray.length()) {
                when (val entry = jsonArray.get(i)) {
                    is String -> apps.add(entry.split(",,").first())
                    is JSONObject -> {
                        val action = entry.optString("action", "none")
                        if (action == "none") continue
                        actions.add(QuickAction(
                            id = entry.optString("id", ""),
                            name = entry.optString("name", entry.optString("title", "")),
                            action = action,
                            uri = entry.optString("uri", ""),
                            packageName = entry.optString("packageName", ""),
                            className = entry.optString("className", "")
                        ))
                    }
                }
            }

            cachedResult = apps
            cachedQuickActions = actions
        } catch (e: Throwable) {
            Log.w(TAG, "loadDockData: ${e.message}")
        }
    }

    private fun loadSuggestionApps(context: Context): List<String> {
        return try {
            val cls = Class.forName("android.util.MiuiMultiWindowUtils")
            val method = try {
                cls.getMethod("getFreeformSuggestionList", Context::class.java)
            } catch (_: NoSuchMethodException) {
                cls.getDeclaredMethod("getFreeformSuggestionList", Context::class.java).apply {
                    isAccessible = true
                }
            }
            @Suppress("UNCHECKED_CAST")
            val rawList = method.invoke(null, context) as? List<String> ?: emptyList()
            rawList.map { it.split(",,").first() }
        } catch (e: Throwable) {
            Log.w(TAG, "loadSuggestionApps: ${e.message}")
            emptyList()
        }
    }
}
