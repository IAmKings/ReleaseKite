#!/usr/bin/env bash
# ============================================================================
# ReleaseKite consumer smoke test
#
# 目的：验证「发布产物」而不是源码工程：
#   1) ./gradlew publishToMavenLocal 产出三个模块；
#   2) 一个独立的最小 Compose App 仅通过 mavenLocal 坐标消费
#      <GROUP>:releasekite-compose:<VERSION>（GROUP/VERSION 读取 gradle.properties）；
#   3) 该 App 以 R8 minify + shrinkResources 构建 release，验证 POM/Gradle
#      module metadata、Compose 依赖 api scope、consumer ProGuard 规则。
#
# 用法：scripts/consumer-smoke.sh
# 依赖：本机 Android SDK（local.properties 或 ANDROID_HOME）、JDK 21。
# 所有临时工程写入 $TMPDIR，退出时删除。
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${TMPDIR:-/tmp}/releasekite-consumer-smoke"
VERSION="$(sed -n 's/^VERSION_NAME=//p' "$ROOT/gradle.properties" | head -1)"
GROUP="$(sed -n 's/^GROUP=//p' "$ROOT/gradle.properties" | head -1)"
[ -n "$VERSION" ] || { echo "无法从 gradle.properties 读取 VERSION_NAME" >&2; exit 1; }

echo "==> publishToMavenLocal (GROUP=$GROUP VERSION=$VERSION)"
(cd "$ROOT" && ./gradlew -q publishToMavenLocal)

rm -rf "$WORK"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/app/src/main/java/io/github/releasekite/consumer"

if [ -f "$ROOT/local.properties" ]; then
  cp "$ROOT/local.properties" "$WORK/local.properties"
fi

cat > "$WORK/settings.gradle.kts" <<'EOF'
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}
rootProject.name = "releasekite-consumer-smoke"
include(":app")
EOF

cat > "$WORK/gradle.properties" <<'EOF'
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
EOF

cat > "$WORK/app/build.gradle.kts" <<EOF
plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
}

android {
    namespace = "io.github.releasekite.consumer"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.releasekite.consumer"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true }
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
    // 被测发布物：只从 mavenLocal 取 AAR/POM，不使用 project(":...")。
    implementation("$GROUP:releasekite-compose:$VERSION")

    // 一个真实 Compose 宿主自身的依赖（BOM + 编译所需 Compose 栈）。
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
}
EOF

: > "$WORK/app/proguard-rules.pro"

cat > "$WORK/app/src/main/AndroidManifest.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:name=".ConsumerApplication"
        android:allowBackup="false"
        android:label="ReleaseKite Consumer"
        android:supportsRtl="true">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
EOF

cat > "$WORK/app/src/main/java/io/github/releasekite/consumer/ConsumerApplication.kt" <<'EOF'
package io.github.releasekite.consumer

import android.app.Application
import io.github.releasekite.ReleaseKite
import io.github.releasekite.core.ReleaseKiteConfig

class ConsumerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ReleaseKite.initialize(
            context = this,
            config = ReleaseKiteConfig(repository = "octocat/Hello-World"),
        )
    }
}
EOF

cat > "$WORK/app/src/main/java/io/github/releasekite/consumer/MainActivity.kt" <<'EOF'
package io.github.releasekite.consumer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import io.github.releasekite.compose.ReleaseKiteHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    Text("ReleaseKite consumer smoke")
                    // 公开 Compose API 必须能在仅依赖发布物的工程中编译。
                    ReleaseKiteHost()
                }
            }
        }
    }
}
EOF

echo "==> assembleRelease (R8 minify + shrinkResources)"
(cd "$WORK" && "$ROOT/gradlew" -p "$WORK" :app:assembleRelease --console=plain -q)

APK="$WORK/app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || { echo "consumer release APK 未生成：$APK" >&2; exit 1; }

SEEDS="$WORK/app/build/outputs/mapping/release/seeds.txt"
[ -f "$SEEDS" ] || { echo "缺少 R8 seeds：$SEEDS" >&2; exit 1; }
grep -q "io.github.releasekite.internal.ReleaseKiteDownloadWorker" "$SEEDS" || {
  echo "R8 seeds 未保留 ReleaseKiteDownloadWorker（consumer ProGuard 规则失效）" >&2
  exit 1
}
grep -q "io.github.releasekite.internal.ReleaseKiteFileProvider" "$SEEDS" || {
  echo "R8 seeds 未保留 ReleaseKiteFileProvider" >&2
  exit 1
}

echo "==> OK: $APK"
echo "==> R8 seeds 保留 Worker/FileProvider；POM + mavenLocal consumer 编译/打包通过"
