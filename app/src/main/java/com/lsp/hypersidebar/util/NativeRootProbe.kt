package com.lsp.hypersidebar.util

import android.content.Context
import android.os.Environment
import com.lsp.hypersidebar.BuildConfig
import com.lsp.hypersidebar.prefs.HostPackages
import com.lsp.hypersidebar.prefs.PropNames
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * native hook 的 root 取证桥（spike/os4-native，2026-10-08/09）。
 *
 * **为什么必须有**：OS4 桌面进程里模块的 Java 半侧不跑（实测：`com.miui.home` 没有任何
 * XposedInit/HLog 行，导出里也没有 `[launcher]` 段——`NativeProbe.report()` 在桌面没人调），
 * `g_hook.hits` 与"hook 字节还在不在"都读不到。logcat 是桌面侧唯一出口，而它不在导出链里，
 * 于是用模块已有的 root 通道（`ShortcutLauncher.isRootAvailable` 同源）在导出时抓一拍。
 *
 * **三件事分开**（1008-1009 两轮踩出来的分工）：
 *  - 编译态：读自身 APK 里那份 so 的字节 ⇒ 区分"prop 关"与"包根本没编进 hook"（CMake 缓存
 *    曾把装机包变成 8296B 的 hook-less 空包，光看日志完全分不出来）。
 *  - 活性：读目标地址 16 字节与原始 prologue 比对 ⇒ == 已被打回文件映射（madvise/remap），
 *    ≠ trampoline 仍在位。**不需要用户做任何手势**。
 *  - 吞吐：`hook HIT` / `hook ALIVE` 原文 ⇒ 证明它还在被调用。
 *
 * ## 进程定位：只用 logcat 行自身的 pid，不要 pidof（1009 实踩，连着错两轮）
 *
 * `pidof com.miui.home` 报的 pid **不一定是扛 hook 的那个进程**：1009 12:38 那份导出里
 * pidof=14523（该进程有 base.apk 的 dex 映射、跑过 Java、却没跑过 native_init，一行
 * HSBNative 都没有），而真正装着 hook 的桌面进程是 781/7370——按 pidof 分桶就永远看不到它，
 * 于是"未见安装记录"、白判一轮"注入丢失"。
 *
 * 现在改成：抓一段 HSBNative，从**每行自己的 pid 列**取 pid（`parsePid` 同时认
 * `I/TAG(1234)` 与 `1234-1234` 两种 logcat 形态），按 pid 分组。副作用是这重新定义了
 * "桌面进程"的判定——**只有出现过 `hook installed` 的 pid 才是**，因为 libapp_launcher.so
 * 只存在于桌面进程，那句日志别的进程打不出来。`dd /proc/<pid>/mem` 也因此必然与
 * target 同生命周期（pid 就取自同一行）。
 *
 * 只读，绝不写宿主内存。dd 的 skip 对 `/proc/pid/mem` 是 lseek（该文件不可顺序读），
 * 不会把几个 TB 的顺序读跑一遍。
 */
object NativeRootProbe {

    private const val TAG = "NativeRoot"
    private const val HOST_PKG = "com.miui.home"

    /** 状态文件前缀（C 侧 HSB_STATE_PREFIX 镜像，ConfigPathMirrorTest 守） */
    private const val STATE_PREFIX = "hsb_native_state."

    /**
     * 一次抓多少行 HSBNative。要在"够翻到安装行"与"别把整份 logcat 灌进报告"之间取平衡。
     * 之前用 60 行被 `:ui`/`sys` 每次导出刷的 `live:` 回灌行吃干净，桌面那行
     * `hook installed` 被挤出视野；5000 行对一行约 120B 也算轻（过滤在 logcat 服务端做）。
     *
     * ⚠ 但**调大窗口治不了根**：ctor/native_init/hook installed 只在桌面进程启动那一拍各打
     * 一次，而导出永远发生在动作之后。1016 那轮就是这么误判的——窗口里只剩导出瞬间的回灌行，
     * ctor=0 于是被读成"没有进程加载 .so"，而手动 dump 抓得到（它在重启之后立刻抓）。
     * 主判据永远是"重启桌面后立刻 adb logcat -d -s HSBNative:*"，本段只在窗口没滚掉时顶用。
     */
    private const val LOG_TAIL = 5000

