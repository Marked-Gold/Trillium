# Triplo — Production Readiness

Tracking the launch of **Triplo** (a KorGE 6 powers-of-three puzzle game) to the Google Play
Store and the Apple App Store, published by **AllMeat Games**.

Legend: ✅ done · ⏳ in progress / waiting · ❌ not started

## Key references

| Item | Value |
|---|---|
| App name | Triplo (renamed from "Trillium") |
| Application / bundle ID | `com.allmeatgames.triplo` |
| Studio | AllMeat Games — allmeatgames.com |
| Marketing / redirect domain | triplo.club |
| Developer-account email | mark@allmeatgames.com |
| App version | 1.1.0 (versionCode 8, iOS build 6) — bump `androidVersionCode` / `iosBuildNumber` in `build.gradle.kts` for every upload |
| Android AdMob app ID | `ca-app-pub-7742910323184344~4789526938` |
| Android interstitial ad unit | `ca-app-pub-7742910323184344/7551421648` |
| iOS AdMob app ID | `ca-app-pub-7742910323184344~4136123498` |
| iOS interstitial ad unit | `ca-app-pub-7742910323184344/7698900922` |
| iOS App Store ID | `6772526976` (used in App Store URL: `apps.apple.com/app/id6772526976`) |
| iOS Apple Developer Team ID | `784936QA8D` (paid Apple Developer Program, individual) |
| Upload keystore | `triplo-upload-key.jks` — gitignored; file + password stored in 1Password; alias `triplo-upload` |

## Toolchain

- KorGE 6.0.0 (latest stable), Kotlin 2.0, JDK 21.
- Android: `compileSdk`/`targetSdk` 36, `minSdk` 23; Google Mobile Ads SDK 25.2.0.
  - GMA 25.x ships Kotlin 2.2 metadata; KorGE pins Kotlin 2.0, so the Android compile passes
    `-Xskip-metadata-version-check` (build-time only, no runtime effect).
- iOS: Google Mobile Ads SDK 13.4.0 + UMP SDK 3.0.0 (Swift Package Manager), bundle-bridged through
  `native/ios/TriploAds.{h,m}`; `preferredIphoneSimulatorVersion = 17` (iOS 26.5 dropped iPhone 8).
