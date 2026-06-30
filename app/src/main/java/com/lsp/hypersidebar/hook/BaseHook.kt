package com.lsp.hypersidebar.hook


abstract class BaseHook {
    abstract fun init()
    abstract val name: String
    var isInit: Boolean = false
}