    /** 读目标地址处多少字节做比对（与 ORIGINAL_PROLOGUE 参照长度一致） */
    private const val PROBE_BYTES = 16

    /** 报告里带多少行原文（比摘要更有说服力：命中时刻、since_install、拒装原因都能直接看） */
    private const val RAW_TAIL = 20

    /**
     * 原始 prologue 前 16 字节 = `app/src/main/cpp/launcher_profiles.h` 的镜像
     * （由该头文件机械提取，键与 `debug.hsb.native.anchor` 白名单一致）。
     * ⚠ C 侧 profile 若变更必须同步这里，否则会把"hook 还在"误报成"被打回"。
     * 同步关系由 `NativePrologueMirrorTest` 守着——别再靠注释提醒。
     *
     * 2026-10-11 补齐：原来只有 3 个锚点（第 1 步那批），而当前门控挂的是
     * `is_in_bottom_gesture_region` ⇒ 最该被盯活性的那个锚点**没有参照值**，
     * 报告只会打"该锚点无 prologue 参照"，等于门控锚点的存活无从判断。现在 12 个全在表内
     * （2026-10-14 加第 12 个 `nav_hot_zone`：只读观测锚点，见 launcher_profiles.h）。
     */
    internal val ORIGINAL_PROLOGUE = mapOf(
        "try_delegate_back_to_sidebar" to "ff8301d1fd7b03a9f52300f9f44f05a9",
        "on_swipe_start" to "ff4303d1e84300fdfd7b09a9f85f0aa9",
        "on_swipe_process" to "ff8304d1eb2b0a6de9230b6dfd7b0ca9",
        "on_back_invoke" to "ff8304d1fd7b0ca9fc6f0da9fa670ea9",
        "on_back_cancelled" to "ffc302d1fd7b08a9f65709a9f44f0aa9",
        "handle_back_gesture" to "eb2bb86de923016dfd7b02a9fc6f03a9",
        "init_gesture_type" to "ff0303d1e83300fdfd7b07a9fa6708a9",
        "is_in_bottom_gesture_region" to "ffc300d1fd7b01a9f31300f9fd430091",
        "match_region_check" to "0800381e01020036093040b93f01086b",
        "pilfer_pointers" to "ff4301d1fd7b03a9f44f04a9fdc30091",
        // 2026-10-12：手势终态路径入口（"取消"的第二半：箭头收尾 + 取消/提交分叉都在它下游）
        "on_swipe_stop_direct" to "ffc301d1e82300fdfdfb04a9f52f00f9",
        // 2026-10-14：系统导航热区谓词（只读观测锚点，prop debug.hsb.native.anchor3 nav_hot_zone）
        "nav_hot_zone" to "ffc303d1ed33096deb2b0a6de9230b6d",
        // 2026-10-15：Home 判据本体 trigger_gesture_judge（只读观测锚点，prop anchor3 judge_probe）
        "judge_probe" to "ff8304d1ec5300fdeb2b0b6de9230c6d",
        // 2026-10-15：pilfer 动作本体 do_pilfer（只读观测锚点，prop anchor3 pilfer_probe）
        "pilfer_probe" to "ffc301d1fd7b04a9f65705a9f44f06a9",
        // 2026-10-16：系统"是否处理事件"总闸 should_handle_event（只读观测锚点，prop anchor3 action_probe）
        "action_probe" to "ffc304d1fd7b0da9fc6f0ea9fa670fa9",
        // 2026-10-18：当前旋转下的屏尺寸 display_api::get_current_rotate_screen_size
        //（**只调用不挂钩**：侧滑触发带的横屏判据，见 hsb_live_screen_size）
        "rotate_size" to "ffc302d1fd7b06a9fa6707a9f85f08a9",
    )

    /** 导出用的一段文本（诊断页 / 日志页 / 使用数据页三条路径都收口在 `SelfCheck.export`） */
    fun snapshot(context: Context): String =
        stateFileSection() + "\n" + snapshotCore(context)

