package com.lsp.hypersidebar.anchor

import android.os.Build
import com.lsp.hypersidebar.BuildConfig
import com.lsp.hypersidebar.util.HLog
import io.github.kyuubiran.ezxhelper.xposed.EzXposed
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * DexKit 的 `.so` 加载器（结构化锚点解析 L2 的前置件）。
 *
 * **为什么必须自己加载**：DexKit 的 AAR 内**没有任何 `System.loadLibrary`**（实测 2.3.0：全部 class
 * 里都不引用 `java/lang/System`），并且 LSPosed 把模块注入宿主进程时，模块 APK 的 `lib/` 不在宿主的
 * native 搜索路径里 ⇒ 必须由我们把 `lib/arm64-v8a/libdexkit.so` 从模块 APK 抽到宿主 cacheDir，
 * 再按**绝对路径** `System.load`。
 *
 * 这也是 README「第三方组件与许可」里承诺的"用户可用同 ABI 的 libdexkit.so 覆盖该文件来替换此库"
 * 所依赖的机制（LGPL-3.0 §4 的可替换性要求）；因此**不得**对该文件做签名或完整性校验。
 *
 * **失败一律闭锁**：`ensureLoaded` 返回 false ⇒ 调用方放弃 L2，退回 L0/L1；不抛异常、不影响宿主。
 */
internal object NativeLibLoader {

    private const val TAG = "AnchorNative"
    private const val ABI = "arm64-v8a"
    private const val APK_ENTRY = "lib/$ABI/libdexkit.so"

    /** 用户替换件（LGPL-3.0 §4 可替换性入口）：放入宿主 cacheDir 即优先加载，不做任何校验 */
    private const val OVERRIDE_NAME = "hypersidebar_libdexkit_override_$ABI.so"

    enum class State {
        NOT_TRIED,
        LOADED_OVERRIDE,
        LOADED_APK,
        LOADED_EXTRACTED,
        UNSUPPORTED_ABI,
        NO_MODULE_PATH,
        NO_CACHE_DIR,
        EXTRACT_FAILED,
        LOAD_FAILED,
        ;

        val isLoaded: Boolean
            get() = this == LOADED_OVERRIDE || this == LOADED_APK || this == LOADED_EXTRACTED
    }

    @Volatile
    private var state = State.NOT_TRIED

    @Volatile
    var detail: String = ""
        private set

    /** 自检报告用的一行状态 */
    val statusLine: String
        get() = "native=${state.name}" + if (detail.isEmpty()) "" else "($detail)"

    /** 幂等；成功返回 true。任何异常都被吞掉并记入 [statusLine]。 */
    fun ensureLoaded(cacheDir: File?): Boolean {
        if (state.isLoaded) return true
        synchronized(this) {
            if (state.isLoaded) return true
            if (!Build.SUPPORTED_ABIS.contains(ABI)) {
                return fail(State.UNSUPPORTED_ABI, Build.SUPPORTED_ABIS.joinToString())
            }
            val apk = runCatching { EzXposed.modulePath }.getOrNull()
            if (apk.isNullOrEmpty()) {
                return fail(State.NO_MODULE_PATH, "EzXposed.modulePath 未初始化")
            }
            val notes = StringBuilder()

            // 1) 用户替换件优先（存在才试；加载失败则继续走标准路径）
            if (cacheDir != null) {
                val override = File(cacheDir, OVERRIDE_NAME)
                if (override.isFile) {
                    val err = runCatching { System.load(override.absolutePath) }.exceptionOrNull()
                    if (err == null) {
                        state = State.LOADED_OVERRIDE
                        detail = override.name
                        HLog.i(TAG, "libdexkit loaded from user override: ${override.absolutePath}")
                        return true
                    }
                    HLog.w(TAG, "user override rejected: ${brief(err)}")
                    notes.append("override: ").append(brief(err)).append("; ")
                }
            }

            // 2) 直接从模块 APK：条目 STORED + 页对齐 ⇒ linker 可 mmap，不需要解包，
            //    也不受宿主域对 cacheDir 文件的执行限制（securitycenter:ui 的解包版会被拒）
            val apkErr = runCatching { System.load("$apk!/$APK_ENTRY") }.exceptionOrNull()
            if (apkErr == null) {
                state = State.LOADED_APK
                detail = "apk"
                HLog.i(TAG, "libdexkit loaded from module apk: $apk!/$APK_ENTRY")
                return true
            }
            notes.append("apk: ").append(brief(apkErr)).append("; ")

            // 3) 兜底：抽到宿主 cacheDir 再 System.load(绝对路径)
            val dir = cacheDir ?: return fail(State.NO_CACHE_DIR, notes.toString())
            val target = File(dir, "hypersidebar_libdexkit_${BuildConfig.VERSION_CODE}_$ABI.so")
            return try {
                extract(apk, target)
                System.load(target.absolutePath)
                state = State.LOADED_EXTRACTED
                detail = target.name
                HLog.i(TAG, "libdexkit loaded from extracted file: ${target.absolutePath}")
                true
            } catch (t: Throwable) {
                notes.append("extract: ").append(brief(t))
                target.delete()
                fail(if (target.exists()) State.LOAD_FAILED else State.EXTRACT_FAILED, notes.toString())
            }
        }
    }

    private fun brief(t: Throwable): String = (t.message ?: t.javaClass.simpleName).take(200)

    private fun fail(s: State, why: String): Boolean {
        state = s
        detail = why
        HLog.w(TAG, "libdexkit unavailable: ${s.name} ($why) -> L2 disabled, L0/L1 only")
        return false
    }

    /**
     * 抽到 `.tmp` 再改名，避免半截文件被后续进程误用；已存在且大小一致则直接复用
     * （同名规则 = 模块 versionCode + ABI，覆盖安装后自动失效重抽）。
     */
    private fun extract(apk: String, target: File) {
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry(APK_ENTRY) ?: error("entry not found: $APK_ENTRY")
            if (target.isFile && target.length() == entry.size) return
            val tmp = File(target.parentFile, target.name + ".tmp")
            zip.getInputStream(entry).use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
            }
            if (tmp.length() != entry.size) {
                val got = tmp.length()
                tmp.delete()
                error("size mismatch: $got != ${entry.size}")
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    error("rename failed for ${target.name}")
                }
            }
        }
    }
}