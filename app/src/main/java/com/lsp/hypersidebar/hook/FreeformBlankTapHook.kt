package com.lsp.hypersidebar.hook

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.view.MotionEvent
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.util.HLog
import io.github.kyuubiran.ezxhelper.core.ClassLoaderProvider
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createAfterHook

/**
 * 小窗「点击空白处缩小/关闭」的**动作层**（ROM 耦合全集中在这里）。
 *
 * ## 分工
 * - 检测：`SystemUiInputSpy`（已有的全局 monitor 通道，无条件启动）判定"轻点"，
 *   判定核是纯 JVM 的 `BlankTapDetector`；本类提供"这一下算不算空白"（[isBlank]）。
 * - 动作：本类按动作档调 ROM 的小窗控制器（[perform]）。
 *
 * ## 为什么不做成自己的 hook
 * ROM 自己已有一个全屏输入 spy（OS3/4 `MulWinSwitchEventHandler.onEvent`，OS2
 * `MiuiWindowDecorViewModel.handleReceivedMotionEvent`），但**我们已经有一个**常驻的
 * （spy 通道 `hypersidebar-spy`，带聋 monitor 重建自愈）。再挂一个 ROM 入口只多一份
 * 类名漂移面 ⇒ 检测复用既有 spy（设计文档 §4 路线 A 的落地取舍）。
 * 唯一例外是 OS2 的**实例捕获**（见下），它是只读的、且不参与判定。
 *
 * ## ROM 分化（2026-10-16 真机实证，与文档 §6 不同处以此为准）
 *
 * 设备（merlin，HyperOS 2.1.1）**自己的 SystemUI APK dex** 里的名字与
 * `docs/adaptation/freeform-blank-tap-scheme.md` §6（来自另一份 OS2 OTA 的 jadx）**不一致**：
 * OS2 这份构建用的是 `com.android.wm.shell.miuifreeform.*`
 * （不是 `…shell.multitasking.miuifreeform.*`），持有者是
 * `…miuimultiwinswitch.miuiwindowdecor.MiuiWindowDecorViewModel`
 * （不是 `MulWinSwitchDecorViewModel`，也没有 `MulWinSwitchInteractUtil`/`MultiTaskingControllerImpl`）。
 * 两族命名都存在 ⇒ 一律**候选表 + 运行时探测**，命中哪个用哪个（fail-closed）。
 *
 * | 能力 | OS3 / OS4（WMShell jar） | OS2（宿主 APK dex） |
 * |---|---|---|
 * | 句柄源 | `…dagger.MultiTaskingControllerImpl.getInstance()`（有 `getMiuiFreeformModeController`/`getMiuiFreeformModeTaskRepository`） | 无单例；**捕获** `MiuiWindowDecorViewModel` 实例（只读 hook） |
 * | 空白判定 | `…MulWinSwitchInteractUtil.getInstance().isTouchedInFreeformWindow(ev)`（**public**） | `MiuiWindowDecorViewModel.isTouchedInFreeformWindow(ev)`（**private**，setAccessible） |
 * | 缩小 | `fromFreeformToMini(I)V`（**public**） | 同签名，但 **private** ⇒ setAccessible |
 * | 关闭单任务 | `exitFreeformTask(I,Z[,Z])` | **无 API** ⇒ 降级广播 `state=toHome` |
 *
 * 反射一律**按方法名 + 参数个数**匹配（`exitFreeformTask` 的 2/3 参代差已在反编译里坐实），
 * 任何一步失败都只记日志并退到下一层——绝不抛（跑在 SystemUI 主线程）。
 */
class FreeformBlankTapHook(private val prefs: SharedPreferences) : BaseHook() {

    override val name = "FreeformBlankTap"

    companion object {
        private const val TAG = "FreeformTap"

        /** OS4/OS3 的 `TaskOperations`（caption X 关闭的真实入口持有者；构造器只读捕获）。 */
        private const val TASK_OPERATIONS = "com.android.wm.shell.windowdecor.TaskOperations"

        /** 暂存的 RunningTaskInfo 上限（OS2 close 用；超出丢最旧）。 */
        private const val TASK_INFO_CACHE = 8

        /** 稳定帧/动画空闲门的轮询间隔（一帧） */
        private const val POLL_MS = 16L
        /** 稳定帧门超时（ms）：超了照做（fail-open，不添乱） */
        private const val STABLE_TIMEOUT_MS = 700L
        /** "动画空闲"门超时（ms）：超了照关（fail-open） */
        private const val IDLE_ANIM_TIMEOUT_MS = 700L

        /** 当前装入的实例（spy 是 `object`，通过这里拿到动作层；与 B2StreamDispatch 同款做法） */
        @Volatile
        var current: FreeformBlankTapHook? = null; private set

        // ---- OS3/OS4：Dagger 持有者 + 单例判定工具（jadx: wmshell-os4-full 实证）----
        private const val HOLDER_OS34 = "com.android.wm.shell.dagger.MultiTaskingControllerImpl"

        /** holder 解析失败后的冷却（10s）：期内不再碰单例，期满后的下一个使用点重试。 */
        private const val HOLDER_RETRY_COOLDOWN_MS = 10_000L

        /** pinAll posted 后的生效回查延迟（与 close 链的 verifyGoneThen 同量级）。 */
        private const val PIN_VERIFY_DELAY_MS = 700L
        private const val INTERACT_UTIL_OS34 =
            "com.android.wm.shell.multitasking.miuimultiwinswitch.MulWinSwitchInteractUtil"
        private const val DESKTOP_STATUS_OS34 =
            "com.android.wm.shell.multitasking.stubs.miuidesktopmode.MiuiDesktopModeStatus"

        /**
         * OS2 的窗口装饰 ViewModel：**实例没有静态出口**（2026-10-16 全 dex 扫过：该类及其
         * 接口都没有 static 字段/getInstance），只能靠只读 hook 捕获 `this`。
         * 两个候选是两份 OS2 构建的实测名（设备 dexdump / 另一份 OTA 的 jadx）。
         */
        private val VM_CANDIDATES = listOf(
            "com.android.wm.shell.miuimultiwinswitch.miuiwindowdecor.MiuiWindowDecorViewModel",
            "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.MulWinSwitchDecorViewModel",
        )

        /**
         * 捕获点：public 的任务变化回调优先；ROM 的全屏 spy 兜底（窗口存在时每次触摸都会到）。
         * 按"方法名 + 参数个数 + 首个参数类型"匹配，不写 `InputMonitor` 类型——那是 @hide
         * 类，模块编译期看不到（`hidden-api/` 只放了 InputChannel/InputEventReceiver）。
         */
        private val CAPTURE_METHODS = listOf(
            Triple("onTaskInfoChanged", 1, "android.app.ActivityManager\$RunningTaskInfo"),
            Triple("handleReceivedMotionEvent", 2, "android.view.MotionEvent"),
        )
    }

    /** 一次性解析出来的 ROM 侧句柄（失败即为 null ⇒ 对应能力不可用，fail-closed） */
    private var controller: Any? = null
    private var repository: Any? = null
    /** OS2：捕获到的 `MiuiWindowDecorViewModel` 实例（`null` = 还没捕到）。 */
    private var decorVm: Any? = null
    /**
     * OS2 的"关闭单任务"需要一个 `ActivityManager.RunningTaskInfo`（`MiuiTaskOperations.minimizeTask`
     * 只收这个类型；`MiuiFreeformModeTaskInfo` 不暴露它）。**不去猜 `mTopFocusTaskInfo`**——那可能
     * 是桌面（误最小化桌面是灾难）；改为复用已有的捕获 hook：ROM 的
     * `onTaskInfoChanged(RunningTaskInfo)` 每次都会带一个 info 进来，按 taskId 存最近一份。
     * 上限 [TASK_INFO_CACHE] 条，取不到就退广播兜底。
     */
    private val taskInfoByTask = java.util.LinkedHashMap<Int, Any>()
    /** 捕获到的 `TaskOperations`（`startRemoveTransition` = 原生 caption X 的"关闭"过渡）。 */
    private var taskOps: Any? = null
    private var predicateOwner: Any? = null
    private var predicateMethod: java.lang.reflect.Method? = null
    private var desktopActiveMethod: java.lang.reflect.Method? = null
    private var primed = false
    /** 谓词/仓库访问器失败只打一次（否则每次抬手刷屏）。 */
    private var predDiagLogged = false
    private var repoDiagLogged = false
    private var available = false
    /** 解析过程的结论（自检/日志用：哪条路成了、哪条没有）。 */
    private var resolveNote = "not primed"

    override fun init() {
        current = this
        prime()
        HLog.i(TAG, "primed=$primed available=$available $resolveNote")
    }

    /** 热重载释放：丢掉旧代的反射句柄与捕获实例（旧 classloader 引用），不摘任何宿主侧东西。 */
    override fun releaseForReload() {
        controller = null
        repository = null
        decorVm = null
        predicateOwner = null
        predicateMethod = null
        desktopActiveMethod = null
        // 旧代的 Class 引用必须丢：它会把整个旧 classloader 钉住（热重载回收不了）。
        decorVmClass = null
        holderClass = null
        holderFailedAtMs = null
        taskOps = null
        predDiagLogged = false
        repoDiagLogged = false
        synchronized(taskInfoByTask) { taskInfoByTask.clear() }
        primed = false
        available = false
        current = null
    }

    /**
     * 宿主类解析**必须走宿主 classloader**。
     *
     * `Class.forName(name)` 用的是**模块自身**的 loader，看不到 SystemUI 的类（仓库惯例的
     * 由来见 `SystemUiHook.hookAdapterStash` 的注释：`javaClass.classLoader` 是模块自身的，
     * 11:22 实测 not found）——2026-10-16 真机实测本文件里三处 `Class.forName` 全部
     * ClassNotFoundException 就是这个原因。
     *
     * 另外 `anchor/` 的三条 freeform role **不能**当本功能的前置：DexKit 结构扫只看宿主 APK，
     * 而 OS2 这批类确实在宿主 APK 里、OS3/4 却在 WMShell jar 里（OS2 实测 anchor 三条全
     * NOT_FOUND 而类其实可用）。所以这里自己按候选表解析，anchor 那三条只作 ROM 形态自证。
     */
    private fun hostClass(name: String): Class<*> = ClassLoaderProvider.safeClassLoader.loadClass(name)

