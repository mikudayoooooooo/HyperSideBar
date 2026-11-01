package com.lsp.hypersidebar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 添加路径的容量裁决（2026-10-28 审查批次 A1 的纯逻辑钉子）。
 *
 * 修的是两个真 bug：①添加不查启用上限，已有 5 个启用项时能产出第 6 个；
 * ②存储满时 `addShortcut` 返回 false 但调用方丢弃返回值 ⇒ 用户填完一整轮什么都没存下。
 * 裁决被抽成纯函数就是为了能在 JVM 上钉住这两条。
 */
class ShortcutStoreAddDecisionTest {

    private val candidate = ShortcutAction(
        id = "new", kind = ShortcutKind.COMPONENT, label = "新项",
        packageName = "com.example", enabled = true
    )

    @Test
    fun `normal add saves as enabled`() {
        val d = ShortcutStore.decideAdd(candidate, enabledCount = 2, storageUnits = 3)
        assertTrue(d is ShortcutStore.AddDecision.Save)
        d as ShortcutStore.AddDecision.Save
        assertFalse(d.disabledByCap)
        assertTrue(d.item.enabled)
        assertEquals("new", d.item.id)
    }

    @Test
    fun `reaching enabled cap degrades to disabled instead of rejecting`() {
        val d = ShortcutStore.decideAdd(
            candidate,
            enabledCount = ShortcutStore.ENABLED_CAP,
            storageUnits = 2
        )
        assertTrue(d is ShortcutStore.AddDecision.Save)
        d as ShortcutStore.AddDecision.Save
        assertTrue("超启用上限应降级保存", d.disabledByCap)
        assertFalse("降级后必须是未启用，否则仍是第 6 个启用项", d.item.enabled)
    }

    @Test
    fun `already disabled candidate stays disabled and is not flagged`() {
        val d = ShortcutStore.decideAdd(
            candidate.copy(enabled = false),
            enabledCount = ShortcutStore.ENABLED_CAP,
            storageUnits = 0
        )
        d as ShortcutStore.AddDecision.Save
        assertFalse(d.disabledByCap)
        assertFalse(d.item.enabled)
    }

    @Test
    fun `storage cap rejects so caller can keep the draft`() {
        val d = ShortcutStore.decideAdd(
            candidate,
            enabledCount = 0,
            storageUnits = ShortcutStore.MAX_STORED_SHORTCUTS
        )
        assertEquals(ShortcutStore.AddDecision.RejectedStorageFull, d)
    }
}
