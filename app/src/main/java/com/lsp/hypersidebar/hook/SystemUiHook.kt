package com.lsp.hypersidebar.hook

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.util.Log
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.util.RelayToken
import com.lsp.hypersidebar.util.SystemUiHookResult
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
 * 方案：本进程注册令牌校验的点击接收器，从数据层（MiuiQSHostAdapter.interactor
 * .getCurrentQSTiles()）按 spec 找到磁贴实例直接 click(null)——绕过一切面板状态门禁，
 * QS 收起也能触发，零可见动作。
 *
 * resultCode 协议（有序广播）：1=已点击；0=适配器未就绪/磁贴不在当前 QS/异常，
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
        val adapterClass = runCatching {
            javaClass.classLoader.loadClass(ADAPTER_CLASS)
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
                            if (isOrderedBroadcast) resultCode = clickTile(cn)
                        }
                    },
                    IntentFilter(PrefKeys.QS_TILE_CLICK_ACTION),
                    Context.RECEIVER_EXPORTED
                )
                Log.i(TAG, "qs tile click receiver registered (via Application.attach)")
            }
    }

    /** 数据层直点磁贴；返回 1=已点击，0=未就绪/未找到/异常（发送端据此回退兜底）。
     *  纯反射（libxposed 新 API 无 XposedHelpers），字段/方法均为 public。 */
    private fun clickTile(cn: ComponentName): Int {
        val adapter = hostAdapter ?: run {
            Log.w(TAG, "clickTile: adapter not stashed yet")
            return 0
        }
        return runCatching {
            val cl = adapter.javaClass.classLoader
            val interactor = adapter.javaClass.getMethod("getInteractor").invoke(adapter)
            val tiles = interactor.javaClass.methods
                .first { it.name == "getCurrentQSTiles" && it.parameterCount == 0 }
                .invoke(interactor) as? List<*>
                ?: return@runCatching 0
            val toSpec = cl.loadClass(CUSTOM_TILE_CLASS)
                .methods.first { it.name == "toSpec" && it.parameterCount == 1 }
            val spec = toSpec.invoke(null, cn) as? String ?: return@runCatching 0
            val tile = tiles.firstOrNull { t ->
                t != null && runCatching {
                    t.javaClass.methods
                        .first { it.name == "getTileSpec" && it.parameterCount == 0 }
                        .invoke(t) == spec
                }.getOrNull() == true
            } ?: run {
                Log.w(TAG, "clickTile: tile not in current QS: $spec")
                return@runCatching 0
            }
            tile.javaClass.methods
                .firstOrNull { it.name == "click" && it.parameterCount == 1 }
                ?.let { it.invoke(tile, null) }
            Log.i(TAG, "clickTile: clicked $spec")
            SystemUiHookResult.RESULT_CLICKED
        }.getOrElse {
            Log.w(TAG, "clickTile failed: ${it.message}")
            0
        }
    }

    private companion object {
        const val ADAPTER_CLASS =
            "com.android.systemui.qs.pipeline.domain.adapter.MiuiQSHostAdapter"
        const val CUSTOM_TILE_CLASS = "com.android.systemui.qs.external.CustomTile"
    }
}
