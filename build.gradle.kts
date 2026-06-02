import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import korlibs.korge.gradle.*
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.util.Properties

plugins {
    alias(libs.plugins.korge)
}

// =====================================================================================
// AdMob test-mode toggle.
// -------------------------------------------------------------------------------------
// Flip `useTestAdIds` to `true` to swap in Google's universal test ad-unit IDs (which
// always serve a "Test Ad" placeholder) for BOTH iOS and Android. This is the only way
// to verify the ad-show flow before AdMob has approved the publisher account — production
// ad units silently fail with `Account not approved yet` until Google's review completes.
//
// MUST be `false` for any Play Store / App Store upload — shipping with test IDs is an
// AdMob policy violation and bricks future ad serving. A loud warning is printed on every
// gradle run while this is `true` so it can't slip through unnoticed.
// =====================================================================================
val useTestAdIds = false

// Google's universal test ad-unit IDs (https://developers.google.com/admob/ios/test-ads,
// https://developers.google.com/admob/android/test-ads). Production IDs are the AllMeat Games
// publisher's actual app/ad-unit IDs from the AdMob console.
val iosAdMobAppId = if (useTestAdIds) "ca-app-pub-3940256099942544~1458002511"
                    else "ca-app-pub-7742910323184344~4136123498"
val iosInterstitialAdUnit = if (useTestAdIds) "ca-app-pub-3940256099942544/4411468910"
                            else "ca-app-pub-7742910323184344/7698900922"
val androidAdMobAppId = if (useTestAdIds) "ca-app-pub-3940256099942544~3347511713"
                       else "ca-app-pub-7742910323184344~4789526938"
val androidInterstitialAdUnit = if (useTestAdIds) "ca-app-pub-3940256099942544/1033173712"
                                else "ca-app-pub-7742910323184344/7551421648"

if (useTestAdIds) {
    logger.lifecycle("============================================================")
    logger.lifecycle("WARNING: useTestAdIds = true — building with AdMob TEST IDs.")
    logger.lifecycle("         DO NOT upload this build to Play Store / App Store.")
    logger.lifecycle("         Flip useTestAdIds = false in build.gradle.kts to ship.")
    logger.lifecycle("============================================================")
}

korge {
    id = "com.allmeatgames.triplo"
    name = "Triplo"
    version = "1.1.0"

    orientation = Orientation.PORTRAIT

    // iOS 26.5 dropped support for the iPhone 8 (KorGE 6.0's default simulator); bump to a
    // current-generation device so `xcrun simctl create` succeeds.
    preferredIphoneSimulatorVersion = 17

    // Apple Developer Program team ID for AllMeat Games (individual enrollment under
    // Mark Robertson Gaskin). Used as DEVELOPMENT_TEAM in the generated Xcode project for all
    // iOS variants. The corresponding distribution certificate / profile is auto-managed by
    // Xcode under this team and is what allows App Store Connect uploads.
    appleDevelopmentTeamId = "784936QA8D"

    // Google Play requires targetSdk 35+ for new apps (and 36+ from Aug 2026), so target 36.
    // minSdk 23 is the floor for Google Mobile Ads SDK 24+.
    androidSdk(compileSdk = 36, minSdk = 23, targetSdk = 36)

// To selectively enable targets
    targetJvm()
    targetJs()
    targetDesktop()
    targetIos()
    targetAndroid()

    // --- AdMob (Google Mobile Ads) ---
    // Required so the ads SDK can fetch ads.
    androidPermission("android.permission.INTERNET")
    // Lets the game play haptic feedback through the system Vibrator (see src@android/HapticsAndroid.kt).
    androidPermission("android.permission.VIBRATE")
    // AdMob application id for the Android app. Switches between production and test IDs
    // based on the `useTestAdIds` flag near the top of this file.
    androidManifestApplicationChunk(
        "<meta-data android:name=\"com.google.android.gms.ads.APPLICATION_ID\" " +
            "android:value=\"$androidAdMobAppId\" />"
    )
}

dependencies {
    add("commonMainApi", project(":deps"))
    // Google Mobile Ads SDK - only needed by the Android target (see src@android/AdsAndroid.kt).
    add("androidMainImplementation", "com.google.android.gms:play-services-ads:25.2.0")
}

