package com.lsp.hypersidebar.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

@Composable
internal fun OverlayDropdownMenu(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null
) {
    OverlayDropdownPreference(
        items = options,
        selectedIndex = selectedIndex.coerceIn(options.indices),
        title = title,
        summary = summary,
        modifier = modifier,
        renderInRootScaffold = true,
        onSelectedIndexChange = onSelectedIndexChange
    )
}
