package com.lsp.hypersidebar.prefs

import android.content.SharedPreferences

/**
 * prefs wire 协议契约：跨进程（设置 App ↔ hook 宿主）的唯一 key 与默认值定义。
 * 两侧读写必须引用本文件，禁止散落字面量；新增 key 先加这里。
 */

object PrefKeys {
    const val ENABLED = "enabled"
    const val THEME_MODE = "themeMode"

    const val ICON_SIZE = "iconSize"
    const val INNER_RADIUS = "innerRadius"
    const val OUTER_RADIUS_MAX = "outerRadiusMax"
    const val MAX_APPS_OUTER = "maxAppsOuter"
    const val MAX_APPS_INNER = "maxAppsInner"

    const val LANDSCAPE_ICON_SIZE = "landscapeIconSize"
    const val LANDSCAPE_MAX_APPS_OUTER = "landscapeMaxAppsOuter"
    const val LANDSCAPE_MAX_APPS_INNER = "landscapeMaxAppsInner"
    const val LANDSCAPE_INNER_RADIUS = "landscapeInnerRadius"
    const val LANDSCAPE_OUTER_RADIUS = "landscapeOuterRadius"

    const val DEAD_ZONE = "deadZone"
    const val TRIGGER_DWELL_MS = "triggerDwellMs"
    const val TRIGGER_MIN_DISTANCE = "triggerMinDistance"

    /** 扇形背景雾化浓度（0~0.70，0=关闭填充；路线 C 纯视觉增强，无真模糊） */
    const val FAN_FOG_INTENSITY = "fanFogIntensity"
    /** 呼出扇形时全屏压暗开关（Compose scrim 24% 黑，与菜单同步淡入） */
    const val FAN_DIM_ENABLED = "fanDimEnabled"

    const val CUSTOM_APPS = "customApps"
    // 已选固定应用的拖动排序（JSON 数组字符串，仅含已选包名；StringSet 不保序，
    // 扇形/面板按此键排序，缺失项排尾部）
    const val CUSTOM_APPS_ORDER = "customAppsOrder"
    const val SHORTCUT_ACTIONS = "shortcut_actions"   // 用户快捷方式 JSON（权威 key）

    /** 穿透失效自动降级状态（1C：:ui 写入，设置页读出标注；非用户设置） */
    const val PASSTHROUGH_DEGRADED = "passthroughDegraded"

    /** 熔断状态（1C 轮二）：按进程分键——一端重启只清自己的键，另一端状态不被误清 */
    const val CIRCUIT_OPEN_HOME = "circuitOpen.home"
    const val CIRCUIT_OPEN_UI = "circuitOpen.ui"

    /** 设置页"手动重试"时间戳：hook 侧比较 resetAt > 本端熔断时刻即解除 */
    const val CIRCUIT_RESET_AT = "circuitResetAt"

    /** 调试开关：模拟 :ui 执行端失联（熔断链路验证用；:ui 自然死亡窗口太短无法实测） */
    const val DEBUG_RELAY_BLACKHOLE = "debugRelayBlackhole"

    // ===== :ui → 模块 App 的快捷方式 root 代发通道（§2.4 实测定案） =====
    // :ui（平台签名特权，非 uid 1000）对非 exported 目标 startActivityAsUser 静默假成功且无 su 授权；
    // 模块 App 进程持 root（su am start 可启动非导出组件，编辑页测试已验证）。
    // :ui 预检失败时把完整 ShortcutAction JSON 广播给模块 App 代发。
    const val RELAY_LAUNCH_ACTION = "com.lsp.hypersidebar.action.RELAY_LAUNCH_SHORTCUT"
    const val RELAY_LAUNCH_EXTRA_SHORTCUT = "shortcut"
    const val RELAY_LAUNCH_EXTRA_TOKEN = "token"

    /** root 代发结果回告（模块 App → :ui，失败 toast 前台；内容同时落 LAST_RELAY_RESULT 供自检报告） */
    const val ACTION_RELAY_RESULT = "com.lsp.hypersidebar.action.RELAY_RESULT"
    /** 上次 root 代发结果（模块 App 写 remotePrefs）：trace|label|ok|reason|ts */
    const val LAST_RELAY_RESULT = "lastRelayResult"

