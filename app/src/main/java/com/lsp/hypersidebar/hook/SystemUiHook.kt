package com.lsp.hypersidebar.hook

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.util.RelayToken
import com.lsp.hypersidebar.util.SystemUiHookResult
import io.github.kyuubiran.ezxhelper.core.ClassLoaderProvider
import java.util.concurrent.atomic.AtomicBoolean
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createAfterHook
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createAfterHooks

/**
 * SystemUI 进程 hook（批次 3，2026-09-05 定稿）：QS 磁贴数据层直点桥。
 *
 * 背景（真机测试矩阵实锤）：`cmd statusbar click-tile` 仅在 QS 交互/展开态真生效
 * （fire-and-forget——QS 收起时静默丢弃且 exit=0，与调用 uid 无关）。SystemUI 源码
 * （ear/jadx-full）显示 CommandQueue 的两处 clickTile 回调（MiuiQSFragment /
 * CentralSurfacesCommandQueueCallbacks）在"使用控制中心"时早退，门禁全在回调层，
 * `QSTile.click()` 本身无任何面板状态约束。
 *
 * 方案：本进程注册令牌校验的点击接收器，从数据层按 spec 找磁贴实例直接 click(null)
 * ——绕过一切面板状态门禁，QS 收起也能触发，零可见动作。**未固定在 QS 的磁贴**
 * 经 `MiuiQSHostAdapter.createTile(spec)`（QSHost 接口方法，QS 磁贴建议同机制）现场
 * 创建实例后点击，同样不依赖固定状态。
 *
 * resultCode 协议（有序广播）：1=已点击；0=适配器未就绪/createTile 失败/异常，
 * 发送端据此回退 root 三连兜底。
 */
class SystemUiHook(private val prefs: SharedPreferences) : BaseHook() {

    override val name = "SystemUiHook"

    private val TAG = "SystemUiHook"

    /** dagger 单例，构造于 SystemUI 启动期——构造即 stash 供接收器使用 */
    @Volatile private var hostAdapter: Any? = null

    override fun init() {
        hookAdapterStash()
        hookClickReceiver()
    }

    private fun hookAdapterStash() {
        // 类解析必须走宿主 classloader（EzXHelper ClassLoaderProvider）——
        // javaClass.classLoader 是模块自身的，看不到 SystemUI 类（11:22 实测 not found）
        val adapterClass = runCatching {
            ClassLoaderProvider.safeClassLoader.loadClass(ADAPTER_CLASS)
        }.getOrNull() ?: run {
            Log.w(TAG, "MiuiQSHostAdapter not found (ROM drift?)")
            return
        }
        adapterClass.declaredConstructors.toList().createAfterHooks { param ->
            hostAdapter = param.thisObjectOrNull
            Log.i(TAG, "MiuiQSHostAdapter stashed")
        }
    }

    private fun hookClickReceiver() {
        MethodFinder.fromClass("android.app.Application")
            .filterByName("attach")
            .filterByParamTypes(Context::class.java)
            .firstOrNull()
            ?.createAfterHook {
                val ctx = it.args[0] as? Context ?: return@createAfterHook
                ctx.registerReceiver(
                    object : BroadcastReceiver() {
                        override fun onReceive(c: Context, intent: Intent) {
                            // 严格令牌校验：点击可切换任意 QS 开关（含 VPN），无令牌一律拒绝
                            val expected = RelayToken.read(prefs)
                            val got = intent.getStringExtra(PrefKeys.RELAY_LAUNCH_EXTRA_TOKEN)
                            if (expected.isNullOrEmpty() || got != expected) {
                                Log.w(TAG, "qs tile click rejected: bad token")
                                return
                            }
                            val cn = intent.getStringExtra(PrefKeys.QS_TILE_CLICK_EXTRA)
                                ?.let { ComponentName.unflattenFromString(it) } ?: return
                            val prebind = intent.getBooleanExtra(PrefKeys.QS_TILE_PREBIND_EXTRA, false)
                            if (isOrderedBroadcast) resultCode = resolveAndClick(c, cn, prebind)
                        }
                    },
                    IntentFilter(PrefKeys.QS_TILE_CLICK_ACTION),
                    Context.RECEIVER_EXPORTED
                )
                Log.i(TAG, "qs tile click receiver registered (via Application.attach)")
            }
    }

