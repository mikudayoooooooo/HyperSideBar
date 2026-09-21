# HyperSideBar 项目笔记

> **本文件由 AI 代理维护**，是仓库内的「项目理解入口 + 硬结论台账」。
> 维护约定见文末 §11。**结论性内容只写已核实的**，推测一律标注【待验证】。

---

## 1. 项目定位

把 MIUI 原生侧边栏替换为**双层半圆扇形摇杆菜单**的 LSPosed 模块。

- 交互：贴边向内滑动 → 位移停滞 `dwell`（默认 250ms）→ 呼出扇形 → 停留选中 → 松手以小窗打开。
- 可选 N1：竖屏底部左右角斜向内上滑直接呼出（`CORNER_SWIPE_ENABLED`，默认关）。
- 快滑松手 = **零干扰透传**原生返回手势；滑回边缘 / 出外圈 / 进死区 = 重置或取消。
- 运行前提：**Root + LSPosed（需 API 101+）**；仅在 **HyperOS 3.300** 验证（`com.miui.securitycenter` 12.2.8、`com.miui.home` 6.01.05）。
- 产品形态说明：`channelMode`（EDGE/HANDLE）已废弃，EDGE 是唯一产品形态；原生侧边栏开关必须保持开启（`:ui` 靠它常驻）。

---

## 2. 技术栈与构建

| 项 | 值 | 说明 |
|---|---|---|
| 语言 | Kotlin 2.4.0 | |
| UI | Jetpack Compose + **miuix 0.9.3** | `miuix-ui / preference / icons / squircle / blur / navigation3-ui` |
| 构建 | AGP 9.1.1 / Gradle 9.3.1 / daemon Java 21 | 单模块 `:app`；源码与字节码目标仍为 Java 17 |
| SDK | compileSdk **37** / minSdk **33** / targetSdk 36 | minSdk 33 是 miuix-blur 的 RuntimeShader 要求 |
| Xposed | `io.github.libxposed:api 101.0.1`（compileOnly）+ `service 101.0.0` + **EzXHelper 3.2.0-preview1** | 用 EzXHelper 的 `MethodFinder` / `HookFactory` DSL 做 hook |
| 版本 | versionName **2.1.0** | 三段式语义：2.0.0 = 包名断代标记 |
| versionCode | `yyyyMMdd * 100 + 当日序号`，由 `app/build.gradle.kts` 的 `preBuild` **自动写入** `version.properties` | ⇒ 每次构建都会弄脏 `version.properties`，属既有设计 |
| 签名 | `keystore.properties`（不入库）；缺失时 release 产未签名包 | |
| R8 | release 开 minify + shrinkResources；`XposedInit` 由 `proguard-rules.pro` 强 keep | `META-INF/xposed/java_init.list` 点名该类，被混淆即模块失联 |

**身份分裂（易错点）**：

- `namespace` = `com.lsp.hypersidebar`（源码目录、类路径、资源、广播受理类名**不动**）
- `applicationId` = `io.github.mikudayoooooooo.hypersidebar`（LSPosed 仓库审批硬约束，`com.lsp.*` 前缀不可批）

跨进程字符串寻址时，**包名部分用 applicationId，类路径部分用 namespace**（见 `DirectLaunchStrategy` 收件人寻址）。

---

## 3. 运行架构：三宿主 + 四类 hook

入口唯一：`XposedInit.kt`（`XposedModule` 子类）。按 **包名 + 进程名** 路由，包名常量集中在 `prefs/PrefContract.kt` 的 `HostPackages`（改宿主只改这里）。

| 宿主 | 进程 | Hook 类 | 职责 |
|---|---|---|---|
| `com.miui.securitycenter` | `:ui` | `TurboLayout` | **横屏 B 路线触发**（hook `com.miui.dock.sidebar.f.onTouch`，隐藏条本身即触发器）+ 竖屏穿透 flag 注入 + 视觉隐藏三层封口 + 自动降级（1C） |
| `com.miui.securitycenter` | `:ui` | `FreeformRelayHook` | 执行端：接收 fan 启动广播、校验令牌、代发结果回告、探针应答 |
| `com.miui.home` | 主进程 | `EdgeGestureHook` | **竖屏边缘手势**（hook `com.miui.home.recents.GestureStubView` + `$3.onSwipeStop` 兜底）+ **底角斜滑**（hook `com.miui.home.recents.NavStubView.onTouchEvent`）+ manifest 清单桥 + `startShortcut` 代发（仅默认桌面有权） |
| `com.android.systemui` | 主进程 | `SystemUiHook` | **QS 磁贴数据层直点**（绕开 CommandQueue 回调层的 click-tile 门禁） |

辅助 hook：`BaseHook`（统一 init 骨架）、`CircuitBreaker`、`DataDeadState`、`PanelHideState`、`GestureThresholds`、`HookProbeState`、`HookToast`。

