package com.lsp.hypersidebar.anchor

import com.lsp.hypersidebar.util.HLog
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.query.matchers.MethodsMatcher
import java.io.File

/**
 * L2 后端：DexKit 实现（name-agnostic 的类定位）。
 *
 * **为什么用 [DexKitCacheBridge] 而不是裸的 [org.luckypray.dexkit.DexKitBridge]**：
 * 前者把"查询结果缓存 + native bridge 生命周期（空闲回收）"一起托管了。实测（2.3.0 源码）：
 * - 缓存 API 形如 `getClassesOrEmpty(key, query)` —— **必须传 key**，传 null 的重载会静默绕过缓存；
 * - `idleTimeoutMillis` / `cachePolicy` 是 Kotlin 属性，不是 setter；
 * - `Cache` SPI 的 `getString(key, default: String?)` 第二参可空（见 [AnchorDiskCache] 实现）。
 *
 * 缓存命名空间由 `appTag = <宿主包>#<versionCode>#<apkLength>` 决定 ⇒ ROM 升级自动换 tag ⇒
 * 旧条目作废并重新解析（[AnchorDiskCache] 里按 tag 校验）。
 *
 * 查询只下推"包 + 自身声明方法"，**super 链约束交给 L1 反射**（见 [DexIndex] 注释：
 * DexKit 的 `ClassMatcher.superClass` 只匹配直接父类，而 OS4 的 cover 中间隔了一层）。
 * 任何失败都返回空列表并记一行日志，绝不向上抛。
 */
@OptIn(DexKitExperimentalApi::class)
internal class DexKitIndex private constructor(
    private val bridge: DexKitCacheBridge.RecyclableBridge,
) : DexIndex {

    override val stateLine: String = "dexkit=ready"

    override fun findClasses(pkg: String, own: MethodSpec): List<String> = try {
        val builder = object : DexKitCacheBridge.RecyclableBridge.FindClassBuilder {
            override fun build(f: FindClass) {
                f.searchPackages(pkg)
                f.matcher(
                    ClassMatcher().apply {
                        methods(
                            MethodsMatcher().apply {
                                add(
                                    MethodMatcher().apply {
                                        name(own.name)
                                        if (own.params.isEmpty()) {
                                            paramTypes()
                                        } else {
                                            paramTypes(*own.params.toTypedArray())
                                        }
                                        returnType(own.ret)
                                    }
                                )
                            }
                        )
                    }
                )
            }
        }
        // key 与 role 无关、只由 (包, 方法签名) 决定 ⇒ 多个 role 共用同一份缓存条目
        bridge.getClassesOrEmpty(cacheKey(pkg, own), builder)
            .map { normalize(it.className) }
            .distinct()
            .sorted()
    } catch (t: Throwable) {
        HLog.w(TAG, "L2 query failed for ${own.signature} in $pkg: ${t.message}")
        emptyList()
    }

    private fun cacheKey(pkg: String, own: MethodSpec): String = "$pkg|${own.signature}"

    private fun normalize(raw: String): String {
        var s = raw.trim()
        // DexClass.className 正常是点分名；若拿到描述符形式也顺手归一
        if (s.length > 1 && s[0] == 'L' && s.endsWith(";")) s = s.substring(1, s.length - 1)
        return s.replace('/', '.')
    }

    companion object {
        private const val TAG = "AnchorDexKit"
        private const val CACHE_FILE = "hypersidebar_anchor_cache.json"

        /** native bridge 空闲回收阈值：宿主进程（systemui / home）不该长期持有 native 资源 */
        private const val IDLE_TIMEOUT_MS = 60_000L

        @Volatile
        private var installedTag: String? = null

        /**
         * @param appTag      宿主身份键（`pkg#versionCode#apkLength`）—— 缓存命名空间 + 失效闸
         * @param classLoader **宿主**的 ClassLoader（DexKit 从它枚举宿主 dex，不需要 APK 路径）
         * @param cacheDir    宿主 cacheDir；null ⇒ 直接放弃 L2
         * @return null 表示 L2 不可用（native 未加载 / 初始化失败），调用方退回 L0/L1
         */
        fun create(appTag: String, classLoader: ClassLoader, cacheDir: File?): DexKitIndex? {
            if (cacheDir == null) return null
            if (!NativeLibLoader.ensureLoaded(cacheDir)) return null
            return try {
                if (installedTag != appTag) {
                    // 换 tag（或首次）⇒ 重装缓存介质与策略；同一进程内 tag 不会变，这里只是幂等保护
                    DexKitCacheBridge.init(AnchorDiskCache(File(cacheDir, CACHE_FILE), appTag))
                    DexKitCacheBridge.idleTimeoutMillis = IDLE_TIMEOUT_MS
                    DexKitCacheBridge.cachePolicy = DexKitCacheBridge.CachePolicy(
                        // 只缓存成功查询的结果；不缓存失败（保守：一次失败不该长期压住本可成功的查询）
                        cacheSuccess = true,
                        failurePolicy = DexKitCacheBridge.CacheFailurePolicy.NONE,
                    )
                    installedTag = appTag
                }
                DexKitIndex(DexKitCacheBridge.create(appTag, classLoader)).also {
                    HLog.i(TAG, "L2 ready: appTag=$appTag cache=$CACHE_FILE")
                }
            } catch (t: Throwable) {
                HLog.w(TAG, "L2 init failed (L0/L1 only): ${t.message}")
                null
            }
        }
    }
}