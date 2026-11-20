package com.lsp.hypersidebar.prefs

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

import com.lsp.hypersidebar.util.HLog

private const val TAG = "SettingsRepository"

/**
 * 设置 App 侧的单一数据源：typed 读写 + revision 失效通道。
 *
 * RemotePreferences 没有入站推送，"外部写入 → UI 失效重读"只能靠本地写回触发——
 * revision 是全 App 唯一的响应式桥（替代各页自管失效计数）。
 * 页面迁移策略：Phase 1 仅 MainScreen 接入；各页逐个迁移到 typed getter 在 Phase 7 完成。
 *
 * prefs 实例可能切换（remote 绑定前后），调用方用 remember(prefs) { SettingsRepository(prefs) }
 * 管理生命周期，并在 onDispose 调用 [dispose]。
 */
class SettingsRepository(val prefs: SharedPreferences) {

    var revision by mutableIntStateOf(0)
        private set

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        revision++
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun dispose() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    // ===== typed getters（默认值一律取自 LayoutDefaults / 契约） =====
    // 布局 10 键走草稿优先读法：BottomSheet 编辑期只改草稿（实时预览跟随），
    // 取消=discard 整体丢弃、保存=commit 批量落盘；非编辑期草稿为空=直读落盘值。

    fun enabled(): Boolean = prefs.getBoolean(PrefKeys.ENABLED, true)

    fun themeMode(): String =
        prefs.getString(PrefKeys.THEME_MODE, "MONET_SYSTEM") ?: "MONET_SYSTEM"

    fun iconSize(): Float = getDraft(PrefKeys.ICON_SIZE) {
        prefs.getFloat(PrefKeys.ICON_SIZE, LayoutDefaults.ICON_SIZE)
    }
    fun innerRadius(): Float = getDraft(PrefKeys.INNER_RADIUS) {
        prefs.getFloat(PrefKeys.INNER_RADIUS, LayoutDefaults.INNER_RADIUS)
    }
    fun outerRadiusMax(): Float = getDraft(PrefKeys.OUTER_RADIUS_MAX) {
        prefs.getFloat(PrefKeys.OUTER_RADIUS_MAX, LayoutDefaults.OUTER_RADIUS_MAX)
    }
    fun maxAppsOuter(): Int = getDraft(PrefKeys.MAX_APPS_OUTER) {
        prefs.getInt(PrefKeys.MAX_APPS_OUTER, LayoutDefaults.MAX_APPS_OUTER)
    }
    fun maxAppsInner(): Int = getDraft(PrefKeys.MAX_APPS_INNER) {
        prefs.getInt(PrefKeys.MAX_APPS_INNER, LayoutDefaults.MAX_APPS_INNER)
    }

    fun landscapeIconSize(): Float = getDraft(PrefKeys.LANDSCAPE_ICON_SIZE) {
        prefs.getFloat(PrefKeys.LANDSCAPE_ICON_SIZE, LayoutDefaults.LANDSCAPE_ICON_SIZE)
    }
    fun landscapeMaxAppsOuter(): Int = getDraft(PrefKeys.LANDSCAPE_MAX_APPS_OUTER) {
        prefs.getInt(PrefKeys.LANDSCAPE_MAX_APPS_OUTER, LayoutDefaults.LANDSCAPE_MAX_APPS_OUTER)
    }
    fun landscapeMaxAppsInner(): Int = getDraft(PrefKeys.LANDSCAPE_MAX_APPS_INNER) {
        prefs.getInt(PrefKeys.LANDSCAPE_MAX_APPS_INNER, LayoutDefaults.LANDSCAPE_MAX_APPS_INNER)
    }
    fun landscapeInnerRadius(): Float = getDraft(PrefKeys.LANDSCAPE_INNER_RADIUS) {
        prefs.getFloat(PrefKeys.LANDSCAPE_INNER_RADIUS, LayoutDefaults.LANDSCAPE_INNER_RADIUS)
    }
    fun landscapeOuterRadius(): Float = getDraft(PrefKeys.LANDSCAPE_OUTER_RADIUS) {
        prefs.getFloat(PrefKeys.LANDSCAPE_OUTER_RADIUS, LayoutDefaults.LANDSCAPE_OUTER_RADIUS)
    }

