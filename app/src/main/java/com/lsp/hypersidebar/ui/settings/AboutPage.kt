package com.lsp.hypersidebar.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.runtime.remember
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
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AboutPage(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: context.getString(R.string.unknown)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
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
                        summary = "37"
                    )
                }
            }
        }
    }
}
