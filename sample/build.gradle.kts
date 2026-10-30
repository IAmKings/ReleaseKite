plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.releasekite.sample"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.releasekite.sample"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.0.1"

        // 演示用的真实 GitHub 仓库（只读检查；下载的资产会在签名/包名校验处被拒绝）。
        buildConfigField(
            "String",
            "DEMO_REPO",
            "\"${providers.gradleProperty("releasekite.demoRepo").getOrElse("IAmKings/ZhiBox")}\"",
        )
    }

    buildTypes {
        release {
            // 示例工程的 release 变体也作为「R8/minify + resource shrink」门禁：
            // 用于验证库的 consumer rules、Compose 运行时与 WorkManager Worker 在混淆下可构建。
            // 注意：签名仍用本地 debug keystore，仅用于构建验证/本机安装，不可发布。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":releasekite-compose"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // 示例页面用 collectAsStateWithLifecycle 收集 manager.state（生命周期感知）。
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    // 示例页面使用 Column/Row/verticalScroll 等 foundation 能力；显式声明避免依赖传递偶然性。
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)
}
