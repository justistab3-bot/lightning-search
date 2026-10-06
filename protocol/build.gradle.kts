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
    // 网络探针（probe 包）默认跳过，靠 -DchatProbe=1 / -DsearchProbe=1 / -DcontentProbe=1 显式开启；
    // 开启时才把 stdout 打出来，平时保持安静。
    val chatProbe = System.getProperty("chatProbe")
    val searchProbe = System.getProperty("searchProbe")
    val contentProbe = System.getProperty("contentProbe")
    val contentProbe2 = System.getProperty("contentProbe2")
    systemProperty("chatProbe", chatProbe ?: "")
    systemProperty("searchProbe", searchProbe ?: "")
    systemProperty("contentProbe", contentProbe ?: "")
    systemProperty("contentProbe2", contentProbe2 ?: "")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = chatProbe == "1" || searchProbe == "1" ||
            contentProbe == "1" || contentProbe2 == "1"
    }
}
