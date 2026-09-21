# phone-ai-kiosk

Android app that will become a kiosk AI assistant on a Samsung Galaxy A07
(Android 15).

## Phase 0 — what this is right now

An empty shell that builds, installs and launches. One activity showing
`Phone AI Kiosk - Phase 0 OK`, and nothing else. No Device Owner, no kiosk
lock, no wake word — those are later phases.

| | |
|---|---|
| applicationId | `com.mammonrn.phoneaikiosk` (debug builds: `.debug`) |
| minSdk | 26 (Android 8.0) |
| targetSdk | 36 (Android 16) |
| compileSdk | 37 |
| Toolchain | AGP 9.3.1, Gradle 9.7.0, JDK 17 target |

`targetSdk` is 36 rather than 37 on purpose: 37 opts the app into runtime
behaviour changes that nothing here has been tested against. Both are above
the Android 15 (35) floor.

## Getting the APK

Every push to `main` builds a debug APK and uploads it under the
**Actions → Build Android APK → phone-ai-kiosk-debug-apk** artifact.
Download, unzip, and `adb install -r app-debug.apk` (or sideload it).

## Building locally

Needs a JDK (17 or newer) and an Android SDK with platform 37 installed.
Point Gradle at the SDK with an untracked `local.properties`:

```
sdk.dir=/path/to/android-sdk
```

Then:

```
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.
