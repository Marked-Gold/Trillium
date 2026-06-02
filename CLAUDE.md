# CLAUDE.md

Project notes for working on **Triplo** (a KorGE 6.0 game). See `README.md` for
the full overview, build commands, and the AdMob ads setup.

## Build essentials

- KorGE 6 needs **JDK 21**; it is not on PATH, so prefix Gradle calls:
  `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew <task>`
- Android `adb` is not on PATH either — full path:
  `/opt/homebrew/share/android-commandlinetools/platform-tools/adb`
- Android application id (package): `com.allmeatgames.triplo`

## Debugging a crash on a connected Android device

When the app crashes on the phone, pull the logs with `adb`:

```bash
ADB=/opt/homebrew/share/android-commandlinetools/platform-tools/adb

# Confirm the device is connected and authorized
$ADB devices -l

# Dump the dedicated crash buffer (Kotlin/Java stack traces land here)
$ADB logcat -b crash -d -t 200

# Or filter the full log for this app / KorGE
$ADB logcat -d -t 400 | grep -iE 'FATAL|AndroidRuntime|com.allmeatgames.triplo|korge|triplo'
```

Tips:
- Clear the log buffers first (`$ADB logcat -c`), reproduce the crash, then dump — keeps it short.
- Stack frames like `AnimationKt$animateBomb$1...(Animation.kt:212)` map directly to source files
  in `src/` (debug builds are not obfuscated).
- Reinstall after a fix: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew installAndroidDebug`
- Relaunch without touching the phone:
  `$ADB shell monkey -p com.allmeatgames.triplo -c android.intent.category.LAUNCHER 1`

## Shipping a new version to the App Store + Play Store

There is **no CI / fastlane**: releases are built locally and uploaded by hand. Stores
require strictly-increasing version identifiers on *every* upload (across all tracks), so the
first step is always to bump the four values below. Reference IDs live in
`production-readiness.md`; the store-listing copy is in `ios-listing-copy.md`.

### 1. Bump versions (all four, in `build.gradle.kts`)

| What | Constant | Rule |
|---|---|---|
| Marketing version (both stores) | `korge { version = "x.y.z" }` | Human-facing; patch for fixes, minor for features. |
| iOS marketing version | `iosShortVersion` | Keep equal to `korge.version`. |
| iOS build number | `iosBuildNumber` | **Strictly > the last value ever *uploaded*** to App Store Connect (not just released). |
| Android version code | `androidVersionCode` | **Strictly > the last value ever uploaded** to Play (any track). Integer. |

KorGE hardcodes `1.0`/`1` into the generated iOS `Info.plist`, so `iosShortVersion`/`iosBuildNumber`
are re-stamped by the `patchIosInfoPlist` task — that's why they're separate constants. KorGE also
leaves `versionCode` alone, so `androidVersionCode` is applied via the AGP variant API at the
bottom of the file.

### 2. Pre-flight checks

- **`useTestAdIds` MUST be `false`** (top of `build.gradle.kts`). Shipping test ad IDs is an AdMob
  policy violation and bricks ad serving. A loud warning prints on every gradle run while it's `true`.
- Run the tests: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew jvmTest`
- Commit the version bump (solo project → push straight to `main`).

### 3. Android — build the signed AAB, upload to Play

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew packageAndroidRelease
# → build/outputs/bundle/release/Triplo-release.aab  (R8-minified, signed with the upload key)
```
- The upload key comes from `keystore.properties` + `triplo-upload-key.jks` (gitignored; backed up
  in 1Password, alias `triplo-upload`). Without those files the build is left unsigned.
- Verify before uploading (needs JDK 21 on PATH):
  `jarsigner -verify build/outputs/bundle/release/Triplo-release.aab` → `jar verified`
  (the "self-signed / chain invalid" notes are expected for an upload key — Play re-signs on its side).
- **Upload:** Play Console → Triplo → Test and release → pick the track → **Create new release** →
  upload the `.aab` → add release notes → Review → roll out.
  - **Production is gated** until the 14-day closed test (12+ active testers) completes. Until then,
    upload to the **Closed testing** track only; production rollout unblocks after the clock finishes.

### 4. iOS — build the archive, upload via Xcode Organizer

Requires a full **Xcode** install (`xcode-select -p` → `/Applications/Xcode.app/...`) and being
signed into Xcode with the AllMeat Games Apple ID. There is **no Apple *Distribution* certificate**
in the keychain — only Development certs — because distribution signing is created on the fly by
Xcode's **Distribute App** flow. So: build the archive via CLI, distribute via the Organizer GUI.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"          # the Xcode build phase shells out to ./gradlew

# Regenerate + patch the Xcode project so the new version lands in Info.plist
./gradlew prepareKotlinNativeIosProject
/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' build/platforms/ios/app/Info.plist
/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion'            build/platforms/ios/app/Info.plist   # sanity-check

# Archive the App Store target (slow: release Kotlin/Native arm64 compile)
xcodebuild archive \
  -project build/platforms/ios/app.xcodeproj \
  -scheme app-Arm64-Release -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath build/ios-archive/Triplo-<version>.xcarchive \
  -allowProvisioningUpdates CODE_SIGN_STYLE=Automatic
```

`app-Arm64-Release` is the only target with the App Store patches (bundle id pinned to
`com.allmeatgames.triplo`, `STRIP_STYLE=non-global` so `_OBJC_CLASS_$_TriploAds` survives strip,
`TARGETED_DEVICE_FAMILY=1` iPhone-only). The other targets are for local testing.

**Distribute (GUI):** open the archive — `open build/ios-archive/Triplo-<version>.xcarchive` (or
Xcode → Window → Organizer → Archives) → **Distribute App** → **App Store Connect** → **Upload** →
*Automatically manage signing* (Xcode creates the distribution cert/profile here) → Upload.

**Then in App Store Connect** (appstoreconnect.com → Triplo, App Store ID `6772526976`): create a
new iOS version, select the uploaded build once it finishes processing, fill in **What's New**, and
**Submit for Review**. (App Privacy / age-rating are already set from the first submission.)

### Gotchas baked into `build.gradle.kts` (don't undo these)

- iOS `STRIP_STYLE: non-global` on `app-Arm64-Release` — without it `strip` wipes the dyld export
  trie and the app crashes on launch with `symbol not found in flat namespace '_OBJC_CLASS_$_TriploAds'`.
- A `postBuildScripts` step verifies `_OBJC_CLASS_$_TriploAds` is exported and fails the build if not.
- Android pins `play-services-ads` and overrides the R8/D8 dexer in `settings.gradle.kts` — GMA 25.x
  otherwise crashes on launch with a D8 `VerifyError` under KorGE's AGP.
