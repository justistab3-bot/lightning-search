// 注意：脚本里 `java` 是 Gradle 的 Java 插件扩展，会遮蔽 java 包名，
// 所以要用 Properties 必须显式 import。
import java.util.Properties

plugins {
    // 注意：AGP 9.3.1 内置 Kotlin 支持（自身依赖 kotlin-gradle-plugin:2.2.10），
    // 会自动注册 `kotlin` 扩展。再手动 apply org.jetbrains.kotlin.android 会冲突：
    // "Cannot add extension with name 'kotlin'"。所以这里只 apply AGP。
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.heikeji.phonesearch"
    compileSdk = 37
    // 显式固定：AGP 9.3.1 期望的 build-tools 正好是 36.0.0。
    buildToolsVersion = "36.0.0"

    // 友盟 AppKey 从 local.properties 读（该文件不入库）。
    // 这是开源项目：AppKey 写死在源码里的话，别人 fork 之后数据会打进同一个友盟账号。
    // 读不到就留空，代码里会跳过友盟初始化，功能照常。
    val umengAppKey: String = run {
        val props = Properties()
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { props.load(it) }
        props.getProperty("umeng.appkey").orEmpty()
    }

    defaultConfig {
        applicationId = "com.heikeji.phonesearch"
        // 21 = Android 5.0。词典笔这类设备普遍停在 5.x/6.x。
        minSdk = 21
        targetSdk = 37
        // 每轮迭代都往上走：装到机器上后可以直接从「设置 - 应用」或首页底部确认版本。
        versionCode = 32
        versionName = "1.32.0"

        buildConfigField("String", "UMENG_APPKEY", "\"$umengAppKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // :protocol 是纯 JVM 模块，用了 Java 8+ 的 API（java.util.Base64、Optional 等），
        // Android 5.0 上没有，必须靠脱糖在编译期改写成等价实现。
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":protocol"))

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)

    // 友盟+：common + asms 是统计底座，apm 负责崩溃/ANR 采集
    implementation(libs.umeng.common)
    implementation(libs.umeng.asms)
    implementation(libs.umeng.apm)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    // 单元测试跑在 JVM 上，android.jar 里的 org.json 是桩实现（一调用就抛 not mocked）。
    // 引入真实实现，让更新源的 JSON 解析能被测到。
    testImplementation(libs.json)
}
