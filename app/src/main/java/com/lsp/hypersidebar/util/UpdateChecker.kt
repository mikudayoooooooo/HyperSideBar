package com.lsp.hypersidebar.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Release 检测更新。
 * 仓库归属（[REPO_PATH]）是 AboutPage 项目地址/作者链接与本期 API 的同一来源（0912 收口）——
 * 改仓库只改 REPO_PATH，不再两处手工同步。
 */
object UpdateChecker {

    /** 仓库归属（owner/name）：单源定义，RELEASES_API 与 AboutPage.PROJECT_URL 皆由此派生 */
    const val REPO_PATH = "mikudayoooooooo/HyperSideBar"
    const val REPO_URL = "https://github.com/" + REPO_PATH
    private const val RELEASES_API = "https://api.github.com/repos/" + REPO_PATH + "/releases/latest"
    private const val TIMEOUT_MS = 8000

    data class ReleaseInfo(
        val version: String,
        val notes: String,
        val pageUrl: String
    )

    /** 取最新正式 Release；网络失败或非 200 抛异常，由调用方决定失败态 */
    suspend fun fetchLatestRelease(): ReleaseInfo = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                // GitHub API 强制要求 User-Agent，缺省 UA 直接 403
                setRequestProperty("User-Agent", "HyperSideBar-UpdateCheck")
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) error("HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            ReleaseInfo(
                version = json.optString("tag_name").trim().removePrefix("v").removePrefix("V"),
                notes = json.optString("body"),
                pageUrl = json.optString("html_url")
            )
        } finally {
            conn?.disconnect()
        }
    }

    /** 逐段数值比较形如 2.0.0 / 1.1 的版本号，latest 严格更新才返回 true */
    fun isNewer(latest: String, current: String): Boolean {
        val a = segmentVersions(latest)
        val b = segmentVersions(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val l = a.getOrElse(i) { 0 }
            val c = b.getOrElse(i) { 0 }
            if (l != c) return l > c
        }
        return false
    }

    private fun segmentVersions(version: String): List<Int> =
        version.split('.', '-')
            .map { segment -> segment.filter(Char::isDigit).toIntOrNull() ?: 0 }
}
