// ReleaseKite：从 ZhiBox 抽取的可复用 GitHub Release 更新库。
//
// 仓库顺序：
// - 本地开发（默认）：Aliyun 镜像优先（本机 dl.google.com TLS 不稳定），官方仓库兜底；
// - CI / JitPack（CI=true / JITPACK=true）：官方 google()/mavenCentral() 优先（海外 runner 可达），
//   Aliyun 仅作为兜底，避免公共构建依赖国内镜像不可达。
// 两边都保留完整集合，某一仓库不可达时仍可回退（Gradle 会继续尝试后续仓库）。
pluginManagement {
    repositories {
        if (System.getenv("CI") == "true" || System.getenv("JITPACK") == "true") {
            google {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                    includeGroupByRegex("androidx.*")
                }
            }
            mavenCentral()
        }
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        if (System.getenv("CI") != "true" && System.getenv("JITPACK") != "true") {
            google {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                    includeGroupByRegex("androidx.*")
                }
            }
            mavenCentral()
        }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("CI") == "true" || System.getenv("JITPACK") == "true") {
            google {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                    includeGroupByRegex("androidx.*")
                }
            }
            mavenCentral()
        }
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        if (System.getenv("CI") != "true" && System.getenv("JITPACK") != "true") {
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
}

rootProject.name = "ReleaseKite"

include(":releasekite-core")
include(":releasekite")
include(":releasekite-compose")
include(":sample")
