package com.lsp.hypersidebar.util

import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.UserHandle
import android.util.Log
import com.lsp.hypersidebar.BuildConfig
import com.lsp.hypersidebar.util.HLog

private const val TAG = "FreeformLauncher"

object FreeformLauncher {

    /** 模块自身包名：Intent(context, Class) 会把 component 包名绑到宿主进程包（:ui 里
     *  =com.miui.securitycenter，实测系统 resolve "does not exist" result -92 且
     *  startActivityAsUser 静默"成功"）——activity 类随模块代码加载进宿主，但 manifest
     *  注册在模块包，跨进程拉起必须显式用模块包名。
     *
     *  迭代五批次 0：必须等于 app/build.gradle.kts 的 applicationId（APK 身份），
     *  与源码 namespace（com.lsp.hypersidebar）解耦——0912 起直接取 BuildConfig
     *  （编译期常量内联），gradle 改包名此处自动跟随，不再依赖注释约定手工同步。 */
    const val MODULE_PACKAGE = BuildConfig.APPLICATION_ID
    /** 源码命名空间（类路径前缀）：与 applicationId 解耦，跨进程寻址"包名 + 类路径"时拼接用。 */
    const val MODULE_CLASS_NAMESPACE = "com.lsp.hypersidebar"

    /** 反射靶点（本类与 DataLoader 共用；ROM 漂移时按反编译同步这里） */
    const val MIUI_MULTI_WINDOW_UTILS = "android.util.MiuiMultiWindowUtils"
    const val MIUI_MULTI_WINDOW_ADAPTER = "android.util.MiuiMultiWindowAdapter"
    const val START_ACTIVITY_AS_USER = "startActivityAsUser"

    /**
     * 小窗启动（三路宿主唯一漏斗：launcher 扇形广播→:ui 中继、:ui 扇形直调、AllApps
     * 面板中继——grep 证实唯一调用方 DirectLaunchStrategy）。
     * @param source 呼出扇形时冻结的前台应用（C3 上下文记录；null=未知/桌面/launcher，
     *               该次不记）。记录口径 = "走到 tryMiuiMultiWindow 且 options 非空 +
     *               invoke 未抛"（与 ROM saveStartFreeform 同源；startActivityAsUser
     *               静默假成功无法再深检）。
     */
    fun launch(context: Context, packageName: String, source: String? = null) {
        val clsName = getMainActivity(context, packageName)
        if (clsName == null) {
            HLog.w(TAG, "launch: cannot resolve main activity for $packageName")
            toastOnMain(context, "无法打开 $packageName：找不到入口 Activity")
            return
        }

        // 唯一路径：MiuiMultiWindowUtils.getActivityOptions + startActivityAsUser（实测验证）。
        // com.miui.freeform.FreeformUtil 在任何进程都 ClassNotFound——旧"优先路径"从未生效，
        // 只贡献每次启动一条异常栈噪音，已移除
        tryMiuiMultiWindow(context, packageName, clsName, source)
    }