- Build the signed release bundle: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew bundleRelease`

---

## Work completed

### Rebrand & Android build — ✅
- ✅ Renamed Trillium → Triplo throughout; application ID → `com.allmeatgames.triplo`.
- ✅ Modernized: API 36, GMA 25.2.0, minSdk 23, app version 1.0.0.
- ✅ Release signing wired in (upload keystore + `keystore.properties`, both gitignored).
- ✅ Signed release AAB builds and verified (`jarsigner` clean).
- ✅ R8-minified release build smoke-tested on a physical device — no crashes.

### Android AdMob — ✅ code complete
- ✅ Real AdMob app ID + interstitial ad unit ID wired in.
- ✅ UMP (User Messaging Platform) consent flow integrated — gathers consent before ad init.
- ✅ Test device registered (Pixel 10) so ads can be tested without invalid-traffic risk.

### Developer accounts & domains — ✅ partial
- ✅ Cloudflare: `triplo.club` + `allmeatgames.com` registered, email routing configured.
- ✅ Google Play Console — enrolled as Individual; $25 paid.
- ✅ AdMob account created; Android app added.

---

## Work remaining

### Accounts — ⏳ / ❌
- ✅ Google Play Console — government photo ID verification cleared.
- ⏳ AdMob account — under review.
- ⏸ AdMob: **Payments** section deferred — full bank/payout setup is gated by the $100 earnings threshold, which the app can only cross after shipping.
- ✅ AdMob: GDPR (IAB TCF) + US-states **Privacy & messaging** consent messages published; linked to both Android and iOS apps.
- ✅ In-app **AD PERMISSIONS** revocation entry point added (pause menu → Settings) so users can re-open the UMP consent form anytime; required for GDPR compliance + store review.
- ✅ Apple Developer Program — enrolled (individual, Team ID `784936QA8D`).

### iOS — ⏳ in progress
- ✅ Xcode 26.5 installed; iOS 26.5 simulator runtime installed; license accepted.
- ✅ iOS simulator build verified — app boots on iOS 26.5 and the UMP consent pre-prompt fires.
- ✅ Bumped Google Mobile Ads SPM 11.13.0 → **13.4.0**.
- ✅ Added UMP (User Messaging Platform) SPM 3.0.0 via the same project.yml patch.
- ✅ Obj-C bridge now drives the UMP consent flow → ATT prompt → `MobileAds.start` end-to-end
  (`requestConsentAndStartAds:completion:` in `native/ios/TriploAds.m`).
- ✅ `NSUserTrackingUsageDescription` added to the patched `Info.plist`.
- ✅ Full `SKAdNetworkItems` list (50 identifiers from Google's 3p list) in the Info.plist patch.
- ✅ Renamed Kotlin `enum class Number` → `Rank` to avoid the `GameMainNumber` Obj-C symbol
  collision with `kotlin.Number` at framework-link time.
- ✅ `native/GoogleMobileAdsBridge.def` now passes `-undefined dynamic_lookup` so the Kotlin/Native
  framework defers `TriploAds` symbol resolution to the app-binary link step.
- ✅ iOS app created in AdMob; real app ID + interstitial ad unit ID wired in.
- ❌ Privacy & messaging UMP consent messages configured in the AdMob console (GDPR + US states).
- ✅ Verified on a physical iPhone (Amy's, via free Personal Team provisioning).
- ✅ iOS signing / provisioning under the paid AllMeat Games team (App Store distribution profile auto-managed by Xcode; `app-Arm64-Release` target pinned to `com.allmeatgames.triplo` via the patch task).
- ✅ App bundle is iPhone-only (`TARGETED_DEVICE_FAMILY = 1`) — runs on iPad in iPhone compatibility mode, so no iPad screenshots / iPad multitasking review needed.

### Store listings — ⏳ partial
- ✅ Privacy policy drafted and live at `triplo.club/privacy` (Cloudflare Worker deployed).
- ✅ iOS 6.9" screenshots captured from the iPhone 17 Pro Max simulator.
- ✅ Android phone screenshots (`screenshot1.png`, `screenshot2.png` — 912×2048).
- ✅ 512×512 icon (`icon-512.png`) + 1024×500 feature graphic (`feature-graphic.png`).
- ✅ Short + full Android store description drafted.
- ✅ Content rating questionnaire (Google) submitted. ❌ Age rating (Apple) — needs App Store Connect.
- ✅ Data safety form (Google) submitted. ❌ App Privacy labels (Apple) — needs App Store Connect.
- ✅ `app-ads.txt` served from `triplo.club/app-ads.txt` (Cloudflare Worker). AdMob will crawl
  this only when the developer-website URL in each store listing points to `triplo.club`.

### triplo.club redirect — ✅ deployed
- ✅ Cloudflare Worker (`cloudflare-worker/`): platform-detecting redirect
  (iOS → App Store, Android → Play Store, desktop → landing page) + `/privacy` + `/age-rating` pages.
- ✅ `APP_STORE_URL` wired to `apps.apple.com/app/id6772526976`. Needs `wrangler deploy` to push live.

### Launch — ⏳ Apple in review, Google closed test running
- ⏳ Google **closed test**: signed AAB uploaded to a closed-testing track with 12+ testers opted in;
  day 1 in progress, 14-day continuous-active clock expected to complete **2026-06-07**.
- ❌ Google production submission & rollout (unblocks 2026-06-07).
- ⏳ Apple App Store: **submitted for review 2026-05-23** (build 3, version 1.0). Review decision
  typically 24–48h. Release setting: confirm on the submission page (auto vs manual).

---

## Notes

- The 14-day Google closed test is in progress; finishes **2026-06-07** assuming 12+ testers
  stay active for the full 14 continuous days. Drops below 12 likely reset the clock — keep
  the tester roster well above 12 to be safe.
- Apple Developer enrollment is the iOS critical path; everything iOS-Store-Connect-related
  (signing, App Privacy labels, age rating, TestFlight, submission, Cloudflare worker
  `APP_STORE_URL`) unblocks the moment verification lands.
- Useful parallel work in the 2026-05-22 → 2026-06-07 window: draft iOS listing copy and
  publish `app-ads.txt` so we can paste/wire them in immediately once accounts unlock.
