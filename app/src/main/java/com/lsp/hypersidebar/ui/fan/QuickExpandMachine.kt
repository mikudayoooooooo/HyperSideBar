package com.lsp.hypersidebar.ui.fan

/**
 * 快捷组悬停展开纯状态机（计划 §B3/C6，2026-10-25；无 Android 依赖）。
 *
 * 只接管「快捷栏命中 → 计时 → 提交（展开/收回/启动/点空白）」段；扇形侧（fan ring）
 * 的极坐标评分选中仍在 ComposeFanHost（本机经 [QuickHit.FanItem] 只感知"命中了
 * 扇区"这一事实）。
 *
 * 三处定案（plan §5②③）：
 * - **几何换帧闩锁**：展开后须收到 ≥1 个真实 MOVE 才开始评估收回（静止手指不产生
 *   MOVE；设置闩锁的那次 MOVE 本身豁免收回判定）。配合 C6 的锚定布局（子项锚定
 *   原组图标中心），手指落点天然在包络内 ⇒ 无振荡：收回只在真实移出包络后发生。
 * - **包络双阈值**：收回判定用「新栏包围盒外扩」布尔输入 [MachineEvent.insideExpandedEnvelope]
 *   （host 从展开后几何算，含 ~0.5 图标外扩），本机不重算几何。
 * - released/常驻复用：常驻模式 dwell 门清零、DOWN 未命中=TAP_OUTSIDE 关整扇
 *   （与既有 released 语义一致）；组分支只展开不启动。
 */
object QuickExpandMachine {

    // MotionEvent.ACTION_* 数值镜像（纯 JVM 不可 import android.view）
    const val ACTION_DOWN = 0
    const val ACTION_MOVE = 1
    const val ACTION_UP = 2
    const val ACTION_CANCEL = 3

    /** 快捷栏命中目标（扇区命中只作为事实传入，不参与组逻辑） */
    sealed class QuickHit {
        object None : QuickHit()
        data class FanItem(val index: Int) : QuickHit()
        /** @param isGroup 折叠态=组图标；展开态恒 false（子项/普通项） */
        data class Quick(val id: String, val isGroup: Boolean) : QuickHit()
    }

    enum class Command {
        NONE, EXPAND_GROUP, COLLAPSE_GROUP, LAUNCH_QUICK, LAUNCH_APP, TAP_OUTSIDE
    }

    data class State(
        val expanded: Boolean = false,
        val expandedGroupId: String? = null,
        /** quick 侧选中（组 id 或子项/普通项 id）；null=无选中 */
        val selectedId: String? = null,
        val selectedIsGroup: Boolean = false,
        /** 选中起点（uptimeMs）；0=本段从未选中（快速扫过松手 dwell 按 0 计） */
        val selectedSinceMs: Long = 0L,
        /** 连续悬停总计时起点（换项只重置 selectedSince，沿用既有两段计时语义） */
        val streakSinceMs: Long = 0L,
        /** 几何换帧闩锁：展开后是否已收到 ≥1 个 MOVE */
        val sawMoveAfterExpand: Boolean = false
    ) {
        val hasQuickSelection: Boolean get() = selectedId != null
    }

    data class Event(
        val action: Int,
        val hit: QuickHit,
        val uptimeMs: Long,
        /** 展开态收回判定输入：触点是否在「新栏包围盒外扩」内（host 从展开几何算） */
        val insideExpandedEnvelope: Boolean = true
    )