**触发端 → 执行端链路**：launcher（`EdgeGestureHook`）→ `BroadcastLaunchStrategy` 广播 → `:ui`（`FreeformRelayHook` → `DirectLaunchStrategy`）本进程直执行；非导出组件预检失败再转模块 App **root 代发**（`ShortcutRelayReceiver`）。

---

## 4. 代码地图

```
app/src/main/java/com/lsp/hypersidebar/
├── XposedInit.kt              唯一模块入口，三宿主路由
├── MainActivity.kt            设置 App 宿主
├── ShortcutRelayReceiver.kt   root 代发收件人
├── ConfigPullService.kt       配置拉取应答（bind，兜底通道）
├── UnfreezeRelayService.kt    解冻 relay（bind 唤醒冻结进程）
├── hook/                      三宿主 hook + 熔断/探针/状态 + CornerTrigger
├── prefs/                     ⭐ PrefContract（wire 契约）+ SettingsRepository
├── ui/fan/                    ComposeFanHost(778) / FanBoard / FanGeometry(343)
│                              / FanMenuCompose / FanMenuController / QuickAppsBar /
│                              FanLaunchStrategy / IconLoader / FanMenuModels
├── ui/settings/               20+ 设置页（miuix + navigation3）
├── ui/allapps/AllAppsActivity 全部应用小窗面板
├── util/                      ShortcutLauncher(1080) / FreeformLauncher / DataLoader /
│                              AppMetaCache / AppIconCache / FanPrewarmer / FanUiWarmup /
│                              ConfigSync / ConfigPullBridge / RemotePrefsBridge /
│                              RelayToken / StatsRecorder / HLog / LogCollector /
│                              LogDumpBridge / SelfCheck / WallpaperSampler / UpdateChecker / Trace
└── theme/                     Theme.kt
```

行数最大的几个文件往往就是最危险的地方：`ShortcutLauncher.kt` > `ComposeFanHost.kt` > `TurboLayout.kt` ≈ `AllAppsActivity.kt` > `EdgeGestureHook.kt`。

---

## 5. 跨进程契约（本项目最核心的设计约束）

**hook 进程的 `RemotePreferences` 是死快照**——读侧永远是 `newInstance` 那一刻的 `mMap`，AIDL 里**没有 prefs 推送接口**，`registerOnSharedPreferenceChangeListener` 只对本进程自己的写入回调（且内部是 `WeakHashMap`，裸 lambda 一次 GC 即回收）。**这一条是 0915 读 libxposed service 源码定案的**，历史上两条错误注释已删除。

因此配置实时性完全靠自建通道：

1. `SyncedPrefs` 装饰器：读取分流（广播缓存命中优先 → remotePrefs 兜底）
2. `ConfigSync`：设置页写入 → 广播 → 各 hook 进程 `applySync` 刷新
3. `ConfigPullBridge` / `ConfigPullService`：广播丢失时 bind 拉全量（兜底）

**通道清单**（全部常量化在 `PrefContract.kt`，禁止裸字符串）：

| 通道 | 方向 | 用途 |
|---|---|---|
| `RELAY_LAUNCH_ACTION` / `ACTION_RELAY_RESULT` | `:ui` ↔ 模块 App | 非导出组件 root 代发 + 结果回告 |
| `MANIFEST_SHORTCUTS_REQUEST/REPLY` | 设置页 ↔ launcher | 非默认桌面调 `LauncherApps.getShortcuts` 抛 SecurityException，故经 launcher 代查 |
| `SHORTCUT_ID_LAUNCH_REQUEST` | 模块 App / `:ui` → launcher | 动态/固定快捷方式代发（有序广播回执 1/0） |
| `QS_TILE_CLICK_ACTION` (+ `prebind`) | fan → SystemUI | QS 磁贴数据层直点；`prebind` = fan 呼出时只预热不投递 |
| `PROBE_ACTION_HOME` / `PROBE_EXTRA` | 设置页 → 各 hook | 有序 ping，`resultCode` 0=死 1=OK 2=降级 3=熔断 4=数据源死亡 5=令牌不匹配 |
| `ACTION_REQUEST_SUGGESTIONS` | 设置页 → `:ui` | 要 `getFreeformSuggestionList` 准入列表（模块进程被 hidden API blocklist 拒） |
| `LOG_DUMP_REQUEST/REPLY` | 模块 App → 三进程 | 日志环形缓冲回传（`proc` / `logs` / `status` / `stats`） |

**安全**：`RelayToken` 运行期随机生成存 remotePrefs，跨进程广播校验（旧硬编码常量已废止——反编译即可读出＝零校验）；`ShortcutRelayReceiver` / `UnfreezeRelayService` 双重校验（Binder uid + 令牌）。

---

## 6. 记忆与文档索引（本项目的事实上的记忆系统）

