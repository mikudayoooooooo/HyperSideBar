package com.lsp.hypersidebar

import com.lsp.hypersidebar.prefs.savePref
import com.lsp.hypersidebar.prefs.PrefKeys
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lsp.hypersidebar.theme.HyperSidebarTheme
import com.lsp.hypersidebar.theme.ThemeMode
import com.lsp.hypersidebar.theme.ThemeModes
import com.lsp.hypersidebar.ui.settings.MainScreen
import com.lsp.hypersidebar.util.RemotePrefsBridge
import io.github.libxposed.service.XposedService

private const val PREFS_NAME = "hyperSidebar_prefs"

class MainActivity : ComponentActivity() {

    private var xposedService by mutableStateOf<XposedService?>(null)
    private var remotePrefs by mutableStateOf<SharedPreferences?>(null)
    private lateinit var fallbackPrefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fallbackPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // D7 修复：改走进程级绑定桥（原自注册 listener 在"设置页先完成绑定后，
        // 本 Activity 重建时二次注册收不到回调"路径下 remotePrefs 永远为 null
        // ——计数 0 的根因；见 RemotePrefsBridge）
        RemotePrefsBridge.addListener { prefs ->
            runOnUiThread {
                remotePrefs = prefs
                xposedService = RemotePrefsBridge.service
            }
        }

        val storedTheme = fallbackPrefs.getString(PrefKeys.THEME_MODE, ThemeModes.MONET_SYSTEM)
            ?: ThemeModes.MONET_SYSTEM

        enableEdgeToEdge()
        setContent {
            var themeMode by remember { mutableStateOf<ThemeMode>(storedTheme) }
            val activePrefs = remotePrefs ?: fallbackPrefs

            LaunchedEffect(activePrefs) {
                themeMode = activePrefs.getString(PrefKeys.THEME_MODE, themeMode) ?: themeMode
            }

            HyperSidebarTheme(colorMode = themeMode) {
                MainScreen(
                    prefs = activePrefs,
                    service = xposedService,
                    themeMode = themeMode,
                    onThemeModeChange = { mode ->
                        themeMode = mode
                        activePrefs.savePref(PrefKeys.THEME_MODE, mode)
                        fallbackPrefs.savePref(PrefKeys.THEME_MODE, mode)
                    }
                )
            }
        }
    }
}
