// 发布约定（所有对外模块 apply 本脚本）：
// - 坐标来自根 gradle.properties 的 GROUP / VERSION_NAME；
// - Java 模块发布 JAR + sources；Android 模块发布 AAR + sources
//   （Android 侧在各自 build.gradle.kts 的 android.publishing.singleVariant 中声明）；
// - publishToMavenLocal 供 JitPack（根 install 任务聚合）与宿主本地验证；
// - publish 任务额外写入 root build/maven-repo（不是 publishToMavenLocal 的路径）。
apply(plugin = "maven-publish")

val libGroup = providers.gradleProperty("GROUP").get()
val libVersion = providers.gradleProperty("VERSION_NAME").get()
val isAndroid = plugins.hasPlugin("com.android.library")

val moduleDescription = when (project.name) {
    "releasekite-core" -> "ReleaseKite core: GitHub Release update engine, pure Kotlin/JVM."
    "releasekite" -> "ReleaseKite Android integration: WorkManager download, install, notifications, storage."
    "releasekite-compose" -> "ReleaseKite Compose UI: update dialog, settings row and settings section."
    else -> "ReleaseKite module ${project.name}."
}

// POM 的 URL/SCM 元数据：优先 POM_URL，其次 CI 的 GITHUB_REPOSITORY，最后本仓库真实地址。
val pomUrl: String = providers.gradleProperty("POM_URL").orNull
    ?: providers.environmentVariable("GITHUB_REPOSITORY").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { "https://github.com/$it" }
    ?: "https://github.com/IAmKings/ReleaseKite"
val repoPath = pomUrl.substringAfter("github.com/", "").trim('/').removeSuffix(".git")
val scmUrl = "https://github.com/$repoPath.git"
val scmSsh = "scm:git:ssh://git@github.com/$repoPath.git"

// GitHub Packages 发布：仅在提供 GITHUB_ACTOR/GITHUB_TOKEN 时启用（CI workflow 注入）。
// publishToMavenLocal / JitPack install 不需要 token，也不会尝试触碰该仓库。
val ghActor = providers.environmentVariable("GITHUB_ACTOR").orNull
val ghToken = providers.environmentVariable("GITHUB_TOKEN").orNull
val ghRepository = providers.environmentVariable("GITHUB_REPOSITORY").orNull
    ?.takeIf { it.isNotBlank() }
    ?: repoPath


afterEvaluate {
    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("release") {
                groupId = libGroup
                artifactId = project.name
                version = libVersion
                if (isAndroid) {
                    from(components["release"])
                } else {
                    from(components["java"])
                }
                pom {
                    name.set(project.name)
                    description.set(moduleDescription)
                    url.set(pomUrl)
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    developers {
                        developer {
                            id.set("releasekite")
                            name.set("ReleaseKite contributors")
                        }
                    }
                    scm {
                        connection.set(scmUrl)
                        developerConnection.set(scmSsh)
                        url.set(pomUrl)
                    }
                }
            }
        }
        repositories {
            maven {
                name = "LocalRepo"
                url = uri(rootProject.layout.buildDirectory.dir("maven-repo"))
            }
            if (!ghActor.isNullOrBlank() && !ghToken.isNullOrBlank()) {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/$ghRepository")
                    credentials {
                        username = ghActor
                        password = ghToken
                    }
                }
            }
        }
    }
}