    private fun prime() {
        if (primed) return
        primed = true
        val notes = StringBuilder()

        // ① OS3/OS4：Dagger 持有者——**只 loadClass，绝不在这里调 getInstance()**。
        //
        // 2026-10-17 OS4 真机血案：`MultiTaskingControllerStub.sInstance` 是
        // `static final … = MiuiStubUtil.getInstance(MultiTaskingControllerStub.class)`，
        // 即在**类初始化那一刻**定死。hook 安装发生在 WMShell/Dagger 注册实现之前，
        // 此时调 `MultiTaskingControllerImpl.getInstance()` 会把这个 static final 钉成
        // bare stub ⇒ ROM 自己的 `SoScSplitSplitScreenController.onInit` 之后
        // `(MultiTaskingControllerImpl) getInstance()` 必抛 ClassCastException
        // ⇒ **SystemUI 崩溃循环**（实测 `Crash unexpectedly … ClassCastException`，每次重启复现）。
        // ⇒ 推迟到首次真正使用（那时 UI 已起、Dagger 已注册）再解析：见 resolveHolderLazy()。
        holderClass = runCatching { hostClass(HOLDER_OS34) }.getOrNull()
        notes.append(if (holderClass != null) "holder=OS3/4(deferred) " else "holder=OS3/4:none ")

        // ② 判定句柄（OS3/4 的 public 单例；OS2 的 private 方法在后面捕获到实例时再挂）
        runCatching {
            val util = hostClass(INTERACT_UTIL_OS34).getMethod("getInstance").invoke(null)
            val m = util.javaClass.getMethod("isTouchedInFreeformWindow", MotionEvent::class.java)
            predicateOwner = util
            predicateMethod = m
            notes.append("pred=OS3/4 ")
        }.onFailure {
            notes.append("pred=OS3/4:none(${it.javaClass.simpleName}) ")
        }

        // ②b 关闭过渡的持有者：TaskOperations（只读捕获构造器；不依赖谁 new 它）
        attachTaskOperationsCapture(notes)

        // ③ OS2：没有单例 ⇒ 只读捕获实例（含其 private 判定方法的 Method 句柄）
        if (controller == null) attachVmCapture(notes)

        // ④ 桌面模式早退判据（只被纯核回退用到）
        runCatching {
            desktopActiveMethod = hostClass(DESKTOP_STATUS_OS34).getMethod("isActive", Context::class.java)
        }.onFailure { notes.append("desktopStatus=none ") }

        // "可用"= 已经有控制器，或捕获路径已装好（实例可能还没到，届时按 fail-closed 处理）
        available = controller != null || decorVmClass != null || holderClass != null
        resolveNote = notes.toString().trim()
    }

    /**
     * 只读捕获 `TaskOperations` 实例。
     *
     * 为什么需要它：ROM 的"主动关闭"（caption X）不是 `exitFreeformTask`（那条内部是
     * `exitFreeformWithoutAnimation`，无过渡），而是
     * `WCT.removeTask(taskToken)` + `TaskOperations.mTransitionStarter.startRemoveTransition(wct)`
     * （反编译实证）。`TaskOperations` 由 `CaptionWindowDecorViewModel` / `DesktopModeWindowDecorViewModel`
     * 在构造期 new 出来，MIUI 的 freeform 装饰未必是这两个类 ⇒ 直接挂**它自己的构造器**最稳。
     * after-hook 只存引用、不做任何事（零行为变化）。
     */
    private fun attachTaskOperationsCapture(notes: StringBuilder) {
        runCatching {
            val cls = hostClass(TASK_OPERATIONS)
            var hooked = 0
            cls.declaredConstructors.forEach { ctor ->
                runCatching {
                    ctor.createAfterHook { p -> if (taskOps == null) taskOps = p.thisObjectOrNull }
                    hooked++
                }
            }
            notes.append(if (hooked > 0) "taskOps=hooked($hooked) " else "taskOps=noCtor ")
        }.onFailure { notes.append("taskOps=none(${it.javaClass.simpleName}) ") }
    }

    /** 捕获用的类句柄（判 ②③ 是否"有路可走"用；实例本身在 [decorVm]）。 */
    private var decorVmClass: Class<*>? = null

    /** OS3/4 持有者类（**只 loadClass**；真正调 getInstance 一律推迟到 [resolveHolderLazy]）。 */
    private var holderClass: Class<*>? = null
    /** 上次 holder 解析失败的 monotonic 时间戳；null = 从未失败（解析成功后靠 controller!=null 短路）。 */
    private var holderFailedAtMs: Long? = null

    /**
     * OS2 路径：只读 hook 一个 ROM 自己的方法，把 `this`（`MiuiWindowDecorViewModel`）存下来。
     *
     * 为什么必须这样拿实例：全 dex 扫过，该类及其接口都没有 static 字段/getInstance（2026-10-16）；
     * 而它同时握着控制器（public `getMiuiFreeformModeController()`）、任务仓库（private 字段
     * `mMiuiFreeformModeTaskRepository`）和 ROM 自己的空白判定（private `isTouchedInFreeformWindow`）。
     *
     * 捕获点是 ROM 自己的调用路径，**只读不改**（after-hook 里只存引用、不做任何事）：
     * - `onTaskInfoChanged(RunningTaskInfo)`：任务变化即调（public，设备实证）；
     * - `handleReceivedMotionEvent(MotionEvent,InputMonitor)`：ROM 的全屏 spy，窗口存在时每次触摸
     *   都会到（private，设备实证）⇒ 即使 onTaskInfoChanged 一直不响，同一次手势的 DOWN 也会先捕到。
     */
    private fun attachVmCapture(notes: StringBuilder) {
        for (cn in VM_CANDIDATES) {
            val cls = runCatching { hostClass(cn) }.getOrNull() ?: continue
            decorVmClass = cls
            // 判定方法的 Method 句柄（private，setAccessible；实例到位后即可用）
            runCatching {
                val m = cls.getDeclaredMethod("isTouchedInFreeformWindow", MotionEvent::class.java)
                m.isAccessible = true
                predicateMethod = m
            }.onFailure { notes.append("pred=OS2:noMethod ") }
            var hooked = 0
            for ((mn, arity, firstParam) in CAPTURE_METHODS) {
                val m = cls.declaredMethods.firstOrNull {
                    it.name == mn && it.parameterCount == arity &&
                        it.parameterTypes.firstOrNull()?.name == firstParam &&
                        it.parameterTypes.getOrNull(1)?.name == "android.view.InputMonitor" || (
                            it.name == mn && it.parameterCount == arity &&
                                it.parameterTypes.firstOrNull()?.name == firstParam && arity == 1
                            )
                } ?: continue
                runCatching {
                    m.isAccessible = true
                    m.createAfterHook { p ->
                        // 顺带把 ROM 给的 RunningTaskInfo 按 taskId 存一份（OS2 close 用）
                        runCatching {
                            val arg = p.args.firstOrNull()
                            val id = arg?.javaClass?.getField("taskId")?.getInt(arg) ?: return@runCatching
                            synchronized(taskInfoByTask) {
                                taskInfoByTask[id] = arg
                                while (taskInfoByTask.size > TASK_INFO_CACHE) {
                                    val it = taskInfoByTask.keys.iterator()
                                    if (it.hasNext()) { it.next(); it.remove() }
                                }
                            }
                        }
                        if (decorVm == null) {
                            decorVm = p.thisObjectOrNull
                            // ⚠ 只有判定方法确实**声明在 VM 类上**时才把 owner 换成 VM；
                            // OS4 的判定方法来自 MulWinSwitchInteractUtil 单例，换 owner 会抛
                            // IllegalArgumentException（2026-10-17 真机实测）。
                            val ownerOk = predicateMethod?.declaringClass?.isInstance(decorVm) == true
                            if (ownerOk) predicateOwner = decorVm
                            adoptVmHandles()
                            HLog.i(TAG, "decor VM captured via $mn: controller=${controller != null} predOwner=$ownerOk")
                        }
                    }
                    hooked++
                }.onFailure { HLog.w(TAG, "capture hook $cn#$mn failed: ${it.message}") }
            }
            if (hooked > 0) {
                notes.append("vm=$cn(hooks=$hooked) ")
                return
            }
        }
        notes.append("vm=none ")
    }

    /**
     * 首次真正使用时才解析 OS3/4 持有者（**绝对不能在 hook 安装期做**，见 prime 里的血案注释）。
     * 只在"控制器还没拿到"时试一次；`getMiuiFreeformModeController` 若因单例不对而失败，
     * runCatching 兜住（绝不把 ROM 的 CCE 抛进 SystemUI）。
     */
    private fun resolveHolderLazy() {
        if (controller != null) return
        // 2026-10-25 改：原"一次性锁死"（首次失败永久不再试）⇒ 首个使用点恰逢单例未就绪
        //（WMShell 尚未装配完）时，整个 boot 都落 su 兜底。改为**失败冷却重试**：≥10s 后的
        // 下一个使用点允许再解析。红线不变——仍只在 isBlank/perform 的使用时点碰单例
        //（AGENTS：绝不在 hook 安装期触碰 WMShell 单例）。
        holderFailedAtMs?.let {
            if (android.os.SystemClock.elapsedRealtime() - it < HOLDER_RETRY_COOLDOWN_MS) return
        }
        val h = holderClass ?: return
        val ok = runCatching {
            val inst = h.getMethod("getInstance").invoke(null)
            controller = h.getMethod("getMiuiFreeformModeController").invoke(inst)
            // 仓库：OS3/4 的 z-order/取任务访问器在 **MultiTaskingTaskRepository** 上，
            // 而 `MiuiFreeformModeTaskRepository` **不继承**它（三代反编译实证）；
            // 优先取前者，拿不到再退回后者（OS2 的后者自带访问器）。
            repository = pickTaskRepository(inst)
            HLog.i(TAG, "holder resolved lazily: controller=${controller != null} " +
                "repo=${repository?.javaClass?.simpleName}")
            controller != null
        }.onFailure {
            HLog.w(TAG, "holder lazy resolve failed: ${causeLine(it)}")
        }.getOrDefault(false)
        if (!ok) holderFailedAtMs = android.os.SystemClock.elapsedRealtime()
    }

    /**
     * 取"带 z-order / 取任务访问器"的那个仓库。
     *
     * 三代结构（反编译实证）：OS2 只有 `MiuiFreeformModeTaskRepository` 且访问器在它自己身上；
     * OS3/OS4 另有 `MultiTaskingTaskRepository`（`getFreeformTasksInZOrder()` / `getMiuiFreeformTaskInfo(int)`），
     * 而 `MiuiFreeformModeTaskRepository` **不继承**它 ⇒ 必须优先取后者，否则取不到任何窗口（实测 kernel=null）。
     */
    private fun pickTaskRepository(holder: Any?): Any? {
        holder?.let { h ->
            runCatching { h.javaClass.getMethod("getMultiTaskingTaskRepository").invoke(h) }
                .getOrNull()?.let { return it }
        }
        val c = controller
        if (c != null) {
            runCatching {
                val f = c.javaClass.getDeclaredField("mMultiTaskingTaskRepository")
                f.isAccessible = true
                f.get(c)
            }.getOrNull()?.let { return it }
        }
        return holder?.let { h ->
            runCatching { h.javaClass.getMethod("getMiuiFreeformModeTaskRepository").invoke(h) }.getOrNull()
        } ?: repository
    }

    /** 捕获到实例后，把它的控制器/仓库/判定 owner 接到统一句柄上。 */
    private fun adoptVmHandles() {
        val vm = decorVm ?: return
        runCatching {
            controller = vm.javaClass.getMethod("getMiuiFreeformModeController").invoke(vm)
        }.onFailure { HLog.w(TAG, "getMiuiFreeformModeController failed: ${it.message}") }
        repository = pickTaskRepository(holder = null) ?: runCatching {
            // OS2 设备实证：字段 `mMiuiFreeformModeTaskRepository`（private）；
            // 另一份构建里 R8 还生成了同名 getter，两条都试。
            val f = vm.javaClass.getDeclaredField("mMiuiFreeformModeTaskRepository")
            f.isAccessible = true
            f.get(vm)
        }.getOrElse {
            runCatching {
                vm.javaClass.getMethod("getmMiuiFreeformModeTaskRepository").invoke(vm)
            }.getOrNull()
        }
        runCatching { repository?.let { it } }
    }

