package com.lsp.hypersidebar.util

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 特征扫描的「多命中消歧」是**安全边界**，不能退回两个极端：
 *  - 退回"第二处命中就早停"（2026-11-07 之前）：OS4.0.6 的 launcher 8.01.02.8602 上
 *    `handle_back_gesture` 位移且序言出现 3 份 ⇒ 一律拒装 ⇒ 扇形呼不出来（静默）；
 *  - 退回"取第一处命中"：同序言的不同函数被当成锚点 ⇒ 在错误的地址上装 inline hook，
 *    轻则功能失效、重则崩桌面。
 *
 * 正确口径：**收全部命中**（有上限），再用 required_imports 的结构证据消歧，
 * 只有"恰好一条通过"才采纳；命中表被截断时也必须拒绝（否则"唯一"是假的）。
 *
 * 这些条件写在 C 里，删掉任何一条都不会编译失败——所以用源码文本守住，和
 * `NativeArrowfixInvariantTest` 同一套做法。
 */
class NativeSigscanDisambiguationTest {

    private fun nativeSource(): File {
        val candidates = listOf(
            File("src/main/cpp/hypersidebar_native.c"),        // AGP 单测工作目录 = 模块目录
            File("app/src/main/cpp/hypersidebar_native.c")     // 从仓库根跑时的兜底
        )
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 native 源码，试过：${candidates.joinToString { it.absolutePath }}")
    }

    @Test
    fun collectsAllHitsWithCap() {
        val src = nativeSource().readText()
        assertTrue(
            "缺少 HSB_MAX_SIG_HITS 上限（收全部命中必须封顶，否则恶意/异常映像会拖爆循环）",
            Regex("""#define\s+HSB_MAX_SIG_HITS\s+(\d+)u?""").find(src) != null
        )
        val cap = Regex("""#define\s+HSB_MAX_SIG_HITS\s+(\d+)u?""").find(src)!!
            .groupValues[1].toInt()
        assertTrue("HSB_MAX_SIG_HITS=$cap 太小：同序言重复在真机上是 3 处起", cap >= 3)
        assertTrue(
            "缺少收集型扫描 hsb_sigscan_collect（多命中消歧的前提）",
            src.contains("hsb_sigscan_collect")
        )
        // 旧的"见第二处即早停"必须真的消失——留着就等于消歧路径永远拿不到多命中
        assertTrue(
            "仍存在早停写法（hits > 1 即 return 0）⇒ 消歧成了死代码",
            !src.contains("if (hits > 1u) { *hits_out = hits; return 0u; }")
        )
    }

    @Test
    fun refusesGenericPrologues() {
        val src = nativeSource().readText()
        // 序言越泛，"恰好一条通过结构校验"越可能只是运气（实测 action_probe 序言在新构建里有 88 处）
        assertTrue(
            "缺少 HSB_MAX_DISAMBIG_HITS 上限：泛序言必须直接拒，不得尝试消歧",
            src.contains("HSB_MAX_DISAMBIG_HITS")
        )
        assertTrue(
            "多命中且无结构判据时必须拒绝（hits > 1u && (!has_struct || hits > HSB_MAX_DISAMBIG_HITS)）",
            Regex("""hits\s*>\s*1u\s*&&\s*\(!has_struct\s*\|\|\s*hits\s*>\s*HSB_MAX_DISAMBIG_HITS\)""")
                .containsMatchIn(src)
        )
    }

    @Test
    fun acceptsOnlyUniqueStructuralMatch() {
        val src = nativeSource().readText()
        assertTrue(
            "采纳条件必须是「恰好一条通过结构校验」(pass == 1u)，否则可能选中同序言的错误函数",
            Regex("""pass\s*==\s*1u""").containsMatchIn(src)
        )
        assertTrue(
            "缺少截断保护 hits <= usable：命中表被截断时'唯一'不成立，必须拒绝",
            Regex("""hits\s*<=\s*usable""").containsMatchIn(src)
        )
        assertTrue(
            "消歧必须走 required_imports 的结构判据（hsb_import_ok_any_base）",
            src.contains("hsb_import_ok_any_base")
        )
        assertTrue(
            "无结构判据（required_imports == NULL）时不得采纳多命中",
            src.contains("if (a->required_imports == NULL) return 0;")
        )
    }
}
