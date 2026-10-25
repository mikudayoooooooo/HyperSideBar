package com.lsp.hypersidebar.util

import android.content.SharedPreferences
import android.os.Environment
import com.lsp.hypersidebar.BuildConfig
import com.lsp.hypersidebar.prefs.PrefKeys
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * native 桌面 hook 的**总闸桥**（2026-10-14「默认化」→ 当晚「只写关断」收敛）。
 *
 * ## 它要解决什么
 * OS4 的 `com.miui.home` 由 `hyos_spawner` fork、不过 ART ⇒ 该进程**没有模块 Java 半侧**，
 * 读不到 `RemotePreferences`：模块开关对 launcher 是隐形的。本桥负责把"该不该接管"
 * 这个结论送到 launcher 侧的 native（`hsb_prop_enabled()` / `hsb_gate_marker_probe()`）。
 *
 * ## native 侧的三档语义（决定了这里只需要写"关"）
 * ① `debug.hsb.native.hook` 有值 ⇒ 用它（排障覆盖，重启即清）；
 * ② 任一通道**显式写 0** ⇒ 关：`persist.hsb.native.hook == "0"`（属性，手工/adb 用）
 *    或 marker 首字符 `'0'`（本桥唯一写的东西，见下）；
 * ③ 都没表态（属性缺失/非 0、marker 不存在）⇒ **开**（fail-open：`.so` 被 LSPosed 按
 *    "模块启用 + 作用域"注入到桌面进程，这件事本身就是用户的授权）。
 *
 * 所以"写 1"在语义上是**空操作**（缺省即开），而两条免-su 属性写路真机都已证死：
 * 模块 App 的 `su` 被 `EACCES` 挡在 `execve`（`Cannot program "su": error=13`），
 * systemui 的 `SystemProperties.set` 抛 `RuntimeException: failed to set system property`
 * （本 `.so` 内直调 `__system_property_set` 也回 `rc=-1 readback=0`）。
 * ⇒ **本桥不再写属性、不再用 su**，只在"该关"时写一个免 root 的文件 marker。
 * 属性通道保留给手工/adb 关断（native 仍读它，本桥只**读来告警**，见 `warnIfPropStuckOff()`）。
 *
 * ## 唯一的写通道：免 root 文件 marker
 * `Android/media/<applicationId>/hsb_native_gate`（应用专属媒体目录：**不需要任何权限、
 * 不需要 su**）。首字符 `'0'` ⇒ native 关断；文件不存在/空 ⇒ 不表态（保持开）。
 * launcher 侧 native 读同一路径的多候选集合（`hsb_gate_marker_probe()`，plan §17）。
 * 只在本模块 App 自己的进程里写（别的进程写不进这个 media 目录，也没必要）。
 *
 * ## 三态判定（**不猜**）
 * - 判不出"OS4 纯 native 桌面"（APK 探测失败）⇒ **不表态**：探测失败时按"没找到"去写 0，
 *   会把真机上的总闸误关（比"不生效"糟得多，同 plan §15.8 的单向性原则）。
 * - 模块开关开 + OS4 ⇒ **开**：删掉可能残留的 `'0'` marker（把通道恢复成"没表态"）。
 * - 模块开关关 + OS4 ⇒ **关**：写 marker `'0'`。
 * - 不是 OS4 ⇒ 不表态（native hook 与它无关）。
 *
 * ## 不做的事（用户 m02478 明令）
 * 不 `force-stop` / 不 kill / 不重启任何宿主进程。native hook 只在**桌面进程启动时**装载，
 * 因此值变化等**下一次桌面启动**自然生效；要立刻生效由**用户自己**重启桌面，或点设置页里
 * 那条用户确认的「重启 hook 宿主」（既有功能，不在本桥里）。
 *
 * 失败语义：写不进去只记日志、**不抛**。native 侧缺省=开 ⇒ 本桥失败只影响"关断是否生效"，
 * 不影响"能不能用"（LSPosed 里禁用模块可彻底关）。
 */
object NativeGateBridge {

    private const val TAG = "NativeGate"

    /**
     * native 侧**仍然读**的属性名（手工/adb 覆盖用，`adb shell su -c 'setprop … 1'`）。
     * 本桥不写它——只在被手工置 0 时读出来告警一次。
     */
    const val PROP_HOOK = "persist.hsb.native.hook"

    /** 文件 marker 名（native 读同一个名字，见 plan §17） */
    private const val MARKER_NAME = "hsb_native_gate"

