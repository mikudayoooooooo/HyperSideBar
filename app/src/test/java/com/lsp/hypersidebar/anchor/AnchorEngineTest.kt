package com.lsp.hypersidebar.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则引擎的 JVM 单测（无需设备 / 无需 ROM）。
 *
 * 用合成事实复刻三版本的真实形态，重点覆盖两个真实事故面：
 * 1. OS4 上"名字还在但语义变了"—— 候选名 `f` 从触摸监听变成了 Drawable，**必须被拒**；
 * 2. super 链跨中间层 —— OS4 的 cover 是 `e → com.miui.dock.base.b → android.view.View`。
 */
class AnchorEngineTest {

    private class FakeFacts(
        private val parents: Map<String, String?>,
        private val methods: Map<String, Set<String>> = emptyMap(),
    ) : FactsSource {
        override fun exists(fqcn: String) = parents.containsKey(fqcn)
        override fun declaredSignatures(fqcn: String) = methods[fqcn] ?: emptySet()
        override fun superName(fqcn: String) = parents[fqcn]
    }

    private class FakeIndex(private val byKey: Map<String, List<String>>) : DexIndex {
        override val stateLine = "fake"
        override fun findClasses(pkg: String, own: MethodSpec) =
            byKey["$pkg|${own.signature}"] ?: emptyList()
    }

    private val onTouch = MethodSpec.of(
        "onTouch", listOf("android.view.View", "android.view.MotionEvent"), "boolean"
    ).signature

    private val draw = MethodSpec.of("draw", listOf("android.graphics.Canvas"), "void").signature

    private val dispatch = MethodSpec.of(
        "dispatchTouchEvent", listOf("android.view.MotionEvent"), "boolean"
    ).signature

    // ===== L0：现名命中 =====

    @Test
    fun `OS2-OS3 shape resolves both sidebar roles from hints`() {
        // f = 监听器（Object + onTouch），b = cover（View + onTouch）
        val src = FakeFacts(
            parents = mapOf(
                "com.miui.dock.sidebar.f" to "java.lang.Object",
                "com.miui.dock.sidebar.b" to "android.view.View",
            ),
            methods = mapOf(
                "com.miui.dock.sidebar.f" to setOf(onTouch),
                "com.miui.dock.sidebar.b" to setOf(onTouch),
            ),
        )
        val touch = AnchorEngine.resolve(src, null, AnchorRoles.SIDEBAR_TOUCH)
        val cover = AnchorEngine.resolve(src, null, AnchorRoles.SIDEBAR_COVER)

        assertTrue(touch.isResolved)
        assertEquals("com.miui.dock.sidebar.f", touch.fqcn)
        assertEquals(Layer.HINT, touch.layer)
        assertTrue(cover.isResolved)
        assertEquals("com.miui.dock.sidebar.b", cover.fqcn)
    }

    // ===== L1：名字撞车必须被拒（OS4 真实形态）=====

    @Test
    fun `hint with changed semantics is rejected and falls back to structure`() {
        // OS4：f 变成 Drawable（不再是监听器），k 才是监听器
        val src = FakeFacts(
            parents = mapOf(
                "com.miui.dock.sidebar.f" to "android.graphics.drawable.Drawable",
                "com.miui.dock.sidebar.k" to "java.lang.Object",
            ),
            methods = mapOf(
                "com.miui.dock.sidebar.f" to setOf(draw),
                "com.miui.dock.sidebar.k" to setOf(onTouch),
            ),
        )
        val index = FakeIndex(
            mapOf(
                "com.miui.dock.sidebar|$onTouch" to
                    listOf("com.miui.dock.sidebar.f", "com.miui.dock.sidebar.k")
            )
        )
        val r = AnchorEngine.resolve(src, index, AnchorRoles.SIDEBAR_TOUCH)

        assertTrue("should resolve structurally to k", r.isResolved)
        assertEquals("com.miui.dock.sidebar.k", r.fqcn)
        assertEquals(Layer.STRUCTURAL, r.layer)
    }

    @Test
    fun `cover role matches through an intermediate super class`() {
        // OS4 真实形态：e 是 cover（经 com.miui.dock.base.b 达 View），k 是监听器（Object）
        val src = FakeFacts(
            parents = mapOf(
                "com.miui.dock.sidebar.e" to "com.miui.dock.base.b",
                "com.miui.dock.base.b" to "android.view.View",
                "com.miui.dock.sidebar.k" to "java.lang.Object",
            ),
            methods = mapOf(
                "com.miui.dock.sidebar.e" to setOf(onTouch),
                "com.miui.dock.sidebar.k" to setOf(onTouch),
            ),
        )
        val index = FakeIndex(
            mapOf(
                "com.miui.dock.sidebar|$onTouch" to
                    listOf("com.miui.dock.sidebar.e", "com.miui.dock.sidebar.k")
            )
        )
        val r = AnchorEngine.resolve(src, index, AnchorRoles.SIDEBAR_COVER)
        assertTrue(r.isResolved)
        assertEquals("com.miui.dock.sidebar.e", r.fqcn)
        assertEquals(Layer.STRUCTURAL, r.layer)
    }

