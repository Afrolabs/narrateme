import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tapeaids.narrateme"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tapeaids.narrateme"
        minSdk = 29 // Meta Wearables DAT 1.0.1 requires Android 10+
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0"
        // OpenCV's native libraries are large; ship phones (arm64) and the CI emulator (x86_64) only.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)
    implementation(libs.mwdat.inputs)
    // Mock Device Kit runs in-app (not test-only), so the prototype can be tried without glasses.
    implementation(libs.mwdat.mockdevice)

    // Page recognition: ORB keypoints + homography check, on the phone, offline.
    implementation(libs.opencv)

    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
}
