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

    /** 板材质浓度（0~0.70）：统一驱动 miuix 板材质的「主题混色 + Screen 提亮 + 噪点抖动」，
     *  0=只剩弧线与图标。键名沿用旧雾化键，保用户已调数值 */
    const val FAN_FOG_INTENSITY = "fanFogIntensity"
    /** 呼出扇形时全屏压暗开关（Compose scrim 24% 黑，与菜单同步淡入） */
    const val FAN_DIM_ENABLED = "fanDimEnabled"
    /** 毛玻璃总开关（0913 用户拍板）：开=窗口收缩包围盒+系统 blur-behind（真模糊身后内容）
     *  +磨砂板（雾化滑条控浓度，延伸覆盖快捷栏=连体玻璃板）；系统模糊被关
     *  （isCrossWindowBlurEnabled=false）时自动降级全屏窗口 */
    const val FAN_FROSTED_ENABLED = "fanFrostedEnabled"
    /** 背景模糊来源（0915 Route D 定稿：单项下拉，取代旧三开关）。
     *  取值见下方 FAN_BLUR_SOURCE_*；auto=按宿主与能力自动选路 */
    const val FAN_BLUR_SOURCE = "fanBlurSource"
    const val FAN_BLUR_SOURCE_AUTO = "auto"
    /**
     * 透明：不取背后内容、也不加板材质，只剩弧线与图标（原有的「系统裁剪模糊」已退役——
     * AOSP 背景模糊区域恒等于窗口矩形，与楔形板形必然打架，一个圆角大矩形不可接受；
     * 旧 key `dialog` 因此落到 auto）
     */
    const val FAN_BLUR_SOURCE_TRANSPARENT = "transparent"
    const val FAN_BLUR_SOURCE_WALLPAPER = "wallpaper"
    const val FAN_BLUR_SOURCE_BEHIND = "behind"
    const val FAN_BLUR_SOURCE_OFF = "off"

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

    /** 运行时调试开关：逐手势高频明细日志（g#N/s#N 系列；默认关、release 可开，§11.2）。
     *  赋值链=各进程 hook init 读一次 + ConfigSync.applySync 随全量配置刷新 */
    const val DEBUG_VERBOSE_LOGS = "debugVerboseLogs"

    // ===== 运行日志拉取通道（迭代六 §11.2）：模块 App 请求 hook 进程回传 HLog 环形缓冲 =====
    // 与 manifest 快捷方式桥同模式（REQUEST 广播 → 各进程回 REPLY；模块 App 前台=活进程必收）
    const val LOG_DUMP_REQUEST = "com.lsp.hypersidebar.action.LOG_DUMP_REQUEST"
    const val LOG_DUMP_REPLY = "com.lsp.hypersidebar.action.LOG_DUMP_REPLY"
    /** 回传 extra：进程短名（launcher/ui/app） */
    const val LOG_DUMP_EXTRA_PROC = "proc"
    /** 回传 extra：HLog.dumpJson 产物（JSON 数组字符串） */
    const val LOG_DUMP_EXTRA_LOGS = "logs"
    /** 回传 extra：进程状态快照（CircuitBreaker 等，JSON 对象字符串，可缺省） */
    const val LOG_DUMP_EXTRA_STATUS = "status"
    /** 回传 extra：StatsRecorder 全量聚合 JSON（§11.3，可缺省） */
    const val LOG_DUMP_EXTRA_STATS = "stats"

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

    // ===== FAN relay（launcher/AllApps → :ui）extra 键（0912 收口：此前裸字符串三方配对）=====
    // 注意 FAN_EXTRA_SHORTCUT/FAN_EXTRA_PKG 与 RELAY_LAUNCH_EXTRA_SHORTCUT/
    // SHORTCUT_ID_LAUNCH_EXTRA_PKG 值相同纯属两个通道取了同一个词——语义不同，勿合并常量
    const val FAN_EXTRA_PKG = "pkg"
    const val FAN_EXTRA_ALL_APPS = "allApps"
    const val FAN_EXTRA_SHORTCUT = "shortcut"
    const val FAN_EXTRA_OPEN_PANEL = "openPanel"

    // ===== root 代发结果回告（模块 App → :ui）extra 键（ShortcutRelayReceiver 写、FreeformRelayHook 读）=====
    const val RELAY_RESULT_EXTRA_OK = "ok"
    const val RELAY_RESULT_EXTRA_LABEL = "label"
    const val RELAY_RESULT_EXTRA_REASON = "reason"

    // ===== 解冻 relay（:ui → 模块 App Messenger）data 键（UnfreezeBridge 写、UnfreezeRelayService 读）=====
    const val UNFREEZE_EXTRA_PKG = "pkg"
    const val UNFREEZE_EXTRA_TOKEN = "token"

    /** 配置拉取应答（模块 App → hook 宿主 Messenger）data 键（ConfigPullService 写、ConfigPullBridge 读） */
    const val CONFIG_PULL_EXTRA_MAP = "map"

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
    const val SHORTCUT_ID_LAUNCH_TARGET = HostPackages.HOME

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

