plugins {
    alias(libs.plugins.kotlin.jvm)
}

// 纯 Kotlin/JVM 领域库：不依赖 Android/Compose（模块边界即“与 UI 解耦”的硬约束）。
apply(from = rootProject.file("gradle/publishing.gradle.kts"))

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 公共 API 暴露 StateFlow/Flow/suspend，故为 api。
    api(libs.kotlinx.coroutines.core)
    // 仅内部使用 JSON DOM（不启用 @Serializable 编译器插件）。
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