    /**
     * native 状态文件镜像读侧（2026-11-22 增强 1）：宿主进程写、导出读。
     * 媒体目录段免 root（仅当 ROM 允许跨 uid 写时存在——写侧 best-effort 候选）；
     * 宿主 data 目录段经 root（保底候选，本模块本就要求 root）。
     * 前缀/目录族契约由 ConfigPathMirrorTest 守；放在 snapshot 最前 ⇒ root 不可用时
     * 媒体段仍带得上（no-root 早退在 core 里）。
     */
    private fun stateFileSection(): String = buildString {
        appendLine("== native 状态文件镜像（宿主进程写 / 导出读；不依赖 logcat 新鲜度）==")
        var found = 0
        val mediaDir = runCatching {
            File(Environment.getExternalStorageDirectory(), "Android/media/${BuildConfig.APPLICATION_ID}")
        }.getOrNull()
        mediaDir?.listFiles()?.filter { it.isFile && it.name.startsWith(STATE_PREFIX) }
            ?.sortedBy { it.name }?.forEach { f ->
                found++
                val mt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(f.lastModified()))
                appendLine("[media] ${f.name} mtime=$mt")
                appendLine("  " + runCatching { f.readText() }.getOrDefault("<读失败>").trim().take(700))
            }
        if (ShortcutLauncher.isRootAvailable()) {
            for (pkg in listOf(HostPackages.HOME, HostPackages.UI_HOST, HostPackages.SYSTEM_UI)) {
                // grep -H 自带文件名前缀，免 shell 变量（$f 会被 Kotlin 插值吃掉）
                val out = runRoot(
                    "grep -H '' /data/user/0/$pkg/$STATE_PREFIX* 2>/dev/null"
                )?.trim().orEmpty()
                if (out.isNotEmpty()) {
                    found++
                    out.lines().forEach { appendLine("[host-data] $it") }
                }
            }
        }
        if (found == 0) {
            appendLine("（无状态文件：本构建宿主侧 native 尚未写成功或通道被挡 ⇒ 回落下方 logcat 判词）")
        }
    }

    private fun snapshotCore(context: Context): String {
        val buildState = packageBuildState(context)
        if (!ShortcutLauncher.isRootAvailable()) {
            return "本包编译态：$buildState\nroot 不可用 ⇒ 桌面侧 native 状态取不到（活性/命中读不了）"
        }
        val log = runRoot("logcat -d -v time -s HSBNative:* | tail -n $LOG_TAIL")
            ?: return "本包编译态：$buildState\nsu logcat 执行失败/超时"

        val lines = log.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            return buildString {
                appendLine("本包编译态：$buildState")
                appendLine("== HSBNative 窗口为空 ==")
                appendLine(
                    "root 抓到的 logcat 里没有一条 HSBNative 行 ⇒ **窗口内**没有 C 侧行。"
                )
                appendLine(
                    "⚠ 不能据此断言\"没有进程加载我们的 .so\"：ctor/native_init/hook installed 只在桌面进程"
                )
                appendLine(
                    "启动那一拍各打一次，而导出永远发生在动作之后 ⇒ 稀疏行极可能已被主缓冲轮换掉（1016 实锤）。"
                )
                appendLine(
                    "请改抓：adb shell su -c 'am force-stop com.miui.home' 之后**立刻** " +
                        "adb shell \"su -c 'logcat -d -s HSBNative:*'\""
                )
                appendLine("（映射数同样不能证明 so 进没进——真正判据是窗口里有没有 ctor 行）")
            }.trimEnd()
        }

        // 进程定位一律来自行自身（见类注释）：pidof 会指到不扛 hook 的进程。
        val byPid = lines.groupBy { parsePid(it) ?: "?" }

        val sb = StringBuilder()
        sb.appendLine("本包编译态：$buildState")
        sb.appendLine("== HSBNative 进程概览（窗口内出现过的 pid，取自各行自身；非 pidof）==")
        byPid.forEach { (pid, pidLines) -> sb.appendLine(overviewLine(pid, pidLines)) }

        val installedPids = byPid.filter { (_, pidLines) ->
            pidLines.any { it.contains("hook installed") }
        }.keys

        if (installedPids.isEmpty()) {
            sb.appendLine("== 没有任何进程出现过 hook installed ==")
            sb.appendLine(noInstallVerdict(lines))
        } else {
            sb.appendLine("== 已装 hook 的进程 ==")
            installedPids.forEach { pid ->
                val pidLines = byPid.getValue(pid)
                val installed = pidLines.last { it.contains("hook installed") }
                val target = Regex("target=0x([0-9a-f]+)").find(installed)?.groupValues?.get(1)
                val anchor = Regex("installed (\\w+)").find(installed)?.groupValues?.get(1)
                val hits = pidLines.count { it.contains("hook HIT") }
                val beat = pidLines.lastOrNull { it.contains("hook HIT") || it.contains("hook ALIVE") }
                sb.appendLine("pid=$pid installed: ${installed.substringAfter("installed").trim().take(130)}")
                sb.appendLine(
                    "  HIT 行数=$hits（桩只打前 12 条，此数只能证明在响；累计值以 ALIVE 行的 hits= 为准）"
                )
                sb.appendLine(
                    "  最近心跳/命中：${beat?.substringAfter("HSBNative")?.trim()?.take(150) ?: "无（装好后未再被调用）"}"
                )
                sb.appendLine(
                    if (target != null) "  " + probeBytes(pid, target, anchor)
                    else "  installed 行里没有 target：${installed.takeLast(90)}"
                )
            }
        }

        sb.appendLine("---- HSBNative 原文（尾部 ${RAW_TAIL} 行，只留 hook 相关）----")
        lines.filter { it.contains("hook") || it.contains("anchor selected") }
            .takeLast(RAW_TAIL)
            .forEach { sb.appendLine(it.take(200)) }
        return sb.toString().trimEnd()
    }

    /** 一行看懂某个 pid 在窗口里说了什么：有没有跑到 native entry、装没装上、中没中 */
    private fun overviewLine(pid: String, pidLines: List<String>): String {
        val hasEntry = pidLines.any { it.contains("native_init") || it.contains("probe so LOADED") }
        val installed = pidLines.any { it.contains("hook installed") }
        val hits = pidLines.count { it.contains("hook HIT") }
        val alive = pidLines.any { it.contains("hook ALIVE") }
        val refused = pidLines.lastOrNull {
            it.contains("hook refused") || it.contains("hook not ready") || it.contains("hook failed")
        }
        val entry = if (hasEntry) "native_entry✓" else "native_entry✗(无 ctor/native_init 行)"
        val hook = when {
            installed -> "hook=installed"
            refused != null -> "hook=未装（${refused.substringAfter("HSBNative").trim().take(80)}）"
            else -> "hook=?（窗口内无安装/拒绝行）"
        }
        return "pid=$pid  ${pidLines.size}行  $entry  $hook  HIT行=$hits ALIVE=${if (alive) "有" else "无"}"
    }

    /** 没人装上的判读：区分"桌面进程起早了"与"窗口内压根没有桌面进程的行" */
    private fun noInstallVerdict(lines: List<String>): String {
        val hasDesktopSignal = lines.any { it.contains("libapp_launcher") || it.contains("anchor selected") }
        val ctor = lines.count { it.contains("probe so LOADED") }
        return buildString {
            appendLine(
                "窗口内跑过 native entry 的进程=$ctor 个；出现 libapp_launcher/anchor 行=" +
                    if (hasDesktopSignal) "有" else "无"
            )
            if (ctor == 0) {
                appendLine(
                    "⇒ 窗口内没有 C 侧行。**不能据此下结论**：稀疏行（ctor/native_init/hook installed）"
                )
                appendLine(
                    "只在桌面进程启动那一拍打一次，导出又在动作之后 ⇒ 很可能只是滚掉了。"
                )
                appendLine(
                    "先按这个抓一拍（重启后立刻抓，中间别做别的操作）："
                )
                appendLine(
                    "   adb shell su -c 'am force-stop com.miui.home' && adb shell \"su -c 'logcat -d -s HSBNative:*'\""
                )
            } else if (!hasDesktopSignal) {
                appendLine(
                    "⇒ .so 加载了，但桌面原生库还没在这些进程眼里出现（或它们的行已滚出 ${LOG_TAIL} 行窗口）。"
                )
                appendLine(
                    "   装 hook 只发生在桌面进程启动那一拍，且 prop 必须在重启桌面**之前**设好。"
                )
                appendLine(
                    "   若你确信设过了：清缓冲再重启一次，让安装行落进窗口 —— adb logcat -c 后重启宿主。"
                )
            } else {
                appendLine("⇒ 桌面 native_init 跑过但没装上（上面的概览里有各进程的拒绝原因）。")
            }
        }.trimEnd()
    }

    private fun desishLine(b: Boolean) = b

    /** 读自身 APK 里那份 so 的字节，判断 hook 机器在不在（与测试清单 §7.3 的电脑侧命令同源） */
    private fun packageBuildState(context: Context): String = runCatching {
        java.util.zip.ZipFile(context.applicationInfo.sourceDir).use { z ->
            val e = z.getEntry("lib/arm64-v8a/libhypersidebar_native.so")
                ?: return@runCatching "APK 内无 arm64 so（异常包）"
            z.getInputStream(e).use { ins ->
                val bytes = ins.readBytes()
                val txt = String(bytes, Charsets.ISO_8859_1)
                val hook = txt.contains(PropNames.NATIVE_HOOK) &&
                    txt.contains("try_delegate_back_to_sidebar")
                (if (hook) "hook 机器已编进（spike 态）"
                 else "⚠ hook 机器不在包里（编译态总闸关着 ⇒ 装机白测；先查 CMakeLists 那两行与 app/.cxx 缓存）") +
                    "，so=${bytes.size}B"
            }
        }
    }.getOrElse { "读自身 APK 失败：${it.message}" }

    /** 读目标地址 16 字节：与原始 prologue 相等 ⇒ 被打回；不等 ⇒ trampoline 仍在位 */
    private fun probeBytes(pid: String, target: String, anchor: String?): String {
        val addr = runCatching { target.toLong(16) }.getOrNull() ?: return "target 解析失败：$target"
        val hex = runRoot(
            "dd if=/proc/$pid/mem bs=1 skip=$addr count=$PROBE_BYTES 2>/dev/null | od -An -v -tx1"
        )?.trim()?.replace(Regex("\\s+"), "")?.lowercase()
            ?: return "读 /proc/$pid/mem@$target 失败（SELinux 或未授权）"
        if (hex.length < PROBE_BYTES * 2) {
            return "读到的字节不足（hex=$hex）⇒ 该地址在此进程已不可映射（进程换了新生命周期，旧 target 过期）"
        }
        val ref = anchor?.let { ORIGINAL_PROLOGUE[it] }
            ?: return "字节=$hex（锚点 ${anchor ?: "?"} 无 prologue 参照 ⇒ 存活无法判定；" +
                "把 launcher_profiles.h 里该锚点的前 16B 补进 ORIGINAL_PROLOGUE）"
        return if (hex.startsWith(ref))
            "⚠ 字节=$hex == 原始 prologue ⇒ hook 已被打回文件映射（madvise/remap 坐实：第 2 步之前必须上归档守卫）"
        else "✓ 字节=$hex ≠ 原始 prologue ⇒ trampoline 仍在位（抽样此刻 hook 活着）"
    }

    /**
     * logcat 行里取 pid，两种形态都认：
     *  - `I/HSBNative(7370): msg`（我们自己的 `-v time` 在本 ROM 的实际输出）
     *  - `…  7370-7370  I HSBNative: msg`（设备默认 threadtime/描述式输出）
     */
    private fun parsePid(line: String): String? =
        Regex("""\((\d+)\)""").find(line)?.groupValues?.get(1)
            ?: Regex("""\s(\d+)-\d+\s""").find(line)?.groupValues?.get(1)

    /** su 执行一条命令取 stdout；失败或超时返回 null（不抛、不阻塞导出） */
    private fun runRoot(cmd: String, timeoutS: Long = 6): String? = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        if (!p.waitFor(timeoutS, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroy()
            null
        } else out
    }.onFailure { HLog.w(TAG, "root probe failed: ${it.message}") }.getOrNull()
}
