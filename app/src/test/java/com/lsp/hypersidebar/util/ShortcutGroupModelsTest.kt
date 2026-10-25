package com.lsp.hypersidebar.util

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快捷组数据模型单测（计划 §B1 验收：序列化往返 / 脏数据迁移 / 归一化解散 /
 * 嵌套与工具箱拒绝 / 配额口径 / 预热名单提取）。
 */
class ShortcutGroupModelsTest {

    private fun act(id: String, pkg: String, enabled: Boolean = true) = ShortcutAction(
        id = id, kind = ShortcutKind.COMPONENT, label = id, packageName = pkg, enabled = enabled
    )

    private fun group(id: String, label: String, children: List<ShortcutAction>, enabled: Boolean = true) =
        ShortcutAction(
            id = id, kind = ShortcutKind.GROUP, label = label, enabled = enabled,
            iconPackageName = null, children = children
        )

    // ===== 序列化往返 =====

    @Test
    fun `group json roundtrip keeps children`() {
        val g = group("g1", "工作", listOf(act("a", "com.a"), act("b", "com.b")))
        val parsed = ShortcutAction.fromJson(g.toJson())
        assertEquals(g, parsed)
        assertEquals(2, parsed.children?.size)
    }

    @Test
    fun `legacy json without children parses flat`() {
        val json = """{"id":"x","kind":"COMPONENT","label":"X","order":0}"""
        val parsed = ShortcutAction.fromJson(org.json.JSONObject(json))
        assertEquals(ShortcutKind.COMPONENT, parsed.kind)
        assertNull(parsed.children)
    }

    @Test
    fun `dirty json garbage children does not crash`() {
        val json = """{"id":"g","kind":"GROUP","label":"G","children":"notarray"}"""
        val parsed = ShortcutAction.fromJson(org.json.JSONObject(json))
        assertNull(parsed.children)
        val nested = ShortcutAction.fromJson(
            org.json.JSONObject("""{"id":"g","kind":"GROUP","label":"G","children":[1,"s",{"id":"a","kind":"COMPONENT","label":"A"}]}""")
        )
        assertEquals(listOf("a"), nested.children?.map { it.id })
    }

    // ===== 校验 =====

    @Test
    fun `validateChildren boundaries`() {
        val two = listOf(act("a", "com.a"), act("b", "com.b"))
        val three = two + act("c", "com.c")
        assertTrue(ShortcutGroups.validateChildren(two))
        assertTrue(ShortcutGroups.validateChildren(three))
        // 1 个 / 4 个越界
        assertFalse(ShortcutGroups.validateChildren(two.subList(0, 1)))
        assertFalse(ShortcutGroups.validateChildren(three + act("d", "com.d")))
        // 嵌套组 / 工具箱 / 重复 id / 空 id
        assertFalse(ShortcutGroups.validateChildren(two + group("g", "G", two)))
        assertFalse(ShortcutGroups.validateChildren(two + act("t", "com.miui.securitycenter").copy(kind = ShortcutKind.TOOLBOX)))
        assertFalse(ShortcutGroups.validateChildren(listOf(act("a", "com.a"), act("a", "com.a2"))))
        assertFalse(ShortcutGroups.validateChildren(listOf(act("", "com.a"), act("b", "com.b"))))
    }

    // ===== 归一化 =====

    @Test
    fun `normalize dissolves undersized group and lifts children`() {
        val list = listOf(
            act("keep", "com.k"),
            group("g", "G", listOf(act("a", "com.a"))),  // 1 子 ⇒ 解散
            act("tail", "com.t")
        )
        val out = ShortcutGroups.normalize(list)
        assertEquals(listOf("keep", "a", "tail"), out.map { it.id })
    }

    @Test
    fun `normalize dissolves all-disabled group`() {
        val list = listOf(
            group("g", "G", listOf(act("a", "com.a", enabled = false), act("b", "com.b", enabled = false)))
        )
        val out = ShortcutGroups.normalize(list)
        assertEquals(listOf("a", "b"), out.map { it.id })
    }

    @Test
    fun `normalize strips nested group toolbox and dup ids`() {
        val nested = group(
            "g", "G",
            listOf(
                act("a", "com.a"),
                act("dup", "com.d"),
                act("dup", "com.d2"),
                group("inner", "I", listOf(act("x", "com.x"))),
                act("tb", "com.miui").copy(kind = ShortcutKind.TOOLBOX),
                act("", "com.blank")
            )
        )
        val out = ShortcutGroups.normalize(listOf(nested))
        // 剥除 GROUP/TOOLBOX/空 id + id 去重 ⇒ [a, dup] 仍 ≥2 ⇒ 组保留且 children 已清洗
        assertEquals(listOf("g"), out.map { it.id })
        assertEquals(listOf("a", "dup"), out[0].children?.map { it.id })
    }

    @Test
    fun `normalize keeps valid group and cleans its children`() {
        val g = group("g", "G", listOf(act("a", "com.a"), act("dup", "com.d"), act("dup", "com.d2")))
        val out = ShortcutGroups.normalize(listOf(g))
        assertEquals(1, out.size)
        assertEquals(ShortcutKind.GROUP, out[0].kind)
        assertEquals(listOf("a", "dup"), out[0].children?.map { it.id })
    }

    @Test
    fun `normalize passes flat items untouched`() {
        val flat = listOf(act("a", "com.a"), act("b", "com.b").copy(enabled = false))
        assertEquals(flat, ShortcutGroups.normalize(flat))
    }

    // ===== 配额 =====

    @Test
    fun `storageUnits counts group plus children`() {
        val list = listOf(
            act("a", "com.a"),
            group("g", "G", listOf(act("c1", "com.c1"), act("c2", "com.c2"), act("c3", "com.c3")))
        )
        // 1 单项 + 1 组条目 + 3 子项 = 5（子项计入配额，计划 §B1）
        assertEquals(5, ShortcutGroups.storageUnits(list))
        // 2 单项 + 两组×(1+3) = 9；再加 1 单项 = 10 用满（计划 §B1 算术）
        val twoGroups = list + group("g2", "G2", listOf(act("d1", "com.d1"), act("d2", "com.d2"), act("d3", "com.d3")))
        assertEquals(9, ShortcutGroups.storageUnits(twoGroups))
        assertEquals(10, ShortcutGroups.storageUnits(twoGroups + act("e1", "com.e1")))
    }
}
