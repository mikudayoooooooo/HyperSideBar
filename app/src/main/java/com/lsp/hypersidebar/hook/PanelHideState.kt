package com.lsp.hypersidebar.hook

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 原生面板呼出时的 dock 隐藏状态（:ui 进程内共享）。
 * 消费方：TurboLayout.hookDockLayoutVisibility；
 * 写入方：DirectLaunchStrategy（小白条通道直接选中）/ FreeformRelayHook（launcher 广播转发）。
 */
object PanelHideState {
    val hidden = AtomicBoolean(false)
}
