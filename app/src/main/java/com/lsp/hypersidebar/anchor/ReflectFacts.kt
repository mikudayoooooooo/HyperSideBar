package com.lsp.hypersidebar.anchor

import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * L1 的 Android 实现：**反射**取类事实。
 *
 * 为什么是反射而不是复用 dex 事实：L1 在**每次启动**都要跑一遍，而反射的开销只是
 * "加载类 + 两个查询" —— 关键是 `Class.forName(name, false, cl)` 用 `initialize = false`，
 * **不触发静态初始化**。这样 L0 命中时整条路径都不需要 DEX 扫描，也让"三宿主都做"几乎免费。
 *
 * 注意：`declaredMethods` 与规则要求的"**自身声明**"同口径 —— 继承来的方法不算，
 * 与 ezxhelper `MethodFinder.fromClass()`（declared + 本类直接实现的接口）一致。
 */
internal class ReflectFacts(private val classLoader: ClassLoader) : FactsSource {

    private val classes = ConcurrentHashMap<String, Class<*>?>()
    private val signatures = ConcurrentHashMap<String, Set<String>>()

    override fun exists(fqcn: String): Boolean = load(fqcn) != null

    override fun declaredSignatures(fqcn: String): Set<String> =
        signatures.getOrPut(fqcn) {
            val cls = load(fqcn) ?: return@getOrPut emptySet()
            cls.declaredMethods.mapTo(HashSet()) { signatureOf(it) }
        }

    override fun superName(fqcn: String): String? = load(fqcn)?.superclass?.name

    private fun load(fqcn: String): Class<*>? =
        classes.getOrPut(fqcn) {
            // initialize=false：只加载与链接，不跑静态初始化（避免副作用）
            runCatching { Class.forName(fqcn, false, classLoader) }.getOrNull()
        }

    /** 与 [MethodSpec.signature] 同格式：`name(param,param):ret`，用点分名 */
    private fun signatureOf(m: Method): String {
        val params = m.parameterTypes.joinToString(",") { it.name }
        return "${m.name}($params):${m.returnType.name}"
    }
}