// =====================================================================================
// iOS AdMob bridge
// -------------------------------------------------------------------------------------
// The iOS interstitial ads go through an Objective-C bridge (native/ios/TriploAds.{h,m}).
//  * The cinterop below exposes the bridge *header* to Kotlin/Native.
//  * patchIosProject injects the GoogleMobileAds SwiftPM package, the bridge .m source and the
//    required Info.plist keys into KorGE's generated Xcode project (KorGE regenerates it on every
//    build, so the patch re-runs every build).
// Building any iOS target requires a full Xcode install.
// =====================================================================================

// iosAdMobAppId is declared above alongside the `useTestAdIds` toggle.

// iOS App Store build/version. KorGE writes "1.0" / "1" into the generated Info.plist regardless
// of `korge.version`, so we re-stamp these in `patchIosInfoPlist`. App Store Connect requires a
// strictly higher CFBundleVersion than the previous *uploaded* build (not just the previous
// released build). Build 1.0 (4) was rejected (guideline 2.1) because App Review on iPadOS 26.5
// could not find the App Tracking Transparency prompt — it was being requested while the app was
// not yet active, so iOS silently dropped it. Fixed in TriploAds.m by deferring the ATT request
// to the active state. Bump on every new upload.
val iosShortVersion = "1.1.0"
val iosBuildNumber = "6"
val googleMobileAdsSpmUrl = "https://github.com/googleads/swift-package-manager-google-mobile-ads.git"
val googleMobileAdsSpmVersion = "13.4.0"
val googleUmpSpmUrl = "https://github.com/googleads/swift-package-manager-google-user-messaging-platform.git"
val googleUmpSpmVersion = "3.0.0"

// SKAdNetwork identifiers recommended by Google for AdMob's mediation/3p partners. Apple uses
// these for install attribution under iOS 14+ privacy rules. Source:
// https://developers.google.com/admob/ios/3p-skadnetworks
val skAdNetworkIdentifiers = listOf(
    "cstr6suwn9", "4fzdc2evr5", "2fnua5tdw4", "ydx93a7ass", "p78axxw29g",
    "v72qych5uu", "ludvb6z3bs", "cp8zw746q7", "3sh42y64q3", "c6k4g5qg8m",
    "s39g8k73mm", "wg4vff78zm", "3qy4746246", "f38h382jlk", "hs6bdukanm",
    "mlmmfzh3r3", "v4nxqhlyqp", "wzmmz9fp6w", "su67r6k2v3", "yclnxrl5pm",
    "t38b2kh725", "7ug5zh24hu", "gta9lk7p23", "vutu7akeur", "y5ghdn5j9k",
    "v9wttpbfk9", "n38lu8286q", "47vhws6wlr", "kbd757ywx3", "9t245vhmpl",
    "a2p9lx4jpn", "22mmun2rn5", "44jx6755aq", "k674qkevps", "4468km3ulz",
    "2u9pt9hc89", "8s468mfl3y", "klf5c3l5u5", "ppxm28t8ap", "kbmxgpxpgc",
    "uw77j35x4d", "578prtvx9j", "4dzt52r2t5", "tl55sbb4fm", "c3frkrj4fj",
    "e5fvkxwrpn", "8c4e2ghe7u", "3rd42ekr43", "97r2b46745", "3qcr597p9d",
)

// Shown to the user the first time iOS asks for App Tracking Transparency permission.
val iosTrackingUsageDescription =
    "We use this identifier to show you relevant interstitial ads. You can change this any time in Settings."

kotlin {
    for (targetName in listOf("iosArm64", "iosX64", "iosSimulatorArm64")) {
        val target = targets.findByName(targetName) as? KotlinNativeTarget ?: continue
        target.compilations.getByName("main").cinterops.create("GoogleMobileAdsBridge") {
            defFile(project.file("native/GoogleMobileAdsBridge.def"))
            includeDirs(project.file("native/ios"))
        }
    }
}

