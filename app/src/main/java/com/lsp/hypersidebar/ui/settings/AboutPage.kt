package com.lsp.hypersidebar.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.BuildConfig
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.prefs.savePref
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
internal fun AboutPage(
    service: XposedService?,
    prefs: SharedPreferences,
    prefsRevision: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val relayBlackhole by remember(prefs, prefsRevision) {
        mutableStateOf(
            runCatching { prefs.getBoolean(PrefKeys.DEBUG_RELAY_BLACKHOLE, false) }.getOrDefault(false)
        )
    }
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: context.getString(R.string.unknown)
    }
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
            .overScrollVertical(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = stringResource(R.string.app_icon_content_desc),
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(MiuixTheme.colorScheme.primaryContainer)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.module_description),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }

        item { SmallTitle(text = stringResource(R.string.about_info)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    BasicComponent(
                        title = stringResource(R.string.about_version),
                        summary = versionName
                    )
                    BasicComponent(
                        title = stringResource(R.string.about_version_code),
                        summary = BuildConfig.VERSION_CODE.toString(),
                        onClick = {
                            val versionCode = BuildConfig.VERSION_CODE.toString()
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("versionCode", versionCode))
                            Toast.makeText(
                                context,
                                context.getString(R.string.version_code_copied),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                    BasicComponent(
                        title = stringResource(R.string.about_based_on),
                        summary = stringResource(
                            R.string.about_based_on_value,
                            BuildConfig.XPOSED_API_VERSION,
                            BuildConfig.EZXHELPER_VERSION
                        )
                    )
                    BasicComponent(
                        title = stringResource(R.string.about_sdk),
                        summary = stringResource(R.string.about_sdk_value)
                    )
                }
            }
        }

        item { SmallTitle(text = stringResource(R.string.framework_info)) }
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

        item { SmallTitle(text = stringResource(R.string.about_links)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ArrowPreference(
                        title = stringResource(R.string.about_author),
                        summary = AUTHOR_HANDLE,
                        onClick = { openExternalUrl(context, AUTHOR_URL) }
                    )
                    ArrowPreference(
                        title = stringResource(R.string.about_project_url),
                        summary = PROJECT_URL.removePrefix("https://"),
                        onClick = { openExternalUrl(context, PROJECT_URL) }
                    )
                }
            }
        }

        item { SmallTitle(text = stringResource(R.string.debug_section)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    title = stringResource(R.string.debug_relay_blackhole),
                    summary = stringResource(R.string.debug_relay_blackhole_summary),
                    checked = relayBlackhole,
                    onCheckedChange = {
                        // 写 remotePrefs：launcher 侧每次执行广播时读取（binder 缓存实时同步）
                        prefs.savePref(PrefKeys.DEBUG_RELAY_BLACKHOLE, it)
                    }
                )
            }
        }
    }
}

// 项目地址取自仓库 origin（github.com/mikudayoooooooo/HyperSideBar）——改仓库时同步改这里
private const val AUTHOR_HANDLE = "mikudayoooooooo"
private const val AUTHOR_URL = "https://github.com/mikudayoooooooo"
private const val PROJECT_URL = "https://github.com/mikudayoooooooo/HyperSideBar"

private fun openExternalUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.open_link_failed), Toast.LENGTH_SHORT).show()
    }
}