    /** 跨进程广播防伪令牌（运行期随机生成，存 remotePrefs，见 util/RelayToken）。
     *  原硬编码常量 RELAY_LAUNCH_TOKEN 已废止——反编译即可读出，等同零校验。 */
    const val RELAY_TOKEN = "relayToken"

    // ===== manifest 快捷方式 launcher 桥（批次 3）=====
    // 背景（2026-09-05 实锤）：LauncherApps.getShortcuts 对非默认桌面应用抛
    // SecurityException（Caller can't access shortcut information）——模块 App 无法直查。
    // 而 hook 的 com.miui.home 恰是默认桌面（有 shortcut 访问权）→ 请求/应答桥：
    // 设置页发 REQUEST，launcher 进程 hook 接收器查询后把 JSON 清单应答给模块 App。
    const val MANIFEST_SHORTCUTS_REQUEST = "com.lsp.hypersidebar.action.MANIFEST_SHORTCUTS_REQUEST"
    const val MANIFEST_SHORTCUTS_REPLY = "com.lsp.hypersidebar.action.MANIFEST_SHORTCUTS_REPLY"
    const val MANIFEST_SHORTCUTS_EXTRA = "list"

    // 动态/固定快捷方式代发（2026-09-08）：startShortcut 仅默认桌面可调——launcher 进程
    // 桥代发（EdgeGestureHook 内 LauncherApps.startShortcut），有序广播回执 resultCode 1/0。
    // 调用方=模块 App（编辑页测试）与 :ui（fan 点击），令牌沿用 RELAY_LAUNCH_EXTRA_TOKEN
    const val SHORTCUT_ID_LAUNCH_REQUEST = "com.lsp.hypersidebar.action.SHORTCUT_ID_LAUNCH"
    const val SHORTCUT_ID_LAUNCH_EXTRA_PKG = "pkg"
    const val SHORTCUT_ID_LAUNCH_EXTRA_ID = "sid"
    /** 代发目标=hook 宿主（默认桌面进程） */
    const val SHORTCUT_ID_LAUNCH_TARGET = "com.miui.home"

    // ===== QS 磁贴 SystemUI 直点桥（批次 3，SystemUiHook）=====
    // click-tile 门禁在 CommandQueue 回调层（控制中心样式早退），QSTile.click 无约束——
    // 有序广播进 SystemUI 进程按 spec 从数据层直点；resultCode 1=已点击 0=未就绪/未找到
    const val QS_TILE_CLICK_ACTION = "com.lsp.hypersidebar.action.QS_TILE_CLICK"
    const val QS_TILE_CLICK_EXTRA = "cn"
    /** 预热模式标记（2026-09-07 fan 呼出预热制）：true=SystemUI 侧只 prime 绑定/listening/
     *  豁免、不投递点击（fan 呼出时 FanPrewarmer 发出），点击本身仍由用户触发 */
    const val QS_TILE_PREBIND_EXTRA = "prebind"

    // ===== hook 状态探针（§2.5.4：设置页打开时有序 ping，hook 侧接收器回 resultCode） =====
    // 背景（1C 实测实锤）：hook 进程的 remotePrefs 只读（写抛 "Read only implementation"），
    // 熔断/降级状态经 prefs 回写设置页的旧通路是死路——改为接收器应答时直读进程内状态。
    /** 触发端（com.miui.home）探针 action：EdgeGestureHook 经 Application.attach 注册的接收器应答 */
    const val PROBE_ACTION_HOME = "com.lsp.hypersidebar.action.PROBE_HOME"
    /** 探针标记 extra：:ui 侧 FreeformRelayHook 收到后短路在一切动作分支之前（只应答不执行） */
    const val PROBE_EXTRA = "probe"

    /** 探针 resultCode：无应答（进程死/接收器未注册/宿主 hook 未初始化） */
    const val PROBE_CODE_DEAD = 0
    const val PROBE_CODE_OK = 1
    /** 仅 :ui（穿透失效自动降级） */
    const val PROBE_CODE_DEGRADED = 2
    const val PROBE_CODE_CIRCUIT = 3
    /** 双端（推荐数据源死亡停摆，迭代四 §1.3）：扇形已停用、hook 让位原生，重启恢复 */
    const val PROBE_CODE_DATA_DEAD = 4
    /** 仅 :ui（自检报告令牌握手，2026-09-05）：probe 附带发送端令牌且校验不通过——
     *  区分 ":ui 进程死" 与 ":ui 活但持有旧令牌快照拒收" 这对同症（都表现为 relay dead） */
    const val PROBE_CODE_TOKEN_MISMATCH = 5