    /** 单线程串行：sendSync 会被设置页写入触发，串行避免并发碰同一个文件。
     *  ⚠ 可重建（不是 `val`）：热重载要把旧代这条线程停掉（线程 Runnable 是旧代 lambda，
     *  不停掉旧 classloader 就被它钉住），停完之后同代内再用必须能重建。 */
    private val executorLock = Any()
    @Volatile private var single: java.util.concurrent.ExecutorService? = null

    private fun exec(): java.util.concurrent.ExecutorService {
        single?.let { return it }
        return synchronized(executorLock) {
            single ?: java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "NativeGateBridge").apply { isDaemon = true }
            }.also { single = it }
        }
    }

    /** 本进程内最近一次的**愿望**（去重，避免每次 sendSync 都碰文件） */
    @Volatile private var lastWish: Wish? = null

    /** 属性告警每进程只打一次 */
    @Volatile private var propWarned = false

    /** OS4 纯 native 桌面判定缓存（探测要读 APK 中央目录，只做一次） */
    @Volatile private var os4Probe: Boolean? = null

    private enum class Wish { OPEN, KILL, UNKNOWN }

    /**
     * 推一次总闸愿望（幂等）。**不重启任何宿主进程。**
     * 只在愿望变化时动作；"开"= 清理残留 marker，"关"= 写 marker，判不出 OS4 = 什么都不做。
     */
    fun push(prefs: SharedPreferences) {
        val wish = wish(prefs)
        if (wish == lastWish) return
        lastWish = wish
        if (wish == Wish.UNKNOWN) {
            HLog.i(TAG, "总闸不表态（判不出 OS4 纯 native 桌面 ⇒ 不写任何通道；native 侧缺省=开）")
            return
        }
        exec().execute {
            if (wish == Wish.KILL) writeMarker("0") else clearMarker()
            warnIfPropStuckOff()
        }
    }

    /**
     * API 102 热重载释放（旧代退休前调用；幂等）。
     *
     * 为什么必须：本 object 持有一条单线程执行器——它的 Runnable 是**旧代 lambda**，
     * 线程活着旧 classloader 就回收不了。停掉它并清 [lastWish] 去重快照，使释放后
     * 同代内再 push 真能动作一次（否则会以为"愿望没变"而跳过）。
     * 只释放线程，**不撤销已写的 marker**：marker 表达的是用户配置的愿望，不是资源。
     */
    fun releaseForReload() {
        runCatching { single?.shutdownNow() }
            .onFailure { HLog.w(TAG, "executor shutdown failed: ${it.message}") }
        single = null
        lastWish = null
        HLog.i(TAG, "releaseForReload: gate executor stopped (marker 保留)")
    }

    private fun wish(prefs: SharedPreferences): Wish {
        val enabled = runCatching { prefs.getBoolean(PrefKeys.ENABLED, true) }.getOrDefault(true)
        if (!isOs4NativeLauncher()) return Wish.UNKNOWN
        return if (enabled) Wish.OPEN else Wish.KILL
    }

    private fun markerFile(): File? = runCatching {
        val dir = File(
            Environment.getExternalStorageDirectory(),
            "Android/media/${BuildConfig.APPLICATION_ID}"
        )
        File(dir, MARKER_NAME)
    }.getOrNull()

    /**
     * 写关断 marker（只有 `"0"` 一种内容）。回读校验首字符——和属性一样，写失败是可能的
     * （目录不可写/没有外部存储），但失败了也只是"关不掉"，功能不受影响（native 缺省开）。
     */
    private fun writeMarker(value: String) {
        // ⚠ 2026-10-25 修：原来拿 HLog.proc()（短名，模块 App="app"）跟 applicationId 比，
        // 恒不等 ⇒ marker 静默写不进（10-14 落地以来一直如此，只因关断从未触发而没暴露）。
        if (HLog.proc() != HLog.PROC_APP) return
        val f = markerFile()
        if (f == null) {
            HLog.w(TAG, "gate marker 路径构造失败 ⇒ 关断通道不可用（本机没有外部存储？）")
            return
        }
        val ok = runCatching {
            f.parentFile?.mkdirs()
            f.writeText(value)
            f.readText().trim().firstOrNull() == '0'
        }.onFailure {
            HLog.w(
                TAG,
                "gate marker 写入失败（不影响功能：native 侧缺省=开）: " +
                    "${it.javaClass.simpleName}(${it.message})"
            )
        }.getOrDefault(false)
        if (ok) {
            HLog.i(TAG, "gate marker: ${f.absolutePath} ← \"$value\"")
        }
    }

    /**
     * 恢复"不表态"（= native 缺省开）：只删自己写的 marker；文件本来就不存在 ⇒ 零动作。
     * 这是"开"的唯一动作——**不写属性**（写 1 在 fail-open 下无意义，且真机上两条写路都被拒）。
     */
    private fun clearMarker() {
        if (HLog.proc() != HLog.PROC_APP) return
        val f = markerFile() ?: return
        if (!runCatching { f.isFile }.getOrDefault(false)) return
        val old = runCatching { f.readText().trim() }.getOrDefault("")
        val ok = runCatching { f.delete() }.getOrDefault(false)
        if (ok) {
            HLog.i(TAG, "gate marker 删除: ${f.absolutePath}（原内容 \"$old\"）⇒ 恢复\"缺省=开\"")
        } else {
            HLog.w(TAG, "gate marker 删除失败: ${f.absolutePath}（关断仍生效，需手工删除）")
        }
    }

    /**
     * 只读告警：属性被**手工/adb**置成 0 时，光删 marker 恢复不了（native 侧两路关断是并联的），
     * 必须有人把属性写回 1。这条只在愿望="开"时看一次，用来避免"我明明开着却不起作用"的错觉。
     * 不写属性、不用 su。
     */
    private fun warnIfPropStuckOff() {
        if (propWarned) return
        propWarned = true
        val v = runCatching {
            val p = ProcessBuilder("getprop", PROP_HOOK).start()
            val txt = p.inputStream.bufferedReader().use { it.readText() }.trim()
            p.waitFor(2, TimeUnit.SECONDS)
            txt
        }.getOrNull()
        if (v == "0") {
            HLog.w(
                TAG,
                "属性 $PROP_HOOK=0（手工/adb 遗留）⇒ 只删 marker 恢复不了；" +
                    "需 `adb shell su -c 'setprop $PROP_HOOK 1'` 后重启桌面"
            )
        }
    }

    /**
     * 本机是否是 OS4 纯 native 桌面：`MiuiHome.apk` 里带
     * `lib/arm64-v8a/libapp_launcher.so`（Rust 手势引擎，OS4 起特有）。
     *
     * 为什么用这个判据而不是"版本号/类名"：模块 App 进程里
     * `Class.forName("com.miui.sidebar.ISidebarOverlay")` 必然失败（该接口在
     * securitycenter/:ui 侧），版本号字符串又随机型漂移；而"桌面 APK 里有没有这个 so"
     * 与 native 侧真正要 hook 的目标是**同一个事实**，不依赖任何 hook 进程在场。
     *
     * 探测失败/无权限一律当 false ⇒ 调用方按"不表态"处理（**不会**误关总闸）。
     * 2026-10-14 晚：删掉了原来的 su 兜底（读桌面 `/proc/<pid>/maps`）——本桥现在**完全不用 su**，
     * 用户明确要求"关着 root 也能用"，而兜底失败时的不表态语义已经足够安全。
     */
    fun isOs4NativeLauncher(): Boolean {
        os4Probe?.let { return it }
        val hit = runCatching { probeOs4Launcher() }.getOrDefault(false)
        os4Probe = hit
        HLog.i(TAG, "OS4 native 桌面探测: $hit（MiuiHome.apk 内 lib/arm64-v8a/libapp_launcher.so）")
        return hit
    }

    private fun probeOs4Launcher(): Boolean {
        val dirs = listOf(
            "/product/priv-app/MiuiHome",
            "/system/priv-app/MiuiHome",
            "/system_ext/priv-app/MiuiHome",
            "/product/app/MiuiHome",
            "/system/app/MiuiHome",
        )
        for (d in dirs) {
            val dir = File(d)
            if (!dir.isDirectory) continue
            // 解压形态（部分 build 会把 lib 直接放到 apk 同级 lib/arm64）
            if (File(dir, "lib/arm64/libapp_launcher.so").exists()) return true
            val apks = dir.listFiles { f -> f.isFile && f.name.endsWith(".apk") } ?: continue
            for (apk in apks.sortedByDescending { it.length() }) {
                runCatching {
                    java.util.zip.ZipFile(apk).use { z ->
                        if (z.getEntry("lib/arm64-v8a/libapp_launcher.so") != null) return true
                    }
                }
            }
        }
        return false
    }
}
