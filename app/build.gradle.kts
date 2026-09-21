plugins {
    // AGP 9 has built-in Kotlin support, so no separate kotlin-android plugin.
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.mammonrn.phoneaikiosk"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mammonrn.phoneaikiosk"
        // minSdk 26 (Android 8.0). The target device is a Galaxy A07 on
        // Android 15, but 26 is the floor the later kiosk phases need.
        minSdk = 26
        targetSdk = 36

        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        debug {
            // Lets a debug build sit beside a future release build on the
            // same phone.
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            // No signing config here on purpose: phase 0 builds debug only,
            // and a release keystore that CI could reach is a keystore anyone
            // with push access could sign with.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
}
