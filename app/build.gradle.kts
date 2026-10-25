import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

val versionFile = rootProject.file("version.properties")
val props = Properties()
if (versionFile.exists()) {
    versionFile.inputStream().use { props.load(it) }
}
val lastCode = props.getProperty("VERSION_CODE", "0").toInt()

val todayPrefix = SimpleDateFormat("yyyyMMdd").format(Date()) // e.g. "20260707"
val dailySeq = if (lastCode / 100 == todayPrefix.toInt()) {
    lastCode % 100 + 1
} else {
    1
}
val computedVersionCode = todayPrefix.toInt() * 100 + dailySeq

// release 签名（迭代四收尾）：keystore.properties 不入库（.gitignore），缺失时
// release 保持未签名（assembleRelease 产 app-release-unsigned.apk）
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lsp.hypersidebar"
    compileSdk = 37

    defaultConfig {
        // 迭代五批次 0：LSPosed 仓库自动审批要求提交者能证明包名所属域名的控制权，
        // com.lsp.*（lsp.com 非本人所有）不可批 → 迁到 io.github.<user>.<module>。
        // 仅改 applicationId（APK 身份）；namespace/目录树/类路径保持 com.lsp.hypersidebar 不动。
        applicationId = "io.github.mikudayoooooooo.hypersidebar"
        // 迭代五批次 1（2026-09-04 用户拍板）：26 → 33，直接支持 miuix-blur
        // （RuntimeShader 自 API 33 起，模糊/混合/噪点/高光全部依赖）——
        // 免去全库 blur 路径的 capability 门控；要求 Android 13+（HyperOS 全系满足）
        minSdk = 33
        targetSdk = 36
        versionCode = computedVersionCode
        // 三段式语义化版本；2.0.0 = 包名断代标记（旧包名无法覆盖安装，需卸载重装）；
        // 2.1.0 = 迭代六全量（扇形背景板材质重构 + 设置热更新根治 + 统计/日志/自检）；
        // 2.2.0 = 底角斜滑新触发通道 + 设置页信息架构收敛 + 误触率口径 v2。
        // 2.3.0 = OS4 快捷方式选择器（桌面 Rust 化后改走 root 枚举）+ 配置恢复通道
        //         + 快捷方式保存防整表覆盖；均无包名断代，2.2.0 用户可直接覆盖安装
        // 2.3.1 = OS4 侧滑停顿以系统返回手势已认领为前提（系统手势门）
        //         + 竖屏扇形张角固定为配置值（修靠上/靠下留白）；无包名断代，可直接覆盖安装
        versionName = "2.3.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 结构化锚点解析的 L2 后端（DexKit）只随 arm64-v8a 分发：目标设备全是 arm64，
        // 增量约 386 KB；x86/x86_64 只在本地模拟器需要（本项目无模拟器）。
        ndk {
            abiFilters += "arm64-v8a"
        }

        // 注入依赖版本到 BuildConfig，运行时可通过 BuildConfig.XXX 读取。
        // 2026-10-18：XPOSED_API_VERSION 原先写死 "101.0.1"（与实编 libxposed:api:102.0.0 不符，
        // About 页显示错版本）；现与 EZXHELPER_VERSION 一样从版本目录取 —— 显示的永远是实际编译
        // 依赖的版本，不可能再漂移。（module.prop 的 minApiVersion=102 是"最低要求"的整数形态，
        // 与本行展示的库版本不是同一个东西。）
        buildConfigField("String", "XPOSED_API_VERSION", "\"${libs.versions.xposed.api.get()}\"")
        buildConfigField("String", "EZXHELPER_VERSION", "\"${libs.versions.ezxhelperVersion.get()}\"")
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 混淆+压缩+资源收缩（Xposed 入口类经 proguard-rules.pro 必保）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        // miuix 0.9.4 起以 JVM 21 目标发布，其中 inline API（miuix-nav 的 entry DSL）要求
        // 调用方同为 21，否则报 "Cannot inline bytecode built with JVM target 21"
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
        // 16KB 页内核硬要求（marble OS3/A16 起；OS4 也是）：
        // `useLegacyPackaging = false` 才会把 .so **以 STORED + 页对齐**打进 APK，
        // 使 linker 能直接 mmap APK 内条目。
        //
        // ⚠️ 这里有两个**互相独立**的要求，缺一不可（2026-09-30 复盘`os4-native-entry-postmortem`）：
        //   ① .so 内部 PT_LOAD 的 p_align >= 16384   ← 链接器（CMakeLists 的 max-page-size）
        //   ② APK 内 ZIP 条目的 offset 按 16384 对齐   ← 打包器（本块）
        // 之前只做了 ①，实测 APK 条目 offset 仍是 0x21d5484 等**非 16KB 倍数** ——
        // 在 16KB 页设备上 dlopen 会失败，而 LSPosed 对 native entry 失败的处理是
        // **整个模块中止**（与 assets/native_init 变体同一故障模式）。
        jniLibs.useLegacyPackaging = false
    }

    // OS4 native 探针（adapt/hyperos4）：LSPosed native entry 骨架——验证本机构建是否
    // 真的解析 META-INF/xposed/native_init.list 并调用 native_init（U1 前提，见
    // docs/adaptation/marble-os4-decompile-verification.md §6 与
    // F:\hyperos\work\docs\lsposed-native-entry-verification.md §4.2）。
    // 纯 C 探针，只导出 native_init，OS2/OS3 上必须安静跳过（无 libapp_launcher.so）。
    ndkVersion = "27.2.12479018"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
}