| 路径 | 内容 | 是否入库 |
|---|---|---|
| `.workbuddy/memory/MEMORY.md` | **长期笔记 / 铁律台账**：架构铁律、miuix backdrop 纪律、单位陷阱、代码纪律、验证方法论 | 🚫 已 ignore（**0918 用户拍板：记忆文件不入库**） |
| `.workbuddy/memory/YYYY-MM-DD.md` | 日期日志：当天的调查、真机证据、拍板、提交 | 🚫 同上 |
| `.zcode/plans/*.md` | 迭代计划与报告：`refactor-plan-v2`（路线总纲）、`iteration1..5-plan`、`fan-bgblur-v2-plan`、`release-v2.1.0-draft`、`U0-nav3-spike-report` | 🚫 同上（代理工作文件） |
| `.mimosa/` | 审计工具产物（finding ledger / task review），两批均 `inconclusive` | 🚫 已 ignore |
| `.mimocode/` | 仅 npm 依赖安装（自带 `.gitignore`），**非记忆** | 🚫 已 ignore |
| `.novel-studio/` | 由 DSH 桌面插件 **`deepseek-harness-novel-studio`** 在「活动工作区」根目录写入的小说项目数据（`project.json`）。与本项目无关 | ☠️ **0918 已删除** + 🚫 已 ignore |
| `analysis_result.md` | 2026-09-05 兼容性验证报告（jadx 反编译核对 hook 目标存在性） | 未入库（待定去留） |
| `result.json` / `hs_err_pid13780.log` / `__pycache__/` | 外部 AI review 结果 / jvm crash dump / 工具残渣 | 🚫 后两者已 ignore |
| `README.md` | 面向用户的门面（功能、注意事项、升级须知） | ✅ 入库 |

**仓库内没有** `AGENTS.md` / `CLAUDE.md` / `.cursorrules`——项目知识走「长期笔记 + 日期日志 + 迭代计划」三轨。

> 关于 `.novel-studio/` 的排查结论（0918）：插件 `store.js` 把项目数据固定落在 `<活动工作区>/.novel-studio/`
> （`project.json` / `projects/<id>.json` / `history/<id>/` / `active-project.txt`），**本地优先、不上传**。
> 本仓库里的那份是**从未使用过的空项目**（`projectId=novel-1789730199060`，`stage=premise`，
> title/genre/chapters/scenes/characters/events 全空，只含插件自带默认技能与写作规则，`updatedAt` = 创建时刻），
> 且全仓库无任何引用（`git grep novel-studio` 零命中）⇒ 删除无影响。
> 副作用：该目录存在时，DSH 会在**此工作区的编码会话**里注入 `<novel-studio-context>` 小说驾驶舱上下文
> （所以本项目会话曾出现「当前章节：尚未选择」这类无关提示）。删除后不再注入。
> 注意：若在 GUI 里打开 Novel Studio 面板并选中本工作区，插件会**重新创建**该目录（已 ignore，不会再污染 `git status`）。

---

## 7. 硬结论（已核实，勿重复踩）

### 7.1 模糊（背景磨砂）——Route D 架构

- **板材质与「背后像素来源」解耦**：板恒为 miuix `textureBlur` 单条管线（采样 backdrop → 高斯 → 主题混色 → Screen 提亮 → 噪点 → 按板形裁剪）；backdrop 装什么由「背景模糊来源」档决定。扇形与快捷栏共用**一块连体板**。
- **来源档**：`auto` / `wallpaper`（采样壁纸，横竖屏共用一张）/ `behind`（后方屏幕全屏）/ `transparent` / **`off`（默认）**。旧的「系统裁剪模糊 / Dialog」通道**已退役**。
- **模糊只有两个家族，没有第三种**：
  1. **系统替你糊** —— 只能递「区域描述符」，形状上限 = 窗口矩形 + 圆角；
  2. **自己糊** —— 形状任意，但必须先拿到像素（Compose 只能采样本窗口已绘制的 GraphicsLayer）。
  ⇒「形状任意 + 真模糊」在 **API 37 之前不可兼得**。
