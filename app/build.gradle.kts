plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.vspace"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.example.vspace"
        // minSdk 28 supaya boleh targetSdk 28 (menghindari aturan DCL/exec/scoped storage yang memberatkan virtual app).
        // Target pengguna tetap Android 10-16.
        minSdk = 28
        targetSdk = 28
        versionCode = 39
        versionName = "0.2.38"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
                cppFlags += listOf("-std=c++17")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // 0.2.38e: keystore debug stabil yang di-commit, supaya build lokal & CI
    // berbagi fingerprint sama. Tanpa ini, `assembleDebug` di CI menghasilkan
    // ~/.android/debug.keystore baru per runner -> `adb install -r` gagal
    // (bukti: 0.2.38 run 41310207186 vs 0.2.38d run 41312249134, signature
    // mismatch INSTALL_FAILED_UPDATE_INCOMPATIBLE). Ini keystore DEBUG,
    // bukan production; aman dibagikan.
    signingConfigs {
        create("vspaceDebug") {
            storeFile = file("../keystore/vspace-debug.p12")
            storeType = "PKCS12"
            storePassword = "vspace-debug-2026"
            keyAlias = "vspace-debug"
            keyPassword = "vspace-debug-2026"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("vspaceDebug")
        }
        getByName("release") {
            // Default: tetap unsigned/standar. Release production pakai kunci terpisah.
        }
    }
}

dependencies {
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
