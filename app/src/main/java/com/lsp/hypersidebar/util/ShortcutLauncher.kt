package com.lsp.hypersidebar.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.UserHandle
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

private const val TAG = "ShortcutLauncher"

/**
 * 启动结果：结构化成功/失败，记录具体失败原因。
 */
sealed class LaunchResult {
    data class Success(val componentName: ComponentName?) : LaunchResult()
    data class Failure(val reason: FailureReason, val detail: String) : LaunchResult()
}

enum class FailureReason {
    INVALID_CONFIG,       // 配置字段缺失或无效
    APP_NOT_INSTALLED,    // 目标应用未安装
    ACTIVITY_NOT_FOUND,   // 目标 Activity 无法解析
    NOT_EXPORTED,         // 目标 Activity 未导出
    INTENT_PARSE_ERROR,   // Intent URI 解析失败
    SECURITY_EXCEPTION,   // 权限拒绝
    LAUNCH_EXCEPTION,     // 启动时其他异常
    ROOT_UNAVAILABLE,     // su 不可用
    ROOT_EXEC_FAILED      // su 执行失败
}

/**
 * 启动策略抽象。
 * 设置页注入 DefaultLaunchStrategy（普通 startActivity），
 * 运行时注入 SystemLaunchStrategy（startActivityAsUser via reflection）。
 * ROOT 策略作为 fallback 自动探测。
 */
interface LaunchStrategy {
    fun startActivity(context: Context, intent: Intent): Boolean
    val name: String
}

/**
 * 普通启动策略：用于设置页测试（普通 app 进程）。
 */
class DefaultLaunchStrategy : LaunchStrategy {
    override val name = "DEFAULT"

    override fun startActivity(context: Context, intent: Intent): Boolean {
        context.startActivity(intent)
        return true
    }
}

/**
 * 系统进程启动策略：用于运行时（UID 1000, com.miui.securitycenter:ui）。
 * 通过反射调用 startActivityAsUser。
 */
class SystemLaunchStrategy : LaunchStrategy {
    override val name = "SYSTEM"

    override fun startActivity(context: Context, intent: Intent): Boolean {
        return try {
            val userHandle = android.os.Process.myUserHandle()
            // 必须用运行时类（ContextImpl）查找：startActivityAsUser 不声明在抽象 Context 上，
            // 用 Context::class.java 会永远 NoSuchMethodException 然后静默降级
            val method = context.javaClass.getMethod(
                "startActivityAsUser", Intent::class.java, UserHandle::class.java
            )
            method.isAccessible = true
            method.invoke(context, intent, userHandle)
            Log.d(TAG, "startActivityAsUser invoked via ${context.javaClass.name}")
            true
        } catch (e: NoSuchMethodException) {
            Log.d(TAG, "startActivityAsUser not found, fallback to startActivity")
            context.startActivity(intent)
            true
        }
    }
}

/**
 * 统一快捷方式启动器。
 * 设置页测试和运行时 fan menu 触发共用同一条路径。
 *
 * 流程：
 * 1. 根据 ShortcutAction 构造 Intent
 * 2. 强制 FLAG_ACTIVITY_NEW_TASK
 * 3. 解析目标 Activity
 * 4. 验证：应用存在、Activity 可解析、exported
 * 5. 使用注入的 LaunchStrategy 启动
 * 6. 失败时自动尝试 ROOT fallback（如果可用）
 */
object ShortcutLauncher {

    private val rootAvailable = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
    private var lastRootCheckTime = 0L
    private val ROOT_CACHE_TIMEOUT_MS = 30_000L
    private val PKG_ACTIVITY_REGEX = Regex("^([a-zA-Z_][a-zA-Z0-9_]*(?:[.][a-zA-Z_][a-zA-Z0-9_]*)*|[.][a-zA-Z_][a-zA-Z0-9_.]*)$")

