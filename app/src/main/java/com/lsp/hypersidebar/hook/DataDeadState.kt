package com.lsp.hypersidebar.hook

/**
 * 数据源死亡停摆开关（迭代四 §1.3，PRD §9.4 用户加强语义：推荐获取失败→取消所有
 * hook、扇形不再展示）。由 DataLoader 的 onDataSourceDead 回调置位（连续失败 ≥5 次
 * 且缓存含盘仍空=ROM 不兼容信号）；本进程所有触摸入口 first-check 此标志，命中即
 * 整条放行原生流。同 hook 进程一一对应（进程各自有独立类加载状态）；无自动恢复
 * （恢复=重启手机/重启模块，同降级语义——盲恢复会反复横跳）。
 */
internal object DataDeadState {
    @Volatile var dead = false
        private set

    /** 幂等置位；返回 true=本次调用首次触发（宿主可在此执行一次性停摆动作）。 */
    fun mark(): Boolean {
        if (dead) return false
        dead = true
        return true
    }
}
