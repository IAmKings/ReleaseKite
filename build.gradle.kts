// ReleaseKite 根工程：插件按模块应用；这里只做公共任务与 JitPack 兼容入口。
//
// 显式使用与 Kotlin 2.4 元数据兼容的 R8（AGP 8.13 自带的 R8 在 release 构建时会打印
// “kotlin metadata 解析失败”警告；与 ZhiBox 相同的版本组合通过该 classpath 覆盖 R8，
// 让 R8 版本与 Kotlin 2.4 metadata 对齐）。storage.googleapis 仅用于该 group 的兜底。
buildscript {
    repositories {
        google()
        maven("https://storage.googleapis.com/r8-releases/raw") {
            content { includeGroup("com.android.tools") }
        }
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools:r8:9.1.29")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed", "skipped")
        showStackTraces = true
    }
}

// JitPack 默认执行 `./gradlew install`；maven-publish 只注册 publishToMavenLocal。
// 根 install 聚合三个发布模块，保证 JitPack 能拿到 AAR/JAR 与 POM。
tasks.register("install") {
    group = "publishing"
    description = "JitPack entrypoint: publish all library modules to the local Maven repository."
    dependsOn(
        ":releasekite-core:publishToMavenLocal",
        ":releasekite:publishToMavenLocal",
        ":releasekite-compose:publishToMavenLocal",
    )
}
