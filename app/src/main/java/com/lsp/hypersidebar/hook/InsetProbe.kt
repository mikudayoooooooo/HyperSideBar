package com.lsp.hypersidebar.hook

import android.content.Context
import android.graphics.PixelFormat
import com.lsp.hypersidebar.util.HLog
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createAfterHook

/**
 * IME（软键盘）占位探针 —— 2026-10-17，"输入法还是有问题"的根治。
 *
 * 真机实证：这台 ROM 上隐藏 API 两条都不可用——
 *   `IWindowManager$Stub$Proxy` **没有** `getWindowInfos`（`framework.jar` 里 0 次出现）；
 *   `InputMethodManager.getInputMethodWindowVisibleHeight()` 反射取不到。
 * ⇒ 改用**纯公开 API**，两条来源同时上，取到谁用谁（谁更大用谁）：
 *
 * ① **轮询 `WindowManager.getCurrentWindowMetrics().getWindowInsets()`**（主路径）：
 *    不需要加窗、不需要监听，`SystemUI` 的 Context 直接问系统要当前窗口 insets ⇒ 读
 *    `Type.ime().bottom`。每次 [imeBottomPx] 读取前由 [refresh] 触发（1 秒缓存，见调用方）。
 * ② **满宽 1px 透明 overlay**（辅路径）：加窗后其 `ViewRootImpl` 会收到 insets 回调，
 *    把 `Type.ime().bottom` 记下来。窗口满宽（不是 1×1——1px 宽的窗口不保证收到 IME insets）、
 *    不可触摸、不可获焦、透明 ⇒ 对用户零影响。
 *
 * 诊断一律走 `android.util.Log`（`HLog` 在 `Application.attach` 极早期可能还没就绪，
 * 真机上就是"探针明明执行了却没有任何日志"）。取不到 ⇒ 一律 0 = "未知"，
 * 调用方行为与历史一致（不排除键盘），绝不让探针把功能搞坏。
 */
object InsetProbe {

    private const val TAG = "InsetProbe"

    /** 键盘占走的底部高度（px）原始值（内部用）。 */
    @Volatile private var imeBottomRaw = 0
    /** 最近一次写入时间（TTL 用）。 */
    @Volatile private var imeBottomAt = 0L
    /** 是否**曾经**拿到过任何一次有效读数（含 0=明确"键盘没起"）。安全阀只在"从未拿到"时生效。 */
    @Volatile var imeKnown: Boolean = false
        private set
    /**
     * 最近一次拿到读数的时刻（uptime）。
     *
     * 2026-10-31：`imeKnown` 是**一次性闸**——只要历史上读到过一次，它就永远为 true，
     * 于是 `FreeformBlankTapHook` 的"键盘高度未知 ⇒ 下部 45% 不算空白"安全阀再也不会生效；
     * 而探针本身随时可能失活（ROM 漂移/键盘收起后来源不再回调）⇒ 键盘上的触摸被当空白。
     * 现要求"读数足够新"才算可信（见 [imeKnownFresh]）。
     */
    @Volatile private var imeStampAt: Long = 0L

    /** 读数是否仍在 [ttlMs] 内（默认 3s：一次点按的手势链里 `imeFrame()` 会刷新它）。 */
    fun imeKnownFresh(ttlMs: Long = 3_000L): Boolean {
        val at = imeStampAt
        return at != 0L && android.os.SystemClock.uptimeMillis() - at <= ttlMs
    }
    /** overlay 探针是否报过读数（报过 ⇒ 它对该窗口的 IME 敏感 ⇒ 它的 0 也可以清带）。 */
    @Volatile private var overlaySeen = false

    /**
     * 键盘占走的底部高度（px）；0 = 未知/键盘未显示。
     *
     * **TTL 10 秒**（2026-10-17 真机教训）：ROM 给的 `WindowMetrics` 是缓存对象，键盘收起后
     * 仍可能一直报旧高度 ✗ ⇒ 旧带会把下半屏永久判成"键盘区域"（用户现象：关掉输入法后下半屏点空白
     * 既穿透又不动作）。所以任何带读数 10 秒内没有被刷新就自动作废；键盘真在时每次点按都会刷新它。
     */
    val imeBottomPx: Int
        get() {
            // WMShell 是**事件驱动**源：`insetsChanged` 只在键盘变化时来。键盘一直起着就没有新事件
            // ⇒ 若套用 TTL，10 秒后权威值过期、保护凭空失效（2026-10-18 真机实测踩到）。
            // ⇒ 事件源一律粘滞，直到下一条事件改写（收起事件一定会来）。
            if (source == SRC_WMSHELL) return imeBottomRaw
            val age = android.os.SystemClock.uptimeMillis() - imeBottomAt
            return if (age in 0..IME_TTL_MS) imeBottomRaw else 0
        }

    private const val IME_TTL_MS = 10_000L

    /**
     * 底栏（导航栏）当前是否在屏上 —— **显示级**信号，来自 SystemUI 自家视图收到的
     * `WindowInsets.isVisible(navigationBars())`（同一个 view 钩子已经在收 insets）。
     * null = 还没收到过任何 insets（未知 ⇒ 调用方按"在屏"处理，不接管）。
     *
     * 用途（2026-11-01 用户口径）：部分竖屏场景（游戏/全屏）ROM 会主动收起底栏，
     * 底角斜滑因此没人接收；底角兜底开关就是为这种时刻准备的，**与导航模式无关**。
     */
    @Volatile private var navBarVisible: Boolean? = null
    /** 底栏占用高度（px）；-1 = 未知。仅在 [navBarVisible] 为 false 时用于日志。 */
    @Volatile private var navBarBottomPx: Int = -1

