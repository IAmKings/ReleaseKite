// 发布约定（所有对外模块 apply 本脚本）：
// - 坐标来自根 gradle.properties 的 GROUP / VERSION_NAME；
// - Java 模块发布 JAR + sources；Android 模块发布 AAR + sources
//   （Android 侧在各自 build.gradle.kts 的 android.publishing.singleVariant 中声明）；
// - publishToMavenLocal 供 JitPack（根 install 任务聚合）与宿主本地验证；
// - 额外写入 root build/maven-repo，便于不依赖 ~/.m2 的制品检查。
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
                    url.set("https://github.com/releasekite/ReleaseKite")
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
                        connection.set("scm:git:git://github.com/releasekite/ReleaseKite.git")
                        developerConnection.set("scm:git:ssh://github.com/releasekite/ReleaseKite.git")
                        url.set("https://github.com/releasekite/ReleaseKite")
                    }
                }
            }
        }
        repositories {
            maven {
                name = "LocalRepo"
                url = uri(rootProject.layout.buildDirectory.dir("maven-repo"))
            }
        }
    }
}
