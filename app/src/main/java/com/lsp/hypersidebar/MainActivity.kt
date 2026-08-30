package com.lsp.hypersidebar

import com.lsp.hypersidebar.prefs.savePref
import com.lsp.hypersidebar.prefs.PrefKeys
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
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
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

private const val TAG = "MainActivity"
private const val PREFS_NAME = "hyperSidebar_prefs"

class MainActivity : ComponentActivity() {

    private var xposedService by mutableStateOf<XposedService?>(null)
    private var remotePrefs by mutableStateOf<SharedPreferences?>(null)
    private lateinit var fallbackPrefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fallbackPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                Log.d(TAG, "XposedService bound")
                xposedService = service
                // getRemotePreferences 是一次性同步 binder 拉取全量快照，挪出主线程
                Thread {
                    val prefs = service.getRemotePreferences("hyperSidebar")
                    runOnUiThread { remotePrefs = prefs }
                }.start()
            }

            override fun onServiceDied(service: XposedService) {
                Log.d(TAG, "XposedService died")
                xposedService = null
                remotePrefs = null
            }
        })

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
                    activity = this,
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
