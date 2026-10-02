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

    defaultConfig {
        applicationId = "com.heikeji.phonesearch"
        minSdk = 24
        targetSdk = 37
        // 每轮迭代都往上走：装到机器上后可以直接从「设置 - 应用」或首页底部确认版本。
        versionCode = 10
        versionName = "1.10.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
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

    testImplementation(libs.junit)
}