    /**
     * 以小窗打开本模块的 Activity（全部应用面板，PRD"实质为一个以 freeform 小窗形式
     * 打开的 activity"）。getActivityOptions 返回 null 或反射失败时降级普通全屏启动，
     * 功能不中断。configure = 启动前对 intent 的附加配置（如携带面板数据 extras）。
     *
     * **资格查询按两个候选依次尝试**（2026-09-26 实机实证，两代 ROM 表现不同）：
     *
     * | ROM | `getActivityOptions(hostPkg)` | `getActivityOptions(modulePkg)` |
     * |---|---|---|
     * | OS3.318 | ✅ 非 null → 小窗 | （未走到） |
     * | **OS2.0.215** | ❌ **null** → 全屏 | ✅ 非 null |
     *
     * 调用方在 `:ui` 进程里，`context.packageName` = 宿主包（`com.miui.securitycenter`），
     * 而被拉起的 Activity 属于 [MODULE_PACKAGE]。MIUI 的小窗资格是**按包判定**的，
     * 且**安全中心的资格随 ROM/安全中心版本变化** —— OS2 上它没资格，于是旧实现
     * 必然降级全屏（实机日志 `own pkg freeform options null (B4: no eligibility?)`）。
     *
     * 处置：**宿主包优先**（=OS3 既有行为，零回归），失败再试模块包（=OS2 修复）。
     * 两者都查不到才降级全屏。同一次会话内的对照实验可佐证：同一 context 下查模块包
     * 成功（`MiuiMultiWindow fallback success`）而查宿主包失败。
     */
    fun launchSelfFreeform(context: Context, activity: Class<*>, configure: ((Intent) -> Unit)? = null) {
        // 显式模块包名（勿用 Intent(context, Class)——宿主进程包名错误，见 MODULE_PACKAGE 注释）
        val intent = Intent().apply {
            setClassName(MODULE_PACKAGE, activity.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            configure?.invoke(this)
        }
        try {
            val cls = Class.forName(MIUI_MULTI_WINDOW_UTILS)
            // 先按 ROM 的资格名单筛一遍（2026-10-17）：名单里没有的包直接跳过，省掉一次必然失败的
            // `getActivityOptions`；名单取不到（隐藏 API 被挡）⇒ isEligible 返回 null ⇒ 行为与旧版
            // 完全一致（按"宿主 → 模块"顺序全试）。
            var eligiblePkg = context.packageName
            var options: android.app.ActivityOptions? = null
            for (pkg in listOf(context.packageName, MODULE_PACKAGE).distinct()) {
                if (FreeformEligibility.isEligible(context, pkg) == false) {
                    HLog.i(TAG, "freeform eligibility list excludes $pkg → skip")
                    continue
                }
                options = getActivityOptions(cls, context, pkg)
                if (options != null) {
                    eligiblePkg = pkg
                    if (pkg != context.packageName) {
                        HLog.i(TAG, "own pkg ineligible, module pkg eligible → freeform via $MODULE_PACKAGE")
                    }
                    break
                }
            }
            if (options != null) {
                val method = context.javaClass.getMethod(
                    START_ACTIVITY_AS_USER,
                    Intent::class.java, android.os.Bundle::class.java, UserHandle::class.java
                )
                method.invoke(context, intent, options.toBundle(), Process.myUserHandle())
                HLog.i(TAG, "own activity freeform ok: ${activity.simpleName} (eligibility=$eligiblePkg)")
                return
            }
            HLog.w(
                TAG,
                "own pkg freeform options null for both ${context.packageName} and $MODULE_PACKAGE " +
                    "(B4: no eligibility?) → plain launch"
            )
        } catch (e: Throwable) {
            HLog.w(TAG, "own activity freeform failed: ${e.message} → plain launch")
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                HLog.e(TAG, "own activity plain launch failed: ${it.message}")
                toastOnMain(context, "面板启动失败：${it.message}")
            }
    }

    /**
     * MiuiMultiWindowUtils.getActivityOptions + startActivityAsUser（实测可用路径）。
     * 失败路径 toast 兜底（PRD §9.4）：options 为 null = 小窗资格变化（getActivityOptions
     * 对无资格包返回 null）；注意 startActivityAsUser 对无效组件静默"成功"（-92），
     * 那条失败路径无法在此检测，只能靠数据源（准入列表）过滤。
     */
    private fun tryMiuiMultiWindow(context: Context, packageName: String, clsName: String, source: String?) {
        try {
            val cls = Class.forName(MIUI_MULTI_WINDOW_UTILS)
            val options = getActivityOptions(cls, context, packageName) ?: run {
                HLog.w(TAG, "MiuiMultiWindow: getActivityOptions returned null")
                // PRD §9.4 字面措辞（展示与启动之间资格变化的兜底）
                toastOnMain(context, "该应用不支持小窗")
                return
            }
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setClassName(packageName, clsName)
                // 只带 NEW_TASK 不带 MULTIPLE_TASK（0909 用户报告：对已全屏运行的应用
                // 小窗化时"先弹小窗再关原应用"两段式观感）。MULTIPLE_TASK 强制开新任务
                // 实例——MIUI 随后清理原全屏任务=两段式闪变；去掉后 NEW_TASK 命中既有
                // 任务，AMS 携小窗 options 把任务整体移入 freeform（单次过渡，原生侧边栏
                // 同语义）。未运行的应用照常全新拉起；已在本模块小窗的应用=把该窗带到
                // 前台。AllApps 面板（launchSelfFreeform）本就只带 NEW_TASK，不受影响
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val method = context.javaClass.getMethod(
                START_ACTIVITY_AS_USER,
                Intent::class.java, android.os.Bundle::class.java, UserHandle::class.java
            )
            method.invoke(context, intent, options.toBundle(), Process.myUserHandle())
            HLog.i(TAG, "MiuiMultiWindow fallback success: $packageName/$clsName")
            // 上下文记录（C3，计划 §A3）：getActivityOptions 非 null 且 invoke 未抛=成功口径
            // （ROM saveStartFreeform 同源）。launcher/桌面来源在 C4 已归一为 null；这里的
            // 双保险再滤一次模块自身。失败不阻塞启动（runCatching 内静默）。
            if (source != null && source != MODULE_PACKAGE) {
                val recorded = ContextLaunchLog.record(
                    context.contentResolver, source, packageName
                )
                if (recorded) {
                    // 写读同进程（:ui）零延迟同步原料；launcher 侧靠 DataLoader 周期刷新
                    DataLoader.updateContextCounts(
                        ContextLaunchLog.countsAll(ContextLaunchLog.read(context.contentResolver))
                    )
                }
                HLog.i(TAG, "ctx recorded: $source->$packageName ok=$recorded")
            }
        } catch (e: Exception) {
            HLog.e(TAG, "MiuiMultiWindow fallback failed: ${e.message}")
            toastOnMain(context, "小窗启动失败：$packageName")
        }
    }

    private fun getActivityOptions(
        cls: Class<*>, context: Context, pkg: String
    ): android.app.ActivityOptions? {
        try {
            val m = cls.getMethod(
                "getActivityOptions",
                Context::class.java, String::class.java, Boolean::class.javaPrimitiveType
            )
            return m.invoke(null, context, pkg, true) as? android.app.ActivityOptions
        } catch (_: NoSuchMethodException) { }
        try {
            val m = cls.getMethod(
                "getActivityOptions",
                Context::class.java, String::class.java
            )
            return m.invoke(null, context, pkg) as? android.app.ActivityOptions
        } catch (_: NoSuchMethodException) { }
        return null
    }

    private fun getMainActivity(context: Context, packageName: String): String? {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }
            context.packageManager.resolveActivity(intent, 0)?.activityInfo?.name
        } catch (_: Exception) { null }
    }
}
