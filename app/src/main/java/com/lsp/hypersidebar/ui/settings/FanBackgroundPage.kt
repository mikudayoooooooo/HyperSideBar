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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.util.RemotePrefsBridge
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 扇形背景二级页（0913 用户拍板，首页交互区入口进入）。
 *
 * Route D（0915 重构）：两条轴各自可控 ——
 * - 板材质轴：「板材质浓度」滑条，统一驱动板的混色/提亮/颗粒（miuix textureBlur 管线）
 * - 背景模糊轴：「背景模糊来源」单项下拉（自动/系统裁剪模糊/采样壁纸/后方屏幕全屏/关闭），
 *   来源失效时由 host 自动降级，保证板永远有材质
 * 另有「背景压暗」opt-in。均"拖动/选择即落盘（=一次 ConfigSync 广播）"节奏，
 * 改动下次呼出生效；revision 通道回读外部改动。
 */
@Composable
internal fun FanBackgroundPage(prefs: SharedPreferences, modifier: Modifier = Modifier) {
    // D1 同款：绑定晚到时参数 prefs 是本地空壳，effectivePrefs 切换强制整页重读
    val effectivePrefs = RemotePrefsBridge.prefs ?: prefs
    val repo = remember(effectivePrefs) { SettingsRepository(effectivePrefs) }
    DisposableEffect(repo) { onDispose { repo.dispose() } }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .overScrollVertical(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { SmallTitle(text = stringResource(R.string.fan_background_section)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    // 量程/步进契约取自 LayoutDefaults（禁止内联），刻度数与吸附由辅助函数推导
                    val fogMin = LayoutDefaults.FAN_FOG_MIN
                    val fogMax = LayoutDefaults.FAN_FOG_MAX
                    val fogStep = LayoutDefaults.FAN_FOG_STEP
                    var fog by remember(effectivePrefs, repo.revision) {
                        mutableStateOf(repo.fanFogIntensity())
                    }
                    SliderPreference(
                        title = stringResource(R.string.fan_fog_title),
                        summary = stringResource(R.string.fan_fog_summary),
                        valueText = "${(fog * 100).roundToInt()}%",
                        value = fog,
                        valueRange = fogMin..fogMax,
                        steps = sliderSteps(fogMin, fogMax, fogStep),
                        onValueChange = { fog = quantizeToStep(it, fogMin, fogMax, fogStep) },
                        onValueChangeFinished = {
                            repo.save(PrefKeys.FAN_FOG_INTENSITY, fog)
                        }
                    )
                    var dimOn by remember(effectivePrefs, repo.revision) {
                        mutableStateOf(repo.fanDimEnabled())
                    }
                    SwitchPreference(
                        title = stringResource(R.string.fan_dim_title),
                        summary = stringResource(R.string.fan_dim_summary),
                        checked = dimOn,
                        onCheckedChange = {
                            dimOn = it
                            repo.save(PrefKeys.FAN_DIM_ENABLED, it)
                        }
                    )
                    // 背景模糊来源（Route D 定稿：单项下拉取代旧三开关互斥）。
                    // 取值顺序 = 契约 LayoutDefaults.FAN_BLUR_SOURCE_VALUES，下标映射不得内联
                    val sourceValues = LayoutDefaults.FAN_BLUR_SOURCE_VALUES
                    val sourceOptions = listOf(
                        stringResource(R.string.fan_blur_source_auto),
                        stringResource(R.string.fan_blur_source_wallpaper),
                        stringResource(R.string.fan_blur_source_behind),
                        stringResource(R.string.fan_blur_source_transparent),
                        stringResource(R.string.fan_blur_source_off)
                    )
                    val sourceSummaries = listOf(
                        stringResource(R.string.fan_blur_source_summary_auto),
                        stringResource(R.string.fan_blur_source_summary_wallpaper),
                        stringResource(R.string.fan_blur_source_summary_behind),
                        stringResource(R.string.fan_blur_source_summary_transparent),
                        stringResource(R.string.fan_blur_source_summary_off)
                    )
                    var blurSource by remember(effectivePrefs, repo.revision) {
                        mutableStateOf(repo.fanBlurSource())
                    }
                    val sourceIndex = sourceValues.indexOf(blurSource).coerceAtLeast(0)
                    OverlayDropdownMenu(
                        title = stringResource(R.string.fan_blur_source_title),
                        options = sourceOptions,
                        selectedIndex = sourceIndex,
                        summary = sourceSummaries[sourceIndex],
                        onSelectedIndexChange = { index ->
                            val value = sourceValues[index]
                            blurSource = value
                            repo.save(PrefKeys.FAN_BLUR_SOURCE, value)
                        }
                    )
                }
            }
        }
    }
}
