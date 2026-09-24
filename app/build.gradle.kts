plugins {
    // AGP 9 has built-in Kotlin support, so no separate kotlin-android plugin.
    alias(libs.plugins.android.application)
}

android {
    // COMPRESSED dex and native libraries (2026-09-23). With minSdk 28+ AGP
    // stores classes.dex uncompressed, and it stores .so files uncompressed
    // from minSdk 23: raising minSdk to 29 took the APK from ~45 to 52 MB.
    // The APK's size is what CI's artifact storage is billed on once the repo
    // is private (500 MB on GitHub Free), so it is kept small; the phone
    // extracts the libraries once at install, which it has room for.
    packaging {
        dex { useLegacyPackaging = true }
        jniLibs { useLegacyPackaging = true }
        // 0.44.0: BouncyCastle (for smbj, the NAS) carries 1.2 MB of data files
        // for post-quantum signatures (Picnic) and German certificate-path
        // messages. SMB uses neither; the classes stay, only these files go.
        resources {
            excludes += "org/bouncycastle/pqc/**"
            excludes += "org/bouncycastle/x509/*.properties"
        }
    }
    namespace = "com.mammonrn.phoneaikiosk"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mammonrn.phoneaikiosk"
        // minSdk 29 (Android 10) since 2026-09-23, Poom's decision: the Google
        // Home APIs SDK needs Android 10 or later. The kiosk is a Galaxy A07 on
        // Android 16, so nothing it runs is lost; 26 was the floor before.
        minSdk = 29
        targetSdk = 36

        versionCode = 74
        versionName = "0.57.0"

        // ONE ABI. The kiosk is a Galaxy A07, which is arm64-v8a, and
        // onnxruntime-android carries a native library for every architecture
        // it supports — armeabi-v7a, x86 and x86_64 as well. Shipping the
        // three that this phone can never load would roughly double the APK
        // for nothing. Poom accepted ~17.7 MB for arm64 alone.
        //
        // The cost of being wrong here is loud rather than subtle: on any other
        // architecture the app fails to load the native library at startup, so
        // it cannot ship a silently broken build.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // Optional pinned debug keystore.
    //
    // By default AGP signs debug builds with ~/.android/debug.keystore and
    // generates that file when it is missing. On an ephemeral CI runner it is
    // always missing, so every run signs with a different certificate — two
    // runs of this repo's own workflow produced SHA-256 d5e883… and b7bd30…
    // for the same app. Android refuses to update an installed app whose
    // signing certificate changed, and this one cannot simply be uninstalled:
    // it is the Device Owner, so a key change means re-provisioning the phone.
    //
    // DEBUG_KEYSTORE_PATH points at a keystore to use instead. Left unset,
    // behaviour is exactly what it was.
    //
    // Blank counts as absent: GitHub Actions substitutes a missing secret as
    // an EMPTY STRING rather than leaving the variable unset, and Gradle
    // reports that as present — so a plain null check would have AGP trying to
    // open "" as a keystore and failing as "keystore password was incorrect".
    fun env(name: String): String? =
        providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

    val pinnedDebugKeystore = env("DEBUG_KEYSTORE_PATH")?.let(::file)?.takeIf { it.isFile }

    signingConfigs {
        if (pinnedDebugKeystore != null) {
            create("debugPinned") {
                storeFile = pinnedDebugKeystore
                // Defaults match Android's standard debug keystore, so only a
                // non-standard one needs the extra variables set.
                storePassword = env("DEBUG_KEYSTORE_PASSWORD") ?: "android"
                keyAlias = env("DEBUG_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = env("DEBUG_KEY_PASSWORD") ?: storePassword
            }
        }
    }

    buildTypes {
        debug {
            // Lets a debug build sit beside a future release build on the
            // same phone.
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            signingConfigs.findByName("debugPinned")?.let { signingConfig = it }
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

// THE GOOGLE HOME APIs SDK (Poom, 2026-09-23). Not on any Maven: Poom downloads
// play-services-home-17.0.0.aar and play-services-home-types-17.0.0.aar from
// Google Home Developers into app/libs/. Until they are there, none of this is
// compiled and the build is exactly what it was; once they are, the code that
// talks to the SDK (src/googlehome/java) comes in with them. Read only in this
// round — see home/HomeSummary.kt and home/HomeGate.kt.
val googleHomeSdk = listOf("libs/play-services-home-17.0.0.aar", "libs/play-services-home-types-17.0.0.aar")
    .map { file(it) }
val hasGoogleHomeSdk = googleHomeSdk.all { it.isFile }
if (hasGoogleHomeSdk) {
    android.sourceSets.getByName("main").java.srcDir("src/googlehome/java")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    if (hasGoogleHomeSdk) implementation(files(googleHomeSdk))

    // The wake word runs here, on the phone: no audio may leave the room
    // before "Hey Jarvis" has been heard, so the three ONNX models have to be
    // executed locally.
    implementation(libs.onnxruntime.android)

    // The identity check: the front camera, and ML Kit to find the face and
    // the blink. The frames never leave the phone. See auth/VerifyActivity.
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.face)

    // The file manager's NAS, read only (0.44.0): SMB in pure Java, no .so.
    implementation(libs.smbj)

    // The music player (0.53.0): ExoPlayer alone. See libs.versions.toml.
    implementation(libs.media3.exoplayer)

    testImplementation(libs.junit)
    // Test classpath only — see the note in libs.versions.toml.
    testImplementation(libs.json)
    // The JVM build of the SAME runtime version, so WakeWordParityTest exercises
    // the identical kernels the phone will run rather than an approximation of
    // them. Without this the parity test could only check our own arithmetic.
    testImplementation(libs.onnxruntime.jvm)
}
