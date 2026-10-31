plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.vspace"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.vspace"
        // minSdk 28 supaya boleh targetSdk 28 (menghindari aturan DCL/exec/scoped storage yang memberatkan virtual app).
        // Target pengguna tetap Android 10-16.
        minSdk = 28
        targetSdk = 28
        versionCode = 19
        versionName = "0.2.17"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