    /**
     * @param persistent 常驻点选态（released）：dwell 门清零、DOWN 未命中=点空白关整扇
     * @param dwellMs    预选时长门（普通模式；悬停自动展开也用它）
     * @param finalMs    松手提交的下限门（普通模式）
     */
    fun onEvent(
        state: State,
        event: Event,
        persistent: Boolean = false,
        dwellMs: Long = 150L,
        finalMs: Long = 150L
    ): Pair<State, Command> = when (event.action) {
        ACTION_DOWN -> onDown(state, event, persistent)
        ACTION_MOVE -> onMove(state, event, persistent, dwellMs)
        ACTION_UP -> onUp(state, event, persistent, dwellMs, finalMs)
        ACTION_CANCEL -> {
            // 系统中止手势：选中清零；展开态收回根栏（安全回退，不关扇）
            val cmd = if (state.expanded) Command.COLLAPSE_GROUP else Command.NONE
            state.copy(
                expanded = false, expandedGroupId = null, selectedId = null,
                selectedSinceMs = 0L, streakSinceMs = 0L, sawMoveAfterExpand = false
            ) to cmd
        }
        else -> state to Command.NONE
    }

    private fun onDown(state: State, event: Event, persistent: Boolean): Pair<State, Command> {
        // 常驻模式：DOWN 未命中任何图标/快捷项 = 点空白 → 关整扇（既有 released 语义，
        // 展开态同样适用——规格"点空白 → 关整个扇形"）
        if (persistent && event.hit == QuickHit.None) {
            return clean() to Command.TAP_OUTSIDE
        }
        val s = when (val hit = event.hit) {
            is QuickHit.Quick -> state.copy(
                selectedId = hit.id, selectedIsGroup = hit.isGroup,
                selectedSinceMs = event.uptimeMs, streakSinceMs = event.uptimeMs
            )
            // 扇区命中也计时（LAUNCH_APP 门用；合成 id 区分扇区换项重置 dwell）
            is QuickHit.FanItem -> state.copy(
                selectedId = "fan:${hit.index}", selectedIsGroup = false,
                selectedSinceMs = event.uptimeMs, streakSinceMs = event.uptimeMs
            )
            else -> state.copy(selectedId = null, selectedIsGroup = false, selectedSinceMs = 0L)
        }
        return s to Command.NONE
    }

    private fun onMove(
        state: State,
        event: Event,
        persistent: Boolean,
        dwellMs: Long
    ): Pair<State, Command> {
        if (state.expanded) return onMoveExpanded(state, event)
        // ===== 折叠态：选中跟踪 + 组悬停自动展开（仅普通模式；常驻=点选展开） =====
        fun idOf(hit: QuickHit): String? = when (hit) {
            is QuickHit.Quick -> hit.id
            is QuickHit.FanItem -> "fan:${hit.index}"
            QuickHit.None -> null
        }
        val s = when (val hitId = idOf(event.hit)) {
            null -> state.copy(selectedId = null, selectedIsGroup = false, selectedSinceMs = 0L)
            state.selectedId -> state  // 同项停留：计时继续
            else -> state.copy(
                selectedId = hitId,
                selectedIsGroup = event.hit is QuickHit.Quick && event.hit.isGroup,
                selectedSinceMs = event.uptimeMs,
                // 换项：只重置"本项停留"，连续悬停总计时沿用既有语义继续累计
                streakSinceMs = if (state.streakSinceMs == 0L) event.uptimeMs else state.streakSinceMs
            )
        }
        // 组悬停达阈值 → 自动展开（无须松手）；快速划过（选中未满阈值）不误展开
        if (!persistent && s.selectedIsGroup && s.selectedSinceMs > 0 &&
            event.uptimeMs - s.selectedSinceMs >= dwellMs
        ) {
            return expand(s, s.selectedId!!, event) to Command.EXPAND_GROUP
        }
        return s to Command.NONE
    }

    private fun onMoveExpanded(state: State, event: Event): Pair<State, Command> {
        // 闩锁：展开后的首个 MOVE 只置闩锁+正常求选中，不评估收回
        var s = state
        if (!s.sawMoveAfterExpand) {
            s = s.copy(sawMoveAfterExpand = true)
        } else if (!event.insideExpandedEnvelope && event.hit !is QuickHit.Quick) {
            // 移出包络（空白或扇区）→ 收回根栏，不关扇形
            return collapse(s) to Command.COLLAPSE_GROUP
        }
        s = when (val hit = event.hit) {
            is QuickHit.Quick -> if (hit.id == s.selectedId) s else s.copy(
                selectedId = hit.id, selectedIsGroup = false,
                selectedSinceMs = event.uptimeMs,
                streakSinceMs = if (s.streakSinceMs == 0L) event.uptimeMs else s.streakSinceMs
            )
            // 包络内的栏间空白：清选中（UP 落这里=收回根栏）
            else -> s.copy(selectedId = null, selectedIsGroup = false, selectedSinceMs = 0L)
        }
        return s to Command.NONE
    }