- **「按板轮廓裁剪」对系统模糊是伪命题**：`DecorView.updateBackgroundBlurCorners()` 只在 `Outline.MODE_ROUND_RECT` 时取 `getRadius()`，模糊矩形恒等于窗口矩形。MIUI 上 `FLAG_BLUR_BEHIND` 实测表现是**整屏糊**（不裁窗口矩形）。
- **模糊结果不可回读**（第三次收口，逐条 SDK 实证）：`PixelCopy` 公开重载只能传自己的句柄；`SurfaceControl`/`Transaction` 成员被剥离；`MediaProjection` 需授权且会把自己拍进去；`AccessibilityService#takeScreenshot` 是唯一能读整屏的公开 API（需用户手动启用无障碍，授权太重）——且**我们窗口的模糊不改变其它层像素**，截到的也是未被本窗口模糊过的画面。**结论：不做，不必再查。**
- **API 37 的平台答案**：`SurfaceView#setBlurRegions(Collection<BlurRegion>)` + `RoundedRectBlurRegion`（公开类，Added in API 37）——一个窗口 + 一个 SurfaceView + N 个独立 alpha/radius 的圆角矩形区域，零回读零反射。⇒ 2026 年自建多窗口拼接 = 造两年后必须拆的脚手架。
- **多窗口拼接（N 个小 overlay 各糊一块）已算过，不做**：楔形填充率仅 68% 而模糊按 2r 外扩，块越小扩边占比越高 ⇒「形状精度」与「采样代价」同向恶化，**没有甜点区**；真正的阻塞是 N 次 `addView` 落在呼出关键路径 + 合成层数 +N + MIUI 模糊层数上限未知。
- **miuix backdrop 纪律**：`layerBackdrop` 只录挂在它身上的 composable 自己画的像素（不含父级 Surface）；**图层里不允许出现透明像素**（未绘制像素是 `(0,0,0,0)`，RGB=0 黑，模糊会扩散成灰黑色块）⇒ 整屏铺满不是浪费，**它就是那层不透明底**；板形裁剪交给 `textureBlur(shape=)`，不要靠「少画一点」来裁。
- **miuix `textureBlur` 的 shape 按「节点尺寸」求值**（0921 blur 源码逐行定案，此坑勿再挖）：`textureBlur` 只是 `drawBackdrop(shape = { shape }, effects = { textureBlurEffect(...) })` 的薄包装（`TextureEffect.kt:153-171`）——"迁 drawBackdrop"对渲染路径是 **no-op**，不是修复手段；`ShapeProvider` 拿 `shape.createOutline(nodeSize, …)` 缓存轮廓后交给 `placeWithLayer(clip = true, shape = …, compositingStrategy = Offscreen)`（`DrawBackdropModifier.kt:320-324 / 484-496`）。⇒ **形状必须与节点尺寸自洽**：要"小区域一块板"，就给它一个该尺寸的节点（`offset` + `size`）配**节点本地坐标**形状；节点 `fillMaxSize` + 远离原点的绝对坐标形状 ⇒ 该层离屏缓冲仍是整窗、裁剪退化成绝对坐标掩膜，真机表现为"胶囊外圈多出一块直角灰矩形"（0921 加蓝/红混色探针后灰矩形**既不是蓝也不是红**，正是这条）。**Outline 是 `Generic` 还是 `Rounded` 与该症状无关**——弧带一直是 `Generic` 却裁剪正常，0921 把胶囊换 `Rounded` 现象不变。
- 已知必修项（AOSP 文档口径，**未收口**）：系统会在省电模式等情况下**运行时停用**窗口模糊，要求监听 `addCrossWindowBlurEnabledListener` 并提高背景 alpha / 加暗层。当前 `ComposeFanHost` 是 show 时**快照读一次**，而 `BEHIND` 档板底 alpha 仅 ≈0.066 ⇒ 系统一停用就表现为「透明」。

### 7.2 单位陷阱

| 通道 | 半径单位 | 上限 |
|---|---|---|
| miuix `blurRadius` | **dp** | 150 |
| 系统 `setBackgroundBlurRadius` / `blurBehindRadius` | **px** | 150 |

AOSP 建议值：blur behind 20px、background blur 80px；本项目取 `FAN_BOARD_BLUR_RADIUS_DP = 120`、`FAN_BEHIND_BLUR_RADIUS_PX = 40`。

### 7.3 宿主与权限

- `:ui`（`com.miui.securitycenter:ui`）真实 **uid = 1000 / system**（持 `START_ANY_ACTIVITY`），0918 用 `ps` + `dumpsys` 定案；0907 的「并非 uid 1000」系 `getCallingUid` 误读，**已作废**。
- 但 `:ui` 对启动不了的目标 `startActivityAsUser` 会**静默假成功**（不抛异常、实际不启动），无法靠异常触发 root 回退 ⇒ 非导出组件统一转模块 App root 代发。
- **组件类名必须是绝对类名**：PMS/AMS 对组件类名做字面匹配，前导点组件查不到也启不了（`ComponentName` 双参构造**不展开**前导点，只有 `unflattenFromString` / `am -n` 才展开）。历史上 `normalizeActivityName` 剥包名前缀产出 `".相对名"`，是 fan COMPONENT 快捷方式**静默假成功的真根因**（0918）。
- QS 磁贴：`click-tile` 门禁在 CommandQueue 回调层（控制中心样式早退），`QSTile.click` 无约束 ⇒ 从数据层直点。
- 快捷方式：`startShortcut` 仅默认桌面可调；`LauncherApps.getShortcuts` 对非默认桌面抛 `SecurityException` ⇒ 经 launcher 进程桥代查/代发。

### 7.4 代码纪律

- 提交惯例：显式文件列表 `git add`（**不用 `-A`**）→ `git commit -F -` 写多行中文 message → `version.properties` 单独一笔 chore；提交前核 `git diff --cached --stat` 防行尾污染。
- 量程/步进/默认值必须**同源常量**（`LayoutDefaults`），UI 禁止内联魔法数；所有可调参数必须能被「恢复默认」完整重置。
- 跨进程 key/action/extra 一律进 `PrefContract.kt`；宿主包名一律进 `HostPackages`。
- 验证方法论：**下结论前先取硬证据**——AOSP 源码 → 官方文档 → 本地 `android.jar` 常量池 + `platforms/android-XX/data/api-versions.xml`（「公开 / @hide」与「since 哪一级」只能靠这两个文件定案）。

