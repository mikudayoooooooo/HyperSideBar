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
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 呼出设置二级页（0913 用户拍板，首页交互区入口进入）。
 *
 * - 滑动距离 = 呼出确认线（PrefKeys.TRIGGER_MIN_DISTANCE，dp）：hook 侧两通道 DOWN 换算
 *   px 缓存，下次手势生效；滑回重置=确认距离一半（GestureThresholds.SWIPE_RESET_RATIO 滞回）
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
            .overScrollVertical(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { SmallTitle(text = stringResource(R.string.invoke_section)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    // 量程/步进契约取自 LayoutDefaults（禁止内联），刻度数与吸附由辅助函数推导
                    val distanceMin = LayoutDefaults.TRIGGER_SWIPE_DISTANCE_MIN_DP.toFloat()
                    val distanceMax = LayoutDefaults.TRIGGER_SWIPE_DISTANCE_MAX_DP.toFloat()
                    val distanceStep = LayoutDefaults.TRIGGER_SWIPE_DISTANCE_STEP_DP.toFloat()
                    val dwellMin = LayoutDefaults.TRIGGER_DWELL_MIN_MS.toFloat()
                    val dwellMax = LayoutDefaults.TRIGGER_DWELL_MAX_MS.toFloat()
                    val dwellStep = LayoutDefaults.TRIGGER_DWELL_STEP_MS.toFloat()
                    var swipeDp by remember(effectivePrefs, repo.revision) {
                        mutableStateOf(repo.triggerMinDistanceDp().roundToInt())
                    }
                    SettingsSliderItem(
                        title = stringResource(R.string.trigger_swipe_distance_title),
                        summary = stringResource(R.string.trigger_swipe_distance_summary, swipeDp),
                        value = swipeDp.toFloat(),
                        valueRange = distanceMin..distanceMax,
                        steps = sliderSteps(distanceMin, distanceMax, distanceStep),
                        onValueChange = {
                            swipeDp = quantizeToStep(it, distanceMin, distanceMax, distanceStep).roundToInt()
                        },
                        onValueChangeFinished = {
                            repo.save(PrefKeys.TRIGGER_MIN_DISTANCE, swipeDp.toFloat())
                        },
                        compact = true
                    )
                    var dwellMs by remember(effectivePrefs, repo.revision) {
                        mutableStateOf(repo.triggerDwellMs())
                    }
                    SettingsSliderItem(
                        title = stringResource(R.string.trigger_dwell_title),
                        summary = stringResource(R.string.trigger_dwell_summary, dwellMs),
                        value = dwellMs.toFloat(),
                        valueRange = dwellMin..dwellMax,
                        steps = sliderSteps(dwellMin, dwellMax, dwellStep),
                        onValueChange = {
                            dwellMs = quantizeToStep(it, dwellMin, dwellMax, dwellStep).roundToInt()
                        },
                        onValueChangeFinished = {
                            repo.save(PrefKeys.TRIGGER_DWELL_MS, dwellMs)
                        },
                        compact = true
                    )
                }
            }
        }
    }
}
