package com.lsp.hypersidebar.ui.fan

/**
 * B2 流驱动派发旗标（os4-trigger-final-plan §7 B2-4 的"孤儿防护旁路"）。
 *
 * B 路径下扇形在手指按住期间呼出，转发流的 DOWN/UP 的 downTime 恒早于扇窗
 * shownUptime ⇒ ComposeFanHost 的孤儿防护会把它们当"呼出前手势尾巴"吞掉
 * （1005 15:54 导出实锤：转发 UP 被吞 ⇒ 拖选永无法提交，松手必收起）。
 *
 * 旗标由 FanMenuController.dispatchTouchEvent 在真实派发点前置位、finally 复位——
 * :ui 侧主线程同步派发；launcher 侧 post 到主线程后派发（2026-11-20 OS3 快选失灵
 * 修复：此前由 EdgeGestureHook/TurboLayout 在调用处置位，launcher 输入线程的旗标
 * 只覆盖 post 入队瞬间，主线程实际派发时早已复位 ⇒ 快选 UP 被孤儿防护吞、选中
 * 丢失，吞与不吞纯看调度竞速）。防护见旗标放行。窗体自身收到的原始事件（pilfer
 * 前的重投递尾巴）不经过 dispatchTouchEvent ⇒ 不带旗标、照旧被防护——两条投递
 * 路径就此分离。
 */
object B2StreamDispatch {
    @Volatile
    var active: Boolean = false
}
