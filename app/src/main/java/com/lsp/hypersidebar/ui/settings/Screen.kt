package com.lsp.hypersidebar.ui.settings

internal enum class RootTab { HOME, SETTINGS, ABOUT }

internal sealed interface DetailScreen {
    data class AppSelection(val prefsKey: String, val title: String) : DetailScreen
    data object ShortcutSettings : DetailScreen
    data object LayoutSettings : DetailScreen
    data object InteractionSettings : DetailScreen
}