    // ===== 空白判定 =====

    /**
     * 该点是否算"小窗之外的空白"。
     *
     * 三层，从权威到兜底：
     * 1. ROM 自己的 `isTouchedInFreeformWindow(ev)`（OS3/4 public 单例 / OS2 private 实例方法）
     *    —— 那是 ROM 判定"点在不在小窗里"的真值，我们只取反；
     * 2. 拿不到 ROM 判定时回落纯核 `FreeformBlankTest`（数据从任务仓库反射取出）；
     * 3. 两层都拿不到数据 ⇒ 返回 **null**（= 不知道，不是"不空白"）。调用方（spy）对
     *    DOWN 端的 null 不否决（首个手势常还没捕到实例），UP 端必须拿到明确的 true 才动作
     *    （fail-closed 落在这一端：拿不到数据就不动作）。
     */
    fun isBlank(context: Context?, ev: MotionEvent): Boolean? {
        prime()
        resolveHolderLazy()
        val owner = predicateOwner
        val m = predicateMethod
        var predBlank: Boolean? = null
        if (owner != null && m != null) {
            runCatching { m.invoke(owner, ev) as Boolean }
                .onSuccess { predBlank = !it }
                .onFailure {
                    if (!predDiagLogged) {
                        predDiagLogged = true
                        HLog.w(TAG, "predicate invoke failed: ${causeLine(it)}")
                    }
                }
        } else if (!predDiagLogged) {
            predDiagLogged = true
            HLog.w(TAG, "predicate unavailable: owner=${owner != null} method=${m != null}")
        }
        // 纯核（回退/补位）：空列表 ≠ "没有小窗"——也可能是仓库/实例还没就绪（OS2 的实例是只读
        // hook 捕来的，首个手势的 DOWN 常常还没捕到）⇒ 归到"拿不到数据"（null）。
        val kernelBlank = runCatching {
            val windows = windowsInZOrder()
            if (windows.isEmpty()) null
            else FreeformBlankTest.isBlank(
                ev.x, ev.y, desktopModeActive = desktopModeActive(context), windowsInZOrder = windows
            )
        }.getOrElse {
            HLog.w(TAG, "blank fallback failed: ${it.message}")
            null
        }
        // 取**与**：任一来源说"点在小窗内"就不算空白（动作触发的安全方向）。
        // 2026-10-16 真机实测：mini 态小窗（贴边缩小后）ROM 的 isTouchedInFreeformWindow 会漏判，
        // 只信 ROM 判定 ⇒ 点 mini 窗内部也会触发动作 ⇒ 必须让纯核补位。
        // 语义取舍（2026-10-16 真机实证）：**几何优先，ROM 判定只做回退**。
        // 原因：ROM 的 `isTouchedInFreeformWindow` 以**未缩放的布局矩形**为准（实测 raw=162,255-1242,1983
        // 而可见窗口只有 scaled=774,120-1044,552），mini/缩放态下会造出一大片"看着是空白、判定说在小窗内"
        // 的死区——那样功能在 mini 窗周围会失灵。纯核用的是可见命中区（getScaledBounds），与用户所见一致。
        // ROM 判定仍在纯核拿不到数据时兜底（例如仓库未就绪）；两边都没有 ⇒ null（调用方按 fail-closed 处理）。
        val verdict = kernelBlank ?: predBlank
        // 硬安全阀（2026-10-17，用户实测"下半区不能打字"后加）：**只要键盘高度未知**，
        // 屏幕下部 45% 一律不算空白（既不动作也不抢流）——宁可少一个动作，也绝不把键盘的触摸吃掉。
        if (verdict == true && !InsetProbe.imeKnown) {
            val dm0 = android.content.res.Resources.getSystem().displayMetrics
            if (ev.y > dm0.heightPixels * 0.55f) {
                HLog.i(TAG, "isBlank → false（无键盘读数 + 下部45%安全阀）")
                return false
            }
        }
        // 软键盘（2026-10-17）：① IME 活动但拿不到高度 ⇒ 直接否决（方向安全）；
        // ② 拿得到高度 ⇒ 命中底部键盘带也否决。两者都与"点在小窗内"同向。
        if (verdict == true && InsetProbe.imeActive && InsetProbe.imeBottomPx <= 0) {
            HLog.i(TAG, "isBlank → false（IME 活动，高度未知）")
            return false
        }
        // ⚠ 必须先调 [imeFrame]（它内含 pull，会把"浮窗键盘/ROM 键盘标志"这些状态刷新），
        // **再**判断护栏——否则同一根手势里护栏永远赶不上状态（2026-10-18 真机踩到）。
        val ime = imeFrame(context)
        if (verdict == true) {
            // 键盘起着但**排除区不可知** ⇒ 不动作。两种来源，任一命中即拦：
            //  ① `floatingIme`：insets 帧退化（浮层键盘，如搜狗「游戏键盘」）——区域不可知；
            //  ② ROM 的 `mImeShowing=true` 且我们连矩形都没有——宁可漏一个动作，也不能点键盘时关窗口。
            val romUp = InsetProbe.romImeShowing()
            if (InsetProbe.floatingIme || (romUp == true && ime == null)) {
                HLog.i(
                    TAG,
                    "isBlank → false（键盘起着但区域不可知：floating=${InsetProbe.floatingIme} " +
                        "romImeShowing=$romUp rect=$ime ⇒ 不动作）"
                )
                return false
            }
            if (ime != null && ime.contains(ev.x.toInt(), ev.y.toInt())) {
                HLog.i(TAG, "isBlank → false（点在软键盘区域 $ime）")
                return false
            }
        }
        // 探针状态一起打出来（自诊断：键盘起着时这两个值决定护栏是否生效——
        // `floating`=insets 帧退化（浮层键盘），`romUp`=ROM 的 mImeShowing；都为 null/false
        // 就说明事件源没装上，那种情况下护栏不可能生效）。
        HLog.i(TAG, "isBlank pred=$predBlank kernel=$kernelBlank -> $verdict " +
            "at (${"%.0f".format(ev.x)},${"%.0f".format(ev.y)}) " +
            "[ime floating=${InsetProbe.floatingIme} romUp=${InsetProbe.romImeShowing()} " +
            "src=${InsetProbe.source}]")
        return verdict
    }

    /**
     * 纯核回退的数据源（best-effort：任一访问器缺失就跳过窗口，全部拿不到就返回空 ⇒ 不动作）。
     * 名字都取自 2026-10-16 设备 dexdump（OS2）与 OS4 jadx：`getMiuiFreeformModeTaskInfoList()`
     * 或 z-order 字段 `mFreeformTasksInZOrder`；info 侧 `getTaskId/getBounds/getFreeformScale/
     * isInPinMode/isExiting`。
     */
    private fun windowsInZOrder(): List<FreeformBlankTest.Window> = runCatching {
        val repo = repository ?: return emptyList()
        val infos = infoList(repo) ?: return emptyList()
        // 每窗一行几何（开启 verbose 才打：这是排查"为什么没命中"的原始数据，平时太吵）
        if (HLog.verboseEnabled) infos.forEach { info ->
            HLog.i(TAG, "win id=${info.getOrNull("getTaskId")} scaled=${info.getOrNull("getScaledBounds")} " +
                "raw=${info.getOrNull("getBounds")} scale=${info.getOrNull("getFreeformScale")} " +
                "mini=${info.getOrNull("isMiniState")} normal=${info.getOrNull("isNormalState")} " +
                "pin=${info.getOrNull("isInPinMode")} exit=${info.getOrNull("isExiting")}")
        }
        infos.mapNotNull { info -> boundsOf(info) }
    }.getOrElse {
        HLog.w(TAG, "windowsInZOrder failed: ${it.message}")
        emptyList()
    }

    /**
     * info（ROM 的 `MiuiFreeformModeTaskInfo`）→ **当前形态**命中区；拿不到 bounds ⇒ null。
     *
     * 命中区要按**当前**形态算：mini 态窗口已经被缩小并贴到边缘，`getBounds()` 是布局前的
     * 基准矩形（用它会把命中区判大或判错位置）。OS2 设备实证有 `getScaledBounds()`；
     * 拿不到就退回 bounds×scale（scale 随之为 `getFreeformScale`）。
     *
     * 从 [windowsInZOrder] 里原地抽出（2026-10-17）：稳定帧门要与"命中区"用**同一套**取值逻辑，
     * 否则两处判断可能对不上。
     */
    private fun boundsOf(info: Any): FreeformBlankTest.Window? {
        // ⚠ 绝不把 `getBounds()` 当**必要条件**（2026-10-18 真机踩到）：
        // ROM 的 `getBounds() = getBaseAnimTarget().getCurrentBounds()`（jadx 实证），
        // 小窗被缩成 mini/挂起后 base anim target 不在 ⇒ 抛 ⇒ 返回 null。若 raw 是必要条件，
        // 这个窗会**整个从几何表消失**：`windowsInZOrder()`/`takeoverRects()` 双空 ⇒
        // isBlank 退化成只信 ROM 谓词、抢流也判"no window geometry"，用户现象＝
        // "小窗收成角落图标后空白处几乎无法正常使用"（既不动作也不接管，点击漏给背后）。
        // 而可见命中区 `getScaledBounds()`（本函数本来就优先用它）在 mini 态是**有效**的
        //（ROM 的 mini 目的地几何）⇒ 只在两者都拿不到时才放弃。
        // 只做这一处放宽：`getBounds()` 从"必要条件"变成"scaled 的备胎"。
        // **不加**其它几何访问器的猜测性回退——它们大多语义不明（例如 `getRestoreMiniBounds`
        // 是"mini **之前**的矩形"），一旦被当成命中区，会把大片**已经是空白**的旧窗口区域
        // 判成"非空白" ⇒ 那片区域点什么都不动作，比"拿不到几何（点击照常穿透）"更糟。
        // 若真机证明 `getScaledBounds()` 在 mini 态也不可用，再按实测数据补特定访问器。
        val scaled = info.getOrNull("getScaledBounds") as? Rect
        val raw = info.getOrNull("getBounds") as? Rect
        if (scaled == null && raw == null) return null
        val scale = (info.getOrNull("getFreeformScale") as? Number)?.toFloat() ?: 1f
        val b = scaled ?: raw ?: return null
        val pinned = info.getOrNull("isInPinMode") as? Boolean ?: false
        val exiting = info.getOrNull("isExiting") as? Boolean ?: false
        val mini = info.getOrNull("isMiniState") as? Boolean ?: false
        val normal = info.getOrNull("isNormalState") as? Boolean ?: false
        return FreeformBlankTest.Window(
            left = b.left, top = b.top,
            width = b.width(), height = b.height(),
            scale = if (scaled != null) 1f else scale,
            pinned = pinned, visible = !exiting && (normal || mini || pinned)
        )
    }

    /** 某个 taskId 的在册 info（取不到 ⇒ null）。 */
    private fun infoForTask(repo: Any, taskId: Int): Any? = runCatching {
        repo.cachedMethod("getMiuiFreeformTaskInfo", Int::class.javaPrimitiveType!!)
            ?.invoke(repo, taskId)
    }.getOrNull()