    /** 底栏是否已被收起（false = 收起；未知也返回 false，宁可不出也不抢系统手势）。 */
    fun navBarHidden(): Boolean = navBarVisible == false

    /** 诊断字符串（自检/日志用）。 */
    fun navBarState(): String = "visible=$navBarVisible bottom=${navBarBottomPx}px"

    /** 最近一次取值来源（打日志/排查用）。 */
    @Volatile var source: String = "none"
        private set

    /**
     * IME 是否"接着"（`InputMethodManager.isActive()`，**公开 API**）。
     *
     * 2026-10-17：这台 ROM 上高度类探测（隐藏 API 两条 + metrics/overlay 两条）全部拿不到或恒 0，
     * 但"打字时点键盘别触发动作"这个需求**不一定需要高度**：只要 IME 是活动状态（有输入焦点），
     * 就整条动作不触发即可 —— 方向安全（正在打字的人不会想关掉小窗），且这条 API 到处都有。
     * 高度若哪天能拿到，仍然优先用"底部 band"（更精确：键盘以外的地方照常可动作）。
     */
    @Volatile var imeActive: Boolean = false
        private set

    /**
     * WMShell 是否**权威地说"键盘没起"**（且这条事件还在 TTL 内）。
     *
     * 为什么需要单独这个判据：键盘收起后 [imeRect] 为 null，但"null"也可能是钩子还没响过
     * ⇒ 不能一看到 null 就断定没键盘。只有"事件源活着 + 最近一次事件说不可见"才是权威否定，
     * 此时**不该再去走 window 清单/服务反射那些老路**——老路在这台 ROM 上要么抛异常、
     * 要么返回陈旧矩形（那正是用户报过的"下半屏永久死"的成因）。
     */
    fun wmShellSaysHidden(): Boolean =
        wmShellLive && imeBottomAt > 0L && imeRectRaw == null && !floatingIme &&
            !imeServiceSaysVisible() && !imeActive

