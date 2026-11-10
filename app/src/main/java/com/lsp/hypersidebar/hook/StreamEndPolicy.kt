package com.lsp.hypersidebar.hook

/**
 * 一条手势流走到终态（UP / CANCEL）时，扇形该收起还是保留成"点选态"。
 *
 * 为什么单独抽出来（2026-11-08，OS4 8602 实测）：
 *
 * 8602 的 launcher 把手势处理合并成了 `on_motion_event`，角区那条链上**只剩一处
 * pilfer**，而且它就在 `should_handle_event`（我们的 `action_probe`）这道闸门后面；
 * 同一道闸门后面还跟着新增的 `on_pilfered_at_down` 收尾。我们为了"独占"把闸门打成 0，
 * 系统于是走新增的 `on_home_event_not_pilfered` → `miui_input_manager::request_redirect`
 * 把这条流**收回**（独占成立），代价是转发侧随后收到的终态是 CANCEL（或紧跟的 UP），
 * 而旧代码把它一律当成"用户抬手"⇒ 扇形在落地 ~100 ms 后被收掉，观感是"闪一下就没了"。
 *
 * 判定口径：
 *  - 没在展示：无所谓，返回 [Decision.DISMISS]（调用方本来也不会调 dismiss）；
 *  - 常驻档 / 组弹层展开：[Decision.KEEP_FOR_TAP]（既有语义）；
 *  - **CANCEL 且独占开着**（①发 pilfer，或 2a 同进程已 pilfer——TurboLayout 传入的
 *    `cornerSelfSuppressed` 已并入 `|| cSysPilfered`）：[Decision.KEEP_FOR_TAP] —— 这个
 *    CANCEL 是我们自己造出来的（拒/抢了 pilfer 导致系统收回流），不是"系统已接管并
 *    开始跑 Home"，所以旧安全阀那套"收窗防三连"的前提不成立；
 *  - 其余（正常 UP、或独占关着时的 CANCEL）：[Decision.DISMISS]，保持"松手即收"。
 *
 * 反向约束（不要改回去）：**独占关着**时 CANCEL 必须继续 dismiss —— 那才是"桌面手势窗口
 * 抢走了流、系统 Home/recents 已经在跑"的真场景，保留扇形会叠成"回桌面 + 扇形 + 启动应用"三连。
 */
object StreamEndPolicy {

    enum class Decision { DISMISS, KEEP_FOR_TAP }

    fun decide(
        isCancel: Boolean,
        isShowing: Boolean,
        stayAfterRelease: Boolean,
        cornerSelfSuppressed: Boolean
    ): Decision = when {
        !isShowing -> Decision.DISMISS
        stayAfterRelease -> Decision.KEEP_FOR_TAP
        isCancel && cornerSelfSuppressed -> Decision.KEEP_FOR_TAP
        else -> Decision.DISMISS
    }
}