    // ===== 固定应用选择页准入列表（设置页 ← :ui，探针同款有序广播信道） =====
    // 背景：选择页此前走 PM 全列表，违反 PRD §7.3.3"无小窗资格的应用在数据源层面
    // 即不展示"；而 getFreeformSuggestionList 在模块进程被 hidden API blocklist 拒绝
    // （AllAppsActivity 同款坑），只能向 :ui（特权宿主）要。模块侧自建落库缓存，
    // :ui 死时读"之前的"，首次且无应答才回退 PM 全列表。
    /** 设置页 → :ui 的准入列表请求 action（有序广播，resultExtras 回带） */
    const val ACTION_REQUEST_SUGGESTIONS = "com.lsp.hypersidebar.action.REQUEST_SUGGESTIONS"
    /** 回带 extra：StringArrayList 包名（与 AllAppsActivity.EXTRA_SUGGESTIONS 同语义） */
    const val EXTRA_SUGGESTION_LIST = "suggestions"
    /** 模块本地准入列表缓存键（设置页 prefs，JSON 数组，与 DataLoader 落盘同格式） */
    const val CACHED_SUGGESTIONS = "cachedSuggestions"
    /** 模块本地 label 镜像键（AppMetaCache 落盘快照，JSON 对象 pkg→label，冷启动组装免逐包 PM binder） */
    const val CACHED_LABELS = "cachedLabels"
}

// channelMode（EDGE/HANDLE）已废弃（1B：EDGE 为唯一产品形态，HANDLE 遗留调试通道代码
// 一并移除）；activeZone 已废弃（运行时唯一用途是 1A 拆除的距离门控——正是"完全没有
// 选中反馈"的根因，选中语义由死区/内外取消区派生即可）。旧 key 残留在 prefs 文件中无害。

/** 布局与交互参数默认值（竖屏/横屏独立），与 PRD 参数表对齐。 */
object LayoutDefaults {
    const val ICON_SIZE = 48f
    const val INNER_RADIUS = 110f       // 实测轮六：150→110（双圈过大）
    const val OUTER_RADIUS_MAX = 150f   // 实测轮六：200→150
    const val MAX_APPS_OUTER = 7
    const val MAX_APPS_INNER = 4

    const val LANDSCAPE_ICON_SIZE = 48f
    const val LANDSCAPE_MAX_APPS_OUTER = 5
    const val LANDSCAPE_MAX_APPS_INNER = 3
    const val LANDSCAPE_INNER_RADIUS = 110f   // 实测轮六：150→110
    const val LANDSCAPE_OUTER_RADIUS = 150f   // 实测轮六：200→150

    const val QUICK_ICON_SIZE = 36f

    const val DEAD_ZONE = 12f
    const val TRIGGER_DWELL_MS = 250
    const val TRIGGER_MIN_DISTANCE_DP = 30f

    // 扇形背景视觉（路线 C）：雾化默认 0.55（弧缘 100% → 锚点 35% 反向渐变，2026-09-06
    // 用户拍板：暗色主题下正向渐变的密度落在屏边不可见区，观感"没区别"）；压暗默认关
    // （opt-in 开关），压暗量固定不做成滑条
    const val FAN_FOG_INTENSITY = 0.55f
    const val FAN_DIM_ENABLED = false
    const val FAN_DIM_AMOUNT = 0.24f

    /** 所有布局相关键。恢复默认时批量写回。 */
    val layoutKeys = listOf(
        PrefKeys.ICON_SIZE,
        PrefKeys.INNER_RADIUS,
        PrefKeys.OUTER_RADIUS_MAX,
        PrefKeys.MAX_APPS_OUTER,
        PrefKeys.MAX_APPS_INNER,
        PrefKeys.LANDSCAPE_ICON_SIZE,
        PrefKeys.LANDSCAPE_MAX_APPS_OUTER,
        PrefKeys.LANDSCAPE_MAX_APPS_INNER,
        PrefKeys.LANDSCAPE_INNER_RADIUS,
        PrefKeys.LANDSCAPE_OUTER_RADIUS
    )
}