    // 底角斜滑扇形样式 5 键同为布局键：走草稿优先读法（sheet 实时预览跟随草稿）
    fun cornerIconSize(): Float = getDraft(PrefKeys.CORNER_ICON_SIZE) {
        prefs.getFloat(PrefKeys.CORNER_ICON_SIZE, LayoutDefaults.CORNER_ICON_SIZE)
    }
    fun cornerInnerRadius(): Float = getDraft(PrefKeys.CORNER_INNER_RADIUS) {
        prefs.getFloat(PrefKeys.CORNER_INNER_RADIUS, LayoutDefaults.CORNER_INNER_RADIUS)
    }
    fun cornerOuterRadius(): Float = getDraft(PrefKeys.CORNER_OUTER_RADIUS) {
        prefs.getFloat(PrefKeys.CORNER_OUTER_RADIUS, LayoutDefaults.CORNER_OUTER_RADIUS)
    }
    fun cornerMaxAppsOuter(): Int = getDraft(PrefKeys.CORNER_MAX_APPS_OUTER) {
        prefs.getInt(PrefKeys.CORNER_MAX_APPS_OUTER, LayoutDefaults.CORNER_MAX_APPS_OUTER)
    }
    fun cornerMaxAppsInner(): Int = getDraft(PrefKeys.CORNER_MAX_APPS_INNER) {
        prefs.getInt(PrefKeys.CORNER_MAX_APPS_INNER, LayoutDefaults.CORNER_MAX_APPS_INNER)
    }

    fun deadZone(): Float = prefs.getFloat(PrefKeys.DEAD_ZONE, LayoutDefaults.DEAD_ZONE)

    /** 呼出停顿（ms）：读时钳到滑条量程（盘上脏值/旧键不得越界；native 侧同量程 150..350）。 */
    fun triggerDwellMs(): Int = prefs.getInt(PrefKeys.TRIGGER_DWELL_MS, LayoutDefaults.TRIGGER_DWELL_MS)
        .coerceIn(LayoutDefaults.TRIGGER_DWELL_MIN_MS, LayoutDefaults.TRIGGER_DWELL_MAX_MS)

    /** 底角"斜滑独占"开关：draft-aware（底角 sheet 内开关与样式"✕全撤/✓全存"同步）。 */
    fun cornerSuppressEnabled(): Boolean = getDraft(PrefKeys.CORNER_SUPPRESS_ENABLED) {
        prefs.getBoolean(PrefKeys.CORNER_SUPPRESS_ENABLED, LayoutDefaults.CORNER_SUPPRESS_ENABLED)
    }

    /** "点空白吞掉触摸"开关（默认关；抢流会干扰正常拖动 ⇔ 真机反馈，见键注释）。 */
    fun freeformTakeoverEnabled(): Boolean = getDraft(PrefKeys.FREEFORM_BLANK_TAP_TAKEOVER) {
        prefs.getBoolean(
            PrefKeys.FREEFORM_BLANK_TAP_TAKEOVER, LayoutDefaults.FREEFORM_BLANK_TAP_TAKEOVER
        )
    }
    /** 底角斜滑兜底开关（默认关；只在小白条不在用时接管，见 PrefKeys 注释）。draft-aware（设置页 sheet）。 */
    fun cornerSpyFallbackEnabled(): Boolean = getDraft(PrefKeys.CORNER_SPY_FALLBACK) {
        prefs.getBoolean(PrefKeys.CORNER_SPY_FALLBACK, LayoutDefaults.CORNER_SPY_FALLBACK)
    }

    /** 底角斜滑触发开关（N1，opt-in）。draft-aware：sheet 内开关与样式"✕全撤/✓全存"同步。 */
    fun cornerSwipeEnabled(): Boolean = getDraft(PrefKeys.CORNER_SWIPE_ENABLED) {
        prefs.getBoolean(PrefKeys.CORNER_SWIPE_ENABLED, LayoutDefaults.CORNER_SWIPE_ENABLED)
    }

    /* 底角触发参数（角窗宽度/角度锥/热区高度）2026-10-16 不再是设置项：常量移至 hook 侧
     * GestureThresholds（OS2/3 同源同值）。原 cornerWidthRatio/cornerMinAngleDeg/
     * cornerMaxAngleDeg/cornerHotPercent 四个读取器随滑条一起删除。 */

