package com.lsp.hypersidebar.anchor

/**
 * 结构化锚点解析的规则引擎（**纯逻辑，无 Android 依赖 ⇒ 可在 JVM 上单测**）。
 *
 * 设计要点（详见 docs/adaptation/structured-anchor-resolution-plan.md）：
 * - 规则形态统一为 `包前缀 ∧ 自身声明的精确方法签名 ∧ super 约束`；
 * - **L0 候选名优先**：保证"现在能用的锚点不会被结构化结果换掉"；
 * - **L1 一律现场校验**：名字命中也要过指纹，所以 OS4 上"名字还在但语义变了"（`f` 变成 Drawable）会被拒；
 * - **闭锁**：任一 role 解析失败只是不装对应 hook，不影响其它 role，也绝不改变既有默认路径。
 */

/** super 约束 */
sealed interface SuperRule {
    /** 不做 super 约束（仅用于"声明了某些方法"即为目标的 role） */
    data object Any : SuperRule

    /** 直接父类等于 [fqcn] */
    data class Exactly(val fqcn: String) : SuperRule

    /** super 链上出现 [fqcn]（可跨中间层，如 OS4 的 `e → com.miui.dock.base.b → View`） */
    data class ChainReaches(val fqcn: String) : SuperRule
}

/**
 * L1 校验所需的最小事实。Android 侧由反射实现（`Class.forName(name, false, cl)` +
 * `getDeclaredMethods` + `getSuperclass`，不触发静态初始化）；JVM 单测直接合成。
 */
interface FactsSource {
    fun exists(fqcn: String): Boolean

    /** 该类**自身声明**的方法签名集合（与 [MethodSpec.signature] 同格式） */
    fun declaredSignatures(fqcn: String): Set<String>

    /** 父类点分名；`java.lang.Object` / 不可达时为 null */
    fun superName(fqcn: String): String?
}

/** 一个语义角色的解析规格 */
data class RoleSpec(
    val role: String,
    val pkg: String,
    /** 必须由该类**自身声明**的方法（多个=全部满足）；空 = 纯名字/纯结构定位 */
    val ownAll: List<MethodSpec> = emptyList(),
    val superRule: SuperRule = SuperRule.Any,
    /** L0 候选名（点分，按优先级）。可用则为快路径，失效则回落 L2 */
    val hints: List<String> = emptyList(),
)

enum class Layer { HINT, STRUCTURAL, NONE }

enum class ResolveState { RESOLVED, NOT_FOUND, AMBIGUOUS }

/** 解析结果（同时驱动 hook 安装与自检报告） */
data class RoleResolution(
    val role: String,
    val fqcn: String?,
    val layer: Layer,
    val hits: Int,
    val state: ResolveState,
    val note: String = "",
) {
    val isResolved: Boolean get() = state == ResolveState.RESOLVED && fqcn != null

    /** 自检表一行：`role  class  layer  hits  state` */
    fun oneLine(): String {
        val cls = fqcn ?: "-"
        val extra = if (note.isEmpty()) "" else "  $note"
        return "%-22s %-46s %-10s %-4d %s%s".format(role, cls, layer.name, hits, state.name, extra)
    }
}

object AnchorEngine {

    const val OBJECT = "java.lang.Object"

    /** super 链步数上限：防御性兜底（畸形 dex / 循环继承） */
    const val SUPER_CHAIN_CAP = 12

    fun chainReaches(src: FactsSource, fqcn: String, target: String): Boolean {
        var cur = src.superName(fqcn) ?: return false
        var steps = 0
        while (steps < SUPER_CHAIN_CAP) {
            if (cur == target) return true
            if (cur == OBJECT) return false
            cur = src.superName(cur) ?: return false
            steps++
        }
        return false
    }

    /** L1：现场指纹校验（名字命中同样要过这一关） */
    fun verify(src: FactsSource, spec: RoleSpec, fqcn: String): Boolean {
        if (!src.exists(fqcn)) return false
        if (spec.ownAll.isNotEmpty()) {
            val sigs = src.declaredSignatures(fqcn)
            if (spec.ownAll.any { it.signature !in sigs }) return false
        }
        return when (val rule = spec.superRule) {
            is SuperRule.Any -> true
            is SuperRule.Exactly -> src.superName(fqcn) == rule.fqcn
            is SuperRule.ChainReaches -> chainReaches(src, fqcn, rule.fqcn)
        }
    }

