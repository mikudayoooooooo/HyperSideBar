package com.lsp.hypersidebar.hook

/**
 * QS 宿主适配器的**候选契约校验**（纯逻辑，可在 JVM 上单测）。
 *
 * 为什么单独抽出来：2026-09-27 实机事故 —— 契约写得太严，把 merlin 上**完全可用**的
 * `qs.QSHostAdapter` 误拒了，导致整条磁贴通道不可用：
 *
 * ```
 * QS host adapter candidate rejected by contract: com.android.systemui.qs.QSHostAdapter
 * QS host adapter candidate rejected by contract: com.android.systemui.qs.QSTileHost
 * QS host adapter not found on any candidate (ROM drift?)
 * ```
 *
 * 旧契约要求"`interactor` 字段的**类型**上存在 `getCurrentQSTiles()`/`getCurrentTilesSpecs()`"，
 * 而 merlin 的 `CurrentTilesInteractorImpl` 在 dex 里**声明方法数为 0**
 * （marble 同类型有 6 个方法）⇒ 该条件在 merlin 上**恒为假**，任何候选都过不了。
 *
 * **修正原则：契约只校验目标类自己声明的东西**，不去要求它依赖的第三方类型具备某具体方法
 * —— 那些类型的形态随 ROM 变化，越权预判只会把可用目标误拒。枚举能力的两档回落
 * 已在 `SystemUiHook.findPinnedTile` 里处理。
 */
internal object QsAdapterContract {

    /** 校验结果：`ok` + 人可读原因（无论成败都写进日志，便于远程判读） */
    data class Verdict(val ok: Boolean, val why: String)

    /**
     * 契约（只需满足这两条）：
     *  ① 声明 `createTile(String)` 且返回类型含 `QSTile`
     *  ② 具备磁贴枚举来源：声明 `interactor` 字段 **或** 自身声明
     *     `getTiles()`/`getSpecs()`/`getTile(String)` 之一
     */
    fun check(cls: Class<*>): Verdict {
        val tile = cls.declaredMethods.firstOrNull { m ->
            m.name == "createTile" && m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == String::class.java
        } ?: return Verdict(false, "no createTile(String)")
        if (!tile.returnType.name.contains("QSTile")) {
            return Verdict(false, "createTile does not return QSTile (${tile.returnType.name})")
        }
        val hasInteractor = cls.declaredFields.any { it.name == "interactor" }
        val selfEnum = cls.declaredMethods.any { m ->
            (m.parameterCount == 0 && (m.name == "getTiles" || m.name == "getSpecs")) ||
                (m.name == "getTile" && m.parameterCount == 1)
        }
        if (!hasInteractor && !selfEnum) {
            return Verdict(false, "neither interactor field nor self enumeration")
        }
        return Verdict(true, "interactor=$hasInteractor selfEnum=$selfEnum")
    }
}