    fun fanFogIntensity(): Float =
        prefs.getFloat(PrefKeys.FAN_FOG_INTENSITY, LayoutDefaults.FAN_FOG_INTENSITY)
    fun fanDimEnabled(): Boolean =
        prefs.getBoolean(PrefKeys.FAN_DIM_ENABLED, LayoutDefaults.FAN_DIM_ENABLED)

    /** 常驻扇形开关（呼出后松手不收起，点选交互；13.1 同批新增）。 */
    fun fanPersistentOnRelease(): Boolean =
        prefs.getBoolean(PrefKeys.FAN_PERSISTENT_ON_RELEASE, LayoutDefaults.FAN_PERSISTENT_ON_RELEASE)

    /** 小窗「点击空白处」开关与动作档（2026-10-16）。动作档做了白名单校验：
     *  取到不在契约里的值一律回落默认（防旧键/手改 prefs 把动作层带进未定义分支）。 */
    fun freeformBlankTapEnabled(): Boolean =
        prefs.getBoolean(PrefKeys.FREEFORM_BLANK_TAP_ENABLED, LayoutDefaults.FREEFORM_BLANK_TAP_ENABLED)

    fun freeformBlankTapMode(): String =
        prefs.getString(PrefKeys.FREEFORM_BLANK_TAP_MODE, LayoutDefaults.FREEFORM_BLANK_TAP_MODE_DEFAULT)
            ?.takeIf { it in LayoutDefaults.FREEFORM_BLANK_TAP_MODE_VALUES }
            ?: LayoutDefaults.FREEFORM_BLANK_TAP_MODE_DEFAULT

    /** 背景来源（亚克力 / 采样壁纸 / 透明）：非法取值（含旧 auto/behind/off/dialog）回落默认档。 */
    fun fanBlurSource(): String =
        prefs.getString(PrefKeys.FAN_BLUR_SOURCE, LayoutDefaults.FAN_BLUR_SOURCE_DEFAULT)
            ?.takeIf { it in LayoutDefaults.FAN_BLUR_SOURCE_VALUES }
            ?: LayoutDefaults.FAN_BLUR_SOURCE_DEFAULT

    fun customApps(): Set<String> = prefs.getStringSet(PrefKeys.CUSTOM_APPS, emptySet()).orEmpty()

    // ===== 草稿/提交层（迭代二 §4 地基，供迭代三 OverlayBottomSheet 消费） =====
    //
    // 语义（refactor-plan §四.4）：编辑中只改草稿不落盘（实时预览跟随草稿态），
    // commit = 单 editor 批量落盘（一次 revision 跳变、hook 侧一次 prefs 同步），
    // discard = 整体丢弃。草稿值优先于落盘值（[getDraft] 系列先查草稿）。

    private val draft = LinkedHashMap<String, Any>()

    val hasDraft: Boolean get() = draft.isNotEmpty()

    fun putDraft(key: String, value: Any) {
        draft[key] = value
        revision++ // 草稿变化也走 revision 通道驱动预览重组
    }

    fun <T> getDraft(key: String, fallback: () -> T): T {
        @Suppress("UNCHECKED_CAST")
        return (draft[key] as? T) ?: fallback()
    }

