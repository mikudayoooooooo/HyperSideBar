package com.lsp.hypersidebar.prefs

import com.lsp.hypersidebar.hook.GestureThresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扇形背景来源与呼出阈值量程的契约单测（2026-10-25 走查批次，2026-10-31 更新）。
 *
 * 守两条**跨进程**约定，两处都是"改一处忘另一处"就会在真机上静默失效的地方：
 * - 背景来源：`LayoutDefaults.FAN_BLUR_SOURCE_VALUES` 的顺序就是设置页下拉的顺序，
 *   而 `SettingsRepository.fanBlurSource()` 用 `it in VALUES` 过滤旧值 —— 表里多/少一项
 *   都不报错，只会让下拉显示与实际生效档位错位，或让某个旧值永久落回默认。
 * - 呼出阈值：滑动距离滑条已删（2026-10-31），确认线由 `GestureThresholds.SWIPE_CONFIRM_DP`
 *   单源定义；停顿量程 `TRIGGER_DWELL_MIN_MS..MAX_MS` 必须与 native 侧
 *   `hsb_strip_dwell_ms()` 的钳位（150..350）一致，否则两个判官跑不同档。
 */
class BackgroundAndSwipeContractTest {

    @Test
    fun blurSourceValuesAreTheThreeShippedTiers() {
        // 顺序即设置页下拉顺序（亚克力/采样壁纸/透明）；auto/behind/off 已撤档
        assertEquals(
            listOf("acrylic", "wallpaper", "transparent"),
            LayoutDefaults.FAN_BLUR_SOURCE_VALUES
        )
    }

    @Test
    fun blurSourceDefaultIsAcrylicAndIsInValues() {
        assertEquals("acrylic", LayoutDefaults.FAN_BLUR_SOURCE_DEFAULT)
        // 默认值必须自身是合法档：否则「恢复默认」写进去的值会被 fanBlurSource() 再过滤一次
        assertTrue(LayoutDefaults.FAN_BLUR_SOURCE_DEFAULT in LayoutDefaults.FAN_BLUR_SOURCE_VALUES)
    }

    @Test
    fun swipeConfirmDistanceIsSingleSourceAndPositive() {
        // 固定 15dp（旧默认值，手感不变）；0 会让手势一抖就呼出
        assertEquals(15f, GestureThresholds.SWIPE_CONFIRM_DP, 0f)
        assertTrue(GestureThresholds.SWIPE_CONFIRM_DP > 0f)
        // 滞回：重置线必须低于确认线，否则近阈值反复清零（0913 取证）
        assertTrue(GestureThresholds.SWIPE_RESET_RATIO in 0f..1f)
    }

    @Test
    fun dwellRangeMatchesNativeClamp() {
        // native: hsb_strip_dwell_ms() 钳 150..350，回退 250
        assertEquals(150, LayoutDefaults.TRIGGER_DWELL_MIN_MS)
        assertEquals(350, LayoutDefaults.TRIGGER_DWELL_MAX_MS)
        assertEquals(250, LayoutDefaults.TRIGGER_DWELL_MS)
        assertTrue(LayoutDefaults.TRIGGER_DWELL_MS in
            LayoutDefaults.TRIGGER_DWELL_MIN_MS..LayoutDefaults.TRIGGER_DWELL_MAX_MS)
        // 默认值须落在步进格点上，否则滑条一碰就跳到相邻档
        val step = LayoutDefaults.TRIGGER_DWELL_STEP_MS
        val steps = (LayoutDefaults.TRIGGER_DWELL_MS - LayoutDefaults.TRIGGER_DWELL_MIN_MS).toFloat() / step
        assertEquals("默认值须落在步进格点上：$steps 格不是整数", steps, Math.round(steps).toFloat(), 0f)
    }
}
