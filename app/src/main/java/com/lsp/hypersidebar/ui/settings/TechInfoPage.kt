package com.lsp.hypersidebar.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.lsp.hypersidebar.BuildConfig
import com.lsp.hypersidebar.R
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card

/**
 * 技术栈与环境二级页（2026-10-27 信息架构收敛）：承接原 About 页「设备与框架」整段
 * 与「基于 / 编译 SDK」两行 —— About 主页只留「应用信息 / 更多」两部分。
 */
@Composable
internal fun TechInfoPage(
    service: XposedService?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val deviceModel = remember { Build.MODEL.ifEmpty { Build.DEVICE } }
    val systemVersion = remember { "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）" }
    val frameworkName by produceState(
        initialValue = context.getString(R.string.unknown),
        key1 = service
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching { service?.frameworkName?.toString() }.getOrNull()
                ?: context.getString(R.string.unknown)
        }
    }
    val frameworkVersion by produceState(
        initialValue = "--",
        key1 = service
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching { service?.frameworkVersion?.toString() }.getOrNull() ?: "--"
        }
    }
    val apiVersion by produceState(
        initialValue = "--",
        key1 = service
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching { service?.apiVersion?.toString() }.getOrNull() ?: "--"
        }
    }

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
        verticalArrangement = Arrangement.spacedBy(SettingsDimens.SectionGap)
    ) {
        item { SectionTitle(text = stringResource(R.string.framework_info)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    BasicComponent(
                        title = stringResource(R.string.device_model),
                        summary = deviceModel
                    )
                    BasicComponent(
                        title = stringResource(R.string.system_version),
                        summary = systemVersion
                    )
                    BasicComponent(
                        title = stringResource(R.string.framework_name),
                        summary = frameworkName
                    )
                    BasicComponent(
                        title = stringResource(R.string.framework_version),
                        summary = frameworkVersion
                    )
                    BasicComponent(
                        title = stringResource(R.string.api_version),
                        summary = apiVersion
                    )
                }
            }
        }

        item { SectionTitle(text = stringResource(R.string.about_dependencies)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    BasicComponent(
                        title = stringResource(R.string.lib_lsposed),
                        summary = BuildConfig.XPOSED_API_VERSION
                    )
                    BasicComponent(
                        title = stringResource(R.string.lib_ezxhelper),
                        summary = BuildConfig.EZXHELPER_VERSION
                    )
                    BasicComponent(
                        title = stringResource(R.string.lib_miuix),
                        summary = BuildConfig.MIUIX_VERSION
                    )
                    BasicComponent(
                        title = stringResource(R.string.lib_compose),
                        summary = BuildConfig.COMPOSE_BOM_VERSION
                    )
                    BasicComponent(
                        title = stringResource(R.string.lib_kotlin),
                        summary = BuildConfig.KOTLIN_VERSION
                    )
                    BasicComponent(
                        title = stringResource(R.string.lib_dexkit),
                        summary = BuildConfig.DEXKIT_VERSION
                    )
                    BasicComponent(
                        title = stringResource(R.string.about_sdk),
                        summary = stringResource(R.string.about_sdk_value)
                    )
                }
            }
        }
    }
}
