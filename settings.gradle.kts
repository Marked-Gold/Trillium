pluginManagement {
    repositories { mavenLocal(); mavenCentral(); google(); gradlePluginPortal() }
    // D8/R8 dexer override. KorGE 6.0 applies AGP 8.2.2, whose bundled D8 mis-dexes
    // play-services-ads 25.x's synchronized/monitor bytecode: the app crashes on launch during
    // ad init with `VerifyError: ... expected to be within a catch-all for an instruction where a
    // monitor is held` (in com.google.android.gms.internal.ads.*). This hits BOTH debug and
    // release — KorGE's release build does not run R8 minification, so release uses the same plain
    // D8 dex path as debug. Whether a device actually crashes depends on its ART verifier
    // strictness, so some users hit it while other devices ran fine. The Gradle build SUCCEEDS
    // regardless (runtime-only dex bug), so verify on-device, not just by building.
    //
    // AGP ships D8/R8 inside com.android.tools:r8; forcing a newer build onto the plugin classpath
    // here upgrades the dexer AGP actually uses, per https://r8.googlesource.com/r8 ("Overriding
    // the R8 version"). This keeps the current play-services-ads 25.x. Verified on-device (debug +
    // signed release) with R8 8.7.18. Remove once KorGE moves to an AGP whose bundled D8 already
    // dexes 25.x correctly. NOTE: the override must live here in pluginManagement.buildscript — an
    // override in the root build.gradle.kts buildscript is not on AGP's dexer classpath and has no
    // effect.
    buildscript {
        repositories {
            mavenCentral()
            google()
            maven { url = uri("https://storage.googleapis.com/r8-releases/raw") }
        }
        dependencies {
            classpath("com.android.tools:r8:8.7.18")
        }
    }
}

buildscript {
    val libsTomlFile = File(this.sourceFile?.parentFile, "gradle/libs.versions.toml").readText()
    var plugins = false
    var version = ""
    for (line in libsTomlFile.lines().map { it.trim() }) {
        if (line.startsWith("#")) continue
        if (line.startsWith("[plugins]")) plugins = true
        if (plugins && line.startsWith("korge") && Regex("^korge\\s*=.*").containsMatchIn(line)) version = Regex("version\\s*=\\s*\"(.*?)\"").find(line)?.groupValues?.get(1) ?: error("Can't find korge version")
    }
    if (version.isEmpty()) error("Can't find korge version in $libsTomlFile")

    repositories { mavenLocal(); mavenCentral(); google(); gradlePluginPortal() }

    dependencies {
        classpath("com.soywiz.korge.settings:com.soywiz.korge.settings.gradle.plugin:$version")
    }
}

apply(plugin = "com.soywiz.korge.settings")

rootProject.name = "Triplo"
