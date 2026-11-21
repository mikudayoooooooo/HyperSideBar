package com.lsp.hypersidebar.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `NativeRootProbe.ORIGINAL_PROLOGUE` 必须与 `app/src/main/cpp/launcher_profiles.h` 里
 * **参考构建表**（8.01.02.8602，kHsbProfiles[0]）逐字节一致。
 *
 * ## 为什么必须有这个测试
 * 那张表是**人工镜像**（C 头文件由 tools/gen_launcher_profiles.py 生成，但 Java 侧
 * 没有 codegen 管线）。漂移的后果是**假阳性**：报告会拿错误的参照值去比对目标地址，
 * 把"hook 还在"报成"已被打回文件映射"——正好是最需要准确的那个结论。
 *
 * 2026-10-11 实际发生过一次：表里只有第 1 步那 3 个锚点，而当时门控挂的是
 * `is_in_bottom_gesture_region` ⇒ 报告打"该锚点无 prologue 参照"，等于门控锚点的
 * 存活无从判断。所以同步关系必须由测试守着，不能靠注释提醒。
 *
 * ## 2026-11-20 多 profile 表
 * header 从单表（kHsbAnchors[]）升级为 profile 索引（kHsbAnchors_N[] × 10 张构建
 * 表 + kHsbProfiles[]）。镜像语义随之收窄为：
 * - 参考构建表（真机验证过的 8.01.02.8602）与 ORIGINAL_PROLOGUE 键集/值全等；
 * - 每张表都必须含 handle_back_gesture（默认生产锚点），锚点名不得超出镜像集合
 *   （新增锚点必须同步 NativeRootProbe 才能让报告继续比对）；
 * - 其余构建的表只校验格式（16B prologue 参照长度），不与 8602 的字节比对——
 *   构建间偏移/序言漂移是常态（7755 有 17 条，92xx 起只有 15 条）。
 *
 * 测试从模块工作目录（= app/）向上找仓库根，再解析头文件；找不到就 fail（不静默跳过，
 * 否则这条保护会在换环境后悄悄失效）。
 */
class NativePrologueMirrorTest {

    private fun headerFile(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val f = File(dir, "app/src/main/cpp/launcher_profiles.h")
            if (f.isFile) return f
            dir = dir.parentFile
        }
        throw AssertionError(
            "找不到 app/src/main/cpp/launcher_profiles.h（工作目录=${File(".").absolutePath}" +
                "——本测试依赖仓库布局，不能在仓外跑"
        )
    }

    /** 单个表块内抽锚点 → 前 16 字节 prologue（小写 hex） */
    private fun entryPrologues(block: String): Map<String, String> {
        // 形如：{"name", 0x1234, 188, {0xff, 0x83, ...}, "symbol", ...}
        val re = Regex("""\{"(\w+)",\s*0x[0-9a-fA-F]+,\s*\d+,\s*\{([^}]*)}""")
        val out = LinkedHashMap<String, String>()
        for (m in re.findAll(block)) {
            val name = m.groupValues[1]
            val bytes = m.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            // 头文件存满 32B，Java 侧只比对前 16B（PROBE_BYTES）
            assertTrue("$name prologue 应满 32B，实际 ${bytes.size}", bytes.size >= 16)
            out[name] = bytes.take(16).joinToString("") { b ->
                val v = b.removePrefix("0x").toInt(16)
                "%02x".format(v)
            }
        }
        return out
    }

    /** 多 profile 头 → {构建标签 → 锚点表}；标签取数组块前的行注释（构建标签行） */
    private fun headerTables(): Map<String, Map<String, String>> {
        val text = headerFile().readText()
        assertTrue(
            "header 应为多 profile 索引格式（kHsbProfiles[]），见到单表格式说明生成模式用错了",
            text.contains("kHsbProfiles[]")
        )
        val blockRe = Regex(
            """(/\*\s*(?<label>[^*]+?)\s*\*/\s*)?static const HsbLauncherAnchor kHsbAnchors_\d+\[\] = \{(?<body>.*?)\n\};""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val out = LinkedHashMap<String, Map<String, String>>()
        for (m in blockRe.findAll(text)) {
            val label = m.groups["label"]?.value ?: "表#${out.size}（缺标签注释）"
            out[label] = entryPrologues(m.groups["body"]?.value ?: "")
        }
        assertTrue("头文件应解析出至少一张构建表", out.isNotEmpty())
        return out
    }

    @Test
    fun mirrorMatchesReferenceBuildExactly() {
        val mirror = NativeRootProbe.ORIGINAL_PROLOGUE
        val tables = headerTables()

        // 每张表：生产锚点必须在。其余锚点集合不做镜像子集断言——构建间 WANT 漂移是
        // 常态（7755/8584 比 8602 多 cancel_home_gesture_to_engine，92xx 起少两条探针），
        // 镜像只需钉死参考表；表里的额外锚点在 C 侧按名解析，无人消费即惰性。
        for ((label, table) in tables) {
            assertTrue("构建 $label 缺 handle_back_gesture（默认生产锚点）",
                "handle_back_gesture" in table)
        }

        // 参考构建表（8.01.02.8602）：与镜像键集、逐字节值全等
        val refLabel = tables.keys.firstOrNull { it.contains("8.01.02.8602") }
        assertNotNull(
            "参考构建表（8.01.02.8602）不在 header 里：labels=${tables.keys}。" +
                "多模式生成时必须把它排在第一个输入",
            refLabel
        )
        val ref = tables.getValue(refLabel!!)
        assertEquals(
            "参考表锚点名集合与镜像不一致：表独有=${ref.keys - mirror.keys}，" +
                "镜像独有=${mirror.keys - ref.keys}",
            mirror.keys.sorted(),
            ref.keys.sorted()
        )
        for ((name, hex) in ref) {
            assertEquals("锚点 $name 的 prologue 与参考表（8.01.02.8602）不一致", hex, mirror[name])
        }
    }

    @Test
    fun everyAnchorHasSixteenByteReference() {
        // PROBE_BYTES=16 ⇒ 每条参照都必须是 32 个 hex 字符，否则比对长度对不上
        for ((name, hex) in NativeRootProbe.ORIGINAL_PROLOGUE) {
            assertEquals("锚点 $name 的参照长度应为 32 个 hex 字符", 32, hex.length)
            assertTrue("锚点 $name 的参照应是小写 hex", hex.all { it in "0123456789abcdef" })
        }
    }
}
