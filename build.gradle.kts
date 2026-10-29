// ReleaseKite 根工程：插件按模块应用；这里只做公共任务与 JitPack 兼容入口。
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
