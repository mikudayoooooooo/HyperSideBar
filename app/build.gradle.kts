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

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lsp.hypersidebar"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lsp.hypersidebar"
        minSdk = 26
        targetSdk = 36
        versionCode = computedVersionCode
        versionName = "0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 注入依赖版本到 BuildConfig，运行时可通过 BuildConfig.XXX 读取
        buildConfigField("String", "XPOSED_API_VERSION", "\"101.0.1\"")
        buildConfigField("String", "EZXHELPER_VERSION", "\"3.2.0-preview1\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
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
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // MIUIX UI Library
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.navigation3.ui)

    // Xposed/LSPosed
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.xposed.api.x01)
    implementation(libs.android.utils)
    implementation(libs.ezxhelper.core)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.activity)
}
