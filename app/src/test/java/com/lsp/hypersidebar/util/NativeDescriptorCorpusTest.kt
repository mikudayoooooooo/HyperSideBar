package com.lsp.hypersidebar.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `anchor_descriptors.h`（形状描述符语料）必须与 `launcher_profiles.h`（偏移表）同步。
 *
 * ## 为什么必须有这个测试
 * 描述符不是独立数据——它是从**已有的 10 张偏移表**派生出来的。派生关系一旦断裂，
 * 后果是静默的：
 *  - 表里加了新构建（新形状），描述符没跟着加 ⇒ 那台设备只能走特征扫描，
 *    序言一漂就整族拒装（表现为"功能没了但日志干净"）；
 *  - 有人手改描述符去"修"某台设备 ⇒ 下次重新生成会把改动冲掉，而中间那段时间
 *    线上行为与表不一致。
 * 两者都不会让任何东西编译失败，所以只能用测试守。
 *
 * 生成器本身也支持 `--check`（同一判据），但那条路依赖 Python；这里用纯 JVM 复核
 * **产物是否覆盖了表里出现的每个形状**，这样 CI/无 Python 环境也能抓住漂移。
 */
class NativeDescriptorCorpusTest {

    private fun cppFile(name: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val f = File(dir, "app/src/main/cpp/$name")
            if (f.isFile) return f
            dir = dir.parentFile
        }
        throw AssertionError("找不到 app/src/main/cpp/$name（工作目录=${File(".").absolutePath}）")
    }

    /** 与 hsb_fn_word_mask / gen_anchor_descriptors.word_mask 逐条同源。 */
    private fun wordMask(word: Int): Int {
        val w = word.toLong() and 0xFFFFFFFFL
        val mask = when {
            (w and 0xFC000000L) == 0x94000000L -> 0xFC000000L
            (w and 0xFC000000L) == 0x14000000L -> 0xFC000000L
            (w and 0xFF000010L) == 0x54000000L -> 0xFF00001FL
            (w and 0x7E000000L) == 0x34000000L -> 0x7E00001FL
            (w and 0x7E000000L) == 0x36000000L -> 0x7E00001FL
            (w and 0x9F000000L) == 0x90000000L -> 0x9F00001FL
            (w and 0x9F000000L) == 0x10000000L -> 0x9F00001FL
            (w and 0x3B000000L) == 0x18000000L -> 0xC400001FL
            (w and 0x3B000000L) == 0x38000000L -> 0xFFC003FFL
            else -> 0xFFFFFFFFL
        }
        return mask.toInt()
    }

    private fun maskedTemplate(bytes: List<Int>): List<Int> =
        (0 until 32 step 4).map { k ->
            val w = bytes[k] or (bytes[k + 1] shl 8) or (bytes[k + 2] shl 16) or (bytes[k + 3] shl 24)
            w and wordMask(w)
        }

    /** launcher_profiles.h → {锚点名 → 该锚点在所有表里出现过的掩码模板集合}。 */
    private fun tableTemplates(): Map<String, Set<List<Int>>> {
        val text = cppFile("launcher_profiles.h").readText()
        val row = Regex("""\{"([a-z0-9_]+)",\s*0x[0-9a-f]+,\s*\d+,\s*\{([^}]*)}""")
        val out = LinkedHashMap<String, MutableSet<List<Int>>>()
        for (m in row.findAll(text)) {
            val bytes = m.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .map { it.removePrefix("0x").toInt(16) }
            if (bytes.size < 32) continue
            out.getOrPut(m.groupValues[1]) { LinkedHashSet() }.add(maskedTemplate(bytes))
        }
        return out
    }

    /** anchor_descriptors.h → {锚点名 → 掩码模板列表}。 */
    private fun descriptorTemplates(): Map<String, List<List<Int>>> {
        val text = cppFile("anchor_descriptors.h").readText()
        assertTrue(
            "anchor_descriptors.h 缺少 kAnchorDescriptors 索引——产物格式变了？",
            text.contains("kAnchorDescriptors[]")
        )
        val out = LinkedHashMap<String, List<List<Int>>>()
        // 形如：static const uint32_t kTemplates_x[] = { ... };
        val arr = Regex("""static const uint32_t kTemplates_([a-z0-9_]+)\[\] = \{(.*?)\};""",
            RegexOption.DOT_MATCHES_ALL)
        for (m in arr.findAll(text)) {
            val words = Regex("""0x([0-9a-f]{8})u""").findAll(m.groupValues[2])
                .map { it.groupValues[1].toLong(16).toInt() }.toList()
            assertTrue(
                "模板数组 ${m.groupValues[1]} 长度 ${words.size} 不是 8 的倍数",
                words.size % 8 == 0 && words.isNotEmpty()
            )
            out[m.groupValues[1]] = words.chunked(8)
        }
        return out
    }

    @Test
    fun everyTableTemplateIsCovered() {
        val tables = tableTemplates()
        val descriptors = descriptorTemplates()
        assertTrue("launcher_profiles.h 应解析出锚点", tables.isNotEmpty())
        assertTrue("anchor_descriptors.h 应解析出锚点", descriptors.isNotEmpty())

        // 描述符语料必须覆盖表里出现的**每一个**形状：漏一个就意味着那台设备
        // 只能靠特征扫描（序言一漂就拒装）。
        val missing = ArrayList<String>()
        for ((name, tmpls) in tables) {
            if (name == "action_probe") continue   // 故意不收，见生成器头注释
            val have = descriptors[name]?.toSet() ?: emptySet()
            for (t in tmpls) {
                if (t !in have) missing += "$name:$t"
            }
        }
        assertEquals(
            "anchor_descriptors.h 缺模板（表里有、描述符没有）⇒ 重跑 " +
                "python tools/gen_anchor_descriptors.py。缺失=$missing",
            emptyList<String>(),
            missing
        )

        // 反向：描述符里的每个模板都必须真在表里（防手改/防生成器读到旧表）。
        val extra = ArrayList<String>()
        for ((name, tmpls) in descriptors) {
            if (name == "action_probe") continue
            val have = tables[name] ?: emptyMap<String, Set<List<Int>>>().let {
                emptySet<List<Int>>()
            }
            for (t in tmpls) {
                if (t !in have) extra += "$name:$t"
            }
        }
        assertEquals(
            "anchor_descriptors.h 有 launcher_profiles.h 里不存在的模板（手改或生成器漂移）=$extra",
            emptyList<String>(),
            extra
        )
    }

    @Test
    fun actionProbeIsDeliberatelyExcluded() {
        // action_probe 的桩会改系统判定（hsb_stub_actionprobe），而它的序言在实测
        // 构建里有 85-88 处命中 ⇒ 形状枚举必然歧义。它只能走偏移表。
        val descriptors = descriptorTemplates()
        assertTrue(
            "action_probe 不得出现在描述符语料里：形状枚举对它必然歧义，猜错会改系统手势判定",
            "action_probe" !in descriptors
        )
        assertTrue(
            "action_probe 必须仍在偏移表里（它只走偏移路）",
            "action_probe" in tableTemplates()
        )
    }

    @Test
    fun sizeCorpusIsNonEmptyForEveryAnchor() {
        val text = cppFile("anchor_descriptors.h").readText()
        val arr = Regex("""static const uint32_t kSizes_([a-z0-9_]+)\[\] = \{(.*?)\};""",
            RegexOption.DOT_MATCHES_ALL)
        val sizes = arr.findAll(text).associate { m ->
            m.groupValues[1] to Regex("""(\d+)u""").findAll(m.groupValues[2])
                .map { it.groupValues[1].toInt() }.toList()
        }
        val descriptors = descriptorTemplates().keys
        assertTrue("描述符应有尺寸数组", sizes.isNotEmpty())
        for (name in descriptors) {
            assertTrue("锚点 $name 缺尺寸数组", name in sizes)
            assertTrue(
                "锚点 $name 的尺寸集合为空：长度判据失效会让多命中锚点无法收敛",
                sizes.getValue(name).isNotEmpty()
            )
        }
    }

    @Test
    fun descriptorCountMatchesIndex() {
        val text = cppFile("anchor_descriptors.h").readText()
        val m = Regex("""#define\s+HSB_DESCRIPTOR_COUNT\s+(\d+)u""").find(text)
        assertTrue("缺少 HSB_DESCRIPTOR_COUNT", m != null)
        val declared = m!!.groupValues[1].toInt()
        val actual = Regex("""^\s*\{"[a-z0-9_]+",\s*kTemplates_""", RegexOption.MULTILINE)
            .findAll(text).count()
        assertEquals(
            "HSB_DESCRIPTOR_COUNT 与 kAnchorDescriptors 行数不一致（C 侧会越界/漏读）",
            declared,
            actual
        )
    }

    /**
     * 审计必须在**任何 hook 装上之前**读参照。
     *
     * 2026-11-21 真机踩过（marble / 9261 / 模块 2026112102）：审计原本放在 `hooks ready`
     * 之后，拿偏移表当参照去比 32B 序言——而那时 inline hook 已经改写目标处字节，
     * 读到的是 trampoline 首字（日志实测 `got=51470fb0 want=eb2bb86d`）⇒ 16 个锚点全部
     * 假阴性 `noref=16`，看起来像"形状路完全不可用"，实际是审计自身时序错。
     *
     * 修法是把 `hsb_desc_audit_all(...)` 移到 `hsb_install_named(...)` 之前。这条断言守
     * 住顺序：源码里 `hsb_desc_audit_all(` 的调用必须出现在 `hsb_install_named(` 之前。
     */
    @Test
    fun auditRunsBeforeAnyHookInstall() {
        val src = cppFile("hypersidebar_native.c").readText()
        val auditCall = src.indexOf("hsb_desc_audit_all(g_cands[0])")
        assertTrue(
            "找不到 hsb_desc_audit_all(g_cands[0]) 调用点——审计如果被删/改名，这条顺序保证就没了",
            auditCall > 0
        )
        val installCall = src.indexOf("hsb_install_named(want, 0)")
        assertTrue("找不到 hsb_install_named(want, 0) 主锚点安装点", installCall > 0)
        assertTrue(
            "hsb_desc_audit_all 必须在 hsb_install_named 之前调用：" +
                "hook 装上后序言已被改写，此后拿偏移表当参照会读到 trampoline 首字而全部假阴性" +
                "（2026-11-21 真机实测 noref=16）。audit@$auditCall install@$installCall",
            auditCall < installCall
        )
    }

    /**
     * 形状路绝不能"猜"：弃权（0 命中 / 多命中未收敛）必须返回 0 而不是取第一处命中。
     * 实测 10 个构建 0 误判全靠这条；一旦改成"取第一个"，错误地址上装 inline hook
     * 轻则功能失效、重则崩桌面（红线第 11 条）。
     */
    @Test
    fun descriptorPathNeverPicksFirstHit() {
        val src = cppFile("hypersidebar_native.c").readText()
        val start = src.indexOf("static uintptr_t hsb_descriptor_locate(")
        assertTrue("找不到 hsb_descriptor_locate", start > 0)
        val body = src.substring(start, src.indexOf("\n}", start))
        assertTrue(
            "hsb_descriptor_locate 里出现 'p->first' 直接返回：单命中才允许用 first，" +
                "多命中后必须有长度判据收敛，不得取第一处",
            body.contains("p->hits == 1u") || body.contains("hits == 1u")
        )
        assertTrue(
            "多命中未收敛时必须 return 0（弃权），找不到该分支",
            body.contains("形状歧义") && body.contains("return 0u;")
        )
    }
}