// KorGE 6.0.0 pins the Kotlin 2.0 compiler, but Google Mobile Ads SDK 25.x ships modules built
// with Kotlin 2.2 metadata. Skip the metadata-version assertion so the 2.0 compiler will consume
// them; the flag only relaxes a version check and changes no generated code.
tasks.matching { it.name == "compileDebugKotlinAndroid" || it.name == "compileReleaseKotlinAndroid" }
    .configureEach {
        (this as org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>)
            .compilerOptions.freeCompilerArgs.add("-Xskip-metadata-version-check")
    }

// Generated commonMain source carrying the chosen AdMob ad-unit IDs. Both AdsIos.kt and
// AdsAndroid.kt read AdConfig at runtime so flipping `useTestAdIds` above rebuilds them with the
// matching unit ids. The task's input properties guarantee Gradle re-runs it (and the downstream
// Kotlin compile) whenever the flag or the ids change.
val adConfigOutDir = layout.buildDirectory.dir("generated/ad-config/kotlin")
val generateAdConfig = tasks.register("generateAdConfig") {
    inputs.property("useTestAdIds", useTestAdIds)
    inputs.property("iosInterstitialAdUnit", iosInterstitialAdUnit)
    inputs.property("androidInterstitialAdUnit", androidInterstitialAdUnit)
    outputs.dir(adConfigOutDir)
    doLast {
        val outDir = adConfigOutDir.get().asFile
        outDir.mkdirs()
        File(outDir, "AdConfig.kt").writeText(
            """
            // GENERATED by build.gradle.kts (generateAdConfig task). Do not edit by hand —
            // flip the `useTestAdIds` flag in build.gradle.kts and rebuild.
            object AdConfig {
                const val USE_TEST_ADS = $useTestAdIds
                const val IOS_INTERSTITIAL_AD_UNIT = "$iosInterstitialAdUnit"
                const val ANDROID_INTERSTITIAL_AD_UNIT = "$androidInterstitialAdUnit"
            }
            """.trimIndent()
        )
    }
}

kotlin.sourceSets.named("commonMain") {
    kotlin.srcDir(generateAdConfig)
}

/** Inserts the AdMob / ATT / SKAdNetwork keys KorGE's generated iOS Info.plist is missing, and
 *  rewrites the orientation arrays to portrait-only (KorGE writes all three/four orientations
 *  regardless of `orientation = Orientation.PORTRAIT`). */
fun patchIosInfoPlist(plist: File) {
    if (!plist.exists()) return
    var text = plist.readText()

    // AdMob / ATT / SKAdNetwork keys are inserted once - skip if already present.
    if (!text.contains("GADApplicationIdentifier")) {
        val additions = buildString {
            append("\t<key>GADApplicationIdentifier</key>\n")
            append("\t<string>").append(iosAdMobAppId).append("</string>\n")
            append("\t<key>NSUserTrackingUsageDescription</key>\n")
            append("\t<string>").append(iosTrackingUsageDescription).append("</string>\n")
            // Opt out of iPad multitasking (Slide Over / Split View / Stage Manager). Without
            // this, App Store Connect upload validation (error 90474) requires the bundle to
            // declare all four orientations in UISupportedInterfaceOrientations, which would
            // break the intentional portrait lock for a one-handed merge puzzle. The app still
            // runs on iPad — it just opens fullscreen rather than in a resizable window.
            append("\t<key>UIRequiresFullScreen</key>\n")
            append("\t<true/>\n")
            append("\t<key>SKAdNetworkItems</key>\n")
            append("\t<array>\n")
            for (id in skAdNetworkIdentifiers) {
                append("\t\t<dict>\n")
                append("\t\t\t<key>SKAdNetworkIdentifier</key>\n")
                append("\t\t\t<string>").append(id).append(".skadnetwork</string>\n")
                append("\t\t</dict>\n")
            }
            append("\t</array>\n")
        }
        text = text.replaceFirst("</dict>", additions + "</dict>")
    }

    // Re-stamp the build / short version from the constants above. KorGE writes hardcoded
    // "1.0" / "1" into the plist regardless of `korge.version`; without this patch every iOS
    // upload would ship with the same CFBundleVersion and App Store Connect would reject it.
    text = text.replace(
        Regex("<key>CFBundleShortVersionString</key>\\s*<string>[^<]*</string>")
    ) {
        "<key>CFBundleShortVersionString</key>\n\t<string>$iosShortVersion</string>"
    }
    text = text.replace(
        Regex("<key>CFBundleVersion</key>\\s*<string>[^<]*</string>")
    ) {
        "<key>CFBundleVersion</key>\n\t<string>$iosBuildNumber</string>"
    }

    // Lock both iPhone and iPad to portrait. Re-running idempotently because the right-hand side
    // already collapses to a single Portrait entry, so re-applying the regex is a no-op.
    text = text.replace(
        Regex("<key>UISupportedInterfaceOrientations(~ipad)?</key>\\s*<array>[\\s\\S]*?</array>")
    ) { match ->
        val suffix = match.groupValues[1]
        "<key>UISupportedInterfaceOrientations$suffix</key>\n" +
            "\t<array>\n" +
            "\t\t<string>UIInterfaceOrientationPortrait</string>\n" +
            "\t</array>"
    }

    plist.writeText(text)
}

