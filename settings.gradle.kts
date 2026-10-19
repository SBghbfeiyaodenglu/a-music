// 仓库顺序说明：
// 本机直连 dl.google.com 只有约 46KB/s，而阿里云的 Google 镜像约 5.8MB/s（2026-09-10 实测），
// 所以国内本机把镜像放前面优先命中，官方源放后面兜底（镜像缺新版本时会自动回退）。
// GitHub Actions 在境外，走阿里云反而慢/不稳（实测 KSP 插件解析失败），
// 所以 CI 上（环境变量 CI=true）只列官方源。两边依赖完全一样，只是仓库顺序不同。
pluginManagement {
    repositories {
        if (System.getenv("CI") == "true") {
            google()
            mavenCentral()
            gradlePluginPortal()
        } else {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/central")
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("CI") == "true") {
            google()
            mavenCentral()
        } else {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
            google()
            mavenCentral()
        }
    }
}

rootProject.name = "A-Music"
include(":app")
