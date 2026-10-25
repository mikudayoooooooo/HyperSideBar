package com.lsp.hypersidebar.hook

import com.lsp.hypersidebar.util.FreeformLauncher
import com.lsp.hypersidebar.util.HLog

/**
 * 呼出扇形时的前台应用捕获（条件式推荐的 source 来源，计划 §A3 三轮定案）。
 *
 * 时机 = 手势识别成立、`fanController.show` 之前的 4 个 postShow* 汇入点各一次；
 * 手段 = 反射 `miui.process.ProcessManager.getForegroundInfo()`（静态、binder、
 * 异常安全返回 null；ROM 自家 :ui 同款用法先例 C8147b.m27985c）。
 *
 * **字段口径（三代反编译实证，见 plan §0）：只取 `mForegroundPackageName`**——
 * freeform（windowingMode 5）的 resume 被 ATMS 桩整体跳过，小窗应用永远不会成为
 * 该字段（恒=背后全屏应用）；`mMultiWindowForegroundPackageName` 是非 freeform
 * resume 时刻的陈旧快照且全 ROM 零消费方，不可用。「从小窗 B 内呼出记到背后 A」
 * 为 v1 已知不精确（与 ROM 自家口径一致），已明文备案。
 *
 * 权限（三代实证）：服务端门 `checkPermission || checkSystemSignature || 白名单`，
 * `checkPermission` 对 uid<10000 恒真（:ui=1000 直过）、launcher=priv-app 系统应用
 * 过 `isSystemApp`；shell(2000) 显式拒。异常/失败/宿主自身 ⇒ null（该次 boost=0，
 * 按设计降级，绝不抛）。
 */
object ForegroundSourceProbe {

    private const val TAG = "FgSource"

    /** 呼出一次调一个 binder（~1-2ms），非热路径红线明文例外（plan §7）。 */
    fun capture(selfPackage: String?): String? {
        val info = runCatching {
            val pm = Class.forName("miui.process.ProcessManager")
            val m = pm.getMethod("getForegroundInfo")
            m.invoke(null)
        }.getOrNull() ?: return null
        // 字段读取同样兜死：ForegroundInfo 三代同构（public String mForegroundPackageName）
        val pkg = runCatching {
            info.javaClass.getField("mForegroundPackageName").get(info) as? String
        }.getOrNull()
        // 过滤宿主自身 + 模块自身（在模块设置页里呼出扇形测试是常态，模块前台不记——
        // 真机日志实证 fg=io.github.mikudayoooooooo.hypersidebar 未被滤）
        val normalized = pkg?.takeIf {
            it.isNotBlank() && it != selfPackage && it != FreeformLauncher.MODULE_PACKAGE
        }
        HLog.i(TAG, "capture: fg=${normalized ?: pkg ?: "null"} src=${normalized ?: "-"}")
        return normalized
    }
}
