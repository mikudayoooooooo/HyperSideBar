package com.lsp.hypersidebar.ui.settings

import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.prefs.SettingsRepository
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.util.RemotePrefsBridge
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * **操作设置**二级页（2026-10-17 由"呼出设置"改名并并入小窗段；0913 用户拍板建页，首页交互区入口进入）。
 *
 * 本页 = 一切的"操作"开关：呼出类（呼出停顿 / 呼出后常驻）+ 小窗类
 * （点击空白处缩小/关闭 + 动作档）。2026-10-17 用户要求把小窗设置并进来，避免二级页碎片化。
 * 2026-10-31 删掉"滑动距离"滑条：固定为 GestureThresholds.SWIPE_CONFIRM_DP（15dp，= 旧默认值），
 * 底角通道由角锥的 CORNER_INWARD_MIN_DP 隐含——两条通道本来就不该共用一个滑条。
 *
 * - 呼出停顿 = 锚点圆 dwell（PrefKeys.TRIGGER_DWELL_MS，ms）：停顿判定逐手势现读，即时生效
 *
 * 滑条同款"拖动暂存、松手落盘（=一次 ConfigSync 广播）"节奏；revision 通道回读外部改动
 *（一键恢复默认后返回本页即刷新）。
 */
@Composable
internal fun InvokeSettingsPage(prefs: SharedPreferences, modifier: Modifier = Modifier) {
    // D1 同款：绑定晚到时参数 prefs 是本地空壳，effectivePrefs 切换强制整页重读
    val effectivePrefs = RemotePrefsBridge.prefs ?: prefs
    val repo = remember(effectivePrefs) { SettingsRepository(effectivePrefs) }
    DisposableEffect(repo) { onDispose { repo.dispose() } }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .settingsScroll(),
        contentPadding = PaddingValues(
            start = SettingsDimens.ScreenInset,
            end = SettingsDimens.ScreenInset,
            top = SettingsDimens.ListTop,
            bottom = SettingsDimens.ListBottom
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    // 量程/步进契约取自 LayoutDefaults（禁止内联），刻度数与吸附由辅助函数推导
                    val dwellMin = LayoutDefaults.TRIGGER_DWELL_MIN_MS.toFloat()
                    val dwellMax = LayoutDefaults.TRIGGER_DWELL_MAX_MS.toFloat()
                    val dwellStep = LayoutDefaults.TRIGGER_DWELL_STEP_MS.toFloat()
                    // 呼出距离不再可调（2026-10-31 用户走查："调到最大也没什么区别"）：
                    // 边缘通道固定 GestureThresholds.SWIPE_CONFIRM_DP，底角通道由角锥
                    // CORNER_INWARD_MIN_DP 隐含——两条通道本来就不该共用一个滑条。
                    var dwellMs by remember(effectivePrefs, repo.revision) {
                        mutableStateOf(repo.triggerDwellMs())
                    }
                    SliderPreference(
                        title = stringResource(R.string.trigger_dwell_title),
                        valueText = "$dwellMs ms",
                        value = dwellMs.toFloat(),
                        valueRange = dwellMin..dwellMax,
                        steps = sliderSteps(dwellMin, dwellMax, dwellStep),
                        onValueChange = {
                            dwellMs = quantizeToStep(it, dwellMin, dwellMax, dwellStep).roundToInt()
                        },
                        onValueChangeFinished = {
                            repo.save(PrefKeys.TRIGGER_DWELL_MS, dwellMs)
                        }
                    )
                    var persistent by remember(effectivePrefs, repo.revision) {
                        mutableStateOf(repo.fanPersistentOnRelease())
                    }
                    SwitchPreference(
                        title = stringResource(R.string.fan_persistent_title),
                        checked = persistent,
                        onCheckedChange = {
                            persistent = it
                            repo.save(PrefKeys.FAN_PERSISTENT_ON_RELEASE, it)
                        }
                    )
                    // （2026-10-13 清理，用户 m03028）本页原有三项已删除：
                    //   ①「边缘停顿借用系统判定」开关（p7 现一律放行给原生，开关失去意义）
                    //   ②「全边缘触发·实验」总开关（B 路线在 OS4 恒开，spy 转发是竖屏唯一驱动源）
                    //   ③ 触发带三滑条（高度/位置/宽度）——几何收敛为 LayoutDefaults 默认参数
                }
            }
        }
        item { SectionTitle(text = stringResource(R.string.freeform_section)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    var enabled by remember(effectivePrefs, repo.revision) {
                        mutableStateOf(repo.freeformBlankTapEnabled())
                    }
                    SwitchPreference(
                        title = stringResource(R.string.freeform_blank_tap_title),
                        summary = stringResource(R.string.freeform_blank_tap_summary),
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            repo.save(PrefKeys.FREEFORM_BLANK_TAP_ENABLED, it)
                        }
                    )
                    // 与总开关联动（2026-10-18 用户口径）：总开关关 ⇒ 子项没有意义，不显示。
                    if (enabled) {
                        var takeover by remember(effectivePrefs, repo.revision) {
                            mutableStateOf(repo.freeformTakeoverEnabled())
                        }
                        // 子项左缩进：与父项拉开一级（2026-11-03 层级修正）
                        SwitchPreference(
                            title = stringResource(R.string.freeform_takeover_title),
                            modifier = Modifier.padding(start = 12.dp),
                            checked = takeover,
                            onCheckedChange = {
                                takeover = it
                                repo.save(PrefKeys.FREEFORM_BLANK_TAP_TAKEOVER, it)
                            }
                        )
                        // 动作档：取值顺序 = 契约 LayoutDefaults.FREEFORM_BLANK_TAP_MODE_VALUES，
                        // 下标映射不得内联（与「背景模糊来源」同款约束）
                        val modeValues = LayoutDefaults.FREEFORM_BLANK_TAP_MODE_VALUES
                        // ⚠ options 与 FREEFORM_BLANK_TAP_MODE_VALUES **按下标一一对应**
                        //（2026-10-18 曾漏第三项 ⇒ 图标档在下拉里看不见）
                        val modeOptions = listOf(
                            stringResource(R.string.freeform_mode_mini),
                            stringResource(R.string.freeform_mode_pin),
                            stringResource(R.string.freeform_mode_close)
                        )
                        var mode by remember(effectivePrefs, repo.revision) {
                            mutableStateOf(repo.freeformBlankTapMode())
                        }
                        val modeIndex = modeValues.indexOf(mode).coerceAtLeast(0)
                        OverlayDropdownMenu(
                            title = stringResource(R.string.freeform_mode_title),
                            modifier = Modifier.padding(start = 12.dp),
                            options = modeOptions,
                            selectedIndex = modeIndex,
                            onSelectedIndexChange = { index ->
                                val value = modeValues[index]
                                mode = value
                                repo.save(PrefKeys.FREEFORM_BLANK_TAP_MODE, value)
                            }
                        )
                    }
                }
            }
        }
    }
}