/** Injects the GoogleMobileAds + UMP SwiftPM packages and the bridge source into the generated project.yml. */
fun patchIosProjectYml(projectYml: File) {
    if (!projectYml.exists()) return
    val lines = projectYml.readText().lines()
    if (lines.any { it.contains("GoogleMobileAds") }) return
    var currentTarget: String? = null
    val out = StringBuilder()
    for (line in lines) {
        // Track which top-level target block we're inside so target-specific patches can be
        // gated on the target name. Targets in KorGE's generated project.yml are indented by
        // exactly two spaces under `targets:` and have names like `app-Arm64-Release`.
        Regex("^  (app-[A-Za-z0-9_-]+):\\s*$").matchEntire(line)?.let {
            currentTarget = it.groupValues[1]
        }

        out.append(line).append('\n')
        when {
            // Top-level SwiftPM package declarations, right after the project name. Both packages
            // expose a single product whose name matches the package name, so xcodegen's
            // `- package: <name>` shorthand resolves to the correct library.
            line.trim() == "name: app" -> {
                out.append("packages:\n")
                out.append("  GoogleMobileAds:\n")
                out.append("    url: ").append(googleMobileAdsSpmUrl).append('\n')
                out.append("    from: \"").append(googleMobileAdsSpmVersion).append("\"\n")
                out.append("  GoogleUserMessagingPlatform:\n")
                out.append("    url: ").append(googleUmpSpmUrl).append('\n')
                out.append("    from: \"").append(googleUmpSpmVersion).append("\"\n")
            }
            // Add the Objective-C bridge sources to every app target.
            Regex("^(\\s*)- app\\s*$").matches(line) -> {
                val indent = line.substringBefore("- app")
                out.append(indent).append("- ../../../native/ios\n")
            }
            // Post-link sanity check on the archived target. Apple rejected build 1.0 (2) for
            // crash-on-launch with `symbol not found in flat namespace '_OBJC_CLASS_$_TriploAds'`
            // — the GameMain framework references TriploAds via `-undefined dynamic_lookup`, but
            // Xcode's incremental cache produced a main binary that did not contain TriploAds.m's
            // compiled output. Fail the build loudly if that ever happens again instead of
            // silently shipping a guaranteed-crash binary.
            Regex("^  app-Arm64-Release:\\s*$").matches(line) -> {
                out.append("    postBuildScripts:\n")
                out.append("      - name: Verify TriploAds linked\n")
                out.append("        basedOnDependencyAnalysis: false\n")
                out.append("        script: |\n")
                out.append("          BIN=\"\$TARGET_BUILD_DIR/\$EXECUTABLE_PATH\"\n")
                // Match capital-S in `nm` output: the class must be EXPORTED (visible in the
                // dynamic export table), not merely present as a private_extern symbol —
                // otherwise dyld's flat-namespace lookup for the symbol from GameMain.framework
                // still fails. The visibility("default") attribute on @interface TriploAds
                // forces this; the check guards against future regressions of either the
                // attribute or the GCC_SYMBOLS_PRIVATE_EXTERN setting.
                out.append("          if ! nm \"\$BIN\" | grep -q 'S _OBJC_CLASS_\$_TriploAds'; then\n")
                out.append("            echo \"error: _OBJC_CLASS_\$_TriploAds is not an exported symbol in \$BIN — either TriploAds.m did not link, or the class is hidden (check __attribute__((visibility(\\\"default\\\"))) on @interface TriploAds). The app will crash on launch with 'symbol not found in flat namespace'.\" >&2\n")
                out.append("            exit 1\n")
                out.append("          fi\n")
            }
            // Link the GoogleMobileAds and UMP packages into every app target.
            line.trimStart().startsWith("- framework:") -> {
                val indent = line.substringBefore("- framework:")
                out.append(indent).append("- package: GoogleMobileAds\n")
                out.append(indent).append("- package: GoogleUserMessagingPlatform\n")
            }
            // xcodegen's `bundleIdPrefix` + target name would otherwise produce
            // `com.allmeatgames.triplo.app-Arm64-Release`, which is not a registered App ID and
            // App Store Connect will refuse to accept. Override the bundle ID on the device-Release
            // target only — that's the one that gets archived and uploaded. The other variants
            // (debug/simulator/x64) keep their auto-suffixed IDs, which is useful for local testing
            // (multiple variants can be installed on the same device side-by-side).
            currentTarget == "app-Arm64-Release" && line.trim().startsWith("DEVELOPMENT_TEAM:") -> {
                val indent = line.substringBefore("DEVELOPMENT_TEAM:")
                out.append(indent).append("PRODUCT_BUNDLE_IDENTIFIER: com.allmeatgames.triplo\n")
                // Force the archived binary to iPhone-only (TARGETED_DEVICE_FAMILY = 1). xcodegen
                // bakes "1,2" into every target's XCBuildConfiguration regardless of the
                // project-level setting below, so the project-level patch alone is overridden.
                // Setting it on the target wins. iPhone-only avoids App Store Connect demanding
                // 13" iPad screenshots and pulling the app into iPad-specific review scrutiny
                // (Stage Manager, Split View, iPad orientation handling); iPad users still get
                // the app via iPhone compatibility mode.
                out.append(indent).append("TARGETED_DEVICE_FAMILY: \"1\"\n")
                // STRIP_STYLE = non-global is load-bearing for the App Store build. xcodegen
                // defaults to "all" for app targets, which causes `strip` during the archive's
                // Install phase to wipe the entire dyld export trie (3000+ entries → just
                // `__mh_execute_header`). That removes _OBJC_CLASS_$_TriploAds from the export
                // table, and GameMain.framework's `-undefined dynamic_lookup` reference fails at
                // launch with the exact same `symbol not found in flat namespace` crash that
                // got builds 2 and 3 rejected. "non-global" preserves global/exported symbols
                // through strip. Note: the postBuildScripts verify check below runs BEFORE
                // strip (Xcode runs strip during Install/Archive finalization, after all build
                // phases), so the verify can't catch this regression — STRIP_STYLE on the
                // archived target is the only durable defense.
                out.append(indent).append("STRIP_STYLE: non-global\n")
            }
            // Every OTHER app target (debug / simulator / x64) also gets iPhone-only. xcodegen
            // bakes "1,2" into each target's XCBuildConfiguration, overriding the project-level
            // setting below, so without this the on-device debug build installs as a universal
            // (iPad-native) app and renders full iPad size instead of iPhone-compat. This branch
            // sits after the Release branch above, so `when` (first-match-wins) only reaches it for
            // non-Release app targets — which is why the bundle-ID/strip patches stay Release-only.
            currentTarget != null && currentTarget!!.startsWith("app-") &&
                line.trim().startsWith("DEVELOPMENT_TEAM:") -> {
                val indent = line.substringBefore("DEVELOPMENT_TEAM:")
                out.append(indent).append("TARGETED_DEVICE_FAMILY: \"1\"\n")
            }
            // Project-level TARGETED_DEVICE_FAMILY = 1 (kept as belt-and-suspenders; the target
            // overrides above are the load-bearing ones).
            currentTarget == null && line.trim().startsWith("DEVELOPMENT_TEAM:") -> {
                val indent = line.substringBefore("DEVELOPMENT_TEAM:")
                out.append(indent).append("TARGETED_DEVICE_FAMILY: \"1\"\n")
            }
        }
    }
    projectYml.writeText(out.toString())
}

