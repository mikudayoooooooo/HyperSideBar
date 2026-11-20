package com.lsp.hypersidebar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.prefs.savePref
import com.lsp.hypersidebar.util.HLog
import com.lsp.hypersidebar.util.RemotePrefsBridge
import com.lsp.hypersidebar.util.RelayToken

private const val TAG = "ConfigRestore"

/** 恢复值的落盘类型（2026-11-02 审查 P2：原先一律 putString ⇒ StringSet 键读出即崩）。 */
internal enum class RestoreKind { JSON_ARRAY, JSON_STRING_SET }

/** 允许恢复的键 → 落盘类型（安全键 enabled/relayToken 等绝不在此列）。 */
internal val RESTORE_SCHEMA: Map<String, RestoreKind> = linkedMapOf(
    PrefKeys.SHORTCUT_ACTIONS to RestoreKind.JSON_ARRAY,
    PrefKeys.CUSTOM_APPS_ORDER to RestoreKind.JSON_ARRAY,
    PrefKeys.CACHED_SUGGESTIONS to RestoreKind.JSON_ARRAY,
    // 该键的读取方是 SettingsRepository.customApps() 的 getStringSet——绝不能再按 String 落盘
    PrefKeys.CUSTOM_APPS to RestoreKind.JSON_STRING_SET,
)

/**
 * 解析 + 校验恢复值：null = 非法（不落盘，不写半截）。
 *
 * JSON_ARRAY 校验通过后原样返回（调用方按 String 落盘，与 UI 保存同格式）；
 * JSON_STRING_SET 返回 `Set<String>`（调用方必须用 putStringSet）。
 */
internal fun parseRestoreValue(raw: String, kind: RestoreKind): Any? = runCatching {
    val arr = org.json.JSONArray(raw)
    when (kind) {
        RestoreKind.JSON_ARRAY -> raw
        RestoreKind.JSON_STRING_SET -> HashSet<String>().apply {
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotEmpty() }?.let { add(it) }
            }
        }
    }
}.getOrNull()

/**
 * 配置恢复通道（2026-10-19 数据丢失事故后新增）：把外部备份的 remotePrefs 键值
 * 灌回模块（经本进程的 LSPosed 服务写回，走与 UI 保存完全相同的链路 + ConfigSync 广播）。
 *
 * 背景：debug↔release 换签名必须卸载重装，模块本地数据随卸载消失；remotePrefs
 * （LSPosed 守护进程侧）虽幸存，但"编辑器读了空缓存后保存"会整表覆盖用户数据
 * （真机事故：6 条快捷方式被 1 条覆盖）。有这个通道，事故后可以从备份秒级恢复。
 *
 * 防伪：与 [ShortcutRelayReceiver] 同款——运行期随机令牌严格校验
 * （[RelayToken.awaitAndVerify]：无令牌/不匹配一律拒绝；缓存为空时先等 remotePrefs
 * 绑定补挂令牌，≤3s，超时按拒绝处理）。令牌只在本进程/授权 hook 进程内流转，
 * 第三方 app 拿不到 ⇒ 不能伪造恢复广播。**鉴权排在取值/读文件/删文件之前**。
 *
 * 只允许恢复 [RESTORE_SCHEMA] 内的键，且按 schema 的类型落盘（JSON 校验失败即拒绝），
 * 防止用它改写 enabled/令牌等安全键，也防止写入类型错误的值把设置页读崩。
 */
class ConfigRestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(PrefKeys.RESTORE_EXTRA_KEY) ?: return
        val kind = RESTORE_SCHEMA[key] ?: run {
            HLog.w(TAG, "restore rejected: key=$key 不在白名单")
            return
        }
        // 鉴权要等 LSPosed 绑定（冷启动补挂）+ 可能读文件 ⇒ goAsync + 后台线程
        val pending = goAsync()
        Thread {
            try {
                val mirror = context.getSharedPreferences(
                    com.lsp.hypersidebar.prefs.PrefsFiles.APP_LOCAL, Context.MODE_PRIVATE
                )
                RelayToken.primeFromMirror(mirror)
                if (!RelayToken.awaitAndVerify(intent)) return@Thread
                RelayToken.saveMirror(mirror)
                val trace = intent.getStringExtra(com.lsp.hypersidebar.util.Trace.EXTRA)
                val value = resolveRestoreValue(intent) ?: return@Thread
                val parsed = parseRestoreValue(value, kind) ?: run {
                    HLog.w(TAG, "restore rejected: $key 值不是合法 ${kind.name}（${value.length}B）")
                    return@Thread
                }
                val prefs = RemotePrefsBridge.prefs ?: run {
                    HLog.w(TAG, "restore failed: remotePrefs 未绑定（LSPosed 桥不可用）")
                    return@Thread
                }
                // 与 UI 保存同款入口 savePref（2026-11-20）：资产键同步 commit（apply 异步
                // 队列在"恢复→立刻退出(kill)"时被截断），CUSTOM_APPS 空集自动走 remove+回填
                // 两段；CACHED_SUGGESTIONS 非资产键保持 apply——与旧行为同型。savePref 内已
                // 显式推一次配置同步（hook 进程实时生效），此处不再单独推。
                prefs.savePref(key, parsed)
                HLog.i(
                    TAG,
                    "config restored: $key (${value.length}B, ${kind.name}, " +
                        "${if (parsed is Set<*>) "set=${parsed.size}" else "string"}) trace=${trace ?: "-"}"
                )
            } finally {
                pending.finish()
            }
        }.start()
    }

    /**
     * 值来源二选一：直接 extra，或文件路径（JSON 含引号/换行时走 adb 转义必炸；
     * 限 /data/local/tmp 下、shell 可写目录，且 receiver 读完即删）。
     * 一律在鉴权之后调用——不能因为一条伪造广播就删掉用户放好的备份文件。
     */
    private fun resolveRestoreValue(intent: Intent): String? =
        intent.getStringExtra(PrefKeys.RESTORE_EXTRA_VALUE)
            ?: intent.getStringExtra(PrefKeys.RESTORE_EXTRA_PATH)?.let { path ->
                runCatching {
                    require(path.startsWith("/data/local/tmp/")) { "path 越界" }
                    val f = java.io.File(path)
                    val s = f.readText()
                    f.delete()
                    s
                }.getOrElse {
                    HLog.w(TAG, "restore value file 读取失败: ${it.message}")
                    null
                }
            }
}