---

## 8. 版本与分支

- **209 个提交**，单人 `cja`，始于 2026-06-30 `init`；远端 `origin = github.com/mikudayoooooooo/HyperSideBar`。
- Tag：`v1.0`(09-02) → `v1.1`(09-02) → `v2.0.0`(09-08，包名断代) → `v2.1.0`(09-15，迭代六全量)。
- 当前工作分支：`exp/flyme-corner-swipe`（HEAD `d6d1b27`）。本地分支较多，实时列表以 `git branch` 为准。
- 历史事故：2026-09-07 `iter5` 提交历史曾损坏（工作区完好），**用户从回收站还原后完整性 100% 重建**。⇒ 涉及历史重写的操作要格外小心。
- 迭代主线：一（手势通道）→ 二（性能/状态治理）→ 三（UI 对齐 PRD + navigation3）→ 四（验收）→ 五（包名迁移 + 安全加固 + AllApps 风格化）→ 六（统计/日志/诊断 + 背景磨砂 Route D + 设置热更新根治 + relay 令牌安全修复）。

---

## 9. 当前状态（截至 2026-09-19）

当前分支 `exp/flyme-corner-swipe`，HEAD `d6d1b27`。0918 的 COMPONENT 快捷方式绝对类名修复已提交为 `5b407b7`，旧「未提交」记录作废。

工作区仍有未提交改动，核心是 **N1 底角斜滑呼出（默认关）**：

| 文件 | 改动 |
|---|---|
| `hook/CornerTrigger.kt` | 新增纯逻辑状态机：底角命中、方向锥、粘性接管、多指放弃、手势结束复位 |
| `hook/EdgeGestureHook.kt` | hook `NavStubView.onTouchEvent`，接管底角手势并走底角专用路径 `postShowFanCorner`（`cornerAnchor=true`）；非底角仍走 `postShowFan` |
| `hook/GestureThresholds.kt` | 新增底角宽度占比、方向锥上下限与热区高度兜底（触发参数不开放，仍为常量） |
| `prefs/PrefContract.kt` / `prefs/SettingsRepository.kt` | 新增 `cornerSwipeEnabled` + 5 项底角独立样式键（`cornerIconSize`/`cornerInnerRadius`/`cornerOuterRadius`/`cornerMaxAppsOuter`/`cornerMaxAppsInner`）的契约、默认值（=竖屏值）与恢复默认 |
| `ui/settings/LayoutBottomSheet.kt` | 布局 sheet 增 `LayoutOrientation.CORNER` 变体：`LayoutSpec` 三方向同构表（键组/默认/数量量程/弦长张角 竖150·横75·底角70）；底角变体含触发开关（同为草稿键）+ 5 滑条 + 恢复默认写 6 键 |
| `ui/settings/SettingsPage.kt` / `ui/settings/FanPreview.kt` | 「效果预览」分区新增整宽底角预览卡 `CornerLayoutPreviewCard`（180dp、含快捷栏）→ `openLayoutSheet(CORNER)`；`previewGeometry`/`FanStaticPreview` 增 `corner` 变体 |
| `ui/settings/InvokeSettingsPage.kt` | 曾加开关，最终**回退**（该文件已回到 HEAD 状态；入口统一收进底角 sheet） |
| `res/values/strings.xml` | 新增 `corner_swipe_title/summary`、`corner_layout`、`corner_preview` |
| `util/SelfCheck.kt` | 自检配置快照增加底角斜滑状态 |
| `README.md` | 用户说明增加可选底角斜滑与接管范围 |
| `ui/fan/FanGeometry.kt` / `ui/fan/FanMenuModels.kt` / `ui/fan/ComposeFanHost.kt` / `ui/fan/FanMenuController.kt` | 新增 `cornerAnchor` 底角几何与 `corner*` 独立样式字段：贴底角、固定设置半径、向上象限展开（70°）、保留全部应用数并按弧长缩小图标、快捷栏置于扇形上缘之上；`iconSizeDp`/半径/数量全部 `when{corner→landscape→竖屏}` |
| `app/src/test/java/.../CornerTriggerTest.kt` | 新增 JUnit 覆盖命中、方向锥、尾巴、多指、复位（16 例） |
| `app/src/test/java/.../FanGeometryCornerTest.kt` | 新增 JUnit 覆盖左右底角角度、独立半径、全应用保留、图标位置、快捷栏位置、首项选中与「corner\* 与竖屏互不影响」（6 例） |
| `.gitignore` / `version.properties` | 工具目录 ignore 与构建自增副产物；后者按惯例单独 chore |

`EdgeGestureHook` 中新增路径的 context/displayMetrics 读取已改用 `safeAppContext()`，避免 `EzXposed.appContext` 未就绪时抛 NPE，并清掉本轮新增 Kotlin 告警。