/** Locates the xcodegen executable KorGE installed (or one on PATH). */
fun findXcodeGen(): String {
    val home = File(System.getProperty("user.home"))
    val candidates = (File(home, ".korge").listFiles { f -> f.name.startsWith("XcodeGen-") } ?: emptyArray())
        .flatMap {
            listOf(
                File(it, ".build/release/xcodegen"),
                File(it, ".build/apple/Products/Release/xcodegen"),
            )
        } + File("/usr/local/bin/xcodegen") + File("/opt/homebrew/bin/xcodegen")
    return candidates.firstOrNull { it.exists() }?.absolutePath ?: "xcodegen"
}

// KorGE's prepareKotlinNativeIosProject task generates project.yml + Info.plist and runs xcodegen.
// We append a step that patches them for AdMob/ATT/SKAdNetwork + portrait-lock and re-runs
// xcodegen so the .xcodeproj picks it up. The `upToDateWhen { false }` forces the task to run
// every build; otherwise gradle skips it when it thinks nothing changed, which also skips our
// doLast and leaves a stale Info.plist in the built .app bundle.
tasks.matching { it.name == "prepareKotlinNativeIosProject" }.configureEach {
    outputs.upToDateWhen { false }
    doLast {
        val iosDir = layout.buildDirectory.dir("platforms/ios").get().asFile
        patchIosInfoPlist(File(iosDir, "app/Info.plist"))
        patchIosProjectYml(File(iosDir, "project.yml"))
        exec {
            workingDir = iosDir
            commandLine(findXcodeGen())
        }
    }
}