    /** 数据层直点磁贴；返回 1=磁贴已定位（点击异步），0=未就绪/未找到/异常。
     *  纯反射（libxposed 新 API 无 XposedHelpers）。注意：jadx 反编译里的 Kotlin
     *  "属性"运行时不一定有 getter（11:36 实测 getInteractor NoSuchMethod），
     *  字段一律 getField 直读。
     *
     *  绑定+listening 双预热：TileLifecycleManager 在服务连接后的 pending 冲刷里，
     *  onClick 仅在 mListening==true 时投递，否则打 "Managed to get click on
     *  non-listening state..." 直接丢弃。框架 handleClick 的 !needListening 分支即
     *  setBindRequested + onStartListening 成对出现——这里补齐另一半（mService=
     *  TileLifecycleManager，onStartListening 置 mListening=true）。
     *  requestListeningState 在 HyperOS 实测抛 NPE（12:20 日志），弃用。
     *
     *  绑定那一半在下方「预热①」里已按方案 B 重写（见该处注释），不再无条件 unbind。 */
    private fun resolveAndClick(context: Context, cn: ComponentName, prebind: Boolean): Int {
        val adapter = hostAdapter ?: run {
            Log.w(TAG, "clickTile: adapter not stashed yet")
            return 0
        }
        return runCatching {
            val cl = adapter.javaClass.classLoader
            val interactor = readField(adapter, "interactor")
                ?: error("interactor field is null")
            val tiles = interactor.javaClass.methods
                .first { it.name == "getCurrentQSTiles" && it.parameterCount == 0 }
                .invoke(interactor) as? List<*>
                ?: return@runCatching 0
            Log.i(TAG, "clickTile: current tiles=${tiles.size}")
            val toSpec = cl.loadClass(CUSTOM_TILE_CLASS)
                .methods.first { it.name == "toSpec" && it.parameterCount == 1 }
            val spec = toSpec.invoke(null, cn) as? String ?: return@runCatching 0

            var isPinned = false
            val tile = tiles.firstOrNull { t ->
                t != null && runCatching {
                    t.javaClass.methods
                        .first { it.name == "getTileSpec" && it.parameterCount == 0 }
                        .invoke(t) == spec
                }.getOrNull() == true
            }?.also { isPinned = true } ?: createdTiles[spec]
            ?: run {
                // 未固定磁贴：现场创建（createTile 返回 null 则彻底不可触发）
                val created = adapter.javaClass.methods
                    .firstOrNull { it.name == "createTile" && it.parameterCount == 1 }
                    ?.invoke(adapter, spec)
                    ?: run {
                        Log.w(TAG, "clickTile: createTile returned null: $spec")
                        return@runCatching 0
                    }
                Log.i(TAG, "clickTile: tile created on demand: $spec")
                createdTiles[spec] = created
                created
            }

            // 预热①绑定（2026-09-07 方案 B 重写，取代 09-06 的"解绑再重绑"）
            //
            // 旧实现的死因（反编译 TileServiceManager.java 实证）：
            //   setBindRequested 首行 `if (mBindRequested == bindRequested) return;`
            //   而 unbindService() 只清 mBound、不清 mBindRequested → 第一次点击之后
            //   mBindRequested 永久停在 true，之后所有 setBindRequested(true) 全是空操作，
            //   再也不会 bindService()；onClick 入队（TileLifecycleManager.queueMessage）后
            //   唯一的兜底 handleDeath() 又因为 mIsBound 被我们自己 unbind 清掉而首行 return
            //   → 点击永久排队，只有"手动拉一下状态栏"触发真实绑定才被冲刷补发。
            //   clash 之所以"稳定"，只是它每 27~30s 被系统回收一次、顺带把毒状态洗掉；
            //   quickpay 长连接常驻不回收 → 首次之后必挂（本次日志 08:45:28 clash 3s 内连点同样挂）。
            //
// 方案 B：不再无条件 unbind —— 已连接就保留连接走直连投递（最快，且保住
//   handleDeath 的重绑兜底），冻结由 :ui 侧 FanPrewarmer 预热制处理（fan 呼出时
//   kill+预 bind）；
            //   只有"服务实际没连上"时才强制一次绑定，且必须先复位 mBindRequested /
            //   mBindAllowed（否则仍会落进上面的早退分支），让 setBindRequested(true)
            //   必然走到 bindService()。
            var mgr: Any? = null
            var lifecycle: Any? = null
            var connected = false
            runCatching {
                mgr = readField(tile, "mServiceManager")
                    ?: error("mServiceManager field is null")
                lifecycle = readField(tile, "mService")
                    ?: error("mService field is null")
                // TileLifecycleManager.mBound 是 AtomicBoolean，代表真实连接是否还在
                connected = (readField(lifecycle!!, "mBound") as? AtomicBoolean)?.get() == true
                if (!connected) {
                    writeField(mgr!!, "mBindRequested", false)
                    writeField(mgr!!, "mBindAllowed", true)
                    // mBound=true 但服务其实没连上 → 一并清掉，否则走不到 bind 分支
                    if (readField(mgr!!, "mBound") == true) writeField(mgr!!, "mBound", false)
                }
                mgr!!.javaClass.methods
                    .first { it.name == "setBindRequested" && it.parameterCount == 1 }
                    .invoke(mgr, true)
            }.onFailure {
                Log.w(TAG, "prime bind failed: ${it.javaClass.simpleName}: ${it.message}")
            }
            // 预热②listening：TileLifecycleManager.onStartListening 置 mListening=true，
            // 未连接场景服务连上后冲刷按序投递 onStartListening→onClick（handlePendingMessages
            // 里 !mListening 会打 "Managed to get click on non-listening state..." 直接丢弃）
            runCatching {
                lifecycle!!.javaClass.methods
                    .first { it.name == "onStartListening" && it.parameterCount == 0 }
                    .invoke(lifecycle)
            }.onFailure {
                Log.w(TAG, "onStartListening failed: ${it.javaClass.simpleName}: ${it.message}")
            }

            // 预热③豁免：MIUI 安全服务 exemptTemporarily（与框架 handleClick 同款动作，
            // 经 mCustomTileExt 反射）——豁免后台弹出/启动限制。冻结场景下配合 300ms
            // 延迟投递给 system_server 豁免/解冻传播留窗（SystemUI 直写 cgroup 被
            // SELinux 拒绝：23:10 EACCES 实证，故走豁免+延迟）
            runCatching {
                readField(tile, "mCustomTileExt")?.let { ext ->
                    ext.javaClass.methods
                        .firstOrNull { it.name == "exemptTemporarily" && it.parameterCount == 0 }
                        ?.invoke(ext)
                }
            }.onFailure {
                Log.w(TAG, "exemptTemporarily failed: ${it.javaClass.simpleName}: ${it.message}")
            }

            // 字段诊断（21:45 对照实验：固定磁贴 handleClick 有日志且生效，created 磁贴
            // 连 handleClick 都不进——四个候选死因 state==0 早退/mCustomTileExt 缺失
            // NPE 静默/handler 卡死/实例销毁，用字段值直接判）
            val diag = runCatching {
                val state = (readField(tile, "mTile") as? android.service.quicksettings.Tile)?.state
                val ext = readField(tile, "mCustomTileExt") != null
                val bound = mgr?.let { runCatching { readField(it, "mBound") }.getOrNull() }
                val req = mgr?.let { runCatching { readField(it, "mBindRequested") }.getOrNull() }
                val allow = mgr?.let { runCatching { readField(it, "mBindAllowed") }.getOrNull() }
                val listening = lifecycle?.let { runCatching { readField(it, "mListening") }.getOrNull() }
                "pinned=$isPinned state=$state ext=$ext connected=$connected bound=$bound req=$req allow=$allow listening=$listening"
            }.getOrNull() ?: "diag failed"
            Log.i(TAG, "clickTile: $diag")

            if (prebind) {
                // 预热模式（2026-09-07 fan 呼出预热制）：prime（绑定+listening+豁免）已完成，
                // 不投递点击——未固定磁贴的实例已进 createdTiles 缓存，用户点击时直连命中。
                // RESULT_CLICKED 语义="预热受理"（bindService 请求已发出，AMS 拉新进程）
                Log.i(TAG, "clickTile: prebind only, click deferred to user: $spec")
                return SystemUiHookResult.RESULT_CLICKED
            }

            if (isPinned) {
                // 固定磁贴：click(null) 实证路径（handleClick 全链路含 ext 联动）
                scheduleClick(lifecycle) {
                    runCatching {
                        tile.javaClass.methods
                            .firstOrNull { it.name == "click" && it.parameterCount == 1 }
                            ?.let { it.invoke(tile, null) }
                        Log.i(TAG, "clickTile: clicked $spec")
                    }.onFailure { Log.w(TAG, "click failed: ${it.message}") }
                }
                Log.i(TAG, "clickTile: primed bind+listening, clicked $spec")
            } else {
                // 未固定磁贴（created on demand）：handleClick 不可依赖（21:45 对照实验
                // 实锤——clicked 后无 CustomTileExt 日志无动作；候选死因见上方 diag）。
                // 直接复刻框架 handleClick 尾段投递：addWindowToken（磁贴内启动权限）
                // → mService.onClick（豁免已在上方预热完成）
                scheduleClick(lifecycle) {
                    runCatching {
                        val token = readField(tile, "mToken") ?: error("mToken field is null")
                        runCatching {
                            readField(tile, "mWindowManager")?.let { wm ->
                                wm.javaClass.methods
                                    .firstOrNull { it.name == "addWindowToken" && it.parameterCount == 4 }
                                    ?.invoke(wm, token, 2035, 0, null)
                            }
                        }.onFailure { Log.w(TAG, "addWindowToken failed: ${it.message}") }
                        val svc = readField(tile, "mService") ?: error("mService field is null")
                        svc.javaClass.methods
                            .first { it.name == "onClick" && it.parameterCount == 1 }
                            .invoke(svc, token)
                        Log.i(TAG, "clickTile: direct onClick delivered $spec")
                    }.onFailure { Log.w(TAG, "direct onClick failed: ${it.message}") }
                }
                Log.i(TAG, "clickTile: primed bind+listening, direct onClick scheduled $spec")
            }
            SystemUiHookResult.RESULT_CLICKED
        }.getOrElse {
            Log.w(TAG, "clickTile failed: ${it.message}")
            0
        }
    }

