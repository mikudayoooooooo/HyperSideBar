package com.lsp.hypersidebar.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

/**
 * QS 适配器候选契约的 JVM 单测。
 *
 * 回归防线：2026-09-27 实机事故 —— 契约曾要求"`interactor` 字段的**类型**上存在
 * `getCurrentQSTiles()`/`getCurrentTilesSpecs()`"。merlin 的
 * `CurrentTilesInteractorImpl` 在 dex 里声明方法数为 0（marble 有 6 个），
 * 于是该条件恒假，把**完全可用**的 `qs.QSHostAdapter` 误拒，整条磁贴通道失效。
 *
 * 下面的夹具复刻两个 ROM 的真实形状，确保"interactor 类型为空"也必须通过。
 */
class QsAdapterContractTest {

    // ===== 夹具：复刻真实 ROM 上的形状 =====
    //
    // 注意两个夹具约束（踩过）：
    //  1. 契约要求 createTile 的**返回类型名**含 "QSTile" ⇒ 夹具的 tile 类名必须带上
    //    （真实是 com.android.systemui.plugins.qs.QSTile；这里用 QSTile 子类同理）
    //  2. 字段必须是 **@JvmField / 显式 private 以外** 的可见声明：Kotlin `val` 会编译成
    //     private 字段 + getter，而契约用 declaredFields 查 —— `@JvmField` 才是真字段

    /** 返回类型名必须含 QSTile（契约按类名判定） */
    interface QSTile

    private class FakeTile : QSTile

    /** merlin 形态：interactor 类型**不声明**任何枚举方法（真实是 0 个方法） */
    private class EmptyInteractor

    /** marble 形态：interactor 类型声明 getCurrentTilesSpecs() */
    @Suppress("unused")
    private class InteractorWithSpecs {
        fun getCurrentTilesSpecs(): List<String> = emptyList()
    }

    /** 复刻 QSHostAdapter：createTile(String) + interactor 字段（merlin 用这个形态） */
    @Suppress("unused")
    private class AdapterMerlinLike {
        @JvmField val interactor = EmptyInteractor()
        fun createTile(spec: String): QSTile = FakeTile()
        fun getTiles(): Collection<QSTile> = emptyList()
        fun getSpecs(): List<String> = emptyList()
    }

    /** 复刻 marble 形态：interactor 有枚举方法 */
    @Suppress("unused")
    private class AdapterMarbleLike {
        @JvmField val interactor = InteractorWithSpecs()
        fun createTile(spec: String): QSTile = FakeTile()
    }

    /** 只有 interactor、没有任何自身枚举方法（仍应通过 —— interactor 就是枚举来源） */
    @Suppress("unused")
    private class AdapterInteractorOnly {
        @JvmField val interactor = InteractorWithSpecs()
        fun createTile(spec: String): QSTile = FakeTile()
    }

    /** 无 createTile ⇒ 必须拒 */
    @Suppress("unused")
    private class AdapterWithoutCreateTile {
        @JvmField val interactor = InteractorWithSpecs()
    }

    /** 有 createTile 但既无 interactor 也无自身枚举 ⇒ 必须拒 */
    @Suppress("unused")
    private class AdapterNoEnumerationSource {
        fun createTile(spec: String): QSTile = FakeTile()
    }

    /** createTile 签名不对（无参）⇒ 必须拒 */
    @Suppress("unused")
    private class AdapterBadCreateTile {
        @JvmField val interactor = InteractorWithSpecs()
        fun createTile(): QSTile = FakeTile()
    }

    // ===== 通过用例 =====

    @Test
    fun `merlin-shaped adapter passes even when the interactor type declares nothing`() {
        // 这条就是 09-27 事故的回归防线：旧契约会因为 EmptyInteractor 无枚举方法而误拒
        val v = QsAdapterContract.check(AdapterMerlinLike::class.java)
        assertTrue("merlin 形态必须通过，原因：${v.why}", v.ok)
        assertTrue(v.why.contains("selfEnum=true"))
    }

    @Test
    fun `marble-shaped adapter passes`() {
        val v = QsAdapterContract.check(AdapterMarbleLike::class.java)
        assertTrue("marble 形态必须通过，原因：${v.why}", v.ok)
        assertTrue(v.why.contains("interactor=true"))
    }

    @Test
    fun `adapter with only an interactor field still passes`() {
        // 契约只看"目标类自己声明了什么"，不要求它依赖的类型
        assertTrue(QsAdapterContract.check(AdapterInteractorOnly::class.java).ok)
    }

    // ===== 拒绝用例 =====

    @Test
    fun `adapter without createTile is rejected`() {
        val v = QsAdapterContract.check(AdapterWithoutCreateTile::class.java)
        assertFalse(v.ok)
        assertTrue(v.why.contains("createTile"))
    }

    @Test
    fun `adapter with no enumeration source at all is rejected`() {
        val v = QsAdapterContract.check(AdapterNoEnumerationSource::class.java)
        assertFalse(v.ok)
        assertTrue(v.why.contains("neither interactor"))
    }

    @Test
    fun `adapter whose createTile has the wrong signature is rejected`() {
        val v = QsAdapterContract.check(AdapterBadCreateTile::class.java)
        assertFalse(v.ok)
        assertTrue(v.why.contains("createTile"))
    }

    @Test
    fun `every verdict carries a human readable reason`() {
        // 无论成败都要有原因 —— 远程判读依赖它区分"ROM 漂移"与"契约写错"
        listOf(
            AdapterMerlinLike::class.java, AdapterMarbleLike::class.java,
            AdapterWithoutCreateTile::class.java, AdapterNoEnumerationSource::class.java,
            AdapterBadCreateTile::class.java,
        ).forEach { cls ->
            assertTrue("$cls 的原因不能为空", QsAdapterContract.check(cls).why.isNotBlank())
        }
    }

    @Test
    fun `check never throws on a class with unusual members`() {
        // 防御：任何反射边角都不应让校验抛异常（否则整个候选表会被跳过）
        val v = QsAdapterContract.check(Unit::class.java)
        assertFalse(v.ok)
        assertTrue(v.why.isNotBlank())
    }

    /** 确认夹具的 createTile 返回类型确实是单参 String 版本（防夹具写歪） */
    @Test
    fun `fixture createTile really takes a String`() {
        val m: Method = AdapterMerlinLike::class.java.declaredMethods
            .first { it.name == "createTile" }
        assertTrue(m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java)
    }
}