/**
 * hook 宿主包名与进程名（0912 审查收口）：此前 ~20 处裸字面量散落在广播寻址/
 * uid ACL/进程判定里，此处为唯一来源，改宿主名/加宿主只改这里。
 */
object HostPackages {
    /** 执行端宿主：fan 直启 / root 代发请求方 / QS 磁贴点击接收方（:ui 进程） */
    const val UI_HOST = "com.miui.securitycenter"
    /** 触发端宿主：边缘手势 + manifest 清单桥 + startShortcut 代发（默认桌面） */
    const val HOME = "com.miui.home"
    /** QS 磁贴数据层直点桥宿主（主进程） */
    const val SYSTEM_UI = "com.android.systemui"
    /** 执行端子进程名后缀（XposedInit 进程判定 / HLog 短名判定共用） */
    const val UI_PROCESS_SUFFIX = ":ui"
}

/** prefs 文件名（0912 审查收口）：跨进程/跨文件共用名的唯一来源。 */
object PrefsFiles {
    /** LSPosed remotePrefs——wire 契约：XposedInit 与 RemotePrefsBridge 必须同源，
     *  写错=hook 进程读到另一份空配置（模块静默失联级） */
    const val REMOTE = "hyperSidebar"
    /** 模块 App 本地 prefs（AllApps fallback + label 镜像 + manifest 快捷方式缓存） */
    const val APP_LOCAL = "hyperSidebar_prefs"
    /** hook 进程推荐列表落盘（DataLoader） */
    const val DATA = "hyperSidebar_data"
    /** 使用数据落盘（StatsRecorder，各 hook 进程本地） */
    const val STATS = "hyperSidebar_stats"
}

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

    // ===== 滑条量程契约（0913 用户拍板：量程/步进与默认值同源定义，UI 禁止内联数字）=====
    /** 滑动距离滑条（dp）：min / max / 步进。默认值=上方 TRIGGER_MIN_DISTANCE_DP，须落在格点上 */
    const val TRIGGER_SWIPE_DISTANCE_MIN_DP = 10
    const val TRIGGER_SWIPE_DISTANCE_MAX_DP = 150
    const val TRIGGER_SWIPE_DISTANCE_STEP_DP = 5
    /** 呼出停顿滑条（ms）：min / max / 步进（D2 定稿档位）。默认值=上方 TRIGGER_DWELL_MS */
    const val TRIGGER_DWELL_MIN_MS = 150
    const val TRIGGER_DWELL_MAX_MS = 350
    const val TRIGGER_DWELL_STEP_MS = 50
    /** 扇形雾化滑条：min / max / 步进。默认值=下方 FAN_FOG_INTENSITY */
    const val FAN_FOG_MIN = 0f
    const val FAN_FOG_MAX = 0.70f
    const val FAN_FOG_STEP = 0.05f

    /**
     * 图标尺寸滑条量程上限：用户"请求"的最大值。**实际可达上限还要受环上弦长约束**
     * （见 LayoutBottomSheet 的动态量程），所以这只是请求上限，不是生效上限。
     */
    const val ICON_SIZE_UI_MAX = 80f

    /**
     * 图标尺寸几何硬下限——与 `FanGeometry.fitIconSize()` 的钳制下限**同源**，禁止内联。
     * 滑条量程下限必须取它而非另写一个更大的值：弦长收缩后的生效尺寸可以低于任何
     * UI 下限（实测外圈 10 个图标时上限仅 31.5dp，曾因滑条下限写死 32dp 而使整条滑条落进死区）。
     */
    const val ICON_SIZE_HARD_MIN = 24f

    // 滑动距离（呼出确认线，dp）：原 GestureThresholds.SWIPE_CONFIRM_PX=40px 死值改可配置
    //（0913 用户拍板"40px 太极端"；15dp≈40px@440dpi 手感不变）。重置阈值=确认距离一半
    //（滞回，见 GestureThresholds.SWIPE_RESET_RATIO）。此键原为 PRD §9.5 预留死键，本次接管。
    const val TRIGGER_MIN_DISTANCE_DP = 15f

    // 扇形背景视觉（路线 C）：雾化默认 0.55（弧缘 100% → 锚点 35% 反向渐变，2026-09-06
    // 用户拍板：暗色主题下正向渐变的密度落在屏边不可见区，观感"没区别"）；压暗默认关
    // （opt-in 开关），压暗量固定不做成滑条
    const val FAN_FOG_INTENSITY = 0.55f
    const val FAN_DIM_ENABLED = false
    const val FAN_DIM_AMOUNT = 0.24f

    // ===== 板材质（Route D，0915 重构：板材质与「背后像素来源」解耦）=====
    // 板 = miuix textureBlur 单条管线：采样 backdrop（壁纸 / 空）→ 高斯模糊 → 主题混色
    // → Screen 白提亮 → 噪点抖动，按板形（扇形饼∪快捷栏胶囊）裁剪。
    // 浓度滑条（FAN_FOG_INTENSITY）统一驱动「混色+提亮+噪点」，各来源按比例缩放：
    //   · 采样壁纸——有真像素可糊，板要淡，否则闷死模糊
    //   · 系统跨窗模糊 / 后方屏幕模糊——像素在窗口层，板再浓就盖死系统模糊，只出一层淡色保底可见
    //   · 无来源——板自身承担全部材质，浓度直通
    /** 内层模糊半径（px，机械口径）：miuix 0.9.1 起 `textureBlur.blurRadius` 改按 dp 解释
     *  （内部自动 ×density）。此常量沿用 0.9.0 时代的 px 物理量（120px@440dpi≈43.6dp），
     *  调用处除以 density 还原，保证跨设备观感与旧版一致。AOSP/miuix 文档上限 150dp */
    const val FAN_BOARD_BLUR_RADIUS_PX = 120f
    /** 噪点抗条带系数基数（= miuix BlurDefaults.NoiseCoefficient 同值） */
    const val FAN_BOARD_NOISE_BASE = 0.0045f
    /** 噪点系数随浓度的增量（亚克力颗粒感来源） */
    const val FAN_BOARD_NOISE_SCALE = 0.030f
    /** 板底混色浓度缩放：采样壁纸。壁纸常是亮色而主题可能是暗色，不压一层主题色就会得到
     *  一块亮晃晃的板（真机 0915 反馈）——混色即「把背后真像素拉回主题色域」的主要手段 */
    const val FAN_BOARD_VEIL_SCALE_WALLPAPER = 0.80f
    /** 板底混色浓度缩放：系统模糊来源。这层是叠在真模糊**之上**的（零和），
     *  浓一点就把系统模糊遮没了，必须极淡 */
    const val FAN_BOARD_VEIL_SCALE_SYSTEM = 0.12f
    /** 板底混色浓度缩放：无来源——混色即板的底色本身，故远大于前者 */
    const val FAN_BOARD_VEIL_SCALE_PLAIN = 1.30f
    /** 板底混色浓度下限：保证 backdrop 图层恒非空，miuix 混色/噪点管线有像素可依 */
    const val FAN_BOARD_VEIL_MIN = 0.02f
    /** 板底混色浓度上限 */
    const val FAN_BOARD_VEIL_MAX = 0.92f
    /** Screen 白提亮随浓度的缩放：有背后内容（同混色，宁淡勿浓） */
    const val FAN_BOARD_SHEEN_SCALE_BLURRED = 0.06f
    /** Screen 白提亮随浓度的缩放：无来源（板自身即材质） */
    const val FAN_BOARD_SHEEN_SCALE_PLAIN = 0.15f
    /** 亮度补偿（[−1,1]）：只给无来源的板用——自绘底色需要按主题微调；
     *  有背后真像素时不额外提亮/压暗，否则亮壁纸会被推得更亮 */
    const val FAN_BOARD_BRIGHTNESS_DARK = 0.04f
    /** 亮色主题下的亮度补偿（白板易过曝，轻微下压） */
    const val FAN_BOARD_BRIGHTNESS_LIGHT = -0.02f
    /** 采样饱和度增益（玻璃质感） */
    const val FAN_BOARD_SATURATION = 1.15f

    /** 背景模糊来源默认值：关闭=不取背后内容，板完全由板材质浓度决定（0915 用户拍板）；
     *  非法取值的回落与「恢复默认」都引用本常量，改默认只改这里 */
    const val FAN_BLUR_SOURCE_DEFAULT = PrefKeys.FAN_BLUR_SOURCE_OFF

    /** 背景模糊来源下拉的取值顺序——设置页下标 ↔ 契约字符串的唯一映射（UI 禁止内联） */
    val FAN_BLUR_SOURCE_VALUES = listOf(
        PrefKeys.FAN_BLUR_SOURCE_AUTO,
        PrefKeys.FAN_BLUR_SOURCE_WALLPAPER,
        PrefKeys.FAN_BLUR_SOURCE_BEHIND,
        PrefKeys.FAN_BLUR_SOURCE_TRANSPARENT,
        PrefKeys.FAN_BLUR_SOURCE_OFF
    )

    /**
     * FLAG_BLUR_BEHIND 全屏景深半径，单位 **px**。AOSP 文档：模糊后方屏幕 20px 即够，
     * 同样不得超 150px。此处取 40px（比文档建议略强一点，全屏不刺眼）。
     */
    const val FAN_BEHIND_BLUR_RADIUS_PX = 40f

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
        PrefKeys.LANDSCAPE_OUTER_RADIUS,
        PrefKeys.FAN_FOG_INTENSITY,
        PrefKeys.FAN_DIM_ENABLED,
        PrefKeys.FAN_BLUR_SOURCE
    )
}