    /** 点击调度：（2026-09-07 更新）不再人为延迟、也不再解绑——
     *  绑定请求已由预热①同步发出，点击落在"bind 在飞"的窗口内就会被
     *  TileLifecycleManager 排队，服务连上后由 handlePendingMessages 按序冲刷投递。
     *
     *  lifecycle 用于投递后诊断：hasPendingClick()=true 说明这次点击只是入队、尚未送达
     *  （要等下一次绑定才冲刷），=false 说明已直连或已冲刷送达。 */
    private fun scheduleClick(lifecycle: Any?, clickAction: Runnable) {
        val h = Handler(Looper.getMainLooper())
        h.post(clickAction)
        h.postDelayed({
            val pending = runCatching {
                lifecycle?.javaClass?.methods
                    ?.first { it.name == "hasPendingClick" && it.parameterCount == 0 }
                    ?.invoke(lifecycle) as? Boolean
            }.getOrNull()
            Log.i(TAG, "clickTile: post-delivery pendingClick=$pending")
        }, DELIVERY_DIAG_DELAY_MS)
    }

    /** 公有字段直读，非 public 回退 declared+accessible（反射容错统一入口） */
    private fun readField(obj: Any, name: String): Any? =
        runCatching { obj.javaClass.getField(name).get(obj) }
            .getOrElse {
                obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(obj)
            }

    /** 字段直写（与 readField 同款容错），用于复位 TileServiceManager 绑定标志位 */
    private fun writeField(obj: Any, name: String, value: Any) {
        runCatching { obj.javaClass.getField(name).set(obj, value) }
            .getOrElse {
                obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(obj, value)
            }
    }

    private companion object {
        const val ADAPTER_CLASS =
            "com.android.systemui.qs.pipeline.domain.adapter.MiuiQSHostAdapter"
        const val CUSTOM_TILE_CLASS = "com.android.systemui.qs.external.CustomTile"

        /** 投递后诊断延迟：等 bindService→onServiceConnected 冲刷完成后再查 pendingClick */
        const val DELIVERY_DIAG_DELAY_MS = 800L

        /** createTile 现场创建的实例按 spec 缓存复用（上界=用户添加的磁贴快捷方式数） */
        val createdTiles = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }
}