验证：`.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain` 已 `BUILD SUCCESSFUL`，单测 23/23（`ExampleUnitTest` 1 + `CornerTriggerTest` 16 + `FanGeometryCornerTest` 6）。尚未真机验证 `NavStubView` hook 与底部原生手势交互（`adb devices` 为空，B1–B6 待连机）。

`PROJECT_NOTES.md` 仍未跟踪；`analysis_result.md` / `result.json` 去留待定。

---

## 10. 未收口待办

1. **「后方屏幕全屏」档 alpha 下限**（建议 0.35 起）+ 把快照读换成 `addCrossWindowBlurEnabledListener` 监听。
2. **清理过期文案**：`strings.xml` 的 `fan_frosted_summary` 仍写「AOSP 背景模糊通道，按板轮廓裁剪」（已退役的 Dialog 通道描述）。
3. **`FAN_BOARD_VEIL_MIN = 0.02` 的注释与实际不符**：注释称「保证 backdrop 图层恒非空」，但按 miuix 文档口径，0.02 的图层仍是 98% 的 `(0,0,0,0)`——「空 vs 非空」≠「透明 vs 不透明」。要么多余、要么不够，建议改注释。
4. **dwell 定时器方案**：0915 定案「达标检查是事件驱动的」是更底层的根因——dwell 必须是**真定时器**，不能是事件驱动的检查。当时用户决定**下次迭代再做**。
5. **roadmap**：把 API 37 的 `SurfaceView#setBlurRegions` 写进路线文档。
6. ~~**仓库卫生**：`.workbuddy/`、`.zcode/`、`.novel-studio/`、`.mimocode/` 均未 ignore~~ ✅ **0918 已处理**：按用户拍板「记忆文件不入库」，`.workbuddy/` `.zcode/` `.mimocode/` `.novel-studio/` 及 `.kotlin/` `__pycache__/` `hs_err_pid*.log` 全部进 `.gitignore`；`.novel-studio/` 实体已删除。**遗留**：源码树内嵌的 `.mimosa` 产物（`app/src/main/java/.../.mimosa/`、`ui/settings/.mimosa/`）虽被 ignore，仍会让 IDE 索引与行数统计失真，建议挪到仓库根。
7. **去留待定**：`analysis_result.md`（2026-09-05 兼容性验证报告）与 `result.json`（外部 AI review 结果）仍未跟踪、未 ignore——按需决定入库或删除。

---

## 11. 本文件的维护约定

1. **每次会话只要改变了架构、契约、硬结论、未收口项，就更新本文件**，并在下方维护日志追加一行（日期 + 改了什么 + 依据）。
2. 结论必须可溯源：写「已核实」时同时写清证据来源（源码 / SDK 常量池 / 真机日志 / 用户拍板），否则标【待验证】。
3. 过程细节不进本文件——细节写 `.workbuddy/memory/YYYY-MM-DD.md`，本文件只放**入口地图 + 跨会话仍成立的硬结论 + 当前状态**。
4. 与 `README.md` 的分工：README 面向用户（功能、升级须知）；本文件面向开发者与代理。
5. `README.md` 里的「注意事项」是用户可见承诺，改动本文件不应与之冲突；冲突时以 README 的用户口径为准并回头修 README。

### 维护日志

