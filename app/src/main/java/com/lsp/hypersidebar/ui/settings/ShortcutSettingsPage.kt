package com.lsp.hypersidebar.ui.settings

import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.lsp.hypersidebar.util.ShortcutAction
import com.lsp.hypersidebar.util.ShortcutKind
import com.lsp.hypersidebar.util.ShortcutStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.UUID

@Composable
internal fun ShortcutSettingsPage(
    prefs: SharedPreferences,
    modifier: Modifier = Modifier
) {
    var editingShortcut by remember { mutableStateOf<ShortcutAction?>(null) }
    var isNewShortcut by remember { mutableStateOf(false) }
    var showActivityPicker by remember { mutableStateOf(false) }
    var listRevision by remember { mutableStateOf(0) }

    val activity = LocalContext.current as? ComponentActivity

    // 内部返回栈：编辑页按返回 → 回到列表。
    // 选择器页（showActivityPicker）的返回由 ActivityPickerPage 自己的回调处理，
    // 此处用 !showActivityPicker 避免重复拦截。列表层时回调禁用，放行给 MainScreen。
    DisposableEffect(activity, editingShortcut, showActivityPicker) {
        val callback = object : OnBackPressedCallback(editingShortcut != null && !showActivityPicker) {
            override fun handleOnBackPressed() {
                editingShortcut = null
            }
        }
        activity?.onBackPressedDispatcher?.addCallback(callback)
        onDispose { callback.remove() }
    }

        when {
        showActivityPicker -> {
            ActivityPickerPage(
                onSelected = { pkg, act, label ->
                    editingShortcut = editingShortcut?.copy(
                        packageName = pkg,
                        activityName = act,
                        // 仅当当前名称为空时才用 Activity 标签自动填充，避免覆盖用户已输入的名称
                        label = editingShortcut?.label?.ifEmpty { label } ?: label,
                        iconPackageName = pkg
                    )
                    showActivityPicker = false
                },
                onBack = { showActivityPicker = false }
            )
        }
        editingShortcut != null -> {
            val derived = remember(editingShortcut?.packageName, editingShortcut?.activityName) {
                SplitResult(
                    pkg = editingShortcut?.packageName ?: "",
                    act = editingShortcut?.activityName ?: ""
                )
            }
            ShortcutEditPage(
                shortcut = editingShortcut!!,
                isNew = isNewShortcut,
                prefs = prefs,
                initialTargetSpec = derived,
                onSave = { updated ->
                    if (isNewShortcut) {
                        ShortcutStore.addShortcut(prefs, updated)
                    } else {
                        ShortcutStore.updateShortcut(prefs, updated)
                    }
                    listRevision++
                    editingShortcut = null
                },
                onDelete = if (isNewShortcut) null else {
                    {
                        ShortcutStore.removeShortcut(prefs, editingShortcut!!.id)
                        listRevision++
                        editingShortcut = null
                    }
                },
                onPickActivity = { showActivityPicker = true },
                onBack = { editingShortcut = null }
            )
        }
        else -> {
            ShortcutListPage(
                prefs = prefs,
                revision = listRevision,
                onEdit = { shortcut ->
                    isNewShortcut = false
                    editingShortcut = shortcut
                },
                onAdd = { kind ->
                    isNewShortcut = true
                    editingShortcut = ShortcutAction(
                        id = UUID.randomUUID().toString(),
                        kind = kind,
                        label = "",
                        enabled = true
                    )
                },
                modifier = modifier
            )
        }
    }
}

/**
 * 设置页专用的应用图标组件。
 * 经 IconLoader 异步加载（LruCache + IO 线程），不在组合期做 PackageManager IPC。
 */
@Composable
internal fun SettingsAppIcon(
    packageName: String,
    appName: String,
    size: Float
) {
    val context = LocalContext.current
    var bitmap by remember(packageName) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(packageName) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(width = 128, height = 128)
            }.getOrNull()
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            painter = BitmapPainter(bmp.asImageBitmap()),
            contentDescription = appName,
            modifier = Modifier.size(size.dp)
        )
    } else {
        FallbackSettingsIcon(appName, size)
    }
}

@Composable
private fun FallbackSettingsIcon(appName: String, size: Float) {
    val text = appName.take(1).ifEmpty { "?" }
    val colors = listOf(0xFF5C6BC0, 0xFF26A69A, 0xFFEF5350, 0xFFFF7043, 0xFFAB47BC)
    val colorIndex = remember(appName) { (appName.hashCode() and 0x7FFFFFFF) % colors.size }
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color(colors[colorIndex])),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MiuixTheme.textStyles.body1
        )
    }
}
