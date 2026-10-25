package com.lsp.hypersidebar.util

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 配置文件通道是 Kotlin↔C 的镜像对：写侧（`ConfigFileBridge`，免 root 落应用专属媒体目录）
 * 与读侧（`hsb_config.c` 的 `kCfgPaths[]` 多候选）任何一边改了路径/文件名/短名前缀而另一边
 * 没跟上，都**不会编译失败**——只会"文件明明写了、native 读不到"（静默回退属性/缺省）。
 * 本用例用源码文本锁住三处契约：媒体目录全路径（applicationId 从 build.gradle.kts 派生，
 * 改包名时 C 侧字面量若没跟着改会在此失败）、文件名、短名前缀。
 */
class ConfigPathMirrorTest {

    private fun moduleFile(rel: String): File {
        val candidates = listOf(File(rel), File("app/$rel"))
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 $rel，试过：${candidates.joinToString { it.absolutePath }}")
    }

    @Test
    fun mediaPathMirroredBetweenWriterAndReader() {
        val c = moduleFile("src/main/cpp/hsb_config.c").readText()
        val kt = moduleFile("src/main/java/com/lsp/hypersidebar/util/ConfigFileBridge.kt").readText()

        // applicationId 真值来自构建脚本（C 里是硬编码字面量，包名漂移时这格会先红）
        val gradle = moduleFile("build.gradle.kts").readText()
        val pkg = Regex("""applicationId\s*=\s*"([^"]+)"""")
            .find(gradle)?.groupValues?.get(1)
            ?: throw AssertionError("build.gradle.kts 里没找到 applicationId")

        // 读侧首候选 = 写侧实际落的媒体目录全路径
        assertTrue(
            "hsb_config.c 首候选缺 \"$pkg/hsb.conf\" 媒体路径（写读两侧脱钩 ⇒ 文件白写）",
            c.contains("\"/storage/emulated/0/Android/media/$pkg/hsb.conf\"")
        )
        // 写侧用 BuildConfig.APPLICATION_ID 拼同一路径 + 同一文件名
        assertTrue(
            "ConfigFileBridge 不再写 Android/media/<applicationId> 路径？",
            kt.contains("Android/media/\${BuildConfig.APPLICATION_ID}")
        )
        assertTrue(
            "文件名 hsb.conf 两侧不一致",
            kt.contains("\"hsb.conf\"") && c.contains("hsb.conf")
        )

        // 短名前缀：Kotlin 剥的与 C 剥的必须是同一个字符串
        assertTrue(
            "短名前缀漂移：Kotlin 侧 removePrefix 与 C 侧 HSB_CFG_PREFIX 不再一致",
            kt.contains("removePrefix(\"persist.hsb.\")") &&
                c.contains("#define HSB_CFG_PREFIX \"persist.hsb.\"")
        )
    }
}
