package com.lsp.hypersidebar.util

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 公共下载目录写入（导出通道唯一实现）。
 *
 * 自检报告 / 运行日志 / 使用统计 / 配置备份四条导出路径共用：MediaStore 不需要存储
 * 权限；失败回落应用私有外部目录（文件管理器可达性差，但至少能取到）。此前是
 * `SelfCheck.export` 内联实现，配置备份再抄一份会立刻漂移，故收口到这里。
 */
internal object DownloadsWriter {

    private const val TAG = "DownloadsWriter"

    /** 写入并返回展示用路径（`下载/<name>` 或 `应用目录/<dir>/<name>`）。 */
    suspend fun write(
        context: Context,
        fileName: String,
        mimeType: String,
        content: String
    ): String = withContext(Dispatchers.IO) {
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
            }
            val uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert returned null")
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(content.toByteArray())
            } ?: error("openOutputStream returned null")
            "下载/$fileName"
        }.getOrElse { e ->
            HLog.w(TAG, "MediaStore export failed, fallback to app dir: ${e.message}")
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: context.filesDir
            File(dir, fileName).writeText(content)
            "应用目录/${dir.name}/$fileName"
        }
    }
}
