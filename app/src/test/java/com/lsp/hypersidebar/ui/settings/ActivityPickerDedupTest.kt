package com.lsp.hypersidebar.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 组件选择器同名条目合并（2026-11-20 真机闪退修复的回归锚）：
 * activity-alias 可与其实体 activity 同 name ⇒ `GET_ACTIVITIES` 扫出两条同
 * className 条目 ⇒ 组件列表 LazyColumn 的 key（className + #a/#s）重复 ⇒
 * 快速滑动时两条同键行同帧组合，IllegalArgumentException 闪退
 * （实锤：marble `adb logcat -b crash`，Key "…PowerKeyGuideActivityV2#a" was already used）。
 */
class ActivityPickerDedupTest {

    private fun info(
        className: String,
        exported: Boolean = false,
        isService: Boolean = false,
        label: String = "",
        isShortcutTarget: Boolean = false,
        isQsTile: Boolean = false,
    ) = ComponentInfo(
        packageName = "pkg",
        className = className,
        label = label,
        appLabel = "app",
        exported = exported,
        isService = isService,
        isShortcutTarget = isShortcutTarget,
        isQsTile = isQsTile,
    )

    @Test
    fun `same-name activity and alias merge into one row`() {
        val merged = dedupeComponents(
            listOf(
                info("com.xiaomi.voiceassistant.powerkey.PowerKeyGuideActivityV2",
                    exported = false, label = "实体"),
                info("com.xiaomi.voiceassistant.powerkey.PowerKeyGuideActivityV2",
                    exported = true, label = "别名"),
            )
        )
        assertEquals("同 name 两条必须合并成一条，否则 LazyColumn 键重复必崩", 1, merged.size)
        assertTrue("alias 可导出 ⇒ 合并后按可导出处理（Default 启动路依赖 exported）",
            merged[0].exported)
        assertEquals("label 取首个非空", "实体", merged[0].label)
    }

    @Test
    fun `merge takes OR of shortcut and qs flags`() {
        val merged = dedupeComponents(
            listOf(
                info("A"),
                info("A", isShortcutTarget = true),
                info("A", isQsTile = true),
            )
        )
        assertEquals(1, merged.size)
        assertTrue("快捷标志取或", merged[0].isShortcutTarget)
        assertTrue("QS 标志取或", merged[0].isQsTile)
        assertFalse("未导出条目不会被同类导出条目带偏", merged[0].exported)
    }

    @Test
    fun `same-name activity and service are not merged`() {
        val merged = dedupeComponents(
            listOf(info("Same"), info("Same", isService = true))
        )
        assertEquals("跨 kind 键本有 #a/#s 后缀，且同一类不可能既是 activity 又是 service", 2, merged.size)
    }

    @Test
    fun `no duplicates returns identical list`() {
        val src = listOf(info("A"), info("B"), info("C"))
        assertEquals("无重复时逐条原样返回", src, dedupeComponents(src))
    }

    @Test
    fun `merge keeps first-seen position`() {
        val merged = dedupeComponents(
            listOf(info("A"), info("B"), info("A", exported = true), info("C"))
        )
        assertEquals(
            "重复项在首现位置原地合并，不打乱排序输入的相对顺序",
            listOf("A", "B", "C"),
            merged.map { it.className },
        )
        assertTrue("合并结果落在首现位置上", merged[0].exported)
        assertEquals("后续列表键仍唯一（键=className+kind）", 3, merged.size)
    }
}