// 编译后将本次 versionCode 写回 version.properties，供下次编译递增
tasks.named("preBuild").configure {
    doLast {
        props.setProperty("VERSION_CODE", computedVersionCode.toString())
        versionFile.outputStream().use { props.store(it, null) }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    // 纯核心解析器单测需要真 org.json（Android 桩 not mocked；test-only 不进 APK）
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // MIUIX UI Library
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.squircle)
    // 毛玻璃（0913 用户拍板路线③）：窗口内组件 backdrop 模糊（AGSL RuntimeShader，API 33+=minSdk）
    implementation(libs.miuix.blur)
    // 0.9.4 起 miuix-navigation3-ui（实为 androidx.navigation3 ui/scene 的内嵌拷贝）停止发布，
    // 由 miuix-nav 引擎取代：自带 NavDisplay/NavBackStack/NavKey + HyperOS 转场（滑动+圆角裁切+遮罩）
    implementation(libs.miuix.nav)

    // Xposed/LSPosed
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.xposed.api.x01)
    implementation(libs.android.utils)
    implementation(libs.ezxhelper.core)

    // @hide 框架类编译桩（InputChannel/InputEventReceiver 等）：compileOnly ⇒ 桩不进 app
    // dex，运行时由宿主 boot classloader 解析真类。2026-10-04 教训：桩放 :app 源集会被
    // R8 判死代码删除（usage.txt 实锤），as? 折叠成常量 null ⇒ spy channel 恒 null
    // （见 docs/adaptation/spy-channel-blocker-briefing.md §复核结论）。禁止加 keep 规则。
    compileOnly(project(":hidden-api"))

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.activity)

    // 列表拖动重排（快捷方式 / 扇形应用固定顺序）
    implementation(libs.reorderable)

    // 运行时 dex 解析（结构化锚点解析 L2）：C++ 实现，自带 consumer proguard 规则保住 native 方法。
    // .so 不由宿主加载路径解析，而是由 NativeLibLoader 从模块 APK 抽出后 System.load
    // （LSPosed 模块的 .so 不在宿主的 native 搜索路径里）。许可义务见 README「第三方组件与许可」。
    implementation(libs.dexkit)
}
