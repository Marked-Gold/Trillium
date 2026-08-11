# Triplo

A powers-of-three merge puzzle game built with the [KorGE](https://korge.org/) Kotlin
Multiplatform game engine. Drag-select chains of equal-value blocks to merge them upward, form
shape patterns for bonuses, and use bomb / rocket power-ups.

## Status

- **Engine:** KorGE **6.0.0** (Kotlin 2.0, Gradle 8.8, JDK 21).
- **Targets:** JVM (desktop), JS (web), Android, iOS.
- **Ads:** AdMob interstitials shown after a round ends and on restart. Android uses the Google
  Mobile Ads SDK; other platforms are no-ops. See [Ads](#ads) below.

## Requirements

- **JDK 21** (KorGE 6 requires it). Installed locally at `/opt/homebrew/opt/openjdk@21`.
- **Android:** the Android SDK. `local.properties` points `sdk.dir` at it.
- **iOS:** macOS with **Xcode** installed (from the App Store) plus the iOS SDKs.

Gradle commands below assume `JAVA_HOME` points at JDK 21, e.g.:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
```

## Running & building

```bash
./gradlew runJvm                 # Run on the desktop (JVM)
./gradlew runJs                  # Run in the browser
./gradlew jsWeb                  # Build the web distribution into build/web

./gradlew assembleDebug          # Build the Android debug APK
./gradlew installAndroidDebug    # Build & install the APK on a connected device
./gradlew runAndroidEmulatorDebug

./gradlew runIosSimulatorDebug   # Build & run on the iOS simulator (needs Xcode)
```

## Project layout

KorGE 6 uses a flat source layout:

- `src/` — shared (`commonMain`) game code.
- `src@android/`, `src@jvm/`, `src@js/`, `src@ios/` — platform-specific code (`expect`/`actual`).
- `resources/` — images and fonts.
- `test/` — shared tests.

## Ads

Interstitial ads are shown when a round ends, when the player restarts from the pause menu, and when
a saved game is loaded.
Common code talks to the platform-agnostic `Ads` facade (`src/Ads.kt`); each platform provides an
`installPlatformAds()` implementation.

| Platform | Implementation |
|----------|----------------|
| Android  | Google Mobile Ads SDK (`src@android/AdsAndroid.kt`) — **verified, APK builds**. |
| iOS      | Google Mobile Ads SDK via an Objective-C bridge (`src@ios/AdsIos.kt` + `native/ios/`) — **implemented, needs an Xcode verification pass** (see below). |
| JVM / JS | No-op. |

### How the iOS bridge works

KorGE regenerates the iOS Xcode project on every build and exposes no hook for extra frameworks,
so the bridge has four parts:

- `native/ios/TriploAds.{h,m}` — a thin Objective-C wrapper over `GADInterstitialAd`. The header
  imports only Foundation; only the `.m` imports the AdMob SDK.
- `native/GoogleMobileAdsBridge.def` — cinterop definition exposing the header to Kotlin/Native.
- `build.gradle.kts` registers the cinterop and a `patchIosProject` step that injects the
  GoogleMobileAds SwiftPM package, the bridge `.m`, and the required `Info.plist` keys into the
  generated Xcode project (re-running after KorGE regenerates it).
- `src@ios/AdsIos.kt` — the Kotlin `actual` calling the bridge.

**Building any iOS target requires a full Xcode install** (not just Command Line Tools).

### Verifying iOS ads (once Xcode is installed)

```bash
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
./gradlew runIosSimulatorDebug
```

AdMob's test ad unit serves real test interstitials in the simulator with no account needed.
Trigger a game-over or a pause-menu restart and confirm the interstitial appears and dismisses.
The `patchIosProject` step and the linker setup are the parts most likely to need small tweaks on
this first real run.

### Before a production release

1. Replace the AdMob **application id** in `build.gradle.kts` (Android) and `iosAdMobAppId` in
   `build.gradle.kts` (iOS) — both are Google sample ids.
2. Replace the **test ad unit ids** in `src@android/AdsAndroid.kt` and `src@ios/AdsIos.kt`.
3. Add the full `SKAdNetworkItems` list to the iOS `Info.plist` patch in `build.gradle.kts`.
4. Consider frequency-capping interstitials so they are not shown on every single round.
