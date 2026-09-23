package com.lsp.hypersidebar.anchor

import android.content.Context
import com.lsp.hypersidebar.util.HLog
import io.github.kyuubiran.ezxhelper.core.ClassLoaderProvider
import io.github.kyuubiran.ezxhelper.xposed.EzXposed
import java.io.File

/**
 * 宿主身份：既是缓存命名空间，也是**失效闸**。
 *
 * `versionCode` 让 ROM 升级自动换 tag；`apkLength` 额外盖住"同版本号但包被替换"的场景
 * （小米推送小版本更新时 versionCode 未必变）。
 */
internal data class HostIdentity(val pkg: String, val versionCode: Long, val apkLength: Long) {
    val tag: String get() = "$pkg#$versionCode#$apkLength"

    companion object {
        /** 注意：`longVersionCode` 在 `PackageInfo` 上，`ApplicationInfo` 没有版本号字段 */
        fun of(ctx: Context?): HostIdentity {
            val info = ctx?.applicationInfo ?: return HostIdentity("unknown", 0L, 0L)
            val len = runCatching { File(info.sourceDir).length() }.getOrDefault(0L)
            val versionCode = runCatching {
                ctx.packageManager.getPackageInfo(info.packageName, 0).longVersionCode
            }.getOrDefault(0L)
            return HostIdentity(info.packageName ?: "unknown", versionCode, len)
        }
    }
}

/**
 * 结构化锚点解析的编排入口。
 *
 * **每宿主进程只解析一次**（进程内 memo）：8 个 hook 共用同一张 [RoleResolution] 表，
 * 表同时驱动 hook 安装与自检报告（详见 docs/adaptation/structured-anchor-resolution-plan.md）。
 *
 * 三层：L0 候选名（快路径，必须过指纹）→ L1 反射指纹校验（每次启动都跑）→
 * L2 DexKit 结构扫描（**仅当 L0 全部未命中**时触发，且结果同样要过 L1）。
 *
 * 失败语义：**逐 role 闭锁** —— 某个 role 解析不出来只是不装对应 hook，不影响其它 role，
 * 更不改变既有默认路径；L2 整体不可用（native 没加载起来）也只退化为 L0/L1。
 */
internal object AnchorResolver {

    private const val TAG = "AnchorResolver"

    @Volatile
    private var table: Map<String, RoleResolution>? = null

    @Volatile
    private var indexState = "not-initialized"

    @Volatile
    private var hostTag = "unknown"

    /** 便捷重载：直接吃宿主侧 prefs（总闸读 [PrefKeys.ANCHOR_STRUCTURAL_SCAN]，默认开） */
    fun resolveAll(prefs: android.content.SharedPreferences?): Map<String, RoleResolution> =
        resolveAll(
            runCatching {
                prefs?.getBoolean(com.lsp.hypersidebar.prefs.PrefKeys.ANCHOR_STRUCTURAL_SCAN, true)
            }.getOrNull() ?: true
        )

    /** 幂等；返回 role → 解析结果。任何异常都在内部消化（绝不打断宿主 init） */
    fun resolveAll(structuralScanEnabled: Boolean): Map<String, RoleResolution> {
        table?.let { return it }
        synchronized(this) {
            table?.let { return it }
            val resolved = runCatching { compute(structuralScanEnabled) }
                .getOrElse { t ->
                    HLog.e(TAG, "resolveAll failed, all roles unresolved: ${t.message}", t)
                    AnchorRoles.ALL.associate { spec ->
                        spec.role to RoleResolution(
                            spec.role, null, Layer.NONE, 0, ResolveState.NOT_FOUND,
                            "resolver crashed: ${t.javaClass.simpleName}"
                        )
                    }
                }
            table = resolved
            return resolved
        }
    }

    private fun compute(structuralScanEnabled: Boolean): Map<String, RoleResolution> {
        val ctx = runCatching { EzXposed.appContext }.getOrNull()
        val host = HostIdentity.of(ctx)
        hostTag = host.tag
        val classLoader = ClassLoaderProvider.safeClassLoader

        val index = if (structuralScanEnabled) {
            DexKitIndex.create(host.tag, classLoader, ctx?.cacheDir).also {
                indexState = it?.stateLine ?: "unavailable"
            }
        } else {
            indexState = "disabled-by-pref"
            null
        }

        val facts = ReflectFacts(classLoader)
        val out = LinkedHashMap<String, RoleResolution>(AnchorRoles.ALL.size)
        for (spec in AnchorRoles.ALL) {
            val r = AnchorEngine.resolve(facts, index, spec)
            HLog.i(TAG, "role ${r.oneLine()}")
            out[spec.role] = r
        }
        HLog.i(TAG, "resolve done: ${summary(out)} | native=${NativeLibLoader.statusLine} index=$indexState")
        return out
    }

    private fun summary(t: Map<String, RoleResolution>): String {
        val resolved = t.values.count { it.isResolved }
        val hint = t.values.count { it.isResolved && it.layer == Layer.HINT }
        val structural = t.values.count { it.isResolved && it.layer == Layer.STRUCTURAL }
        val missing = t.size - resolved
        return "resolved=$resolved/${t.size} (hint=$hint structural=$structural missing=$missing)"
    }

    // ===== 供接线与自检读取 =====

    fun get(role: String): RoleResolution? = table?.get(role)

    /** `null` = 未解析出来 ⇒ 调用方应跳过该 hook（闭锁） */
    fun fqcnOf(role: String): String? = table?.get(role)?.fqcn

    fun isResolved(role: String): Boolean = table?.get(role)?.isResolved == true

    /** 自检报告用：首行是环境，其后每行一个 role */
    fun reportLines(): List<String> = buildList {
        add("host=$hostTag  native=${NativeLibLoader.statusLine}  index=$indexState")
        val t = table
        if (t == null) {
            add("(not resolved yet in this process)")
        } else {
            t.values.forEach { add(it.oneLine()) }
        }
    }

    fun stateLine(): String = "host=$hostTag native=${NativeLibLoader.statusLine} index=$indexState"
}