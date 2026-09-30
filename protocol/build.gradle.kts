plugins {
    alias(libs.plugins.kotlin.jvm)
}

// 不声明 jvmToolchain：本机只有 JDK 25/26，声明 toolchain 会触发下载。
// 直接固定字节码目标为 17，与 :app 保持一致。
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.jsoup)
    implementation(libs.gson)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
    }
}