    /**
     * L0(hints) → L2(index) 编排；顺序刻意是"候选名优先"，
     * 因为 L0 命中意味着"与现状一致"，而 L2 只应在漂移时出手。
     */
    fun resolve(src: FactsSource, index: DexIndex?, spec: RoleSpec): RoleResolution {
        for (hint in spec.hints) {
            if (verify(src, spec, hint)) {
                return RoleResolution(spec.role, hint, Layer.HINT, 1, ResolveState.RESOLVED)
            }
        }
        if (index == null) {
            return RoleResolution(
                spec.role, null, Layer.NONE, spec.hints.size, ResolveState.NOT_FOUND,
                "L2 unavailable; ${spec.hints.size} hint(s) rejected"
            )
        }
        if (spec.ownAll.isEmpty()) {
            return RoleResolution(
                spec.role, null, Layer.NONE, spec.hints.size, ResolveState.NOT_FOUND,
                "no fingerprint to search structurally"
            )
        }
        val cands = index.findClasses(spec.pkg, spec.ownAll.first())
        val verified = cands.filter { verify(src, spec, it) }
        return when (verified.size) {
            1 -> RoleResolution(spec.role, verified[0], Layer.STRUCTURAL, cands.size, ResolveState.RESOLVED)
            0 -> RoleResolution(
                spec.role, null, Layer.STRUCTURAL, cands.size, ResolveState.NOT_FOUND,
                "structure: ${cands.size} candidate(s), none passed fingerprint"
            )
            else -> RoleResolution(
                spec.role, null, Layer.STRUCTURAL, verified.size, ResolveState.AMBIGUOUS,
                "structure: ambiguous -> ${verified.joinToString(",")}"
            )
        }
    }
}

/**
 * 角色表。指纹均经三版本离线核对（`tools/resolve_anchors.py` / `current_anchor_drift.py`），
 * 例如 `onTouch(View,MotionEvent):Z` 在同一包内恰好命中 2 个类，靠 super 约束区分：
 * OS2/OS3 = `f`(Object) + `b`(View 链)；OS4 = `k`(Object) + `e`(经 `com.miui.dock.base.b`)。
 */
object AnchorRoles {

    private const val SIDEBAR = "com.miui.dock.sidebar"
    private const val NEWBOX = "com.miui.gamebooster.windowmanager.newbox"

    private val ON_TOUCH = MethodSpec.of(
        "onTouch", listOf("android.view.View", "android.view.MotionEvent"), "boolean"
    )
    private val DRAW = MethodSpec.of("draw", listOf("android.graphics.Canvas"), "void")
    private val DISPATCH_TOUCH = MethodSpec.of(
        "dispatchTouchEvent", listOf("android.view.MotionEvent"), "boolean"
    )
    private val SET_VISIBILITY = MethodSpec.of("setVisibility", listOf("int"), "void")

    /** 侧边栏触摸监听：监听器语义 ⇒ super 必是 Object（View 有 onTouch 会另算一类） */
    val SIDEBAR_TOUCH = RoleSpec(
        "sidebar_touch", SIDEBAR, listOf(ON_TOUCH),
        SuperRule.Exactly(AnchorEngine.OBJECT), listOf("$SIDEBAR.f")
    )

    /** 覆盖层：自身声明 onTouch 且是 View（OS4 中间隔了 `com.miui.dock.base.b`） */
    val SIDEBAR_COVER = RoleSpec(
        "sidebar_cover", SIDEBAR, listOf(ON_TOUCH),
        SuperRule.ChainReaches("android.view.View"), listOf("$SIDEBAR.b")
    )

    /** 小白条可见像素的唯一出口 */
    val SIDEBAR_DRAWABLE = RoleSpec(
        "sidebar_drawable", SIDEBAR, listOf(DRAW),
        SuperRule.Exactly("android.graphics.drawable.Drawable"), listOf("$SIDEBAR.c")
    )

    /**
     * 把手条。原名 `RegionSamplingImageView` 三版本都稳定，但**不靠名字**：
     * 实测 `dispatchTouchEvent(MotionEvent):Z` 在该包内**唯一**命中它（OS2/OS3/OS4 一致），
     * 且 super 链始终达 ImageView（OS4 变为经 `com.miui.dock.base.a`）。
     */
    val SIDEBAR_HANDLE_BAR = RoleSpec(
        "sidebar_handle_bar", SIDEBAR, listOf(DISPATCH_TOUCH),
        SuperRule.ChainReaches("android.widget.ImageView"),
        listOf("$SIDEBAR.RegionSamplingImageView")
    )

    /** 提示清理（M1/N1）：OS2/OS3 该类不存在、OS4 语义已变 ⇒ 预期 NOT_FOUND（=现状，死代码） */
    val SIDEBAR_HINT_CLEANUP = RoleSpec(
        "sidebar_hint_cleanup", SIDEBAR,
        listOf(MethodSpec.of("M1"), MethodSpec.of("N1")), SuperRule.Any, emptyList()
    )

    /**
     * 工具箱面板可见性。当前硬编码候选（`newbox.e`/`newbox.d`）实测在三个版本上**全部不满足**
     * 指纹（`e` 是 RecyclerView 子类、`d` 是 Runnable），所以这个 hook 至今从未装成功过；
     * 结构化目标唯一命中 `GameToolboxMainView`。**但 base 轮只解析不安装**（语义待复核，见计划 §5）。
     */
    val DOCK_LAYOUT = RoleSpec(
        "dock_layout", NEWBOX, listOf(SET_VISIBILITY),
        SuperRule.ChainReaches("android.view.ViewGroup"),
        listOf("$NEWBOX.e", "$NEWBOX.d")
    )

    /** 需要解析的全部 role（`qs_host_adapter` 走另一套契约校验，见 SystemUiHook） */
    val ALL: List<RoleSpec> = listOf(
        SIDEBAR_TOUCH, SIDEBAR_COVER, SIDEBAR_DRAWABLE, SIDEBAR_HANDLE_BAR,
        SIDEBAR_HINT_CLEANUP, DOCK_LAYOUT
    )
}