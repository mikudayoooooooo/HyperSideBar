package com.lsp.hypersidebar

import com.lsp.hypersidebar.prefs.savePref
import com.lsp.hypersidebar.prefs.PrefKeys
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lsp.hypersidebar.theme.HyperSidebarTheme
import com.lsp.hypersidebar.theme.ThemeMode
import com.lsp.hypersidebar.theme.ThemeModes
import com.lsp.hypersidebar.ui.settings.MainScreen
import com.lsp.hypersidebar.util.ConfigSync
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
            // 配置同步通道（批次 2）：模块进程任何 prefs 写入即全量广播给
            // hook 进程（幂等注册）；绑定完成本身也推一次
            RemotePrefsBridge.registerConfigSync(applicationContext)
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
            // 主题实时性修复（2026-09-04 用户反馈"关闭系统配色要重开应用才刷新"）：
            // LaunchedEffect(activePrefs) 只在实例切换时跑，键值写入不触发——
            // 补 OnSharedPreferenceChangeListener 响应 THEME_MODE 写入（设置页开关/
            // 任何页面写入/进程内他处写入统一实时生效）。同进程写会收到自己的回调，
            // 与 onThemeModeChange 的 state 赋值重复但幂等。
            DisposableEffect(activePrefs) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
                    if (key == PrefKeys.THEME_MODE) {
                        themeMode = p.getString(PrefKeys.THEME_MODE, themeMode) ?: themeMode
                    }
                }
                activePrefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose { activePrefs.unregisterOnSharedPreferenceChangeListener(listener) }
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
