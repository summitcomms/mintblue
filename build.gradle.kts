plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.1.1")  // 최신 Gradle 버전
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.0")  // Kotlin 버전을 1.9.0으로 변경
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // JitPack 저장소 추가
    }
}

// 환경 변수를 사용하는 예시
val sdkDir = System.getenv("ANDROID_SDK_ROOT") ?: "default/sdk/path"
println("SDK Directory: $sdkDir")
