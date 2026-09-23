// 仓库顺序说明：
// 本机直连 dl.google.com 只有约 46KB/s，而阿里云的 Google 镜像约 5.8MB/s（2026-09-10 实测），
// 所以国内镜像放前面优先命中，官方源放后面兜底（镜像缺新版本时会自动回退）。
// 不要删掉后面的 google()/mavenCentral()，否则镜像同步延迟会导致解析失败。
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
    }
}

rootProject.name = "A-Music"
include(":app")