| 日期 | 变更 | 依据 |
|---|---|---|
| 2026-09-18 | 建立本文件：项目定位 / 技术栈 / 三宿主架构 / 代码地图 / 跨进程契约矩阵 / 记忆索引 / 硬结论（模糊、单位、宿主权限、代码纪律）/ 分支拓扑 / 当前未提交状态 / 未收口待办 | 通读 README、`XposedInit`/`PrefContract`/`TurboLayout`/`EdgeGestureHook` 源码、`libs.versions.toml`、`app/build.gradle.kts`、`AndroidManifest.xml`、192 条提交与 tag/分支、`.workbuddy/memory/*`、`.zcode/plans/*`、`analysis_result.md` |
| 2026-09-18 | 用户拍板「记忆文件不入库」：`.gitignore` 新增 `.workbuddy/` `.zcode/` `.novel-studio/` `.mimocode/` `.kotlin/` `__pycache__/` `hs_err_pid*.log`；查明并删除空项目 `.novel-studio/`（来源=DSH 插件 `deepseek-harness-novel-studio`）；§10 第 6 项结案、新增第 7 项 | `git check-ignore -v` 逐条验证；`node` 解析 `project.json` 确认 title/chapters/scenes/characters/events 全空；`git grep novel-studio` 零命中；插件 `SECURITY.md` + `lib/host/store.js:516` |
| 2026-09-19 | 同步技术栈、当前分支与工作区状态；记录 N1 底角斜滑实现、设置开关、自检快照、README 用户说明与 `CornerTriggerTest`；修正 0918 快捷方式修复已提交状态 | `git status/log/branch`、`libs.versions.toml`、`gradle-wrapper.properties`、`gradle-daemon-jvm.properties`、`CornerTrigger`/`EdgeGestureHook`/`SettingsRepository`/`InvokeSettingsPage`/`SelfCheck` 源码、Gradle test+assemble 输出 |
| 2026-09-19 | 记录 `cornerAnchor` 底角几何与 `FanGeometryCornerTest`；`EdgeGestureHook` 新增路径改用 `safeAppContext()`；重跑 CornerTrigger/FanGeometry 单测与 `assembleDebug` 均成功 | 当前源码 diff、`FanGeometry.kt`、`FanGeometryCornerTest.kt`、`HookToast.kt`、Gradle test+assemble 输出 |
| 2026-09-20 | 底角配置入口定稿：底角独立样式 5 键 + `LayoutSpec` 三方向同构表 + 底角 sheet 内触发开关（草稿语义）+ 效果预览第二张卡 `CornerLayoutPreviewCard`；删除 `InvokeSettingsPage` 开关块（该文件回到 HEAD）；`FanGeometry.iconSizeDp` 改 `when`；`FanGeometryCornerTest` 扩到 6 例；全量 test+assemble 成功、23/23 | 用户多轮拍板（不新建二级页 / 开关进 sheet / 保留底角专用几何 / 独立样式参数）；`PrefContract`/`SettingsRepository`/`FanMenuModels`/`FanGeometry`/`FanPreview`/`LayoutBottomSheet`/`SettingsPage` 源码、Gradle test+assemble 输出 |
| 2026-09-21 | 底角分支收口合入：`exp/flyme-corner-swipe` 五笔提交（eeb765e→bd5eabe，含 380/270dp·72° 定稿、磨砂弧带、Contour TabRow 单卡、sheet SliderPreference 卡化、胶囊框线渐变端点修复）以 `--no-ff` 合入 master（33ad05f，未 push）；新分支 `exp/fan-material-motion` 已从 master 切出，计划文件 `.zcode/plans/2026-09-21-fan-material-motion-plan.md`（6 任务，视觉改动须真机验证后 commit）。关键定案：①"一体"=材质观感一致而非几何并集，勿连体；②两层同参 `textureBlur` 真机失败（胶囊出直角包围盒/弧带盖不住图标/无来源与透明档均复现），根因指向 miuix 对 `Outline.Generic` 裁剪不可靠，Task 2 迁 `drawBackdrop` 显式链+胶囊 `Outline.Rounded`；③改 `LayoutDefaults` 默认值对已存 prefs 无效（用户 0921 拍板不加版本戳，方案 A）；④allapps 图标用户自绘；⑤miuix 0.9.4 评估：progressive blur/TabRow 拖拽修复，但移除旧导航接口，Task 1 先核实 `miuix-navigation3-ui` 去留。悬置：计划执行方式未选、`version.properties` 不提交、`analysis_result.md`/`result.json` 去留未决 | 用户 0921 多轮拍板 + 真机截图证据（直角套圆角/覆盖不全）+ 红框探针日志（capsule rect 正确、bandOuter 反推出设备 prefs 仍存旧 110/150）；miuix 0.9.4 release notes 与 blur 源码调研（drawBackdrop/BackdropEffectScope/backdrop 接口） |
| 2026-09-21（晚） | **快捷栏材质/背景与扇形不一致：定位 + 清理 + 代码侧修复（工作区未提交，待真机）**。① 根因不是 Outline 形式：miuix `textureBlur` 的 shape 按**节点尺寸**求值（§7.1 新增硬结论），旧写法"节点 `fillMaxSize` + 绝对坐标胶囊 RoundRect"让胶囊层离屏缓冲仍是整窗、裁剪退化 ⇒ 蓝/红混色探针下灰矩形"既不是蓝也不是红"。改为**胶囊尺寸节点（`offset` + `size`）+ 本地坐标形状**，`quickCapsuleMetrics` 升为模糊层节点尺寸 / ③ 描边框线 / `QuickAppsBar` Row padding·spacing 的唯一来源（新增 `QUICK_BAR_GAP_RATIO`/`QUICK_BAR_SIDE_PAD_RATIO`/`QUICK_BAR_VERTICAL_PAD_RATIO`/`QUICK_BAR_MAX_ICONS`）。② 实体观感另有两处与扇形分叉，逐行对齐 `FanAppIcon`：未选中快捷图标曾恒铺 `surfaceContainerHigh@0.35` 圆角底（扇形常态无底框）、图标按 1.0 绘制（扇形 0.92）；选中标签避让式改为与 `SelectedLabel` 同参（放大半高 + 10dp）。③ 清理全部 TEMP PROBE（蓝/红混色、`probeClipCircle`、绿框包围盒、`squircleClip→clip` 试验）+ **恢复被注释掉的 `QuickAppsBar` 调用**（此前整条快捷栏根本没参与组合，任何材质排查都无意义）；`boardShape`→`bandShape`。验证：`FanBoardShapeTest` 5 例（含"轮廓必须是节点本地坐标"）通过、compile + 全量单测 + `assembleDebug` 均 BUILD SUCCESSFUL；真机验证未做（项目纪律：视觉改动真机确认后再 commit） | miuix-blur 0.9.3 sources jar 逐行（`TextureEffect.kt:153-171`、`internal/ShapeProvider.kt`、`DrawBackdropModifier.kt:320-324/484-496`、`LayerBackdrop.kt`）+ `FanBoard`/`QuickAppsBar`/`FanMenuCompose` diff + Gradle test/assemble 输出 |
| 2026-09-21（晚二） | 用户真机反馈驱动，两件事。**① 材质事故的现场证据更新**：采样壁纸档"大致满足要求"（胶囊节点尺寸自洽的修法方向初步被认可）；但**透明档仍见"一层淡淡的白色矩形"**——透明档 `FanBoard` 的 ① 采集层与 ② 两层 `textureBlur` **全部不建**，故该矩形**不可能出自板材质层**（蓝/红探针的结论在此被独立复现），嫌疑收敛到窗口/合成层或非板节点；已列入计划文件判定清单，等用户截图定性。② **抽象预览收口（用户质问"为什么还保留 previewApps/previewQuickApps"）**：删掉那两份真机应用名清单（20+4 条，纯噪声），改数量占位 `PREVIEW_APP_COUNT`/`PREVIEW_QUICK_COUNT` + `previewPlaceholderApps()`；`previewViewport`/`PreviewQuickBar` 的间距·内边距·数量上限·圆角全部改走 `FanBoard` 导出的 `QUICK_BAR_*` 与新增 `quickCapsuleCornerDp()`（旧值手抄 0.35/0.5/`take(4)`/10dp，且 `take(4)` 使预览包围盒比实机窄）。③ 计划文件新增"有效性重估"表：Task 1 已落地、Task 2·3 前提不成立、Task 4·5 与事故无关可直接用、Task 6 被 miuix-nav 取代。验证：compile + 全量单测 + `assembleDebug` 全绿 | 用户 0921 晚真机观察（采样壁纸档大致满足 / 透明档白色矩形）+ 用户质问预览残留 + `FanPreview.kt`/`FanBoard.kt` diff + Gradle test/assemble 输出 |
| 2026-09-21（晚三） | **已提交两笔**：`d438442 fix(fan)` 快捷栏材质与扇形对齐（胶囊尺寸节点+本地坐标形状、图标收口 `FanAppIcon`、清 TEMP PROBE、`FanBoardShapeTest` 5 例）、`8a24321 refactor(settings)` 抽象预览收口。**矩形归属最终定性**：用户 0921 真机确认"只包住快捷栏那一小块、与胶囊框线重合"⇒ 矩形边界 = 胶囊边界。透明档下板材质层全不建却仍有该矩形 ⇒ **矩形不是板材质**；两种形态下都存在于胶囊边界的代码只有 ③ 的胶囊框线描边，故按构造排除两条链：① 框线由 `Path.addRoundRect` + `drawPath` 改 `DrawScope.drawRoundRect`（Skia 原生圆角矩形光栅化，圆角退化成直角矩形这条路彻底消失，顺手删 `quickCapsulePath`）；② 两块板层都加**外层显式 `Modifier.clip(shape)`**——不再把"材质只出现在形状内"交给 miuix 内部 `placeWithLayer(clip=…)`（真机上弧带正常、胶囊出直角矩形，说明那条链不可靠）。**真机确认通过（用户当晚："可以了"，直角矩形消失）**，本行随修复一起入库。注：两处改动同时生效，**未做单变量归因**（要归因需拆开各验一轮，风险不值） | 用户 0921 晚真机描述（矩形只包住胶囊、与框线重合）+ 真机复验通过 + miuix `DrawBackdropModifier` 裁剪链源码 + Gradle test/assemble 输出 |
| 2026-09-21（晚四） | 用户指出抽象预览"数量上应该和实际的保持一致"，据此把两处数量从占位改成**实读**（`badb209`）：快捷栏项数走真机同源的 `ShortcutStore.buildRuntimeQuickList`（条件性面板占位 + 已启用用户项，上限 `ShortcutStore.MAX_USER_SHORTCUTS` 含占位；读「生效存储」= `RemotePrefsBridge.prefs ?: 传入 prefs`），故启用/排序一变即同步；`FanStaticPreview` 因此新增 `prefs` 参数（两个调用点：效果预览卡、布局 sheet 实时预览，均已有 `repo.prefs`）并把它并入 geometry 的 `remember` key。扇形项数 = 该方向 `maxOuter + maxInner`（真机末位"全部应用"哨兵已计入该值，下限 1），不再截到人为常数 20。`PREVIEW_APP_COUNT`/`PREVIEW_QUICK_COUNT` 两个占位常数彻底删除 | 用户 0921 指令 + `ShortcutStore.buildRuntimeQuickList`/`MAX_USER_SHORTCUTS` 源码 + Gradle test/assemble 输出 |