    /** 批量落盘草稿并清空（保存）。 */
    fun commitDraft() {
        if (draft.isEmpty()) return
        prefs.edit().apply {
            draft.forEach { (k, v) ->
                when (v) {
                    is Boolean -> putBoolean(k, v)
                    is Float -> putFloat(k, v)
                    is Int -> putInt(k, v)
                    is String -> putString(k, v)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        putStringSet(k, v as Set<String>)
                    }
                }
            }
        }.apply()
        draft.clear()
        // 草稿批量落盘不走 savePref，需同样显式推送（布局 BottomSheet 保存=10 键一次改）
        com.lsp.hypersidebar.util.ConfigSync.notifyConfigChanged("(commitDraft)")
    }

    /** 丢弃草稿（取消）。 */
    fun discardDraft() {
        if (draft.isNotEmpty()) {
            draft.clear()
            revision++
        }
    }

    // ===== 写 =====

    fun save(key: String, value: Any) = prefs.savePref(key, value)

    /**
     * 一键恢复默认（§2.5.3，PRD"默认值且可重置"）：全部布局（10）+ 交互（5）参数写回默认值。
     * 写盘即经 LSPosed 推送同步 hook 侧，下次呼出生效（无需重启）。不动用户数据（应用/快捷方式）。
     */
    fun restoreAllDefaults() {
        prefs.edit().apply {
            putFloat(PrefKeys.ICON_SIZE, LayoutDefaults.ICON_SIZE)
            putFloat(PrefKeys.INNER_RADIUS, LayoutDefaults.INNER_RADIUS)
            putFloat(PrefKeys.OUTER_RADIUS_MAX, LayoutDefaults.OUTER_RADIUS_MAX)
            putInt(PrefKeys.MAX_APPS_OUTER, LayoutDefaults.MAX_APPS_OUTER)
            putInt(PrefKeys.MAX_APPS_INNER, LayoutDefaults.MAX_APPS_INNER)
            putFloat(PrefKeys.LANDSCAPE_ICON_SIZE, LayoutDefaults.LANDSCAPE_ICON_SIZE)
            putInt(PrefKeys.LANDSCAPE_MAX_APPS_OUTER, LayoutDefaults.LANDSCAPE_MAX_APPS_OUTER)
            putInt(PrefKeys.LANDSCAPE_MAX_APPS_INNER, LayoutDefaults.LANDSCAPE_MAX_APPS_INNER)
            putFloat(PrefKeys.LANDSCAPE_INNER_RADIUS, LayoutDefaults.LANDSCAPE_INNER_RADIUS)
            putFloat(PrefKeys.LANDSCAPE_OUTER_RADIUS, LayoutDefaults.LANDSCAPE_OUTER_RADIUS)
            putFloat(PrefKeys.CORNER_ICON_SIZE, LayoutDefaults.CORNER_ICON_SIZE)
            putFloat(PrefKeys.CORNER_INNER_RADIUS, LayoutDefaults.CORNER_INNER_RADIUS)
            putFloat(PrefKeys.CORNER_OUTER_RADIUS, LayoutDefaults.CORNER_OUTER_RADIUS)
            putInt(PrefKeys.CORNER_MAX_APPS_OUTER, LayoutDefaults.CORNER_MAX_APPS_OUTER)
            putInt(PrefKeys.CORNER_MAX_APPS_INNER, LayoutDefaults.CORNER_MAX_APPS_INNER)
            putFloat(PrefKeys.DEAD_ZONE, LayoutDefaults.DEAD_ZONE)
            putInt(PrefKeys.TRIGGER_DWELL_MS, LayoutDefaults.TRIGGER_DWELL_MS)
            putBoolean(PrefKeys.CORNER_SWIPE_ENABLED, LayoutDefaults.CORNER_SWIPE_ENABLED)
            putBoolean(PrefKeys.CORNER_SPY_FALLBACK, LayoutDefaults.CORNER_SPY_FALLBACK)
            putBoolean(PrefKeys.FAN_PERSISTENT_ON_RELEASE, LayoutDefaults.FAN_PERSISTENT_ON_RELEASE)
            putBoolean(PrefKeys.FREEFORM_BLANK_TAP_ENABLED, LayoutDefaults.FREEFORM_BLANK_TAP_ENABLED)
            putString(PrefKeys.FREEFORM_BLANK_TAP_MODE, LayoutDefaults.FREEFORM_BLANK_TAP_MODE_DEFAULT)
            putFloat(PrefKeys.FAN_FOG_INTENSITY, LayoutDefaults.FAN_FOG_INTENSITY)
            putBoolean(PrefKeys.FAN_DIM_ENABLED, LayoutDefaults.FAN_DIM_ENABLED)
            putBoolean(PrefKeys.CORNER_SUPPRESS_ENABLED, LayoutDefaults.CORNER_SUPPRESS_ENABLED)
            putBoolean(PrefKeys.FREEFORM_BLANK_TAP_TAKEOVER, LayoutDefaults.FREEFORM_BLANK_TAP_TAKEOVER)
            putString(PrefKeys.FAN_BLUR_SOURCE, LayoutDefaults.FAN_BLUR_SOURCE_DEFAULT)
            // 孤儿键清理（2026-10-17 / 2026-10-31）：已删除的滑条键，设备上的旧值不再被任何代码
            // 读写；"恢复默认"顺手删掉，避免留成永久垃圾。
            remove("cornerWidthRatio")
            remove("cornerHotPercent")
            remove("cornerMinAngleDeg")
            remove("cornerMaxAngleDeg")
            // 呼出距离滑条已删（固定为 GestureThresholds.SWIPE_CONFIRM_DP）
            remove("triggerMinDistance")
        }.apply()
        // 同上：批量写不走 savePref，显式推一次
        com.lsp.hypersidebar.util.ConfigSync.notifyConfigChanged("(restoreAllDefaults)")
    }
}

