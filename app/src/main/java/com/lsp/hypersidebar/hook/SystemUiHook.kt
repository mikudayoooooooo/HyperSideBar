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
                            if (isOrderedBroadcast) resultCode = resolveAndClick(c, cn)
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
     *  点击竞态（2026-09-06 用户实测 CaptureTileService 成功率低）：QS 收起时
     *  TileService 处于解绑态，click() 派发后 onClick 偶发丢失。先
     *  requestListeningState（公开 API）请求绑定进监听态，延迟 250ms 再点——
     *  顺带给扇形收场/目标应用解冻留出时间窗。 */
    private fun resolveAndClick(context: Context, cn: ComponentName): Int {
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

            val tile = tiles.firstOrNull { t ->
                t != null && runCatching {
                    t.javaClass.methods
                        .first { it.name == "getTileSpec" && it.parameterCount == 0 }
                        .invoke(t) == spec
                }.getOrNull() == true
            } ?: createdTiles[spec]
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

            // listening 激活：TileService 未绑定时点击会丢（bind 竞态）
            runCatching {
                cl.loadClass("android.service.quicksettings.TileService")
                    .getMethod(
                        "requestListeningState",
                        Context::class.java, ComponentName::class.java
                    )
                    .invoke(null, context.applicationContext, cn)
            }.onFailure { Log.w(TAG, "requestListeningState failed: ${it.message}") }

            Handler(Looper.getMainLooper()).postDelayed({
                runCatching {
                    tile.javaClass.methods
                        .firstOrNull { it.name == "click" && it.parameterCount == 1 }
                        ?.let { it.invoke(tile, null) }
                    Log.i(TAG, "clickTile: clicked $spec")
                }.onFailure { Log.w(TAG, "delayed click failed: ${it.message}") }
            }, CLICK_DELAY_MS)
            Log.i(TAG, "clickTile: scheduled $spec (+${CLICK_DELAY_MS}ms, listening primed)")
            SystemUiHookResult.RESULT_CLICKED
        }.getOrElse {
            Log.w(TAG, "clickTile failed: ${it.message}")
            0
        }
    }

    /** 公有字段直读，非 public 回退 declared+accessible（反射容错统一入口） */
    private fun readField(obj: Any, name: String): Any? =
        runCatching { obj.javaClass.getField(name).get(obj) }
            .getOrElse {
                obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(obj)
            }

    private companion object {
        const val ADAPTER_CLASS =
            "com.android.systemui.qs.pipeline.domain.adapter.MiuiQSHostAdapter"
        const val CUSTOM_TILE_CLASS = "com.android.systemui.qs.external.CustomTile"
        const val CLICK_DELAY_MS = 250L

        /** createTile 现场创建的实例按 spec 缓存复用（上界=用户添加的磁贴快捷方式数） */
        val createdTiles = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }
}
