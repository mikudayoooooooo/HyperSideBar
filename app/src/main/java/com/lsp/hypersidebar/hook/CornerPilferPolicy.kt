package com.lsp.hypersidebar.hook

/**
 * 角滑 pilfer 分档判据（纯逻辑，无 Android 依赖 ⇒ 可 JUnit 覆盖）。
 *
 * ## 为什么要有分档（2026-11-10 用户质询后重做）
 * 2026-11-09 的诊断只在**本机 8602 launcher** 上成立：我们一发 `pilferPointers`，
 * 8602 立刻回 `HomeEventDispatchHelper request_redirect: from=home_not_pilfered`
 * 并把监视器流收掉（`sys-corner … NO_MOVE`）⇒ 扇形"闪一下消失"。
 * 而 8602 之前的 launcher（OS2/OS3、以及 7719 等旧 OS4 构建）**没有这条收回路径**，
 * pilfer 一直是正常工作的（用户实测：旧版本开着也毫无问题）。
 *
 * 所以正确做法是**按 launcher 构建分档**，而不是"无条件全局关掉"——
 * 后者会用一台机器的实锤去改掉所有版本的行为，属于没有证据的改动。
 *
 * ## 判据
 * - `debug.hsb.corner.pilfer` 显式 `1`/`0` ⇒ 以它为准（排障覆盖，重启即清）；
 * - 未设置 ⇒ 看 launcher versionCode：
 *   - `<= 0`（读不到）⇒ **保持旧行为（发）**：读失败不应改变老版本行为；
 *   - `< 阈值`（8602 之前）⇒ 发（旧行为，真机验证过无副作用）；
 *   - `>= 阈值`（8602 起）⇒ **不发**（新版 launcher 会因 pilfer 收回流）。
 *
 * 阈值 `801028602` = `RELEASE-8.01.02.8602-260918-10261905-R` 的 `com.miui.home` versionCode。
 * 未来构建（>8602）沿用"不发"：即使某天 ROM 去掉了 redirect 路径，不发 pilfer 也只是
 * 少一层冗余保险（独占仍由 native `should_handle_event` 抑制保证），不会更糟。
 */
object CornerPilferPolicy {

    /** 8602 起 launcher 出现 `request_redirect(from=home_not_pilfered)` 收回路径。 */
    const val REVOKED_FROM = 801_028_602L

    /**
     * @param explicitProp `debug.hsb.corner.pilfer` 的原始值（空串=未设置）
     * @param launcherVersionCode `com.miui.home` 的 versionCode；0/负数=读不到
     * @return true = 本次要发 pilfer（旧行为）
     */
    fun decide(
        explicitProp: String,
        launcherVersionCode: Long,
        revokedFrom: Long = REVOKED_FROM
    ): Boolean {
        when (explicitProp.trim()) {
            "1" -> return true
            "0" -> return false
        }
        if (launcherVersionCode <= 0L) return true   // 读不到 ⇒ 保守走旧行为
        return launcherVersionCode < revokedFrom
    }
}
