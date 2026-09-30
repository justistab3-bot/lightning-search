// 依赖仓库统一走阿里云镜像：本机直连 Maven Central 返回 403。
// aliyun 的 google 镜像代理 Google Maven，public 镜像代理 Maven Central。
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
    }
}

rootProject.name = "FlashSearch"

include(":app")
include(":protocol")
