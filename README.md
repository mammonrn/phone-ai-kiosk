# phone-ai-kiosk

Android app that will become a kiosk AI assistant on a Samsung Galaxy A07
(Android 15).

## Phase 1 — what this is right now

A kiosk shell with no AI in it yet. One screen: a clock, the label
`Phase 1 Kiosk`, and a line of provisioning state. Around it:

- **Device Owner** — provisioned over adb (see [TESTING.md](TESTING.md)).
- **Lock task mode** — Home, Recents, the notification shade and the keyguard
  are all off. The power menu stays on, which is the default and is what lets
  you reboot the device to test it.
- **Home / launcher** — the app is set as the persistent preferred HOME
  activity, which is what brings the kiosk back after a reboot: the system
  launches HOME on boot, long before any code of ours could ask it to.
- **Escape hatch** — ten taps in the bottom-right corner, each within 1.5s of
  the last, stops lock task and hands the screen to the device's other home
  app. No PIN, by decision. The app stays the preferred HOME, so the Home
  button still returns here — unlocked — until the process restarts or the
  device reboots.

Still to come: wake word, the AI itself, and the phase 5 display.

| | |
|---|---|
| applicationId | `com.mammonrn.phoneaikiosk` (debug builds: `.debug`) |
| minSdk | 26 (Android 8.0) |
| targetSdk | 36 (Android 16) |
| compileSdk | 37 |
| Toolchain | AGP 9.3.1, Gradle 9.7.0, JDK 17 target |
| Device admin component | `com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.KioskDeviceAdminReceiver` |

`targetSdk` is 36 rather than 37 on purpose: 37 opts the app into runtime
behaviour changes that nothing here has been tested against. Both are above
the Android 15 (35) floor.

## Recovering a device

Debug builds carry `android:testOnly="true"`. That is the difference between
one adb command and a factory reset — `dpm remove-active-admin` refuses to
remove a device owner unless the package is test-only:

```
adb shell dpm remove-active-admin com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.KioskDeviceAdminReceiver
adb uninstall com.mammonrn.phoneaikiosk.debug
```

The cost is that the debug APK needs `adb install -t`. Never put the flag in
the release manifest, where it would block installation outright.

Full step-by-step device testing: **[TESTING.md](TESTING.md)** (Thai).

## Getting the APK

Every push to `main` builds a debug APK and uploads it under the
**Actions → Build Android APK → phone-ai-kiosk-debug-apk** artifact.
Download, unzip, and `adb install -t -r app-debug.apk` — the `-t` is required,
see above.

## Building locally

Needs a JDK (17 or newer) and an Android SDK with platform 37 installed.
Point Gradle at the SDK with an untracked `local.properties`:

```
sdk.dir=/path/to/android-sdk
```

Then:

```
./gradlew testDebugUnitTest assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.