    @Test
    fun `listener role does not accept a View subclass`() {
        // 监听器要求 super==Object；把 View 子类放在 hints 位置必须被拒
        val src = FakeFacts(
            parents = mapOf("com.miui.dock.sidebar.f" to "android.view.View"),
            methods = mapOf("com.miui.dock.sidebar.f" to setOf(onTouch)),
        )
        val r = AnchorEngine.resolve(src, null, AnchorRoles.SIDEBAR_TOUCH)
        assertFalse(r.isResolved)
        assertEquals(ResolveState.NOT_FOUND, r.state)
    }

    // ===== 唯一性 / 闭锁 =====

    @Test
    fun `ambiguous structural result is rejected`() {
        val src = FakeFacts(
            parents = mapOf(
                "com.miui.dock.sidebar.f" to "android.graphics.drawable.Drawable",
                "com.miui.dock.sidebar.x1" to "java.lang.Object",
                "com.miui.dock.sidebar.x2" to "java.lang.Object",
            ),
            methods = mapOf(
                "com.miui.dock.sidebar.x1" to setOf(onTouch),
                "com.miui.dock.sidebar.x2" to setOf(onTouch),
            ),
        )
        val index = FakeIndex(
            mapOf(
                "com.miui.dock.sidebar|$onTouch" to
                    listOf("com.miui.dock.sidebar.x1", "com.miui.dock.sidebar.x2")
            )
        )
        val r = AnchorEngine.resolve(src, index, AnchorRoles.SIDEBAR_TOUCH)
        assertFalse(r.isResolved)
        assertEquals(ResolveState.AMBIGUOUS, r.state)
        assertNull(r.fqcn)
    }

    @Test
    fun `no index means hints only, and failure stays closed`() {
        val src = FakeFacts(parents = mapOf("com.miui.dock.sidebar.f" to "java.lang.Object"))
        val r = AnchorEngine.resolve(src, null, AnchorRoles.SIDEBAR_TOUCH)
        assertFalse(r.isResolved)
        assertEquals(Layer.NONE, r.layer)
    }

    @Test
    fun `handle bar fingerprint works without relying on its name`() {
        // 名字换掉了，但仍声明 dispatchTouchEvent 且 super 链达 ImageView ⇒ 仍能定位
        val src = FakeFacts(
            parents = mapOf(
                "com.miui.dock.sidebar.zzz" to "com.miui.dock.base.a",
                "com.miui.dock.base.a" to "android.widget.ImageView",
            ),
            methods = mapOf("com.miui.dock.sidebar.zzz" to setOf(dispatch)),
        )
        val index = FakeIndex(
            mapOf("com.miui.dock.sidebar|$dispatch" to listOf("com.miui.dock.sidebar.zzz"))
        )
        val r = AnchorEngine.resolve(src, index, AnchorRoles.SIDEBAR_HANDLE_BAR)
        assertTrue(r.isResolved)
        assertEquals("com.miui.dock.sidebar.zzz", r.fqcn)
        assertEquals(Layer.STRUCTURAL, r.layer)
    }

    @Test
    fun `super chain walk terminates on a cyclic hierarchy`() {
        val src = FakeFacts(
            parents = mapOf(
                "com.a.A" to "com.a.B",
                "com.a.B" to "com.a.A",
            )
        )
        assertFalse(AnchorEngine.chainReaches(src, "com.a.A", "android.view.View"))
    }

    @Test
    fun `dock layout resolves to the structural target and keeps hint priority`() {
        val sig = MethodSpec.of("setVisibility", listOf("int"), "void").signature
        val src = FakeFacts(
            parents = mapOf(
                // 现状候选 e：RecyclerView 子类但不声明 setVisibility ⇒ 被拒
                "com.miui.gamebooster.windowmanager.newbox.e" to
                    "androidx.recyclerview.widget.RecyclerView",
                "com.miui.gamebooster.windowmanager.newbox.GameToolboxMainView" to
                    "androidx.constraintlayout.widget.ConstraintLayout",
                // 祖先链（真实反射里由宿主类层次提供；这里手写，供 ChainReaches 走链）
                "androidx.constraintlayout.widget.ConstraintLayout" to "android.view.ViewGroup",
                "android.view.ViewGroup" to "android.view.View",
                "android.view.View" to "java.lang.Object",
            ),
            methods = mapOf(
                "com.miui.gamebooster.windowmanager.newbox.GameToolboxMainView" to setOf(sig),
            ),
        )
        val index = FakeIndex(
            mapOf(
                "com.miui.gamebooster.windowmanager.newbox|$sig" to
                    listOf("com.miui.gamebooster.windowmanager.newbox.GameToolboxMainView")
            )
        )
        val r = AnchorEngine.resolve(src, index, AnchorRoles.DOCK_LAYOUT)
        assertTrue(r.isResolved)
        assertEquals("com.miui.gamebooster.windowmanager.newbox.GameToolboxMainView", r.fqcn)
        assertEquals(Layer.STRUCTURAL, r.layer)
    }
}