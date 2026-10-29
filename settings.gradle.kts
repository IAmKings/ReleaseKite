// ReleaseKite：从 ZhiBox 抽取的可复用 GitHub Release 更新库。
//
// 仓库顺序与 ZhiBox/LKanvas 保持一致：本机 dl.google.com TLS 不稳定，优先 Aliyun 镜像；
// CI 若镜像不可达会继续回退官方 google()/mavenCentral()。
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "ReleaseKite"

include(":releasekite-core")
include(":releasekite")
include(":releasekite-compose")
include(":sample")
