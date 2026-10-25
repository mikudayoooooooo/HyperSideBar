package com.lsp.hypersidebar.hook

/**
 * 小窗「空白处」判定核（纯 JVM，无 Android 依赖）。
 *
 * ## 它与 ROM 原函数的关系
 * OS3/OS4 的 SystemUI 里有现成的
 * `MulWinSwitchInteractUtil.isTouchedInFreeformWindow(MotionEvent)`；本核是它的**忠实移植**
 * （逐行对照 `.ota-extract/jadx/wmshell-os4-full/.../MulWinSwitchInteractUtil.java:294-311`），
 * 用途有二：
 * 1. OS2 —— 同名方法在 `MulWinSwitchDecorViewModel` 里是 **private**，只能反射私有方法或
 *    复制实现；反射私有方法在跨 ROM 时更脆，故用本核（配单测）；
 * 2. OS3/OS4 —— 反射 `MulWinSwitchInteractUtil.getInstance().isTouchedInFreeformWindow(ev)`
 *    仍是首选（那是 ROM 自己的真值），本核作反射失败时的兜底。
 *
 * ## 与原实现的逐条对应（搬运时要保持同步）
 * 原实现（jadx 反编译，变量名已归一）：
 * ```
 * if (MiuiDesktopModeStatus.isActive()) return false;
 * for (id in repo.getFreeformTasksInZOrder()) {
 *     info = repo.getMiuiFreeformTaskInfo(id);
 *     if (info == null || info.getTaskInfo() == null) continue;
 *     if (info.isInPinMode()) continue;
 *     if (!info.getTaskInfo().isVisible()) continue;
 *     scale = info.getTaskScale();
 *     b = info.getTaskBounds();
 *     scaled = Rect(b.left, b.top, b.left + (int)(b.width()*scale), b.top + (int)(b.height()*scale));
 *     if (scaled.contains((int) ev.getX(), (int) ev.getY())) return true;
 * }
 * return false;
 * ```
 * 三个容易搬错的点，本核逐一保留：
 * - **只右/下扩**：left/top 不乘 scale，宽高乘——窗口锚点在左上；
 * - **int 截断**：坐标与缩放后的右下角都截断成 int 再 `contains`（不是浮点比较）；
 * - **跳过 pin 态与不可见**：隐藏的小窗不该被认为"点在里面"。
 *
 * ## 与 ROM 原函数的一处**有意分歧**
 * [isBlank] 在「桌面模式」（ROM 的 `MiuiDesktopModeStatus.isActive()`）下返回 **false**，
 * 而原函数在该模式下返回 false 意味着"不在小窗内"（=按原语义就是空白）。分歧的理由：
 * 我们的动作是"收小窗"，而桌面模式下 ROM 自己都不走小窗判定 ⇒ 我们不动作（fail-closed），
 * 免得在平板/桌面形态下误收用户窗口。想按原语义判就调 [isTouchedInFreeformWindow] 自行取反。
 */
object FreeformBlankTest {

    /**
     * 一个小窗的几何快照。z-order **从前往后**（与
     * `MultiTaskingTaskRepository.getFreeformTasksInZOrder()` 同序）。
     * 字段与 ROM 对象一一对应，接线层负责从 ROM 对象取出来：
     * `MiuiFreeformModeTaskInfo.getBounds()` / `getFreeformScale()` / `isInPinMode()` /
     * `isExiting()` / `isNormalState()` / `isMiniState()`（2026-10-16 设备 dexdump 实证名；
     * 接线在 `FreeformBlankTapHook.windowsInZOrder()`，逐个访问器容忍代差）。
     */
    data class Window(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val scale: Float,
        val pinned: Boolean,
        val visible: Boolean,
    )

    /** 忠实移植：点是否落在任一个可见、非 pin 的在册小窗内（桌面模式恒 false）。 */
    fun isTouchedInFreeformWindow(
        x: Float,
        y: Float,
        desktopModeActive: Boolean,
        windowsInZOrder: List<Window>,
    ): Boolean {
        if (desktopModeActive) return false
        val px = x.toInt()
        val py = y.toInt()
        for (w in windowsInZOrder) {
            if (w.pinned || !w.visible) continue
            val right = w.left + (w.width * w.scale).toInt()
            val bottom = w.top + (w.height * w.scale).toInt()
            if (px >= w.left && px < right && py >= w.top && py < bottom) return true
        }
        return false
    }

    /**
     * 我们真正要的那个谓词：该点算不算"小窗之外的空白"。
     * 桌面模式下恒 false（不动作，见类注释的"有意分歧"）。
     *
     * **与 ROM 谓词的第二处分歧（2026-10-25 乱跳修复）**：挂起（pin）窗**有几何**时，其矩形
     * （= ROM pin 过渡后 `setBounds` 的图标矩形）算"非空白"——点图标必须穿透给 ROM 原生
     * 展开链（`MiuiBubbleStackView` 点击 → `unPinFloatingWindow(…, isClick=true)`）。旧实现
     * 忠实沿用 ROM 谓词的 pinned-skip ⇒ 点图标被判"空白" ⇒ 抢流消费 ⇒ 动作落到别的窗
     * （用户实测"挂起的小窗点击会乱跳而不是展开，得多试几次"）。bounds 缺失的 pinned 窗
     * 不在本列表里（接线层 `boundsOf` 滤掉）⇒ 由 Spy 侧的 `pinnedWithoutGeometry` 门兜底
     * （fail-open：该轮整体不抢流）。
     */
    fun isBlank(
        x: Float,
        y: Float,
        desktopModeActive: Boolean,
        windowsInZOrder: List<Window>,
    ): Boolean {
        if (desktopModeActive) return false
        val px = x.toInt()
        val py = y.toInt()
        for (w in windowsInZOrder) {
            if (!w.pinned || !w.visible) continue
            val right = w.left + (w.width * w.scale).toInt()
            val bottom = w.top + (w.height * w.scale).toInt()
            if (px >= w.left && px < right && py >= w.top && py < bottom) return false
        }
        return !isTouchedInFreeformWindow(x, y, false, windowsInZOrder)
    }
}