    /**
     * OS4 实锤（2026-10-19）：SystemUI 的 `PerDisplay` insets 事件只反映 **SystemUI 自己窗口**
     * 的 IME 状态 —— 键盘是给小窗/其它应用弹的 ⇒ SystemUI 恒报 hidden（真机：键盘开着打字时
     * 事件仍来 `visible=false`），但它被当成全局权威 → `wmShellSaysHidden()=true` → 安全阀失效
     * → 键盘触摸被当"空白点击"吃掉（打字失灵 + 误触发动作）。
     *
     * 否决闸：`input_method` 服务的可见高度 > 0 = 键盘确实起着（`dumpsys input_method` 读的就是
     * 它）→ 此刻绝不能说"没键盘"。1 秒 TTL（判定路径别每拍都过 binder）。OS2/OS3 上事件源
     * 本来就正确（键盘起时 `imeRectRaw != null`，根本走不进 hidden 分支）⇒ 本闸对它们无影响。
     */
    @Volatile private var imeSvcCheckedAt = 0L
    @Volatile private var imeSvcVisibleCache = false
    private fun imeServiceSaysVisible(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - imeSvcCheckedAt > 1_000L) {
            imeSvcVisibleCache = imeHeightFromService() > 0
            imeSvcCheckedAt = now
        }
        return imeSvcVisibleCache
    }

    /** 记录底栏可见性（显示级：任意 SystemUI 视图收到的 navigationBars insets 都一样）。 */
    private fun noteNavBar(insets: WindowInsets) {
        runCatching {
            val visible = insets.isVisible(WindowInsets.Type.navigationBars())
            val bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            if (visible != navBarVisible || bottom != navBarBottomPx) {
                HLog.i(TAG, "nav bar visible=$visible bottom=${bottom}px")
            }
            navBarVisible = visible
            navBarBottomPx = bottom
        }
    }

    /** 供 insets 钩子写入（SystemUI 窗口收到的 IME insets）。 */
    fun setImeBottom(px: Int, src: String) {
        // overlay 是唯一"真实变化驱动"的来源；它一旦报过读数就**最高优先**（含报 0 清带），
        // 因为 metrics 在这台 ROM 上会返回缓存值（键盘收起仍报旧高度 ✗，用户实测下半屏被永久误杀）。
        if (src.startsWith("overlay")) {
            // 2026-10-17 真机实测：这个 1px overlay（type=2003 + ADJUST_RESIZE）**收不到 IME insets**
            // （键盘起着时回调来了但值恒 0 ✗）⇒ 绝不能当权威（否则会把带清成 0 ⇒ 键盘区变"空白"
            // ⇒ 打字被吃）。只记日志，供以后换窗口类型时参考。
            HLog.i(TAG, "overlay insets(忽略)=$px via $src")
            return
        }
        // 只接受**非零**的更新，除非这条 0 来自"同一类来源"（键盘收起）。
        // 为什么要这条防线：fallback 钩子可能命中"在 IME 之上"的 SystemUI 视图（它们恒 0）✗，
        // 若允许它们把已知的非零值覆盖成 0，键盘带就永远算不出来。
        // 2026-10-17 修正：比较**来源种类**（`metrics`/`viewHook`/`overlay`/`imService`），
        // 否则同一个 viewHook 换了类名就被当成"别的来源"，键盘收起后清不掉 ⇒ 整段下半屏被误杀 ✗。
        if (px == 0 && imeBottomRaw > 0 && kindOf(src) != kindOf(source)) return
        if (px == imeBottomRaw && src == source) return
        imeBottomRaw = px
        imeBottomAt = android.os.SystemClock.uptimeMillis()
        imeKnown = true
        imeStampAt = imeBottomAt
        source = src
        HLog.i(TAG, "ime bottom=$px via $src")
    }

    /**
     * **浮窗键盘**（IME 可见但 insets 帧高 0 ⇒ 位置不可知）——2026-10-18 真机实证。
     *
     * 搜狗「游戏键盘」是 `mIsImWindow=true mIsFloatingLayer=true` 的浮层：它**不占屏幕空间**，
     * 所以 insets 源是 `frame=[0,1080][2400,1080]`（高 0）+ `visible=true`（真机 dumpsys 实证）。
     * ⇒ insets 这条路对它**永远拿不到矩形**（不是 bug，是浮层键盘的设计）。
     * ⇒ 这种状态下调用方必须"不动作"（安全方向：宁可少一个动作，也不能点键盘时把窗口关掉）。
     */
    @Volatile var floatingIme: Boolean = false
        private set

    /** WMShell 事件源已就位（事件驱动 = 权威，见 [WmShellImeProbe]）。 */
    @Volatile var wmShellLive: Boolean = false
        private set

    /**
     * 由 [WmShellImeProbe] 写入：ROM 自己的权威事件（键盘每一次弹出/收起/变高都会来）。
     *
     * 与别的来源的区别：**无条件接受**（含 0 = 权威"键盘已收起"）——因为这是 ROM 自己
     * `DisplayImeController` 派发的事实，不存在"缓存陈旧"问题。这也是为什么它一到手就
     * 让 [refresh] 彻底不再读 metrics（那条路在这台 ROM 上恒报旧高度 ✗）。
     */
    fun setImeFromWmShell(px: Int, visible: Boolean) {
        val firstLive = !wmShellLive
        wmShellLive = true
        if (firstLive) removeOverlayAsync()
        imeStampAt = android.os.SystemClock.uptimeMillis()
        if (px == imeBottomRaw && source == SRC_WMSHELL) {
            imeBottomAt = imeStampAt  // 同值也要续 TTL
            return
        }
        imeBottomRaw = px
        imeBottomAt = android.os.SystemClock.uptimeMillis()
        imeKnown = true
        source = SRC_WMSHELL
        HLog.i(TAG, "ime bottom=$px via $SRC_WMSHELL visible=$visible")
    }

    /** WMShell 事件源给出的**权威矩形**（含横屏浮窗键盘的真实位置）；TTL 同 [imeBottomPx]。 */
    @Volatile private var imeRectRaw: android.graphics.Rect? = null

    val imeRect: android.graphics.Rect?
        get() = imeRectRaw?.let { android.graphics.Rect(it) }

    /**
     * 同 [setImeFromWmShell]，但给的是 ROM 的**原始矩形**（`InsetsSource.getFrame()`）。
     *
     * 为什么必须收矩形而不是只有高度：横屏 + 小窗时 MIUI 走**浮窗键盘**（`calcIsFloating`：
     * `frame.height() <= navBarFrameHeight`，jadx `DisplayImeController:367`），它是一块**位置任意**的
     * 小窗，既不满宽也不贴底 ⇒ "底带 = Rect(0, H-h, W, H)" 那种拼法会把旁边的空白也误判成键盘。
     * ROM 自己在这种模式下还把 `mImeFrame.bottom` 往下扩 80dp 的抓手区（`:435`），照抄即可。
     */
    fun setImeRectFromWmShell(frame: android.graphics.Rect, visible: Boolean) {
        val firstLive = !wmShellLive
        wmShellLive = true
        if (firstLive) removeOverlayAsync()
        val navBar = navBarHeightPx()
        val box = ImeRectMath.Box(frame.left, frame.top, frame.right, frame.bottom)
        val resolved = ImeRectMath.resolve(box, navBar, density())
        // 两种"浮窗"要分开：
        //  ① ROM 判据的浮窗（`height <= navBarHeight`，如横屏短键盘）⇒ **有**矩形，照常用；
        //  ② 浮层键盘（搜狗「游戏键盘」）⇒ insets 帧高 0 ⇒ **矩形不可知**，只能"不动作"。
        val romFloating = ImeRectMath.isFloating(box, navBar)
        val floating = visible && resolved.isEmpty
        floatingIme = floating
        val rect = if (visible && !floating)
            android.graphics.Rect(resolved.left, resolved.top, resolved.right, resolved.bottom)
        else null
        val changed = imeRectRaw != rect || imeBottomRaw != (rect?.height() ?: 0) || floatingIme != floating
        imeBottomAt = android.os.SystemClock.uptimeMillis()
        imeKnown = true
        imeStampAt = imeBottomAt
        source = SRC_WMSHELL
        if (changed) {
            HLog.i(TAG, "ime rect=$rect visible=$visible romFloating=$romFloating unknownFloat=$floating raw=$frame via $SRC_WMSHELL")
        }
    }

    /** 系统导航栏高度（公开 API；拿不到 ⇒ 0 = "不判定浮窗"，退化为只用原始矩形）。 */
    private fun navBarHeightPx(): Int = runCatching {
        val res = android.content.res.Resources.getSystem()
        val id = res.getIdentifier("navigation_bar_height", "dimen", "android")
        if (id == 0) 0 else res.getDimensionPixelSize(id)
    }.getOrDefault(0)

    private fun density(): Float =
        runCatching { android.content.res.Resources.getSystem().displayMetrics.density }.getOrDefault(3f)

    private const val SRC_WMSHELL = "wmshell"

    private fun kindOf(src: String): String = src.substringBefore(':', src)

    /** 依次用"宿主 Application 的 loader → 线程上下文 loader → 模块 safeLoader → 系统 loader"找类。 */
    private fun load(name: String): Pair<Class<*>?, String> {
        val candidates = listOf(
            "app" to ctxRef?.classLoader,
            "thread" to Thread.currentThread().contextClassLoader,
            "safe" to runCatching {
                io.github.kyuubiran.ezxhelper.core.ClassLoaderProvider.safeClassLoader
            }.getOrNull(),
            "sys" to ClassLoader.getSystemClassLoader(),
        )
        for ((tag, l) in candidates) {
            if (l == null) continue
            val c = runCatching { Class.forName(name, false, l) }.getOrNull()
            if (c != null) return c to tag
        }
        return null to "none"
    }

    /**
     * 直接问 **`input_method` 服务**：`IInputMethodManager.getInputMethodWindowVisibleHeight()`。
     *
     * 2026-10-17 实测：`InputMethodManager` 上反射不到这个方法（✗），但 `framework.jar` 里**有**这个
     * 名字（2 处）⇒ 它八成在 **AIDL 接口**上 ⇒ 从 `ServiceManager.getService("input_method")` 拿
     * binder、`IInputMethodManager$Stub.asInterface` 之后按方法名找（不写死签名），拿到就是键盘高度。
     * 全失败 ⇒ 返回 -1（未知），绝不影响其它路径。
     */
    private fun imeHeightFromService(): Int {
        val r = runCatching {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "input_method")
                ?: error("input_method service 不存在")
            val stubName = "com.android.internal.view.IInputMethodManager\$Stub"
            val (stub, _) = load(stubName)
            if (stub == null) error("$stubName 取不到")
            val svc = stub.getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, binder) ?: error("asInterface=null")
            val m = svc.javaClass.methods.firstOrNull {
                it.name.contains("getInputMethodWindowVisibleHeight") && it.parameterCount == 0
            } ?: error("服务上没有 getInputMethodWindowVisibleHeight")
            m.isAccessible = true
            (m.invoke(svc) as? Number)?.toInt() ?: error("返回值不是数字")
        }
        return r.getOrElse {
            if (imeSvcErr != it.message) {
                imeSvcErr = it.message
                HLog.i(TAG, "ime service probe failed: ${it.message}")
            }
            -1
        }
    }

    @Volatile private var imeSvcErr: String? = null

    /** 刷新 [imeActive]（幂等、1 秒粒度由调用方保证）。 */
    fun refreshActive(context: Context?) {
        val ctx = context ?: return
        runCatching {
            val imm = ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?: return
            val a = imm.isActive
            if (a != imeActive) {
                imeActive = a
                HLog.i(TAG, "isActive=$a")
            }
        }
    }

    /**
     * IME insets 钩子（2026-10-17 最终解）。
     *
     * 放在 **InsetProbe**（而不是新类）里，是因为真机上反复出现"新增/改动在宿主进程里不生效"
     * （LSPosed dex 缓存陈旧：同一批次里早前的类会生效，后加的不会），而本对象是**已被验证会加载**的
     * （它的 `metrics insets` 日志一直在出）⇒ 把钩子挂在活着的类里最稳。
     *
     * 原理：IME insets 只派发给"在 IME 之下"的窗口。实测 display 级 `getCurrentWindowMetrics()`
     * 的 insets 里 `ime` 恒 0，自建 `TYPE_APPLICATION_OVERLAY`(2038 > IME 2011) 也恒 0 ✗；
     * SystemUI 自己的窗口（导航栏）必然在 IME 之下 ✓ ⇒ 钩它"参数含 `WindowInsets` 的方法"
     * （候选类名取自 AOSP 标准名，`Class.forName(…, false, …)` 不初始化；全不匹配则退到
     * `View.dispatchApplyWindowInsets` 且只认导航栏系 View）。全失败 ⇒ 放弃（fail-open）。
     */
    @Volatile private var insetsHooked = false

    // ===== WMShell 事件源（2026-10-17 唯一可靠的读法；替代已删除的 dumpsys 轮询）=====
    //
    // `DisplayImeController$PerDisplay` 就在 **SystemUI 进程**里（WMShell jar 在 boot classpath），
    // 键盘每次弹出/收起/变高 ROM 自己都会调它（jadx wmshell-os4-full 实证）：
    //   `insetsChanged(InsetsState)` ⇒ `peekSource(ID_IME)` 的 `getFrame()`/`isVisible()` = 权威矩形+显隐；
    //   `updateImeVisibility(boolean)` ⇒ 显隐翻转（高度交给前者）。
    // 两个 after-hook 只读、只写本对象；解析失败 ⇒ 一条日志 + 静默（带保持"未知"⇒ 安全阀生效）。
    private const val PER_DISPLAY = "com.android.wm.shell.common.DisplayImeController\$PerDisplay"

    /** 类确实不存在（ROM 漂移）⇒ 不再重试。**loader 还没就绪不算**——那种情况要能重试。 */
    @Volatile private var wmShellClassMissing = false

    private fun installWmShellImeHooks() {
        if (wmShellHooked || wmShellClassMissing) return
        // ⚠ 不在这里提前置 `wmShellHooked`：启动期（SystemUiHook.init）宿主 classloader 可能还没
        // 就绪 ⇒ 提前置位会让后面的 `ensure()` 也放弃重试，整条事件源就此死掉（2026-10-18 修）。
        runCatching {
            val loader = io.github.kyuubiran.ezxhelper.core.ClassLoaderProvider.safeClassLoader
                ?: return
            val cls = runCatching { Class.forName(PER_DISPLAY, false, loader) }.getOrNull()
            if (cls == null) {
                wmShellClassMissing = true
                HLog.w(TAG, "wmshell ime: $PER_DISPLAY 不存在（ROM 漂移？）⇒ 事件源放弃")
                return
            }
            wmShellHooked = true
            // 晚到装配：attach 期 loader 未就绪时可能已经垫了 1px 探针窗，这里一次性退役
            removeOverlayAsync()
            val insetsStateCls = Class.forName("android.view.InsetsState", false, loader)
            val insetsSourceCls = Class.forName("android.view.InsetsSource", false, loader)
            // `InsetsSource.ID_IME`：读字段，不写死数值（ROM 改了也对得上）
            val idIme = insetsSourceCls.getDeclaredField("ID_IME").apply { isAccessible = true }.getInt(null)
            val peek = insetsStateCls.getMethod("peekSource", Int::class.javaPrimitiveType)
            val getFrame = insetsSourceCls.getMethod("getFrame")
            val isVisible = insetsSourceCls.getMethod("isVisible")

            // 拉取路要用的 Method 句柄（一次解析，之后每手 gesture 只 invoke）
            peekMethod = insetsStateCls.getMethod("peekSource", Int::class.javaPrimitiveType)
            getFrameMethod = insetsSourceCls.getMethod("getFrame")
            isVisibleMethod = insetsSourceCls.getMethod("isVisible")
            // 捕获 PerDisplay 实例：构造器在 WMShell 初始化时就跑（早于任何键盘变化）⇒ 冷启动也能拉到
            runCatching {
                val ctor = cls.declaredConstructors.firstOrNull()
                if (ctor != null) {
                    ctor.createAfterHook { p ->
                        if (perDisplay == null) {
                            perDisplay = p.thisObjectOrNull
                            HLog.i(TAG, "wmshell ime: PerDisplay captured（冷启动拉取路就绪）")
                        }
                    }
                } else {
                    HLog.w(TAG, "wmshell ime: PerDisplay 无构造器（拉取路不可用）")
                }
            }.onFailure { HLog.w(TAG, "wmshell ime: PerDisplay 捕获失败：${it.message}") }

            val mInsets = cls.declaredMethods.firstOrNull {
                it.name == "insetsChanged" && it.parameterCount == 1 && it.parameterTypes[0] == insetsStateCls
            }
            if (mInsets != null) {
                mInsets.createAfterHook { p ->
                    runCatching {
                        val st = p.args[0] ?: return@runCatching
                        val src = peek.invoke(st, idIme) ?: return@runCatching
                        val rect = getFrame.invoke(src) as? android.graphics.Rect ?: return@runCatching
                        val vis = isVisible.invoke(src) as? Boolean ?: false
                        setImeRectFromWmShell(rect, vis)
                    }
                }
                HLog.i(TAG, "wmshell ime: insetsChanged hooked")
            } else {
                HLog.w(TAG, "wmshell ime: insetsChanged(InsetsState) not found（ROM 漂移？）")
            }

            val mVis = cls.declaredMethods.firstOrNull {
                it.name == "updateImeVisibility" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == java.lang.Boolean.TYPE
            }
            if (mVis != null) {
                mVis.createAfterHook { p ->
                    runCatching {
                        // 只取"收起"这一侧（权威清带）；"弹出"的高度由 insetsChanged 给
                        if (p.args[0] as? Boolean == false) {
                            imeRectRaw = null
                            setImeFromWmShell(0, false)
                        }
                    }
                }
                HLog.i(TAG, "wmshell ime: updateImeVisibility hooked")
            } else {
                HLog.w(TAG, "wmshell ime: updateImeVisibility(boolean) not found（ROM 漂移？）")
            }
        }.onFailure {
            HLog.w(TAG, "wmshell ime hook install failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** 捕获到的 `PerDisplay` 实例（冷启动时键盘已起着 ⇒ `insetsChanged` 不会来，只能主动拉）。 */
    @Volatile private var perDisplay: Any? = null
    @Volatile private var peekMethod: java.lang.reflect.Method? = null
    @Volatile private var getFrameMethod: java.lang.reflect.Method? = null
    @Volatile private var isVisibleMethod: java.lang.reflect.Method? = null

    /**
     * ROM 自己的"键盘起着"标志：`PerDisplay.mImeShowing`（jadx `DisplayImeController:650`，
     * 由 `insetsControlChanged` 依据服务端 insets 控制的 `isInitiallyVisible()` 维护）。
     *
     * 为什么比 insets 帧可靠：**浮层键盘**（搜狗「游戏键盘」）的 IME insets 帧是退化的
     * （`frame=[0,1080][2400,1080]` 高 0），但 `mImeShowing` 照样被正确置 true
     * ⇒ 这是"键盘起着"唯一不受帧退化影响的权威来源。拿不到 ⇒ null（调用方 fail-closed）。
     */
    fun romImeShowing(): Boolean? {
        val pd = perDisplay ?: return null
        return runCatching {
            pd.javaClass.getDeclaredField("mImeShowing").apply { isAccessible = true }.getBoolean(pd)
        }.getOrNull()
    }

    /**
     * 主动拉一次权威矩形（冷启动兜底）。
     *
     * 为什么需要：`insetsChanged` **只在键盘变化时**来。SystemUI 重启/热重载时键盘往往已经起着
     * （真机实测：横屏浮窗键盘起着时强停宿主 ⇒ 模块再也拿不到矩形，保护凭空失效）⇒ 必须有拉取路。
     *
     * 做法：读 `PerDisplay.mInsetsState`（ROM 自己维护的 InsetsState，构造器里就 new 好了，
     * jadx `:334`）⇒ 同钩子里的 `peekSource(ID_IME)` ⇒ frame/visible。
     * 失败 ⇒ false（调用方继续走老路，fail-closed）。
     */
    fun pullFromWmShell(): Boolean {
        val pd = perDisplay ?: return false
        val peek = peekMethod ?: return false
        val getFrame = getFrameMethod ?: return false
        val isVisible = isVisibleMethod ?: return false
        val idIme = runCatching {
            Class.forName("android.view.InsetsSource")
                .getDeclaredField("ID_IME").apply { isAccessible = true }.getInt(null)
        }.getOrNull() ?: return false
        // 暂停更新时 ROM 把状态放进 mCachedInsetsState ⇒ 两个都试
        for (field in listOf("mInsetsState", "mCachedInsetsState")) {
            val st = runCatching {
                pd.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(pd)
            }.getOrNull() ?: continue
            val src = runCatching { peek.invoke(st, idIme) }.getOrNull() ?: continue
            val rect = runCatching { getFrame.invoke(src) as? android.graphics.Rect }.getOrNull() ?: continue
            val vis = runCatching { isVisible.invoke(src) as? Boolean }.getOrNull() ?: false
            if (!vis && rect.isEmpty) continue
            setImeRectFromWmShell(rect, vis)
            return true
        }
        return false
    }

    @Volatile private var wmShellHooked = false

    /**
     * 进程启动期就装上 WMShell 事件源（幂等）。
     *
     * 为什么要这么早：`PerDisplay` 的实例在 WMShell/Dagger 建图时就构造完了；若等到"第一次点按"
     * 才装构造器钩子，实例早已存在 ⇒ 冷启动拉取路（键盘在模块初始化前就起着）永远捕不到实例。
     * `SystemUiHook.init()` 跑在 SystemUI 进程加载期，早于 Dagger 建图 ⇒ 现在装就能捕到。
     */
    fun installWmShellHooksEagerly() {
        installWmShellImeHooks()
    }

    private fun installInsetsHook() {
        if (insetsHooked) return
        insetsHooked = true
        HLog.i(TAG, "insets hook: installing")
        val cands = listOf(
            "com.android.systemui.navigationbar.NavigationBar",
            "com.android.systemui.navigationbar.NavigationBarView",
            "com.android.systemui.navigationbar.NavigationBarTransitions",
            "com.android.systemui.navigationbar.NavBarHelper",
            "com.android.systemui.navigationbar.NavigationBarComponent",
        )
        for (name in cands) {
            val (cls, via) = load(name)
            if (cls == null) {
                HLog.i(TAG, "insets hook: $name 取不到（四种 loader 都失败）")
                continue
            }
            HLog.i(TAG, "insets hook: $name 命中（loader=$via）")
            val methods = runCatching {
                cls.declaredMethods.filter { m ->
                    m.parameterTypes.any { it == WindowInsets::class.java }
                }
            }.getOrNull() ?: continue
            if (methods.isEmpty()) {
                HLog.i(TAG, "insets hook: $name 无 WindowInsets 参数方法")
                continue
            }
            var hooked = 0
            for (m in methods) {
                runCatching {
                    m.isAccessible = true
                    m.createAfterHook { param ->
                        for (a in param.args) {
                            if (a is WindowInsets) {
                                setImeBottom(a.getInsets(WindowInsets.Type.ime()).bottom, "insets:${cls.simpleName}")
                                noteNavBar(a)
                            }
                        }
                    }
                    hooked++
                }
            }
            if (hooked > 0) {
                HLog.i(TAG, "insets hook: hooked $name methods=$hooked（继续装 View 兜底）")
            }
        }
        // View 兜底**无条件装**（不早退）：按 z 序，SystemUI 的导航栏(2019)在 IME(2011) **之上** ⇒
        // 它的 insets 不含 IME ✗；而状态栏(2000) 在 IME **之下** ⇒ 它的视图才拿得到键盘高度 ✓。
        // 两者都装，`setImeBottom` 的"拒绝其它来源报 0"保证非零值不被覆盖。
        runCatching {
            val m = android.view.View::class.java
                .getDeclaredMethod("dispatchApplyWindowInsets", WindowInsets::class.java)
            m.isAccessible = true
            m.createAfterHook { param ->
                val cn = param.thisObject?.javaClass?.name ?: return@createAfterHook
                // 只认 SystemUI 自己的视图（导航栏窗口在 IME 之下 ⇒ 会拿到非零 IME insets）；
                // 在 IME 之上的视图恒 0 也无所谓——`setImeBottom` 拒绝"其它来源报 0"覆盖已知非零值。
                if (!cn.startsWith("com.android.systemui.")) return@createAfterHook
                (param.args.getOrNull(0) as? WindowInsets)?.let {
                    setImeBottom(it.getInsets(WindowInsets.Type.ime()).bottom, "viewHook:$cn")
                    noteNavBar(it)
                }
            }
            HLog.i(TAG, "insets hook: view fallback hooked")
        }.onFailure { HLog.w(TAG, "insets hook: view fallback failed: ${it.message}") }
    }

    @Volatile private var ctxRef: Context? = null
    @Volatile private var added = false
    @Volatile private var overlayView: View? = null
    @Volatile private var overlayWm: WindowManager? = null
    private var lastLogged = -1

    /** 窗口增删统一投主线程（refresh 可能从输入线程调进来）。 */
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    private fun onMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    /**
     * WMShell 事件源可用（类存在、钩子已装）⇒ 1px 探针窗没有任何存在理由。
     * 它既读不到 IME insets（[setImeBottom] 对 overlay 来源直接忽略），又是常驻窗口——
     * 常驻悬浮窗会被下层应用算作"被遮盖"（LSPosed 防劫持会直接丢弃触摸）。
     */
    private fun wmShellSourceUsable(): Boolean = wmShellHooked && !wmShellClassMissing

    /** 按需创建 1px 探针窗（幂等；只在 WMShell 事件源不可用时才建）。 */
    private fun maybeAddOverlay() {
        if (added || overlayView != null || wmShellSourceUsable()) return
        val ctx = ctxRef ?: return
        onMain { addOverlay(ctx) }
    }

    /** 退役探针窗（幂等）。 */
    private fun removeOverlayAsync() {
        onMain {
            val v = overlayView ?: return@onMain
            overlayWm?.let { wm ->
                runCatching { wm.removeView(v) }
                    .onFailure { HLog.w(TAG, "overlay remove failed: ${it.message}") }
            }
            overlayView = null
            overlayWm = null
            added = false
        }
    }

    /** 冷启动时调用一次（可失败/可不调用，见 [ensure]）。 */
    fun start(ctx: Context) {
        HLog.i(TAG, "start() called (attach path)")
        ensure(ctx)
    }

    /**
     * **幂等惰性初始化**（2026-10-17 关键修正）：真机上 `SystemUiHook` 挂在 `Application.attach`
     * 的那段回调**没有执行到**（这个类的第一行 `Log.i` 都没出现），于是 `ctxRef` 恒为 null、
     * `refresh()` 永远拿不到值。⇒ 不再依赖 attach：`FreeformBlankTapHook.isBlank(context, ev)`
     * 手里本来就有 SystemUI 的 Context，直接拿它初始化（幂等，只做一次）。
     */
    fun ensure(ctx: Context) {
        if (ctxRef != null) return
        synchronized(this) {
            if (ctxRef != null) return
            ctxRef = ctx.applicationContext ?: ctx
            HLog.i(TAG, "ensure(): ctx=${ctxRef?.javaClass?.simpleName}")
            installWmShellImeHooks()
            maybeAddOverlay()
        }
    }

    /**
     * 热重载释放（2026-10-18）：**摘掉本代加的 overlay 探针窗**，否则——
     * 每次热重载，新代是全新 object（`added=false`）⇒ 再加一个 1px 窗；旧窗没人摘 ⇒
     * 窗数量随重载次数线性涨；且旧窗的 insets 监听器闭包握着旧代 object ⇒
     * 旧 classloader 永远回收不掉（skill 03 的"必须清理：外部回调/宿主侧引用"）。
     *
     * 不碰 `wmShellHooked`：WMShell 钩子的摘除归框架（onHotReloaded 的 oldHookHandles
     * → unhook），这里只管**我们自己加的窗**与本代状态。
     */
    fun releaseForReload() {
        runCatching { mainHandler.removeCallbacksAndMessages(null) }
            .onFailure { HLog.w(TAG, "handler cleanup failed: ${it.message}") }
        val v = overlayView
        val wm = overlayWm
        if (v != null && wm != null) {
            runCatching { wm.removeView(v) }
                .onFailure { HLog.w(TAG, "overlay remove failed: ${it.message}") }
        }
        overlayView = null
        overlayWm = null
        added = false
        ctxRef = null
        overlaySeen = false
        // 键盘带状态一并清（旧代不再读写；新代从零开始，避免旧值被误当"已知"）
        imeBottomRaw = 0
        imeBottomAt = 0L
        imeKnown = false
        imeRectRaw = null
        lastLogged = -1
        source = "none"
        HLog.i(TAG, "releaseForReload: overlay removed, probe state reset")
    }

    private fun addOverlay(ctx: Context) {
        // 幂等：maybeAddOverlay 可能从多个线程/多次 refresh 投递到这里
        if (added || overlayView != null) return
        // 窗口类型必须**低于 IME**（2011）：IME insets 只派发给"在 IME 之下"且声明会被 IME 影响的窗口。
        // 实测 `TYPE_APPLICATION_OVERLAY`(2038) 恒 0 ✗。候选（全部 < 2011）：2003/2006/2005/2002。
        val types = intArrayOf(
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,      // 2003
            WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,    // 2006
            WindowManager.LayoutParams.TYPE_TOAST,             // 2005
            WindowManager.LayoutParams.TYPE_PHONE,             // 2002
        )
        for (t in types) {
            val r = runCatching {
                val wm = ctx.getSystemService(WindowManager::class.java) ?: error("no WindowManager")
                val v = View(ctx)
                v.setOnApplyWindowInsetsListener { _, insets ->
                    val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
                    setImeBottom(ime, "overlay$t")
                    insets
                }
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, 1, t,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.BOTTOM or Gravity.START
                lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                // 绝对不参与遮挡：AOSP InputDispatcher::canBeObscuredBy 里
                // 「alpha==0 且 NOT_TOUCHABLE」的上层窗等同于不可见（不算遮挡）
                lp.alpha = 0f
                wm.addView(v, lp)
                added = true
                overlayView = v
                overlayWm = wm
                // 主动请求一次 insets 派发：否则监听器要等到下一次 insets 变化才回调
                // （键盘在加窗前就弹着的话，永远等不到 ⇒ 探针读不到值 ✗）。
                v.post { runCatching { v.requestApplyInsets() } }
                v.postDelayed({ runCatching { v.requestApplyInsets() } }, 300L)
                true
            }
            if (r.getOrDefault(false)) {
                HLog.i(TAG, "overlay probe added (type=$t)")
                return
            }
            HLog.i(TAG, "overlay type=$t 失败：${r.exceptionOrNull()?.javaClass?.simpleName}: ${r.exceptionOrNull()?.message}")
        }
        HLog.w(TAG, "overlay 探针四种类型全失败（键盘高度只能靠别的来源）")
    }

    /**
     * 轮询路径：问系统要当前窗口 insets。返回 true = 这次拿到了非零 IME 高度。
     * 由 `FreeformBlankTapHook.imeFrame` 在 1 秒缓存过期时调用；[ctx] 用来惰性初始化（幂等）。
     */
    fun refresh(ctx: Context?): Boolean {
        ctx?.let { ensure(it) }
        // 2026-10-17 关键修正：`addOverlay` 原来只在 ensure() 里调一次，而 attach 路径可能已把
        // WMShell 事件源就位 ⇒ metrics 那条路彻底不用（该 ROM 的 metrics 是缓存值 ✗）
        if (wmShellLive) return imeBottomPx > 0
        // WMShell 事件源不可用时才补探针窗（见 wmShellSourceUsable；此前无条件建，白垫一个常驻窗）
        maybeAddOverlay()
        installInsetsHook()
        // 最权威的来源：直接问 input_method 服务（键盘高度 px）
        val svc = imeHeightFromService()
        if (svc > 0) {
            setImeBottom(svc, "imService")
            return true
        }
        val c = ctxRef ?: return false
        // null = 读失败（保留旧值）；0 = **成功读到"没有键盘"**（必须清，否则旧带会误杀下半屏）。
        var readFailed = false
        val r = runCatching {
            // 用**全新的 display context**问 insets：Application/Service context 的 WindowMetrics
            // 在真机上被证实会返回缓存值（键盘收起仍报旧高度 ✗）。
            val fresh = runCatching {
                val dm = c.getSystemService(android.hardware.display.DisplayManager::class.java)
                val d = dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                if (d != null) c.createDisplayContext(d) else c
            }.getOrDefault(c)
            val wm = fresh.getSystemService(WindowManager::class.java) ?: return@runCatching null
            val ins = wm.currentWindowMetrics.windowInsets
            val ime = ins.getInsets(WindowInsets.Type.ime()).bottom
            if (ime != lastLogged) {
                lastLogged = ime
                HLog.i(TAG, "metrics insets: ime=$ime nav=" +
                    "${ins.getInsets(WindowInsets.Type.navigationBars()).bottom} " +
                    "display=${wm.currentWindowMetrics.bounds}")
            }
            ime
        }.getOrElse {
            readFailed = true
            null
        }
        if (readFailed || r == null) return false
        if (r > 0) {
            setImeBottom(r, "metrics(ime=$r)")
            // 续期：值没变也要刷新时间戳，否则打字停顿超过 TTL 就会把键盘带扔掉（打字失效 ✗）。
            imeBottomAt = android.os.SystemClock.uptimeMillis()
            return true
        }
        // 成功读到 0 ⇒ 键盘收起 ⇒ 无条件清带（2026-10-17："多试几次后穿透"的定时炸弹）
        setImeBottom(0, "metrics(ime=0)")
        return false
    }
}
