package com.lsp.hypersidebar.hook

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 流终态（UP/CANCEL）→ 扇形去留。这是 OS4 8602 上"底角斜滑闪一下就消失"的判据点，
 * 四条规则缺一条就会退回旧毛病（收窗）或制造新毛病（该收不收 ⇒ 三连）。
 */
class StreamEndPolicyTest {

    private fun decide(
        isCancel: Boolean = false,
        isShowing: Boolean = true,
        stayAfterRelease: Boolean = false,
        cornerSelfSuppressed: Boolean = false
    ) = StreamEndPolicy.decide(isCancel, isShowing, stayAfterRelease, cornerSelfSuppressed)

    @Test
    fun notShowingNeverKeeps() {
        // 没在展示就无所谓；调用方本来也不会去 dismiss，这里只要求语义单调
        assertEquals(StreamEndPolicy.Decision.DISMISS, decide(isShowing = false))
        assertEquals(
            StreamEndPolicy.Decision.DISMISS,
            decide(isCancel = true, isShowing = false, cornerSelfSuppressed = true)
        )
    }

    @Test
    fun stayAfterReleaseKeepsEvenOnUp() {
        // 「呼出后常驻」/ 组弹层展开：UP 也要保留（既有语义，不能被本次改动吃掉）
        assertEquals(
            StreamEndPolicy.Decision.KEEP_FOR_TAP,
            decide(isCancel = false, stayAfterRelease = true)
        )
        assertEquals(
            StreamEndPolicy.Decision.KEEP_FOR_TAP,
            decide(isCancel = true, stayAfterRelease = true)
        )
    }

    @Test
    fun cancelWithSelfSuppressionKeeps() {
        // 独占开着 ⇒ CANCEL 是我们自己造的（拒 pilfer ⇒ 系统收流），系统没跑 Home
        assertEquals(
            StreamEndPolicy.Decision.KEEP_FOR_TAP,
            decide(isCancel = true, cornerSelfSuppressed = true)
        )
    }

    @Test
    fun cancelWithoutSuppressionStillDismisses() {
        // 反向约束：独占关着时的 CANCEL = 桌面手势窗口抢走了流、系统 Home/recents 在跑
        // ⇒ 必须收窗，否则"回桌面 + 扇形 + 启动应用"三连（2026-10-14 血案）
        assertEquals(
            StreamEndPolicy.Decision.DISMISS,
            decide(isCancel = true, cornerSelfSuppressed = false)
        )
    }

    @Test
    fun plainUpStillDismisses() {
        // 用户真抬手且没选中 ⇒ 保持"松手即收"（不是常驻档时的默认行为）
        assertEquals(
            StreamEndPolicy.Decision.DISMISS,
            decide(isCancel = false, cornerSelfSuppressed = true)
        )
    }
}
