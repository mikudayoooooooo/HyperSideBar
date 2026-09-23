package com.lsp.hypersidebar.anchor

/**
 * 结构化锚点解析的公共类型（纯逻辑，无 Android 依赖 ⇒ 可在 JVM 上单测）。
 */

/**
 * 方法指纹，参数与返回类型都用**点分**类名（`android.view.View` / `boolean` / `void`），
 * 与宿主反射的 `Class.getName()` 及 DexKit 查询 DSL 的书写形式一致。
 */
data class MethodSpec(val name: String, val params: List<String>, val ret: String) {
    val signature: String get() = "$name(${params.joinToString(",")}):$ret"

    companion object {
        fun of(name: String, params: List<String> = emptyList(), ret: String = "void") =
            MethodSpec(name, params, ret)
    }
}

/**
 * L2 后端的唯一抽象：**只要"包 + 自身声明的方法"**就能列出包内命中的类名（点分、有序、去重）。
 *
 * 刻意不把 super 约束下推给后端：DexKit 的 `ClassMatcher.superClass` 只匹配**直接**父类，
 * 而我们的 coverView 规则要求"super 链达 `android.view.View`"（OS4 的 `e` 中间隔了
 * `com.miui.dock.base.b`）。链式判定统一交给 L1 的反射校验做（也能覆盖增删中间层）。
 *
 * 失败一律返回空列表（闭锁），绝不抛异常给调用方。
 */
interface DexIndex {
    fun findClasses(pkg: String, own: MethodSpec): List<String>

    /** 自检报告用的一行状态描述 */
    val stateLine: String
}