    /**
     * 挂起（pin）态但**几何缺失**的小窗数（2026-10-25 乱跳修复的 Spy 侧门）。
     *
     * `FreeformBlankTest.isBlank` 的 pinned 非空白排除只对**进了几何表**的窗生效；bounds
     * 缺失（`boundsOf` 返回 null）的挂起窗不在表里 ⇒ 点它的图标仍会被判"空白"。这种情况
     * 下调用方（`SystemUiInputSpy.takeoverArmed`）宁可本轮整体不抢流（点击全穿透给 ROM），
     * 也不能把挂起图标的点击吃掉——与"拿不到几何（点击照常穿透）优于判错区域"的既有口径
     * 同向（见 [boundsOf] 注释）。
     */
    fun pinnedWithoutGeometry(): Int = runCatching {
        val repo = repository ?: return 0
        val infos = infoList(repo) ?: return 0
        infos.count { info ->
            (info.getOrNull("isInPinMode") as? Boolean) == true && boundsOf(info) == null
        }
    }.getOrDefault(0)

    /** 目标窗口当前形态的矩形（取不到 ⇒ null）。 */
    private fun currentRect(taskId: Int): Rect? = runCatching {
        val repo = repository ?: return null
        val info = infoForTask(repo, taskId) ?: return null
        boundsOf(info)?.let { Rect(it.left, it.top, it.left + it.width, it.top + it.height) }
    }.getOrNull()

    /**
     * 稳定帧门（2026-10-17，"缩小"路径专用）：等目标窗口在当前形态上**连续两帧不动**且不在
     * 动画中，再执行 [then]；超时 [STABLE_TIMEOUT_MS] 也执行（fail-open，不添乱）。
     *
     * 为什么需要：ROM 的 mini 目的地是用 `getBounds()×scale` 算的，若窗口刚还原/刚创建还在过渡，
     * 起止几何不一致 ⇒ 观感"终点偏左再跳一下"（用户实测）。既有 `isInAnimating`/`scaleNotSettled`
     * 两闸收效有限（状态位与实际过渡不同源），改用"几何本身稳定"作判据。
     */
    private fun awaitStable(taskId: Int, then: () -> Unit) {
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        var last: Rect? = null
        var same = 0
        var waited = 0L
        val poll = object : Runnable {
            override fun run() {
                if (done.get()) return
                val r = currentRect(taskId)
                same = if (r != null && r == last) same + 1 else 0
                last = r
                val animating = runCatching { isInAnimating(taskId) }.getOrDefault(false)
                if ((r != null && same >= 2 && !animating) || waited >= STABLE_TIMEOUT_MS) {
                    if (done.compareAndSet(false, true)) {
                        HLog.i(TAG, "stable after ${waited}ms bounds=$r (animating=$animating)")
                        then()
                    }
                    return
                }
                waited += POLL_MS
                h.postDelayed(this, POLL_MS)
            }
        }
        h.postDelayed(poll, POLL_MS)
    }

    /**
     * "动画空闲"门（"关闭"路径专用）：ROM 只在 `startCloseAnimation` 里播**原地缩放淡出**，
     * 而它在 `getAnimatingTypes()` 含 `2`/`20` 时降级为 `NoAnimFinishDeferred … exitFreeformNoAnim`
     * （真机实证：刚还原完就点关闭 ⇒ 从来没动画）。⇒ 等动画位清空再投关闭，超时也投（fail-open）。
     * 取不到 `getAnimatingTypes` ⇒ 立即执行（不阻塞关闭）。
     */
    private fun awaitIdleAnimation(taskId: Int, then: () -> Unit) {
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        var waited = 0L
        val poll = object : Runnable {
            override fun run() {
                if (done.get()) return
                val types = animatingTypes(taskId)
                val busy = types != null && types.any { it == 2 || it == 20 }
                if (!busy || waited >= IDLE_ANIM_TIMEOUT_MS) {
                    if (done.compareAndSet(false, true)) {
                        HLog.i(TAG, "close: animTypes=${types ?: "n/a"} waited=${waited}ms → exit")
                        then()
                    }
                    return
                }
                waited += POLL_MS
                h.postDelayed(this, POLL_MS)
            }
        }
        h.postDelayed(poll, 0L)
    }

    /** ROM 的 `MiuiFreeformModeTaskInfo.getAnimatingTypes()` ⇒ List<Int>；取不到 ⇒ null（=放行）。 */
    private fun animatingTypes(taskId: Int): List<Int>? = runCatching {
        val repo = repository ?: return null
        val info = infoForTask(repo, taskId) ?: return null
        val v = info.getOrNull("getAnimatingTypes") ?: return null
        (v as? Iterable<*>)?.mapNotNull { (it as? Number)?.toInt() }
    }.getOrNull()

    /**
     * 当前形态命中区（给 spy 的输入接管用）：空表 = 没有可见小窗或数据源不可用。
     *
     * spy 侧据此判"DOWN 是否落在小窗外 ≤ [com.lsp.hypersidebar.hook.GestureThresholds.TAKEOVER_RING_DP]
     * 那一圈"——只有在这一圈里才抢流（抢流不可回滚，圈外一律不动）。
     */
    fun hitWindows(): List<FreeformBlankTest.Window> = windowsInZOrder()

    /**
     * 接管专用：所有在册小窗的**当前形态矩形**，**不看 `visible` 状态位**。
     *
     * 为什么不能用 [hitWindows]：`boundsOf` 的 `visible` 依赖 ROM 的 `isNormalState`/`isMiniState`/
     * `isInPinMode` 三个状态位，窗口刚还原/刚结束过渡时可能**瞬时全 false**（2026-10-17 真机：
     * `takeover ring: no visible window` 而 `isBlank` 的纯核同时说"空白"、窗口明明在屏幕上）⇒
     * 结果"要有小窗才抢流"永远不成立。接管只需要"窗口在哪"（几何），状态位交给出空白判定去管。
     */
    fun takeoverRects(): List<FreeformBlankTest.Window> = runCatching {
        val repo = repository ?: return emptyList()
        infoList(repo)?.mapNotNull { boundsOf(it) } ?: emptyList()
    }.getOrElse {
        HLog.w(TAG, "takeoverRects failed: ${it.message}")
        emptyList()
    }

    /**
     * 现在是否有可动作的目标（**正常态**非退出）。
     *
     * 2026-10-17 用户口径：**mini 态不作为目标**（缩成 mini 之后点空白不再动作、点击照常落给系统）。
     * 抢流与动作共用本入口，所以不会出现"吞了却动不了"或"能动却不吞"。
     */
    fun hasActionTarget(mode: String): Boolean =
        runCatching { pickTargetTaskId(mode) != null }.getOrDefault(false)

    /**
     * 是否有可见小窗**正在动画/几何未落定**（还原、mini、pin 的过渡期）。
     *
     * 用途（2026-10-18 真机故障定位）：**抢流前置检查**。真机实证：还原动画刚起，我们的
     * 几何（getScaledBounds 路径）还是**还原前的旧位置** ⇒ 用户点在还原后窗口的真实位置上，
     * isBlank 却误判"空白" ⇒ 抢流 ⇒ 被 ROM 的 caption 监视器回抢（CANCEL）⇒ ROM 把这条
     * 手势当 caption 拖拽 ⇒ **窗口被误最大化**（endWindowMode=1；用户要的是缩小）。
     *
     * 动画期间几何不可信 ⇒ 一律不抢流（点击照常落给窗口/背后——用户本来就在等动画落定）。
     * 判据复用动作路径的既有两个门（isInAnimating / scaleNotSettled），语义一致。
     */
    fun anyWindowAnimating(): Boolean = runCatching {
        val repo = repository ?: return@runCatching false
        infoList(repo)?.any { info ->
            val exiting = info.getOrNull("isExiting") as? Boolean ?: false
            val visible = info.getOrNull("isNormalState") as? Boolean ?: false
            val mini = info.getOrNull("isMiniState") as? Boolean ?: false
            val pinned = info.getOrNull("isInPinMode") as? Boolean ?: false
            if (exiting || !(visible || mini || pinned)) return@any false
            val tid = (info.getOrNull("getTaskId") as? Number)?.toInt() ?: return@any false
            isInAnimating(tid) || scaleNotSettled(tid)
        } ?: false
    }.getOrDefault(false)

    private fun desktopModeActive(context: Context?): Boolean {
        val ctx = context ?: return false
        return runCatching { desktopActiveMethod?.invoke(null, ctx) as? Boolean ?: false }
            .getOrDefault(false)
    }

    // ===== 软键盘（IME）排除（2026-10-17，用户实测场景）================================
    // 问题：`isBlank` 只看**小窗任务表**，而软键盘是另一个窗口 ⇒ 键盘区域被判成"空白" ⇒
    // 用小窗里的应用回消息、点键盘时会触发缩小/关闭（打字直接中招）。
    // 做法：向 WindowManagerService 要窗口清单（`IWindowManager.getWindowInfos()`；SystemUI 持有
    // INTERNAL_SYSTEM_WINDOW 权限），取 `TYPE_INPUT_METHOD(2011)` / `TYPE_INPUT_METHOD_DIALOG(2012)`
    // 的 frame 并集；命中该区域即判"**不是**空白"。
    // 取不到清单（隐藏 API 被挡/权限不足/字段名漂移）⇒ 返回 null ⇒ **不因此否决**（fail-open 到旧行为，
    // 绝不让"取不到键盘位置"变成"功能失灵"）。
    @Volatile private var imeRectCache: Rect? = null
    @Volatile private var imeRectAt = 0L

    /** 最近一次 IME 探测失败的原因（并在 "IME 区域=不可用" 那行里，便于定位是哪一层挂了）。 */
    @Volatile private var imeProbeErr: String? = null

    /** 探针诊断去重（避免每只手势刷日志）。 */
    private var lastProbeLogged = -1
    private var lastActiveLogged = false