    private fun onUp(
        state: State,
        event: Event,
        persistent: Boolean,
        dwellMs: Long,
        finalMs: Long
    ): Pair<State, Command> {
        if (state.expanded) return onUpExpanded(state, event, persistent, dwellMs, finalMs)
        // ===== 折叠态 UP =====
        return when (val hit = event.hit) {
            is QuickHit.Quick -> when {
                // 组：常驻点选展开；普通模式"已预选后松手→展开"（不启动、不关扇形）
                hit.isGroup && (persistent || state.selectedId == hit.id) ->
                    expand(state, hit.id, event) to Command.EXPAND_GROUP
                // 子项/普通项：门通过才启动（常驻门=0）
                !hit.isGroup && passedGates(state, event, persistent, dwellMs, finalMs) ->
                    clean() to Command.LAUNCH_QUICK
                else -> clean() to Command.NONE
            }
            is QuickHit.FanItem ->
                if (passedGates(state, event, persistent, dwellMs, finalMs)) {
                    clean() to Command.LAUNCH_APP
                } else clean() to Command.NONE
            // 常驻 UP 空白：DOWN 已处理 TAP_OUTSIDE；这里兜底同判（DOWN/UP 间漂移场景）
            QuickHit.None -> if (persistent) clean() to Command.TAP_OUTSIDE else clean() to Command.NONE
        }
    }

    private fun onUpExpanded(
        state: State,
        event: Event,
        persistent: Boolean,
        dwellMs: Long,
        finalMs: Long
    ): Pair<State, Command> = when (val hit = event.hit) {
        is QuickHit.Quick ->
            // 子项松手启动（悬停预选门/常驻点选门同折叠态口径）
            if (passedGates(state, event, persistent, dwellMs, finalMs)) {
                clean() to Command.LAUNCH_QUICK
            } else clean() to Command.NONE
        // 展开态松手落子项之外（栏间空白/包络外/扇区）→ 收回根栏，不关扇形
        //（§B3 补充：悬停展开后松手落子项外的未定义情形定案）
        else -> collapse(state) to Command.COLLAPSE_GROUP
    }

    /** 普通模式松手提交门：本项停留 + 连续悬停双 ≥dwellMs，且本项停留 ≥finalMs */
    private fun passedGates(
        state: State, event: Event, persistent: Boolean, dwellMs: Long, finalMs: Long
    ): Boolean {
        if (persistent) return true
        if (state.selectedSinceMs == 0L) return false // 从未预选（快速扫过）⇒ dwell=0
        val dwell = event.uptimeMs - state.selectedSinceMs
        val streak = event.uptimeMs - state.streakSinceMs
        return dwell >= dwellMs && streak >= dwellMs && dwell >= finalMs
    }

    private fun expand(state: State, groupId: String, event: Event): State = State(
        expanded = true,
        expandedGroupId = groupId,
        // 选中态清零：换几何后索引/坐标全部重映射，dwell 从新列表重新累计
        //（fan 侧选中态由 host 保留——扇区几何未变）
        selectedId = null, selectedIsGroup = false,
        selectedSinceMs = 0L, streakSinceMs = 0L,
        sawMoveAfterExpand = false
    )

    private fun collapse(state: State): State = State(
        expanded = false, expandedGroupId = null,
        selectedId = null, selectedIsGroup = false,
        selectedSinceMs = 0L, streakSinceMs = 0L,
        sawMoveAfterExpand = false
    )

    private fun clean(): State = State()
}