// =====================================================================================
// Android adaptive launcher icon
// -------------------------------------------------------------------------------------
// KorGE 6 emits only a legacy bitmap launcher icon (@mipmap/icon). On Android 8+ the
// launcher wraps a legacy icon in a shrunken white circle. android/res/ adds a proper
// <adaptive-icon> (mipmap-anydpi-v26/icon.xml) that overrides @mipmap/icon for API 26+,
// so the artwork fills the launcher mask edge-to-edge. We register it as an extra res
// source dir; KorGE's generated androires (set at configuration time) is left untouched.
// =====================================================================================
project.afterEvaluate {
    val android = extensions.findByName("android") as? com.android.build.gradle.BaseExtension
    android?.sourceSets?.getByName("main")?.res?.srcDir(file("android/res"))
}

// Release signing (upload key). Configured when the Android application plugin is applied — early
// enough for AGP to read it (an afterEvaluate hook runs too late). keystore.properties and the
// .jks are gitignored and backed up in 1Password; without the file the release build is left
// unsigned so non-release tasks still work on machines without the key.
pluginManager.withPlugin("com.android.application") {
    val keystoreProps = file("keystore.properties")
    if (!keystoreProps.exists()) return@withPlugin
    val android = extensions.getByName("android") as com.android.build.gradle.BaseExtension
    val props = Properties().apply { keystoreProps.inputStream().use { load(it) } }
    val release = android.signingConfigs.maybeCreate("release")
    release.storeFile = file(props.getProperty("storeFile"))
    release.storePassword = props.getProperty("storePassword")
    release.keyAlias = props.getProperty("keyAlias")
    release.keyPassword = props.getProperty("keyPassword")
    android.buildTypes.getByName("release").signingConfig = release
}

// Android versionCode. Every Google Play upload (across ALL tracks — internal, closed,
// production) must use a strictly higher versionCode than the last, so bump this on each
// release. KorGE sets versionName from korge.version above but leaves versionCode alone; the
// AGP variant API sets it here, which binds late enough to be authoritative.
val androidVersionCode = 8

pluginManager.withPlugin("com.android.application") {
    extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        .onVariants { variant ->
            variant.outputs.forEach { it.versionCode.set(androidVersionCode) }
        }
}