    private fun imeFrame(context: Context?): Rect? {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - imeRectAt < 1_000L) return imeRectCache
        imeRectAt = now
        // 路径 ⓿（2026-10-17 主路径）：SystemUI 里的 IME 探针（见 InsetProbe）——
        // 先让探针按 1 秒粒度轮询 `getCurrentWindowMetrics().getWindowInsets()`，
        // 拿到就用；拿不到再看 overlay 回调里记下的值。
        //
        // ⚠ 2026-10-18：`InsetProbe.start()`（挂在 SystemUiHook 的 `Application.attach` 回调里）
        // 在这台设备上**不会执行到**（那段 attach 钩子实测从未触发）⇒ 探针永远没初始化，
        // 连 WMShell 事件源都没装上。这里在真正会跑到的路径上做幂等初始化：
        // `ensure()` 装 WMShell 钩子 + overlay，`refresh()` 按 1 秒粒度读 metrics 兜底。
        if (context != null) InsetProbe.ensure(context)
        InsetProbe.refresh(context)
        // IME insets 钩子现在装在 InsetProbe 里（那个类已被验证会加载）——见 InsetProbe.installInsetsHook
        // ⓿ WMShell 权威矩形优先（2026-10-18）：横屏**浮窗键盘**是一块位置任意的小窗，
        // 既不满宽也不贴底 ⇒ 下面那条"贴底满宽带"的拼法在那种模式下会把旁边的空白也判成键盘 ✗。
        val rect = InsetProbe.imeRect
        if (rect != null) {
            imeRectCache = rect
            HLog.i(TAG, "IME 区域=$rect（via ${InsetProbe.source} rect）")
            return imeRectCache
        }
        // 冷启动兜底：键盘在模块初始化**之前**就起着 ⇒ `insetsChanged` 不会来 ⇒ 主动拉一次
        if (rect == null) InsetProbe.pullFromWmShell()
        val pulled = if (rect == null) InsetProbe.imeRect else null
        if (pulled != null) {
            imeRectCache = pulled
            HLog.i(TAG, "IME 区域=$pulled（via ${InsetProbe.source} pull）")
            return imeRectCache
        }
        // 权威"键盘没起" ⇒ 直接判定无可排除区（不再走老路，避免陈旧矩形复活）
        if (InsetProbe.wmShellSaysHidden()) {
            imeRectCache = null
            HLog.i(TAG, "IME 区域=无（via ${InsetProbe.source} 权威：键盘未显示）")
            return null
        }
        val probe = InsetProbe.imeBottomPx
        if (probe > 0) {
            val dm = android.content.res.Resources.getSystem().displayMetrics
            imeRectCache = Rect(0, dm.heightPixels - probe, dm.widthPixels, dm.heightPixels)
            HLog.i(TAG, "IME 区域=$imeRectCache（via ${InsetProbe.source} band）")
            return imeRectCache
        }
        // 探针诊断（2026-10-17）：`InsetProbe` 内部用的是 android.util.Log，而 OS4 上那些行会被
        // 系统吞掉 ⇒ 在这里用 HLog 复述一次"当前探针状态"，否则设备上完全看不到读数。
        if (probe != lastProbeLogged || InsetProbe.imeActive != lastActiveLogged) {
            lastProbeLogged = probe
            lastActiveLogged = InsetProbe.imeActive
            HLog.i(
                TAG,
                "imeProbe: band=$probe src=${InsetProbe.source} active=${InsetProbe.imeActive}"
            )
        }
        // 拿不到高度时的安全判据（2026-10-17）：IME 处于活动状态（有输入焦点）⇒ 由 `isBlank`
        // 直接否决本点按（见其 `InsetProbe.imeActive` 分支）。方向安全（打字时不会想关小窗），
        // 且用的是公开 API `InputMethodManager.isActive()`。
        InsetProbe.refreshActive(context)
        if (InsetProbe.imeActive) {
            HLog.i(TAG, "IME active（拿不到高度）⇒ 本点按不触发动作")
            return null
        }
        // 路径 ①：窗口清单（精确矩形；权限/方法缺失时返回 null）
        imeRectCache = runCatching {
            val smCls = Class.forName("android.os.ServiceManager")
            val binder = smCls.getMethod("getService", String::class.java).invoke(null, "window")
                ?: error("getService(window)=null")
            val wmCls = Class.forName("android.view.IWindowManager\$Stub")
            val wm = wmCls.getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, binder) ?: error("asInterface=null")
            // 2026-10-17：签名不写死——OS4 上很可能是 getWindowInfos(int displayId)；
            // 把同名方法**全部**试一遍（0 参直接调，1 参传 displayId=0 或 true）。
            val cands = wm.javaClass.methods.filter { it.name == "getWindowInfos" }
            if (cands.isEmpty()) error("no getWindowInfos on ${wm.javaClass.name}")
            var lastErr: Throwable? = null
            for (m in cands) {
                val r = runCatching {
                    val args = when (m.parameterCount) {
                        0 -> emptyArray()
                        1 -> arrayOf<Any?>(0)
                        else -> throw IllegalStateException("arity=${m.parameterCount}")
                    }
                    m.isAccessible = true
                    m.invoke(wm, *args)
                }
                if (r.isFailure) {
                    lastErr = r.exceptionOrNull()
                    continue
                }
                val infos = r.getOrNull() as? Array<*> ?: continue
                var out: Rect? = null
                for (wi in infos) {
                    val o = wi ?: continue
                    val type = (o.getOrNull("getType") as? Number)?.toInt()
                        ?: runCatching { o.javaClass.getField("type").getInt(o) }.getOrNull()
                        ?: continue
                    if (type != 2011 && type != 2012) continue
                    val rr = (o.getOrNull("getFrame") as? Rect)
                        ?: runCatching { o.javaClass.getField("frame").get(o) as? Rect }.getOrNull()
                        ?: continue
                    out = out?.also { it.union(rr) } ?: Rect(rr)
                }
                return@runCatching out
            }
            throw lastErr ?: IllegalStateException("getWindowInfos 无可用签名")
        }.getOrElse {
            imeProbeErr = "${it.javaClass.simpleName}: ${it.message}"
            null
        }
        if (imeRectCache == null) {
            // 路径 ②（2026-10-17 新增，真机反馈"输入法还是有问题"后的兜底）：
            // `InputMethodManager.getInputMethodWindowVisibleHeight()`（隐藏 API，SystemUI 可调）
            // ⇒ 直接给出键盘占走的底部高度，按整条底带处理。
            imeRectCache = runCatching {
                val ctx = context ?: return@runCatching null
                val imm = ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    ?: return@runCatching null
                val m = imm.javaClass.getMethod("getInputMethodWindowVisibleHeight")
                val h = (m.invoke(imm) as? Number)?.toInt() ?: return@runCatching null
                if (h <= 0) return@runCatching null
                val dm = android.content.res.Resources.getSystem().displayMetrics
                Rect(0, dm.heightPixels - h, dm.widthPixels, dm.heightPixels)
            }.getOrNull()
            HLog.i(
                TAG,
                "IME 区域=" + (imeRectCache?.toString()
                    ?: "不可用（①${imeProbeErr ?: "null"} ②getInputMethodWindowVisibleHeight 失败）")
            )
        }
        return imeRectCache
    }

    // ===== 动作 =====

    /**
     * 执行动作。返回 true = 已执行（或走了降级）；false = 没有任何在册小窗可操作。
     * 只调 ROM 已有的方法，不投递合成事件。
     */
    fun perform(context: Context, mode: String): Boolean {
        prime()
        resolveHolderLazy()
        // ROM 的动作可能要求主线程（2026-10-17 实测：spy 的输入回调线程上 fromFreeformToMini 抛
        // InvocationTargetException/NPE）。非主线程时整段投到主线程执行（被动通道里 INPUT 回调
        // 不在主线程；主线程调用保持同步返回）。
        val main = android.os.Looper.getMainLooper()
        if (android.os.Looper.myLooper() !== main) {
            android.os.Handler(main).post { runCatching { performNow(context, mode) } }
            HLog.i(TAG, "perform posted to main (mode=$mode)")
            return true
        }
        return performNow(context, mode)
    }

    /** [causeLine]：异常链的真因描述（ITE 只是壳）。 */
    private fun causeLine(t: Throwable): String {
        val c = t.cause ?: t
        return "${c.javaClass.simpleName}: ${c.message}"
    }

    private fun performNow(context: Context, mode: String): Boolean {
        if (controller == null) {
            HLog.w(TAG, "perform skipped: controller unavailable ($resolveNote)")
            return false
        }
        // 档位敏感：close 档下 mini/挂起窗也算目标（见 pickTargetTaskId 的注释）
        val taskId = pickTargetTaskId(mode) ?: run {
            HLog.i(TAG, "perform skipped: no visible freeform task")
            return false
        }
        if (mode == PrefKeys.FREEFORM_BLANK_TAP_MODE_CLOSE) {
            val ok = closeTask(taskId, context)
            HLog.i(TAG, "perform mode=$mode task=$taskId ok=$ok")
            return ok
        }
        if (mode == PrefKeys.FREEFORM_BLANK_TAP_MODE_PIN) {
            val ok = pinAll()
            HLog.i(TAG, "perform mode=$mode task=$taskId ok=$ok")
            return ok
        }
        // ===== 缩小（mini）=====
        // ROM 的 fromFreeformToMini 是 void：既可能抛异常，也可能因"已有动画在跑"**静默返回**。
        // 只在"调用没抛 且 任务确认进入 mini 态"时算成功；否则才退广播兜底
        //（2026-10-17 真机反馈"有时缩小没动画"＝静默返回时仍走了批量广播，那条天生无动画）。
        // 已经是 mini 态 ⇒ 直接跳过（ROM 的 fromFreeformToMini 对这种状态会静默返回，
        // 反复点会表现成"半截/无动画"；2026-10-17 用户反馈）。
        if (runCatching { isMiniState(taskId) }.getOrDefault(false)) {
            HLog.i(TAG, "mini skipped: task=$taskId already mini")
            return true
        }
        // 动画进行中（含刚还原/mini 的过渡）⇒ 本次不动。ROM 的动画状态机会把并发的
        // fromFreeformToMini 静默吞掉，用户快速"mini→还原→再点"时就会看到半截/jank
        //（2026-10-17 真机反馈）。跳过 = 不添乱，等动画落定下一次点击照常。
        if (runCatching { isInAnimating(taskId) }.getOrDefault(false)) {
            HLog.i(TAG, "mini skipped: task=$taskId animating")
            return true
        }
        // 缩放值尚未落到稳定值（还原动画尾巴）⇒ 也跳过：此时 ROM 用 getBounds() 算目的地、
        // 而 SurfaceControl 仍在过渡 ⇒ 起止不一致会出现"终点偏左再跳一下"（用户实测）。
        if (runCatching { scaleNotSettled(taskId) }.getOrDefault(false)) {
            HLog.i(TAG, "mini skipped: task=$taskId scale not settled")
            return true
        }
        // 稳定帧门（2026-10-17）：等目标窗口几何连续两帧不动再缩（ROM 用 getBounds() 算
        // 目的地，刚还原/刚创建时起止不一致 ⇒ "终点偏左再跳一下"）。超时 700ms 照缩。
        // ⚠ 异步：performNow 由主线程进入，不能阻塞等回调 ⇒ 校验与广播兜底都搬进回调。
        awaitStable(taskId) {
            val direct = miniTask(taskId)
            val okMini = direct && runCatching { isMiniState(taskId) }.getOrDefault(false)
            if (okMini) {
                HLog.i(TAG, "perform mode=mini task=$taskId ok=true")
                return@awaitStable
            }
            // 静默 no-op（2026-10-18 稳定性修）：fromFreeformToMini 有六个静默 return 守卫
            //（jadx :582——拖拽缩放动画/连续动画/change 过渡/enterState 1,2,5/elude 动画），
            // 我们的前置门只覆盖了其中一部分 ⇒ 撞上没覆盖的守卫时这次调用什么都没做。
            // 原来直接掉广播兜底（**天生无动画**）⇒ 用户看到的"缩小有时没动画/不稳定"。
            // ⇒ 300ms 后重试一次：那时冲突的动画多半已完，ROM 会正常播 startGestureAnimation。
            // 重试再失败才走广播（兜底的兜底）。
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val retried = runCatching {
                    !isMiniState(taskId) && !isInAnimating(taskId) && miniTask(taskId) &&
                        isMiniState(taskId)
                }.getOrDefault(false)
                if (retried) {
                    HLog.i(TAG, "perform mode=mini task=$taskId ok=true (retry)")
                } else {
                    val ok = broadcastFallback(context, "mini no-op twice (task=$taskId)")
                    HLog.i(TAG, "perform mode=mini task=$taskId ok=$ok (fallback)")
                }
            }, 300L)
        }
        return true
    }

    /**
     * 目标小窗的 taskId。三级来源，全部是 ROM 自己的在册数据：
     * 1. OS2 的 `MiuiWindowDecorViewModel.mFreeformTaskId`（private int，ROM 记的"当前小窗任务"）；
     * 2. 任务仓库的 z-order 列表（字段 `mFreeformTasksInZOrder`/`freeformTasksInZOrder`，优先）：
     *    取首个未退出项（列表为空/语义变了会整段记日志，方便按实测改正）；
     * 3. `getMiuiFreeformModeTaskInfoList()` 首个未退出项的 `getTaskId()`（OS2 设备实证 public）。
     */
    /**
     * 选动作目标：**只认正常态小窗**（2026-10-18 用户口径定稿）。
     *
     * 历史：早先实现无条件跳过 mini；2026-10-18 我曾按 spy 里 10-17 的旧注释改成"close 档
     * 放宽到 mini/钉住"，随即被用户实测否决——把窗收成图标之后，点空白要的是背后桌面/app
     * 的正常操作，不是把图标关掉（"影响正常操作"）。**挂起/钉住都是用户主动收起的状态，
     * 点空白不碰它们**；要关图标：点图标（系统还原）→ 再点空白。
     * `mode` 参数保留：pin 档的动作本体是 pinAll（全量钉住），与"选哪只窗"无关。
     */
    private fun pickTargetTaskId(mode: String): Int? = pickNormalTaskId()

    /**
     * 选动作目标：**只认正常态小窗**（2026-10-18 用户口径，覆盖我当天早些时候的 close 档
     * 放宽——那条被实测否决：挂起/钉住态的点空白关闭会**影响正常操作**——用户把窗收成
     * 图标之后，点空白要的是背后桌面/app 的正常操作，不是把图标关掉）。
     *
     * 挂起（mini）与钉住（pin 图标）都是用户**主动**收起的状态：点空白不碰它们、点击照常
     * 穿透给背后。要关掉图标：点图标（系统还原成正常态）→ 再点空白关闭。
     * `mode` 参数保留：pin 档的动作本体是 pinAll（全量钉住），与"选哪只窗"无关。
     */
    private fun pickNormalTaskId(): Int? {
        val vm = decorVm
        if (vm != null) {
            val id = runCatching {
                val f = vm.javaClass.getDeclaredField("mFreeformTaskId")
                f.isAccessible = true
                f.getInt(vm)
            }.getOrNull() ?: 0
            if (id > 0 && !isExiting(id) && !isMiniState(id) && !isPinned(id)) {
                HLog.i(TAG, "target task=$id (decorVm.mFreeformTaskId)")
                return id
            }
        }
        val repo = repository ?: return null
        val ids = zOrderIds(repo)
        if (ids.isNotEmpty()) {
            HLog.i(TAG, "z-order freeform ids=$ids")
            val normal = ids.firstOrNull { !isExiting(it) && !isMiniState(it) && !isPinned(it) }
            if (normal != null) return normal
            if (ids.any { !isExiting(it) }) {
                HLog.i(TAG, "z-order 只有挂起/钉住态小窗 ⇒ 不作为目标")
            }
        }
        val infos = infoList(repo)
        val id = infos?.asSequence()?.mapNotNull { info ->
            val exiting = info.getOrNull("isExiting") as? Boolean ?: false
            val mini = info.getOrNull("isMiniState") as? Boolean ?: false
            val pinned = info.getOrNull("isInPinMode") as? Boolean ?: false
            val tid = (info.getOrNull("getTaskId") as? Number)?.toInt()
            if (exiting || mini || pinned || tid == null) null else tid
        }?.firstOrNull()
        if (id != null) HLog.i(TAG, "target task=$id (info list)")
        return id
    }

    /**
     * z-order 的 taskId 列表。**方法优先**：OS3 实证 `getFreeformTasksInZOrder(): ArrayList<Integer>`
     * 是 public，且声明在父类 `…multitasking.common.taskmanager.MultiTaskingTaskRepository`
     * （`MiuiFreeformModeTaskRepository` 继承它）⇒ `getMethod` 能直接取到，跨 OS3/OS4 通用。
     * OS2 设备那份没有这个方法（只有 private 字段 `mFreeformTasksInZOrder`）⇒ 保留字段候选做回退。
     */
    private fun zOrderIds(repo: Any): List<Int> {
        runCatching {
            val m = repo.cachedMethod("getFreeformTasksInZOrder") ?: return emptyList()
            // 空列表当作"这个方法在本题没给出信息"⇒ 继续试字段（避免某代返回空而字段有数据时误判）
            val ids = (m.invoke(repo) as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
            if (ids.isNotEmpty()) return ids
        }
        for (fn in listOf(
            "mFreeformTaskIdsInZOrder",     // OS4 MultiTaskingTaskRepository（反编译实证）
            "mFreeformTasksInZOrder",       // OS2 MiuiFreeformModeTaskRepository（设备实证）
            "freeformTasksInZOrder"
        )) {
            val v = runCatching {
                val f = repo.javaClass.getDeclaredField(fn)
                f.isAccessible = true
                f.get(repo) as? List<*>
            }.getOrNull() ?: continue
            return v.mapNotNull { (it as? Number)?.toInt() }
        }
        return emptyList()
    }

    /** 任务信息列表：先试 z-order 的 id → `getMiuiFreeformTaskInfo(int)`，再试公开的列表 getter。 */
    private fun infoList(repo: Any): List<Any>? {
        val byMethod = runCatching {
            repo.cachedMethod("getMiuiFreeformModeTaskInfoList")?.invoke(repo) as? List<*>
        }.getOrNull()
        if (!byMethod.isNullOrEmpty()) return byMethod.filterNotNull()
        val ids = zOrderIds(repo)
        val getInfo = repo.cachedMethod("getMiuiFreeformTaskInfo", Int::class.javaPrimitiveType!!)
        if (!repoDiagLogged) {
            repoDiagLogged = true
            val zField = runCatching {
                val f = repo.javaClass.getDeclaredField("mFreeformTaskIdsInZOrder")
                f.isAccessible = true
                (f.get(repo) as? List<*>)?.size
            }.getOrNull()
            HLog.w(TAG, "repo diag: class=${repo.javaClass.simpleName} listMethod=${byMethod != null} " +
                "zOrderIds=$ids fieldZOrderSize=$zField infoMethod=${getInfo != null}")
        }
        if (ids.isEmpty() || getInfo == null) return null
        return ids.mapNotNull { id -> runCatching { getInfo.invoke(repo, id) }.getOrNull() }
    }

    /** 当前 scale 与目的地 scale 不一致 = 仍在过渡中（ROM 的 fromFreeformToMini 会按未落定的值算目的地）。 */
    private fun scaleNotSettled(taskId: Int): Boolean = runCatching {
        val repo = repository ?: return false
        val info = repo.cachedMethod("getMiuiFreeformTaskInfo", Int::class.javaPrimitiveType!!)
            ?.invoke(repo, taskId) ?: return false
        val now = (info.getOrNull("getFreeformScale") as? Number)?.toFloat() ?: return false
        val dest = (info.getOrNull("getDestinationScaleX") as? Number)?.toFloat() ?: return false
        dest > 0f && kotlin.math.abs(now - dest) > 0.01f
    }.getOrDefault(false)

    /** 目标任务是否正在播放动画（ROM 的状态机在动画期间会吞掉并发的 fromFreeformToMini）。 */
    private fun isInAnimating(taskId: Int): Boolean = runCatching {
        val repo = repository ?: return false
        val info = repo.cachedMethod("getMiuiFreeformTaskInfo", Int::class.javaPrimitiveType!!)
            ?.invoke(repo, taskId) ?: return false
        info.getOrNull("isInAnimating") as? Boolean ?: false
    }.getOrDefault(false)

    /** 目标任务当前是否处于 mini（缩小）态——用于校验 ROM 的 void 动作是否真的生效。 */
    private fun isMiniState(taskId: Int): Boolean = runCatching {
        val repo = repository ?: return false
        val info = repo.cachedMethod("getMiuiFreeformTaskInfo", Int::class.javaPrimitiveType!!)
            ?.invoke(repo, taskId) ?: return false
        info.getOrNull("isMiniState") as? Boolean ?: false
    }.getOrDefault(false)

    private fun isPinned(taskId: Int): Boolean = runCatching {
        val repo = repository ?: return false
        val info = repo.cachedMethod("getMiuiFreeformTaskInfo", Int::class.javaPrimitiveType!!)
            ?.invoke(repo, taskId) ?: return false
        info.getOrNull("isInPinMode") as? Boolean ?: false
    }.getOrDefault(false)

    private fun isExiting(taskId: Int): Boolean = runCatching {
        val repo = repository ?: return false
        val info = repo.cachedMethod("getMiuiFreeformTaskInfo", Int::class.javaPrimitiveType!!)
            ?.invoke(repo, taskId) ?: return false
        info.getOrNull("isExiting") as? Boolean ?: false
    }.getOrDefault(false)

    /** 缩小/最小化（三代同名同签名；OS2 上是 private ⇒ setAccessible）。 */
    private fun miniTask(taskId: Int): Boolean = runCatching {
        val c = controller ?: return false
        val m = c.javaClass.getDeclaredMethod("fromFreeformToMini", Int::class.javaPrimitiveType)
        m.isAccessible = true
        m.invoke(c, taskId)
        true
    }.getOrElse {
        // InvocationTargetException.message 恒为 null（真因在 cause）⇒ 必须打 cause
        HLog.w(TAG, "fromFreeformToMini($taskId) failed: ${causeLine(it)}")
        false
    }

    /**
     * 关闭单任务：优先 `exitFreeformTask`（OS3 两参 / OS4 三参——反编译坐实代差）；
     * ROM 没有该方法（OS2）⇒ 降级广播 `state=toHome`（≈缩小全部小窗），并把降级写进日志。
     */
    private fun closeTask(taskId: Int, context: Context): Boolean {
        val c = controller ?: return broadcastFallback(context, "no controller (task=$taskId)")
        // OS3/4 **带动画的关闭**：ROM 的 exitFreeformTask 内部就是 exitFreeformWithoutAnimation（无过渡），
        // 而 MiuiFreeformModeAnimation.startExitFreeformShellTransition(info, z) 是自包含的
        // （takeTaskSnapshot → fillExitFreeformWct → mTransitions.startTransition → pendingToken），
        // 正是"主动关闭"那条过渡链（2026-10-17 反编译实证）。优先用它。
        // ⚠ 线程：ROM 的过渡 API 会断言"must be called on Handler {…}"——那是 WMShell 自己的
        // HandlerThread（不是主线程），2026-10-17 真机实测在 main 上调用直接 IllegalStateException。
        // ⇒ 整段投到控制器的 `mMainExecutor`（ShellExecutor，绑的正是那个 Handler）上执行。
        // **关闭动画**（2026-10-17 用户口径：关闭是"原地缩放淡出"，**不位移**）：
        // ROM 只在 `MiuiFreeformModeAnimation.startCloseAnimation` 里播这个动画，而它在
        // `getAnimatingTypes()` 含 2/20 时降级成 `NoAnimFinishDeferred … exitFreeformNoAnim`
        // （真机实证：刚还原完就点关闭 ⇒ 从来没动画）。⇒ ① 先过"动画空闲"门；
        // ② 顺序改成 ROM 自己那条 `startExitFreeformShellTransition`（closeWithTransition）优先
        // ——它是自包含的（takeTaskSnapshot → fillExitFreeformWct → mTransitions.startTransition
        // → pendingToken），且 windowingMode==5 时会走到 startCloseAnimation。
        // ⚠ 线程：过渡 API 断言 "must be called on Handler {…}"（WMShell 的 HandlerThread，
        // 不是主线程；2026-10-17 真机在 main 上调直接 IllegalStateException）⇒ 整段投
        // 控制器的 `mMainExecutor`（见 [postToRomExecutor]）。
        awaitIdleAnimation(taskId) { doCloseNow(c, taskId, context) }
        return true
    }

    /**
     * caption × 的**原版关闭路径**（2026-10-18，带 ROM 自己的收尾动画）。
     *
     * 真机+反编译实证的根因：我们此前的 `closeWithTransition`
     * （`MiuiFreeformModeAnimation.startExitFreeformShellTransition`）会注册 pending token，
     * 而 OS4 的过渡分发器（`startBackTransition`）对"2147483545 且带 token"的过渡**固定**
     * 走 `startMiuiFreeformMaximizeMoveToBackTransition`（`[BUGOS2-809979] bypassing Folme`
     * ——故意无动画的旁路，真机日志 `NoAnimFinishDeferred`）⇒ 关闭从来没动画。
     * 有动画的那条（`startCloseAnimation`，原地缩放淡出）要求"2147483545 **无** token"。
     *
     * 而 caption × 按钮的关闭走的是**另一族**过渡：`MulWinSwitchAnimStarter
     * .closeFullOrFreeform(RunningTaskInfo)`（jadx :171）→ `MulWinSwitchTransition
     * .startCloseFullOrFreeform`（:351，自建 wct + takeTaskSnapshot + 自家类型
     * ANIMATION_WIN_CTL_CLOSE_FREEFORM=11114 族）⇒ 完全绕开 2147483545 的分发。
     * starter 是静态入口：`MultiTaskingControllerImpl.getInstance().getMulWinSwitchStarter()`
     * （caption 监听器自己就是这么拿的，jadx :60）——无需捕获实例。
     */
    private fun closeViaCaptionPath(taskId: Int): Boolean = runCatching {
        val h = holderClass ?: return@runCatching false
        val inst = h.getMethod("getInstance").invoke(null)
        val starter = inst.javaClass.methods.firstOrNull {
            it.name == "getMulWinSwitchStarter" && it.parameterCount == 0
        }?.invoke(inst) ?: return@runCatching false
        val repo = repository ?: return@runCatching false
        val info = repo.javaClass
            .getMethod("getMiuiFreeformTaskInfo", Int::class.javaPrimitiveType)
            .invoke(repo, taskId) ?: return@runCatching false
        val ti = info.javaClass.getMethod("getTaskInfo").invoke(info) ?: return@runCatching false
        val m = starter.javaClass.methods.firstOrNull {
            it.name == "closeFullOrFreeform" && it.parameterCount == 1
        } ?: return@runCatching false
        m.invoke(starter, ti)
        true
    }.onFailure { HLog.w(TAG, "closeViaCaptionPath($taskId) failed: ${causeLine(it)}") }
        .getOrDefault(false)

    /**
     * 关闭动作本体（在"动画空闲"门之后执行）：优先 ROM 自己的关闭过渡（**带原地缩放淡出动画**），
     * 没有 ROM 执行器时退到同步降级链（OS2 `minimizeTask` → `exitFreeformTask` → 广播）。
     */
    private fun doCloseNow(c: Any, taskId: Int, context: Context) {
        if (postToRomExecutor(Runnable { doExit(taskId, context) })) return
        // OS2 路径：控制器**没有** exitFreeformTask（设备 dexdump 实证），ROM 自己的"关闭单个小窗"是
        // DecorViewModel.mTaskOperations.minimizeTask(RunningTaskInfo)（同实证 public，语义=退出小窗，
        // 与 OS3/4 的 exit 对齐）。有了它，"关闭"才和"缩小"在观感上不同——否则只能退广播（≈缩小全部）。
        val hasExit = listOf(2, 3).any { n ->
            runCatching {
                val sig = arrayOfNulls<Class<*>>(n)
                sig[0] = Int::class.javaPrimitiveType
                for (i in 1 until n) sig[i] = Boolean::class.javaPrimitiveType
                c.javaClass.getDeclaredMethod("exitFreeformTask", *sig)
            }.isSuccess
        }
        if (!hasExit) {
            val viaOps = runCatching {
                val vm = decorVm ?: return@runCatching false
                val info = synchronized(taskInfoByTask) { taskInfoByTask[taskId] }
                    ?: return@runCatching false
                val f = vm.javaClass.getDeclaredField("mTaskOperations")
                f.isAccessible = true
                val ops = f.get(vm) ?: return@runCatching false
                val m = ops.javaClass.getDeclaredMethod(
                    "minimizeTask", android.app.ActivityManager.RunningTaskInfo::class.java
                )
                m.invoke(ops, info)
                true
            }.getOrDefault(false)
            if (viaOps) {
                HLog.i(TAG, "close via OS2 minimizeTask($taskId)")
                return
            }
        }
        val byArity = listOf(3, 2).firstNotNullOfOrNull { n ->
            runCatching {
                val sig = arrayOfNulls<Class<*>>(n)
                sig[0] = Int::class.javaPrimitiveType
                for (i in 1 until n) sig[i] = Boolean::class.javaPrimitiveType
                c.javaClass.getDeclaredMethod("exitFreeformTask", *sig)
            }.getOrNull()
        }
        if (byArity != null) {
            byArity.isAccessible = true
            val ok = runCatching {
                // useBLASTSync=false（与 ROM 自己的 shell handler 同款）
                val args = arrayOfNulls<Any>(byArity.parameterCount)
                args[0] = taskId
                for (i in 1 until byArity.parameterCount) args[i] = false
                byArity.invoke(c, *args)
                true
            }.getOrElse {
                HLog.w(TAG, "exitFreeformTask($taskId) failed: ${it.message}")
                false
            }
            if (ok) return
        }
        broadcastFallback(context, "no exitFreeformTask on this ROM (task=$taskId)")
    }

    /**
     * 真正退出：**先试 ROM 的关闭过渡，然后验证任务是否真的消失，再逐级降级**。
     *
     * 2026-10-17 真机教训：`startExitFreeformShellTransition` 对"被缩成 mini / 挂起"的任务会
     * **静默 no-op**（日志里 `close via shell transition(27821)` 之后任务 `mode=freeform visible=true`
     * 依旧在 ⇒ 用户看到"点了不关闭"）。旧实现把"没抛异常"当成功 ✗ ⇒ 现在 700ms 后回查，
     * 还在就降级：ROM 自己的 shell 命令 → `exitFreeformTask`（无过渡但确实退出）→ 广播。
     * 验证与降级都在主线程调度；实际调用仍投 ROM 线程（见 [postToRomExecutor]）。
     */
    private fun doExit(taskId: Int, context: Context? = null) {
        // try#1（2026-10-18 换序）：caption × 的原版路径——**有动画**（见 closeViaCaptionPath
        // 的注释：我们旧的 closeWithTransition 被 ROM 分发进"故意无动画"旁路）。
        val ok = closeViaCaptionPath(taskId)
        HLog.i(TAG, "close try#1 captionPath=$ok task=$taskId")
        verifyGoneThen(taskId, 700L) {
            val sh = closeWithTransition(taskId)
            HLog.w(TAG, "close try#2 shellTransition=$sh task=$taskId")
            verifyGoneThen(taskId, 700L) {
                // 2026-10-25 换序：反射 exitFreeformTask 提前（进程内、零 su；反编译实证 shell
                // 命令 handler 调的就是同一 public 方法 ⇒ 语义等价）；su 的 shell 命令降为
                // try#4 末级（rootless 机器本就不可用，rooted 机器也只是保底）。
                val ex = closeViaExitFreeform(taskId)
                HLog.w(TAG, "close try#3 exitFreeformTask=$ex task=$taskId")
                verifyGoneThen(taskId, 700L) {
                    val ef = closeViaShellCommand(taskId)
                    HLog.w(TAG, "close try#4 shell-cmd=$ef task=$taskId")
                    if (!ef) {
                        val ctx = context ?: return@verifyGoneThen
                        broadcastFallback(ctx, "close fallback (task=$taskId)")
                    }
                }
            }
        }
    }

    /** [delayMs] 后回查任务是否仍在册；仍在就 [next]（投 ROM 线程执行）。 */
    private fun verifyGoneThen(taskId: Int, delayMs: Long, next: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val alive = runCatching {
                val repo = repository ?: return@postDelayed
                infoForTask(repo, taskId) != null
            }.getOrDefault(false)
            if (!alive) {
                HLog.i(TAG, "close ok（task=$taskId 已消失）")
                return@postDelayed
            }
            if (!postToRomExecutor(Runnable { next() })) next()
        }, delayMs)
    }

    /**
     * 全部小窗缩成**钉在屏幕边缘的应用图标**（2026-10-18 新增 pin 档的动作本体）。
     *
     * ROM 侧证据（OS4 jadx）：`MiuiFreeformModeController.pinAllFreeForm()`（`:2689`）——
     * 遍历 z-order 里非钉住的小窗，`PinHandler.prepareFreeFormToPin` + `setPinAnimInfo`
     * + `setBounds`，最后 `startAllPinShellTransition`（**带过渡动画**）；已在钉住态的窗被跳过。
     * caption（`MiuiCaptionClickListener`）没有 pin 按钮 ⇒ ROM 自己没有用户入口，这正是补位。
     * 首选直接反射调 controller（零权限），**700ms 回查未生效则重投一次**（2026-10-25 补：
     * 旧实现 posted 即 return true，ROM 侧因"已有动画在跑"等静默不执行时无感知——与 mini 的
     * void 静默返回同病）；shell 命令 `cmd window shell miuifreeform pinAllFreeform` 降为末级。
     */
    private fun pinAll(): Boolean {
        // ⚠ pinAllFreeForm 内部是 WindowContainerTransaction + startAllPinShellTransition
        //（WM 锁 + shell 过渡）——**必须跑在 ROM 的 WMShell 线程**，与 doExit 同一条纪律
        //（"exit 仍必须在 ROM 的 WMShell 线程"，见 postToRomExecutor 注释）。
        // 直接在主线程调 = 锁序死锁 = SystemUI 卡死（2026-10-18 真机：一点空白整机冻住）。
        if (postPinAllDirect()) {
            HLog.i(TAG, "pin via controller.pinAllFreeForm()（ROM 线程，${PIN_VERIFY_DELAY_MS}ms 回查）")
            verifyPinAllThen(retriesLeft = 1)
            return true
        }
        // 直调投递失败 ⇒ shell 命令兜底（进程级，无线程约束）
        return pinViaShellCommand()
    }

    /** 反射直调 pinAllFreeForm（投 ROM 线程；动作是否真生效由 [verifyPinAllThen] 回查）。 */
    private fun postPinAllDirect(): Boolean {
        val c = controller ?: return false
        return postToRomExecutor(Runnable {
            runCatching {
                c.javaClass.methods.firstOrNull {
                    it.name == "pinAllFreeForm" && it.parameterCount == 0
                }?.invoke(c)
            }.onFailure { HLog.w(TAG, "pinAllFreeForm invoke failed: ${it.message}") }
        })
    }

    /**
     * pinAll 的生效回查：[PIN_VERIFY_DELAY_MS] 后查 z-order 里是否仍有"未钉住且未在退出"的
     * 小窗；仍有且还有重试额度 ⇒ 再投一次直调；再败 ⇒ shell 命令兜底（**异步**——旧实现的
     * su 同步跑在 spy 主线程，冷启 100~800ms 最坏 2s，期间输入派发停摆）。
     * repository 缺失时无从查证，不重试也不兜底（保持旧行为的"尽力而为"）。
     */
    private fun verifyPinAllThen(retriesLeft: Int) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val pending = runCatching {
                val repo = repository ?: return@postDelayed
                zOrderIds(repo).any { !isExiting(it) && !isPinned(it) }
            }.getOrDefault(false)
            if (!pending) return@postDelayed
            if (retriesLeft > 0 && postPinAllDirect()) {
                HLog.i(TAG, "pin 回查：仍有未钉住窗 ⇒ 重投一次")
                verifyPinAllThen(retriesLeft - 1)
                return@postDelayed
            }
            HLog.w(TAG, "pin 回查：直调未生效 ⇒ shell 命令兜底")
            pinViaShellCommand()
        }, PIN_VERIFY_DELAY_MS)
    }

    /** pin 的 shell 命令末级兜底。waitFor 超时 = 失败（旧实现返回值被忽略）。 */
    private fun pinViaShellCommand(): Boolean = runCatching {
        val p = ProcessBuilder(
            "su", "-c", "cmd window shell miuifreeform pinAllFreeform"
        ).start()
        val finished = p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            p.destroy()
            HLog.w(TAG, "pin via shell timeout")
            return@runCatching false
        }
        HLog.i(TAG, "pin via shell command")
        true
    }.onFailure { HLog.w(TAG, "pin via shell failed: ${it.message}") }.getOrDefault(false)

    /**
     * ROM 自己的 shell 命令（OS3/OS4 都有，close 链 try#4 **末级**）：
     * `cmd window shell miuifreeform exitFreeformTask <id>`。
     * 2026-10-25：waitFor 返回值原来被忽略——超时也报成功，白烧上级一轮 700ms 回查。
     */
    private fun closeViaShellCommand(taskId: Int): Boolean = runCatching {
        val p = ProcessBuilder(
            "su", "-c", "cmd window shell miuifreeform exitFreeformTask $taskId"
        ).start()
        val finished = p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            p.destroy()
            HLog.w(TAG, "closeViaShellCommand($taskId) timeout")
            return@runCatching false
        }
        true
    }.onFailure { HLog.w(TAG, "closeViaShellCommand($taskId) failed: ${it.message}") }.getOrDefault(false)

    /** ROM 的 `exitFreeformTask`（无过渡，但**确实退出**；OS2 无此方法）。 */
    private fun closeViaExitFreeform(taskId: Int): Boolean {
        val c = controller ?: return false
        val byArity = listOf(3, 2).firstNotNullOfOrNull { n ->
            runCatching {
                val sig = arrayOfNulls<Class<*>>(n)
                sig[0] = Int::class.javaPrimitiveType
                for (i in 1 until n) sig[i] = Boolean::class.javaPrimitiveType
                c.javaClass.getDeclaredMethod("exitFreeformTask", *sig)
            }.getOrNull()
        } ?: return false
        return runCatching {
            byArity.isAccessible = true
            val args = arrayOfNulls<Any>(byArity.parameterCount)
            args[0] = taskId
            for (i in 1 until byArity.parameterCount) args[i] = false
            byArity.invoke(c, *args)
            true
        }.getOrElse {
            HLog.w(TAG, "exitFreeformTask($taskId) failed: ${it.message}")
            false
        }
    }

    /**
     * 把任务投到 ROM 的 WMShell 线程（`controller.mMainExecutor`，退 `animation.mMainExecutor`）。
     * 返回 false = 找不到执行器（调用方自行直接调用，行为与改动前一致）。
     */
    private fun postToRomExecutor(task: Runnable): Boolean {
        val holder: Any = controller ?: return false
        val ex = runCatching {
            val f = holder.javaClass.getDeclaredField("mMainExecutor")
            f.isAccessible = true
            f.get(holder)
        }.getOrNull() ?: runCatching {
            val f = holder.javaClass.getDeclaredField("mMainHandler")
            f.isAccessible = true
            f.get(holder)
        }.getOrNull() ?: return false
        val posted = runCatching {
            ex.javaClass.methods.firstOrNull { it.name == "execute" && it.parameterCount == 1 }
                ?.invoke(ex, task)
            true
        }.getOrElse { false }
        if (posted) return true
        // 兜底：Handler.post
        return runCatching {
            ex.javaClass.getMethod("post", Runnable::class.java).invoke(ex, task)
            true
        }.getOrDefault(false)
    }

    /**
     * **原生关闭**（与 caption X 同 recipe，带过渡）：
     * `wct.removeTask(info.taskInfo.token)` → `taskOps.mTransitionStarter.startRemoveTransition(wct)`。
     * 需要 `TaskOperations`（构造器捕获，见 [attachTaskOperationsCapture]）。
     * 全程反射（`WindowContainerTransaction` / `WindowContainerToken` 都是 @hide，不在公开 SDK）。
     */
    private fun closeViaRemoveTransition(taskId: Int): Boolean = runCatching {
        val ops = taskOps ?: return@runCatching false
        val repo = repository ?: return@runCatching false
        val info = repo.javaClass
            .getMethod("getMiuiFreeformTaskInfo", Int::class.javaPrimitiveType)
            .invoke(repo, taskId) ?: return@runCatching false
        val ti = runCatching { info.javaClass.getMethod("getTaskInfo").invoke(info) }.getOrNull()
            ?: return@runCatching false
        val token = runCatching { ti.javaClass.getField("token").get(ti) }.getOrNull()
            ?: return@runCatching false
        val cl = ti.javaClass.classLoader
        val wctCls = Class.forName("android.window.WindowContainerTransaction", false, cl)
        val wct = wctCls.getConstructor().newInstance()
        val removeTask = wctCls.methods.firstOrNull {
            it.name == "removeTask" && it.parameterCount == 1
        } ?: return@runCatching false
        removeTask.invoke(wct, token)
        val f = ops.javaClass.getDeclaredField("mTransitionStarter")
        f.isAccessible = true
        val starter = f.get(ops) ?: return@runCatching false
        val start = starter.javaClass.methods.firstOrNull {
            it.name == "startRemoveTransition" && it.parameterCount == 1
        } ?: return@runCatching false
        start.invoke(starter, wct)
        true
    }.onFailure {
        HLog.w(TAG, "closeViaRemoveTransition($taskId) failed: ${causeLine(it)}")
    }.getOrDefault(false)

    /**
     * 带过渡的关闭：反射 `controller.mMiuiFreeformModeAnimation.startExitFreeformShellTransition(info, z)`。
     * `info` 由仓库 `getMiuiFreeformTaskInfo(taskId)` 取；调用成功即视为已投递（过渡异步完成）。
     * 任一步失败返回 false ⇒ 调用方回退到 exitFreeformTask / 广播。
     */
    private fun closeWithTransition(taskId: Int): Boolean = runCatching {
        val c = controller ?: return@runCatching false
        val repo = repository ?: return@runCatching false
        val info = repo.javaClass
            .getMethod("getMiuiFreeformTaskInfo", Int::class.javaPrimitiveType)
            .invoke(repo, taskId) ?: return@runCatching false
        val f = c.javaClass.getDeclaredField("mMiuiFreeformModeAnimation")
        f.isAccessible = true
        val anim = f.get(c) ?: return@runCatching false
        val m = anim.javaClass.getMethod(
            "startExitFreeformShellTransition",
            info.javaClass, Boolean::class.javaPrimitiveType
        )
        m.invoke(anim, info, false)
        true
    }.onFailure {
        HLog.w(TAG, "closeWithTransition($taskId) failed: ${causeLine(it)}")
    }.getOrDefault(false)

    /**
     * 降级：广播让 ROM 缩小全部小窗。三代 SystemUI 都监听（见设计文档 §6.3）。
     * 代价是"一刀切"（不只收点在空白处的那一个），所以要在日志里标明这是降级而非精确动作。
     */
    private fun broadcastFallback(context: Context, why: String): Boolean = runCatching {
        // 2026-10-17：这条广播是**兜底**，而 ROM 侧语义 = "把所有小窗一起收"（`state=toHome`）。
        // 多小窗并存时会连用户没点的那几个一起收，还会跟桌面自己的开合动画打架（用户实测：
        // "缩小+挂起后其他动画被影响"）。⇒ 只在"在册可见小窗恰好 1 个"时发（此时它与
        // "收这一个"语义等价）；否则只记日志、把处置权交回用户。
        val n = runCatching { windowsInZOrder().count { it.visible } }.getOrDefault(-1)
        if (n != 1) {
            HLog.w(TAG, "fallback broadcast skipped ($why)：可见小窗=$n ≠ 1")
            return false
        }
        context.sendBroadcast(
            Intent(PrefKeys.FREEFORM_STATE_ACTION)
                .putExtra(PrefKeys.FREEFORM_STATE_EXTRA, PrefKeys.FREEFORM_STATE_TO_HOME)
        )
        HLog.w(TAG, "fallback broadcast toHome ($why)")
        true
    }.getOrElse {
        HLog.w(TAG, "fallback broadcast failed: ${it.message}")
        false
    }

    /** 反射 Method 缓存（2026-10-18 性能审计：`getOrNull`/`infoForTask` 在 blank-tap 判定与
     *  `awaitStable` 16ms 轮询里逐调用 `getMethod`——方法表查找+分配，稳定帧窗内约 350 次/动作）。 */
    private val methodCache = java.util.concurrent.ConcurrentHashMap<String, java.lang.reflect.Method>()

    /** 按「类#名(参数)」缓存 Method；解析失败不缓存（访问器有无按 ROM 构建稳定，成功路径才是热点）。 */
    private fun Any.cachedMethod(name: String, vararg params: Class<*>): java.lang.reflect.Method? =
        runCatching {
            methodCache.getOrPut(javaClass.name + '#' + name +
                params.joinToString(",", "(", ")") { it.name }) {
                javaClass.getMethod(name, *params)
            }
        }.getOrNull()

    /** 反射读一个 getter（没有就 null）——ROM 各构建的访问器有代差，逐个容忍。 */
    private fun Any.getOrNull(method: String): Any? =
        cachedMethod(method)?.let { runCatching { it.invoke(this) }.getOrNull() }

}