/** 用户资产键的可靠落库（2026-11-20 OS3 实测：删空固定应用后残留最后一项）。
 *
 *  两条根因（libxposed RemotePreferences.java 源码 + 设备 DB 实证）：
 *  ① `Editor.apply()` 只改本进程内存，落库是单线程 EXECUTOR 异步 binder——
 *    "清除→立刻退出(kill)"会把队列尾部截断（实测 4 个逐个删后 kill 残留最后 1 个）。
 *  ② 空集 `putStringSet(key, {})` 在框架 DB 层不生效：DB 里 customAppsOrder（后写）
 *    已清、customApps（先写）停旧值 ⇒ 不是 kill 截断，是空集 put 被下游丢弃/忽略——
 *    删空那次永远不落，正是"残留恰好最后一项"的原因。
 *
 *  形状 = 同步 commit（关掉 apply 截断窗口）+ 空集两段写（删行落库 → 回填空集进内存
 *  快照）：回填段不可省——ConfigSync 全量推送与 hook 侧 SyncedPrefs 缺键回落 base
 *  死快照都靠 getAll() 显式带空集，缺键会把旧值复活。
 *  commit() 可感知失败（apply 不能），失败交由 savePref 记日志。 */
fun SharedPreferences.commitUserAsset(key: String, value: Any): Boolean {
    if (value is Set<*> && value.isEmpty()) {
        // ① 删行落库：空集 put 在框架 DB 层不生效（2026-11-20 设备 DB 实证 set 停旧值/order 已清）
        if (!edit().remove(key).commit()) return false
        // ② 回填空集进内存快照：ConfigSync 全量推送 + hook 侧 SyncedPrefs 缺键回落 base
        //   死快照（旧值复活），getAll() 必须显式带着空集
        @Suppress("UNCHECKED_CAST")
        return edit().putStringSet(key, value as Set<String>).commit()
    }
    return edit().apply {
        when (value) {
            is Boolean -> putBoolean(key, value)
            is Float -> putFloat(key, value)
            is Int -> putInt(key, value)
            is String -> putString(key, value)
            is Set<*> -> {
                @Suppress("UNCHECKED_CAST")
                putStringSet(key, value as Set<String>)
            }
        }
    }.commit()
}

/** 类型分发写 prefs（App 侧通用写入口）。
 *
 *  写完显式推一次配置同步——不让「设置能否热更新」依赖 `RemotePreferences` 是否回调本地
 *  变更监听（未写进契约的隐藏依赖；一旦不回调，模块侧只剩「绑定完成即推一次」，
 *  症状就是"改完不生效、重开设置 App 才生效"）。 */
fun SharedPreferences.savePref(key: String, value: Any) {
    if (key in PrefKeys.USER_ASSET_KEYS) {
        // 用户资产键同步 commit：apply 的异步队列在"清除→立刻退出(kill)"时被截断
        // （2026-11-20 实测残留最后 1 项）；空集自动走 remove+回填两段（见 commitUserAsset）
        val ok = commitUserAsset(key, value)
        if (!ok) HLog.w(TAG, "user asset commit failed: $key")
    } else {
        edit().apply {
            when (value) {
                is Boolean -> putBoolean(key, value)
                is Float -> putFloat(key, value)
                is Int -> putInt(key, value)
                is String -> putString(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    putStringSet(key, value as Set<String>)
                }
            }
        }.apply()
    }
    com.lsp.hypersidebar.util.ConfigSync.notifyConfigChanged(key)
}
