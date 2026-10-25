package com.lsp.hypersidebar.util

import android.os.Environment
import com.lsp.hypersidebar.BuildConfig
import java.io.File

/**
 * 配置通道（native 侧读取实现在 `app/src/main/cpp/hsb_config.c`）：文件优先 → 系统属性 → 缺省。
 *
 * **2026-10-24 迁址免 root**：原落点 `/data/local/tmp/hsb.conf` 对普通 app 是 EACCES
 * （2026-10-17 真机实测，当时只能借 `su cp` 落盘，与属性通道共乘同一条 su）。现迁到
 * **应用专属媒体目录** `Android/media/<applicationId>/hsb.conf`——平台规则允许 app 读写
 * 自己的这个目录，**不需要任何存储权限、不需要 root/su**（与总闸 marker `NativeGateBridge`
 * 同一条免 root 通道）。native 侧按多候选路径读（@emulated0/@sdcard/@dmedia0/@tmp，
 * 与 gate marker probe 同款自证，见 `kCfgPaths[]`），首次命中打一行 `cfg: file=…@…` 日志。
 *
 * 键名约定不变：文件里用**短名**（属性名去掉 `persist.hsb.` 前缀），例如 `corner.hot_pct=8`。
 * native 侧 1 秒粒度 + `stat`(mtime/size) 判变更 ⇒ 改完 ≤1s 生效，无需重启任何宿主。
 *
 * 与属性（su）**并存而非替代**：有 root 的机器属性照推（强化路径，`CornerGeomBridge` 里
 * 那条不动）；免 root 机器靠本文件吃到值。写失败只记日志——两路都失败时 native 用编译期
 * 兜底值（fail-open 语义不变，最坏只是几何不跟手，不会吞手势）。
 */
object ConfigFileBridge {

    private const val TAG = "ConfigFile"
    private const val CONF_NAME = "hsb.conf"

    /** 免 root 落点：应用专属媒体目录（= native `kCfgPaths[]` 的 @emulated0 候选，路径镜像由
     *  `ConfigPathMirrorTest` 源码文本锁住）。 */
    private fun confFile(): File? = runCatching {
        File(
            File(Environment.getExternalStorageDirectory(), "Android/media/${BuildConfig.APPLICATION_ID}"),
            CONF_NAME
        )
    }.getOrNull()

    /**
     * 把标量写成 `key=value` 并原子替换。值用 `toString()`；浮点请先自行格式化成与属性一致的
     * 字符串（例如 `"%.4f"`）——native 侧只做 `strtof/strtol`。
     * 只在模块 App 自己的进程写（别的进程对这条路径是"别人的媒体目录"，FUSE 必拒）。
     */
    fun pushScalars(entries: Map<String, Any>) {
        if (entries.isEmpty()) return
        // ⚠ 2026-10-25 真机翻车点：HLog.proc() 返回**短名**（模块 App 进程 = "app"），拿它跟
        // applicationId 比较恒不等 ⇒ 静默跳过、一行日志没有（App 明明起了、文件却没写）。
        if (HLog.proc() != HLog.PROC_APP) {
            HLog.w(TAG, "非模块 App 进程（proc=${HLog.proc()}）⇒ 跳过免 root 文件写入")
            return
        }
        val f = confFile() ?: run {
            HLog.w(TAG, "conf 路径构造失败（本机没有外部存储？）⇒ 免 root 文件通道不可用")
            return
        }
        val body = buildString {
            append("# hyperSidebar native 配置（模块 App 自动生成；手改这个文件也有效，≤1s 生效）\n")
            append("# 键名 = 系统属性名去掉 persist.hsb. 前缀\n")
            entries.forEach { (k, v) ->
                append(k.removePrefix("persist.hsb.")).append('=').append(v.toString()).append('\n')
            }
        }
        val r = runCatching {
            f.parentFile?.mkdirs()
            // 同目录 tmp+rename 原子替换 ⇒ native 永远读不到半截。rename 被拒（目标占用等）
            // 时删旧再试；仍失败直写兜底——native 对越界/NaN 值有范围检查，不会崩。
            val tmp = File(f.parentFile, "$CONF_NAME.tmp")
            tmp.writeText(body)
            if (tmp.renameTo(f)) return@runCatching true
            f.delete()
            if (tmp.renameTo(f)) true else f.writeText(body)
        }
        if (r.isSuccess) {
            HLog.i(TAG, "config pushed: ${entries.size} keys → ${f.absolutePath}（免 root）")
        } else {
            HLog.w(TAG, "config push failed: ${r.exceptionOrNull()?.message}")
        }
    }

    /** 给离线实验/排障用：读回文件内容（文件在自己媒体目录里，直接读，不再需要 su）。 */
    fun dump() {
        val f = confFile() ?: return
        runCatching {
            HLog.i(TAG, "config dump: ${f.readText().trim().replace('\n', '|')}")
        }.onFailure { HLog.w(TAG, "config dump failed: ${it.message}") }
    }
}