    /**
     * 检测 su 是否可用（带超时缓存）。Thread-safe.
     */
    @Synchronized
    fun isRootAvailable(): Boolean {
        val cached = rootAvailable.get()
        val now = System.currentTimeMillis()
        if (cached != null && now - lastRootCheckTime < ROOT_CACHE_TIMEOUT_MS) {
            return cached
        }

        val available = try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "--version"))
            val result = process.waitFor() == 0
            process.destroy()
            result
        } catch (e: Exception) {
            false
        }

        rootAvailable.set(available)
        lastRootCheckTime = now
        Log.d(TAG, "root available: $available")
        return available
    }

    /**
     * 启动快捷方式。统一入口。
     *
     * @param context 当前上下文
     * @param action 快捷方式配置
     * @param strategy 启动策略（设置页用 Default，运行时用 System）
     * @param allowRootFallback 是否允许 ROOT 降级（默认 true）
     * @return LaunchResult
     */
    fun launch(
        context: Context,
        action: ShortcutAction,
        strategy: LaunchStrategy,
        allowRootFallback: Boolean = true
    ): LaunchResult {
        Log.i(TAG, "launch: id=${action.id} kind=${action.kind} strategy=${strategy.name}")

        // TOOLBOX 类型不走这里，由 TurboLayout 直接处理广播
        if (action.kind == ShortcutKind.TOOLBOX) {
            return LaunchResult.Failure(FailureReason.INVALID_CONFIG, "TOOLBOX should be handled by broadcast")
        }

        // SERVICE 有独立的启动路径（startService），不走 Activity 管线
        if (action.kind == ShortcutKind.SERVICE) {
            return launchService(context, action, allowRootFallback)
        }

        // COMPONENT 自动探测类型后分发
        if (action.kind == ShortcutKind.COMPONENT) {
            return launchComponent(context, action, strategy, allowRootFallback)
        }

        // 1. 构造 Intent
        val intent = when (val buildResult = buildIntent(action)) {
            is BuildIntentResult.Success -> buildResult.intent
            is BuildIntentResult.Failure -> return LaunchResult.Failure(buildResult.reason, buildResult.detail)
        }

        // 2. 强制 NEW_TASK
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // 3-4. 解析并验证
        val validation = validateIntent(context, intent, action)
        if (validation is LaunchResult.Failure) {
            // 兜底：validateIntent 对非 exported Activity 可能误判 ACTIVITY_NOT_FOUND，
            // 若 packageName 存在且包名可解析，跳过验证直接尝试启动。
            if (validation.reason == FailureReason.ACTIVITY_NOT_FOUND &&
                !action.packageName.isNullOrEmpty()
            ) {
                Log.w(TAG, "launch: validateIntent refused (${validation.detail}), but package exists, trying direct launch")
                return tryLaunchDirect(context, intent, strategy, action, allowRootFallback)
            }
            // 验证失败，尝试 ROOT fallback（ROOT 可绕过 exported 检查）
            if (allowRootFallback && isRootAvailable() &&
                validation.reason == FailureReason.NOT_EXPORTED) {
                Log.i(TAG, "launch: exported check failed, trying ROOT fallback")
                return launchViaRoot(action)
            }
            return validation
        }

        // 5. 使用策略启动
        return try {
            strategy.startActivity(context, intent)
            val cn = intent.component ?: intent.resolveActivity(context.packageManager)
            Log.i(TAG, "launch: SUCCESS via ${strategy.name}, component=$cn")
            LaunchResult.Success(cn)
        } catch (e: SecurityException) {
            Log.w(TAG, "launch: SecurityException via ${strategy.name}, trying ROOT", e)
            if (allowRootFallback && isRootAvailable()) {
                launchViaRoot(action)
            } else {
                LaunchResult.Failure(FailureReason.SECURITY_EXCEPTION, e.message ?: "Permission denied")
            }
        } catch (e: android.content.ActivityNotFoundException) {
            Log.e(TAG, "launch: ActivityNotFoundException via ${strategy.name}", e)
            LaunchResult.Failure(FailureReason.ACTIVITY_NOT_FOUND, e.message ?: "Activity not found")
        } catch (e: Exception) {
            Log.e(TAG, "launch: exception via ${strategy.name}", e)
            if (allowRootFallback && isRootAvailable()) {
                launchViaRoot(action)
            } else {
                LaunchResult.Failure(FailureReason.LAUNCH_EXCEPTION, e.message ?: "Unknown error")
            }
        }
    }

    /**
     * 仅验证，不实际启动。用于设置页"测试启动"前的预检。
     */
    fun validate(context: Context, action: ShortcutAction): LaunchResult {
        if (action.kind == ShortcutKind.TOOLBOX) {
            return LaunchResult.Failure(FailureReason.INVALID_CONFIG, "TOOLBOX is built-in")
        }

        // SERVICE 用 Service 专用解析，不走 Activity 管线
        if (action.kind == ShortcutKind.SERVICE) {
            return validateService(context, action)
        }

        // COMPONENT 自动探测后分发验证
        if (action.kind == ShortcutKind.COMPONENT) {
            val pkg = action.packageName ?: ""
            val cls = action.activityName ?: ""
            if (pkg.isEmpty() || cls.isEmpty()) {
                return LaunchResult.Failure(FailureReason.INVALID_CONFIG, "packageName or className is empty")
            }
            return when (detectComponentType(context, pkg, cls)) {
                ComponentType.SERVICE -> validateService(context, action.copy(kind = ShortcutKind.SERVICE, serviceName = cls))
                else -> {
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        component = ComponentName(pkg, normalizeActivityName(pkg, cls))
                    }
                    validateIntent(context, intent, action.copy(kind = ShortcutKind.ACTIVITY))
                }
            }
        }

        val intent = when (val buildResult = buildIntent(action)) {
            is BuildIntentResult.Success -> buildResult.intent
            is BuildIntentResult.Failure -> return LaunchResult.Failure(buildResult.reason, buildResult.detail)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return validateIntent(context, intent, action)
    }

    // ─── Internal ────────────────────────────────────────────────────────

    private sealed class BuildIntentResult {
        data class Success(val intent: Intent) : BuildIntentResult()
        data class Failure(val reason: FailureReason, val detail: String) : BuildIntentResult()
    }

    /** 组件类型探测结果 */
    private enum class ComponentType { ACTIVITY, SERVICE, UNKNOWN }

    /**
     * COMPONENT 统一启动路径。
     * 自动探测目标类是 Activity 还是 Service，然后分发到对应路径。
     * COMPONENT 的 className 存储在 action.activityName 字段中（复用）。
     */
    private fun launchComponent(
        context: Context,
        action: ShortcutAction,
        strategy: LaunchStrategy,
        allowRootFallback: Boolean
    ): LaunchResult {
        val pkg = action.packageName
        val cls = action.activityName
        if (pkg.isNullOrEmpty() || cls.isNullOrEmpty()) {
            return LaunchResult.Failure(FailureReason.INVALID_CONFIG, "packageName or className is empty")
        }

        val type = detectComponentType(context, pkg, cls)
        Log.i(TAG, "launchComponent: $pkg/$cls detected as $type")

        return when (type) {
            ComponentType.SERVICE -> launchService(context, action.copy(
                kind = ShortcutKind.SERVICE,
                serviceName = cls
            ), allowRootFallback)
            ComponentType.ACTIVITY, ComponentType.UNKNOWN -> {
                // UNKNOWN 时先尝试 Activity（更常见），失败后 fallback 到 Service
                val activityResult = launchAsActivity(context, action, strategy, allowRootFallback)
                if (activityResult is LaunchResult.Failure && type == ComponentType.UNKNOWN) {
                    Log.i(TAG, "launchComponent: activity path failed, trying service")
                    launchService(context, action.copy(
                        kind = ShortcutKind.SERVICE,
                        serviceName = cls
                    ), allowRootFallback)
                } else {
                    activityResult
                }
            }
        }
    }

    /** 走 Activity 管线启动（供 COMPONENT 复用） */
    private fun launchAsActivity(
        context: Context,
        action: ShortcutAction,
        strategy: LaunchStrategy,
        allowRootFallback: Boolean
    ): LaunchResult {
        val activityAction = action.copy(kind = ShortcutKind.ACTIVITY)
        val intent = when (val r = buildActivityIntent(activityAction)) {
            is BuildIntentResult.Success -> r.intent
            is BuildIntentResult.Failure -> return LaunchResult.Failure(r.reason, r.detail)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val validation = validateIntent(context, intent, activityAction)
        if (validation is LaunchResult.Failure) {
            if (validation.reason == FailureReason.ACTIVITY_NOT_FOUND && !action.packageName.isNullOrEmpty()) {
                return tryLaunchDirect(context, intent, strategy, activityAction, allowRootFallback)
            }
            if (allowRootFallback && isRootAvailable() && validation.reason == FailureReason.NOT_EXPORTED) {
                return launchViaRoot(activityAction)
            }
            return validation
        }

        return try {
            strategy.startActivity(context, intent)
            LaunchResult.Success(intent.component)
        } catch (e: SecurityException) {
            if (allowRootFallback && isRootAvailable()) launchViaRoot(activityAction)
            else LaunchResult.Failure(FailureReason.SECURITY_EXCEPTION, e.message ?: "Permission denied")
        } catch (e: Exception) {
            if (allowRootFallback && isRootAvailable()) launchViaRoot(activityAction)
            else LaunchResult.Failure(FailureReason.LAUNCH_EXCEPTION, e.message ?: "Unknown error")
        }
    }

    /**
     * 探测组件类型：在目标包的已声明组件中查找 className。
     * 优先匹配 Activity（更常见），其次 Service。
     */
    private fun detectComponentType(context: Context, pkg: String, cls: String): ComponentType {
        val pm = context.packageManager
        val normalizedCls = normalizeActivityName(pkg, cls)
        // 展开相对名为绝对名用于匹配
        val fullCls = if (normalizedCls.startsWith(".")) "$pkg$normalizedCls" else normalizedCls

        return try {
            @Suppress("DEPRECATION")
            val pkgInfo = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES)
            val isActivity = pkgInfo.activities?.any { it.name == fullCls || ".$pkg${it.name}" == fullCls } == true
            if (isActivity) return ComponentType.ACTIVITY
            val isService = pkgInfo.services?.any { it.name == fullCls || ".$pkg${it.name}" == fullCls } == true
            if (isService) return ComponentType.SERVICE
            ComponentType.UNKNOWN
        } catch (e: Exception) {
            Log.w(TAG, "detectComponentType: failed for $pkg/$cls: ${e.message}")
            ComponentType.UNKNOWN
        }
    }

    /**
     * SERVICE 专用启动路径。
     * 不走 Activity 管线（resolveActivity / startActivity），改用 startService。
     *
     * 流程：
     * 1. 构造 Service Intent
     * 2. 验证：包已安装、Service 可解析、exported（UID 1000 跳过）
     * 3. context.startService()
     * 4. 失败时 ROOT fallback（am startservice）
     */
    private fun launchService(
        context: Context,
        action: ShortcutAction,
        allowRootFallback: Boolean
    ): LaunchResult {
        val intent = when (val r = buildServiceIntent(action)) {
            is BuildIntentResult.Success -> r.intent
            is BuildIntentResult.Failure -> return LaunchResult.Failure(r.reason, r.detail)
        }

        // 验证
        val validation = validateService(context, action)
        if (validation is LaunchResult.Failure) {
            // 包可见性限制或非 exported 服务可能无法被 resolveService/getServiceInfo 解析，
            // 但 startService 仍可能成功（系统服务、UID 1000 等场景），先盲启动再回退到 ROOT
            if (validation.reason == FailureReason.ACTIVITY_NOT_FOUND &&
                !action.packageName.isNullOrEmpty()
            ) {
                Log.w(TAG, "launchService: validateService refused (${validation.detail}), trying direct launch")
                return tryLaunchServiceDirect(context, intent, action, allowRootFallback)
            }
            // 非 exported 或不可见（包可见性限制）→ ROOT 可绕过
            if (allowRootFallback && isRootAvailable() &&
                (validation.reason == FailureReason.NOT_EXPORTED ||
                 validation.reason == FailureReason.ACTIVITY_NOT_FOUND)
            ) {
                Log.i(TAG, "launchService: validation failed (${validation.reason}), trying ROOT")
                return launchViaRoot(action)
            }
            return validation
        }

        // 启动
        return try {
            context.startService(intent)
            Log.i(TAG, "launchService: SUCCESS via startService, component=${intent.component}")
            LaunchResult.Success(intent.component)
        } catch (e: SecurityException) {
            Log.w(TAG, "launchService: SecurityException, trying ROOT", e)
            if (allowRootFallback && isRootAvailable()) {
                launchViaRoot(action)
            } else {
                LaunchResult.Failure(FailureReason.SECURITY_EXCEPTION, e.message ?: "Permission denied")
            }
        } catch (e: IllegalStateException) {
            // Android 8+ 后台启动限制（从设置页触发时可能出现）
            Log.w(TAG, "launchService: IllegalStateException (background restriction), trying ROOT", e)
            if (allowRootFallback && isRootAvailable()) {
                launchViaRoot(action)
            } else {
                LaunchResult.Failure(FailureReason.LAUNCH_EXCEPTION, e.message ?: "Background service restriction")
            }
        } catch (e: Exception) {
            Log.e(TAG, "launchService: exception", e)
            if (allowRootFallback && isRootAvailable()) {
                launchViaRoot(action)
            } else {
                LaunchResult.Failure(FailureReason.LAUNCH_EXCEPTION, e.message ?: "Unknown error")
            }
        }
    }

    /**
     * SERVICE 专用验证：使用 resolveService / getServiceInfo / GET_SERVICES。
     */
    private fun validateService(context: Context, action: ShortcutAction): LaunchResult {
        val pm = context.packageManager
        val pkg = action.packageName
        val svc = normalizeServiceName(pkg ?: "", action.serviceName ?: "")

        if (pkg.isNullOrEmpty()) {
            return LaunchResult.Failure(FailureReason.INVALID_CONFIG, "packageName is empty")
        }
        if (svc.isEmpty()) {
            return LaunchResult.Failure(FailureReason.INVALID_CONFIG, "serviceName is empty")
        }

        // 检查包已安装
        try {
            pm.getPackageInfo(pkg, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return LaunchResult.Failure(FailureReason.APP_NOT_INSTALLED, "Package not found: $pkg")
        }

        val component = ComponentName(pkg, svc)

        // 解析 Service
        val intent = Intent().apply { this.component = component }
        val resolveInfo = pm.resolveService(intent, 0)

        if (resolveInfo != null) {
            val serviceInfo = resolveInfo.serviceInfo
                ?: return LaunchResult.Failure(FailureReason.ACTIVITY_NOT_FOUND, "Resolved but no serviceInfo")

            // exported 检查（UID 1000 跳过）
            if (!serviceInfo.exported && android.os.Process.myUid() != 1000 /* SYSTEM_UID */) {
                return LaunchResult.Failure(
                    FailureReason.NOT_EXPORTED,
                    "Service not exported: ${serviceInfo.packageName}/${serviceInfo.name}"
                )
            }
            return LaunchResult.Success(ComponentName(serviceInfo.packageName, serviceInfo.name))
        }

        // resolveService 返回 null：可能是非 exported（普通进程不可见）
        // 尝试 getServiceInfo 探测
        try {
            @Suppress("DEPRECATION")
            val si = pm.getServiceInfo(component, 0)
            if (!si.exported && android.os.Process.myUid() != 1000) {
                return LaunchResult.Failure(
                    FailureReason.NOT_EXPORTED,
                    "Service not exported: ${si.packageName}/${si.name}"
                )
            }
            return LaunchResult.Success(component)
        } catch (_: PackageManager.NameNotFoundException) {
            // 再试 GET_SERVICES 遍历
            val found = findServiceInfo(pm, component)
            if (found != null) {
                if (!found.exported && android.os.Process.myUid() != 1000) {
                    return LaunchResult.Failure(
                        FailureReason.NOT_EXPORTED,
                        "Service not exported: ${found.packageName}/${found.name}"
                    )
                }
                return LaunchResult.Success(component)
            }
        } catch (_: SecurityException) {
            val found = findServiceInfo(pm, component)
            if (found != null) return LaunchResult.Success(component)
        }

        return LaunchResult.Failure(
            FailureReason.ACTIVITY_NOT_FOUND,
            "Service not found: ${component.flattenToShortString()}"
        )
    }

    /**
     * 在目标包的所有已声明 Service 中查找 component 对应的 ServiceInfo。
     */
    private fun findServiceInfo(pm: PackageManager, component: ComponentName): android.content.pm.ServiceInfo? {
        val pkgName = component.packageName ?: return null
        // component.className 可能是相对名（如 ".service.X"），展开为绝对名再匹配
        val fullClassName = if (component.className.startsWith("."))
            "$pkgName${component.className}" else component.className
        return try {
            @Suppress("DEPRECATION")
            val pkgInfo = pm.getPackageInfo(pkgName, PackageManager.GET_SERVICES)
            pkgInfo?.services?.find { it.name == fullClassName }
        } catch (e: Exception) {
            Log.w(TAG, "findServiceInfo: cannot query $pkgName services: ${e.message}")
            null
        }
    }

    private fun buildIntent(action: ShortcutAction): BuildIntentResult = when (action.kind) {
        ShortcutKind.COMPONENT, ShortcutKind.ACTIVITY -> buildActivityIntent(action)
        ShortcutKind.INTENT_URI -> buildIntentUri(action)
        ShortcutKind.SERVICE -> buildServiceIntent(action)
        ShortcutKind.TOOLBOX -> BuildIntentResult.Failure(
            FailureReason.INVALID_CONFIG, "TOOLBOX cannot be built as Intent"
        )
    }

    /**
     * 对 activityName 做规范化：
     * - 如果 act 是完整类名且以 packageName 为前缀，则剥离前缀，只保留类名部分（以 . 开头）。
     * - 否则原样返回。
     *
     * 避免 ComponentName(pkg, "com.xxx.Activity") 拼出双重包名导致 Class Not Found。
     */
    private fun normalizeActivityName(pkg: String, act: String): String {
        var cleaned = act.trim { it <= ' ' || it in '\u0000'..'\u001f' || it in '\u007f'..'\u009f' }
        if (cleaned.startsWith(".")) return cleaned  // 已是相对名，无需处理
        if (!cleaned.contains('.')) return cleaned   // 不是类名，原样返回
        if (cleaned.startsWith("$pkg.")) {
            return cleaned.substring(pkg.length)  // 剥离 pkg 前缀，保留以 . 开头的类名
        }
        return cleaned  // act 是其他包的完整类名，无法自动剥离，原样返回让上层判断
    }

    /**
     * 在目标包的所有已声明 Activity 中查找 componentName 对应的 ActivityInfo。
     * 比 getActivityInfo 更宽松：不检查 exported，也不受 QUERY_ALL_PACKAGES 限制。
     * 仅用于 resolveActivity 返回 null 时的 fallback 探测。
     */
    private fun findActivityInfo(pm: PackageManager, component: ComponentName): ActivityInfo? {
        val pkgName = component.packageName ?: return null
        // component.className 可能是相对名，展开为绝对名再匹配
        val fullClassName = if (component.className.startsWith("."))
            "$pkgName${component.className}" else component.className
        return try {
            @Suppress("DEPRECATION")
            val pkgInfo = pm.getPackageInfo(pkgName, PackageManager.GET_ACTIVITIES)
            pkgInfo?.activities?.find { it.name == fullClassName }
        } catch (e: Exception) {
            Log.w(TAG, "findActivityInfo: cannot query $pkgName activities: ${e.message}")
            null
        }
    }

    private fun buildActivityIntent(action: ShortcutAction): BuildIntentResult {
        val pkg = action.packageName
        val act = normalizeActivityName(action.packageName ?: "", action.activityName ?: "")

        if (pkg.isNullOrEmpty()) {
            return BuildIntentResult.Failure(FailureReason.INVALID_CONFIG, "packageName is empty")
        }
        if (act.isNullOrEmpty()) {
            return BuildIntentResult.Failure(FailureReason.INVALID_CONFIG, "activityName is empty")
        }

        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(pkg, act)
        }
        return BuildIntentResult.Success(intent)
    }

    private fun buildServiceIntent(action: ShortcutAction): BuildIntentResult {
        val pkg = action.packageName ?: return BuildIntentResult.Failure(
            FailureReason.INVALID_CONFIG, "packageName is empty")
        val svc = normalizeServiceName(pkg, action.serviceName ?: return BuildIntentResult.Failure(
            FailureReason.INVALID_CONFIG, "serviceName is empty"))

        val intent = Intent().apply {
            component = ComponentName(pkg, svc)
        }
        return BuildIntentResult.Success(intent)
    }

    /**
     * 对 serviceName 做规范化（同 normalizeActivityName）。
     */
    private fun normalizeServiceName(pkg: String, svc: String): String {
        var cleaned = svc.trim { it <= ' ' || it in '\u0000'..'\u001f' || it in '\u007f'..'\u009f' }
        if (cleaned.startsWith(".")) return cleaned
        if (!cleaned.contains('.')) return cleaned
        if (cleaned.startsWith("$pkg.")) {
            return cleaned.substring(pkg.length)
        }
        return cleaned
    }

    private fun buildIntentUri(action: ShortcutAction): BuildIntentResult {
        val uriStr = action.intentUri
        if (uriStr.isNullOrEmpty()) {
            return BuildIntentResult.Failure(FailureReason.INVALID_CONFIG, "intentUri is empty")
        }

        return try {
            // 使用 URI_INTENT_SCHEME 解析（不支持 intent:// 以外的 scheme 时走 VIEW）
            val intent = if (uriStr.startsWith("intent://") || uriStr.startsWith("#Intent")) {
                Intent.parseUri(uriStr, Intent.URI_INTENT_SCHEME)
            } else {
                // 普通 URI (如 weixin://, alipays://) 走 ACTION_VIEW
                Intent(Intent.ACTION_VIEW, Uri.parse(uriStr))
            }

            // 安全清洗：剥离危险字段
            sanitizeIntent(intent)

            // 如果指定了 packageName，限制解析范围
            if (!action.packageName.isNullOrEmpty()) {
                intent.setPackage(action.packageName)
            }

            BuildIntentResult.Success(intent)
        } catch (e: Exception) {
            BuildIntentResult.Failure(
                FailureReason.INTENT_PARSE_ERROR,
                "Failed to parse URI: ${e.message}"
            )
        }
    }

    /**
     * Intent 安全清洗：
     * - 清除 ClipData
     * - 清除 FLAG_GRANT_* 权限位
     * - 剥离 selector
     * - 清除 grants
     */
    private fun sanitizeIntent(intent: Intent) {
        intent.clipData = null
        intent.selector = null

        // 清除所有 extras 防止恶意 payload
        intent.extras?.clear()

        // 清除所有 grant 标志
        intent.flags = intent.flags and
            Intent.FLAG_GRANT_READ_URI_PERMISSION.inv() and
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION.inv() and
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION.inv() and
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION.inv()
    }

    private fun validateIntent(context: Context, intent: Intent, action: ShortcutAction): LaunchResult {
        val pm = context.packageManager

        // 检查目标应用是否安装
        val targetPkg = action.packageName
            ?: intent.`package`
            ?: intent.component?.packageName

        if (targetPkg != null) {
            try {
                pm.getPackageInfo(targetPkg, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                return LaunchResult.Failure(
                    FailureReason.APP_NOT_INSTALLED,
                    "Package not found: $targetPkg"
                )
            }
        }

        // 解析目标 Activity
        val resolveInfo = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?: pm.resolveActivity(intent, 0)

        if (resolveInfo == null) {
            // resolveActivity 对非 exported Activity 返回 null，
            // 回退用 getActivityInfo 探测：如果 Activity 确实存在，放行让 launch() 去试。
            val component = intent.component ?: return LaunchResult.Failure(
                FailureReason.ACTIVITY_NOT_FOUND,
                "No component set and resolveActivity returned null"
            )
            // 第一层：直接 getActivityInfo（对 exported Activity 有效）
            try {
                @Suppress("DEPRECATION")
                pm.getActivityInfo(component, 0)
                Log.d(TAG, "validateIntent: resolveActivity null but getActivityInfo found ${component.flattenToShortString()}, allowing")
                return LaunchResult.Success(component)
            } catch (_: PackageManager.NameNotFoundException) {
                // 第二层：getPackageInfo(GET_ACTIVITIES) 遍历 Activity 列表（比 getActivityInfo 宽松）
                val found = findActivityInfo(pm, component)
                if (found != null) {
                    Log.d(TAG, "validateIntent: getActivityInfo miss but foundActivityInfo hit ${component.flattenToShortString()}, allowing")
                    return LaunchResult.Success(component)
                }
            } catch (_: SecurityException) {
                // 系统 UID 下 SecurityException 可能说明 Activity 存在但不可见，再试 findActivityInfo
                val found = findActivityInfo(pm, component)
                if (found != null) {
                    Log.d(TAG, "validateIntent: getActivityInfo SecurityException but foundActivityInfo hit ${component.flattenToShortString()}, allowing")
                    return LaunchResult.Success(component)
                }
            }
            return LaunchResult.Failure(
                FailureReason.ACTIVITY_NOT_FOUND,
                "No activity resolves and all fallbacks failed: ${component.flattenToShortString()}"
            )
        }

        // 检查 exported
        val activityInfo = resolveInfo.activityInfo ?: return LaunchResult.Failure(
            FailureReason.ACTIVITY_NOT_FOUND,
            "Resolved but no activityInfo"
        )

        // SYSTEM_UID（运行时宿主 securitycenter:ui）可以直接启动非 exported Activity，
        // 无需绕道 ROOT；仅在普通应用进程（如设置页测试启动）才强制 exported 检查
        if (!activityInfo.exported && android.os.Process.myUid() != 1000 /* SYSTEM_UID */) {
            return LaunchResult.Failure(
                FailureReason.NOT_EXPORTED,
                "Activity not exported: ${activityInfo.packageName}/${activityInfo.name}"
            )
        }

        return LaunchResult.Success(
            ComponentName(activityInfo.packageName, activityInfo.name)
        )
    }

    /**
     * ROOT 启动：通过 su -c "am start ..." 绕过 exported 限制。
     */
    private fun launchViaRoot(action: ShortcutAction): LaunchResult {
        val cmdArgs = buildAmCommand(action) ?: return LaunchResult.Failure(
            FailureReason.INVALID_CONFIG,
            "Cannot build am command for kind=${action.kind}"
        )

        // su -c expects a single command string, not individual arguments
        val fullCmd = listOf("su", "-c", cmdArgs.joinToString(" "))
        Log.i(TAG, "launchViaRoot: ${fullCmd.joinToString(" ")}")

        return try {
            val process = ProcessBuilder(fullCmd).start()
            try {
                val exitCode = process.waitFor()

                // 读取 stderr 获取错误信息
                val errorOutput = BufferedReader(InputStreamReader(process.errorStream)).use {
                    it.readText().trim()
                }

                if (exitCode == 0) {
                    Log.i(TAG, "launchViaRoot: SUCCESS")
                    LaunchResult.Success(null)
                } else {
                    Log.w(TAG, "launchViaRoot: exit=$exitCode, error=$errorOutput")
                    if (errorOutput.contains("not exported") || errorOutput.contains("Permission Denial")) {
                        LaunchResult.Failure(FailureReason.NOT_EXPORTED, errorOutput)
                    } else {
                        LaunchResult.Failure(FailureReason.ROOT_EXEC_FAILED, "exit=$exitCode: $errorOutput")
                    }
                }
            } finally {
                process.destroy()
            }
        } catch (e: Exception) {
            Log.e(TAG, "launchViaRoot: exception", e)
            LaunchResult.Failure(FailureReason.ROOT_UNAVAILABLE, e.message ?: "su exec failed")
        }
    }

    /**
     * 兜底启动：跳过 validateIntent 的 exported/not-found 检查，直接 try-catch 启动。
     * 用于 validateIntent 误判非 exported Activity 的场景。
     */
    private fun tryLaunchDirect(
        context: Context,
        intent: Intent,
        strategy: LaunchStrategy,
        action: ShortcutAction,
        allowRootFallback: Boolean
    ): LaunchResult {
        return try {
            strategy.startActivity(context, intent)
            val cn = intent.component ?: intent.resolveActivity(context.packageManager)
            Log.i(TAG, "tryLaunchDirect: SUCCESS via ${strategy.name}, component=$cn")
            LaunchResult.Success(cn)
        } catch (e: SecurityException) {
            Log.w(TAG, "tryLaunchDirect: SecurityException via ${strategy.name}, trying ROOT", e)
            if (allowRootFallback && isRootAvailable()) {
                launchViaRoot(action)
            } else {
                LaunchResult.Failure(FailureReason.SECURITY_EXCEPTION, e.message ?: "Permission denied")
            }
        } catch (e: android.content.ActivityNotFoundException) {
            // Android 对非 exported Activity 在 Instrumentation 层面抛 ANF 而非 SecurityException
            // 预检区分"包存在但 exported=false"（可 ROOT fallback）vs "真不存在"
            val comp = intent.component
            if (comp != null && allowRootFallback) {
                val pkgInstalled = runCatching { context.packageManager.getPackageInfo(comp.packageName, 0) }.isSuccess
                if (pkgInstalled && isRootAvailable()) {
                    Log.w(TAG, "tryLaunchDirect: ActivityNotFoundException but package exists, trying ROOT fallback", e)
                    return launchViaRoot(action)
                }
            }
            Log.e(TAG, "tryLaunchDirect: ActivityNotFoundException via ${strategy.name}", e)
            LaunchResult.Failure(FailureReason.ACTIVITY_NOT_FOUND, e.message ?: "Activity not found")
        } catch (e: Exception) {
            Log.e(TAG, "tryLaunchDirect: exception via ${strategy.name}", e)
            if (allowRootFallback && isRootAvailable()) {
                launchViaRoot(action)
            } else {
                LaunchResult.Failure(FailureReason.LAUNCH_EXCEPTION, e.message ?: "Unknown error")
            }
        }
    }

    /**
      * 兜底启动：跳过 validateService 的解析检查，直接 try-catch 启动 startService。
      * 用于 validateService 因包可见性限制误判 ACTIVITY_NOT_FOUND（实际服务存在且可启动）的场景。
      */
    private fun tryLaunchServiceDirect(
        context: Context,
        intent: Intent,
        action: ShortcutAction,
        allowRootFallback: Boolean
    ): LaunchResult {
        return try {
            context.startService(intent)
            Log.i(TAG, "tryLaunchServiceDirect: SUCCESS via startService, component=${intent.component}")
            LaunchResult.Success(intent.component)
        } catch (e: SecurityException) {
            Log.w(TAG, "tryLaunchServiceDirect: SecurityException, trying ROOT", e)
            if (allowRootFallback && isRootAvailable()) {
                launchViaRoot(action)
            } else {
                LaunchResult.Failure(FailureReason.SECURITY_EXCEPTION, e.message ?: "Permission denied")
            }
        } catch (e: IllegalStateException) {
            // Android 8+ 后台启动限制（从设置页触发时可能出现）
            Log.w(TAG, "tryLaunchServiceDirect: IllegalStateException (background restriction), trying ROOT", e)
            if (allowRootFallback && isRootAvailable()) {
                launchViaRoot(action)
            } else {
                LaunchResult.Failure(FailureReason.LAUNCH_EXCEPTION, e.message ?: "Background service restriction")
            }
        } catch (e: Exception) {
            Log.e(TAG, "tryLaunchServiceDirect: exception", e)
            if (allowRootFallback && isRootAvailable()) {
                launchViaRoot(action)
            } else {
                LaunchResult.Failure(FailureReason.LAUNCH_EXCEPTION, e.message ?: "Unknown error")
            }
        }
    }

    private fun buildAmCommand(action: ShortcutAction): Array<String>? {
        return when (action.kind) {
            ShortcutKind.COMPONENT, ShortcutKind.ACTIVITY -> {
                val pkg = action.packageName ?: return null
                val act = normalizeActivityName(pkg, action.activityName ?: return null)
                // normalizeActivityName 常产出以 "." 开头的相对名（如 ".ui.MainActivity"），
                // PKG_ACTIVITY_REGEX 不接受前导点，先展开为绝对类名再校验
                val fullAct = if (act.startsWith(".")) "$pkg$act" else act
                if (!pkg.matches(PKG_ACTIVITY_REGEX) || !fullAct.matches(PKG_ACTIVITY_REGEX)) {
                    return null
                }
                arrayOf("am", "start", "-n", "$pkg/$fullAct")
            }
            ShortcutKind.INTENT_URI -> {
                val uri = action.intentUri ?: return null
                if (uri.contains('"') || uri.contains(';') || uri.contains('|') ||
                    uri.contains('&') || uri.contains('`') || uri.contains('$') ||
                    uri.contains(' ') || uri.contains('\\') || uri.contains('\n') ||
                    uri.contains('\'')) {
                    return null
                }
                if (uri.startsWith("intent://") || uri.startsWith("#Intent")) {
                    // intent:// URI 必须用 -U（am 内部走 Intent.parseUri），
                    // 用 -d 会把整串当成 data URI，目标收到的是错误 intent
                    arrayOf("am", "start", "-U", uri)
                } else {
                    val pkg = action.packageName
                    if (!pkg.isNullOrEmpty()) {
                        // 限制解析范围要用 -p；裸包名位置参数会被 am 忽略
                        arrayOf("am", "start", "-a", "android.intent.action.VIEW", "-d", uri, "-p", pkg)
                    } else {
                        arrayOf("am", "start", "-a", "android.intent.action.VIEW", "-d", uri)
                    }
                }
            }
            ShortcutKind.SERVICE -> {
                val pkg = action.packageName ?: return null
                val svc = normalizeServiceName(pkg, action.serviceName ?: return null)
                val fullSvc = if (svc.startsWith(".")) "$pkg$svc" else svc
                if (!pkg.matches(PKG_ACTIVITY_REGEX) || !fullSvc.matches(PKG_ACTIVITY_REGEX)) {
                    return null
                }
                arrayOf("am", "startservice", "-n", "$pkg/$fullSvc")
            }
            ShortcutKind.TOOLBOX -> null
        }
    }
}
