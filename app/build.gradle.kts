import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val appVersionCode = providers.gradleProperty("appVersionCode")
    .map(String::toInt)
    .orElse(1)
val appVersionName = providers.gradleProperty("appVersionName")
    .orElse("0.0.1")
val impulseReportSupabaseUrl =
    providers.gradleProperty("impulseReportSupabaseUrl")
        .orElse("https://eymyarugcfcwkezqjiba.supabase.co")
val impulseReportSupabasePublishableKey =
    providers.gradleProperty("impulseReportSupabasePublishableKey")
        .orElse("sb_publishable_2fp6Nav76kCNXklyKtCnYA_ZiM3z5l8")
val impulseReportFunctionName =
    providers.gradleProperty("impulseReportFunctionName").orElse("impulse-report-problem")
val impulseReportDiagnosticsEnabled =
    providers.gradleProperty("impulseReportDiagnosticsEnabled")
        .map(String::toBoolean)
        .orElse(
            appVersionName.map { versionName ->
                versionName.contains("preview", ignoreCase = true)
            }
        )
// Anonymous fleet telemetry (PostHog EU). Empty API key disables the feature at runtime.
val posthogApiKey = providers.gradleProperty("posthogApiKey").orElse("")
val posthogHost =
    providers.gradleProperty("posthogHost").orElse("https://eu.i.posthog.com")
val telemetryVinSalt =
    providers.gradleProperty("telemetryVinSalt").orElse("impulse-fleet-v1")

fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "br.com.redesurftank.havalshisuku"
    compileSdk = 36
    ndkVersion = "27.1.12297006"

    externalNativeBuild {
        cmake {
            path = file("../scripts/carplay-native-analysis/native/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        applicationId = "br.com.redesurftank.havalshisuku"
        minSdk = 28
        externalNativeBuild {
            cmake {
                abiFilters += listOf("arm64-v8a")
                targets += listOf("carplay_video_bridge")
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
        //noinspection ExpiredTargetSdkVersion
        targetSdk = 28
        versionCode = appVersionCode.get()
        versionName = appVersionName.get()
        // HUD renders on an emulator (androidTest/HudRenderTest): the car is not needed to see a frame.
        testInstrumentationRunner = "br.com.redesurftank.havalshisuku.hud.HudRenderRunner"
        buildConfigField("boolean", "EMBED_FRIDA_TOOLS", "true")
        buildConfigField(
            "String",
            "IMPULSE_REPORT_SUPABASE_URL",
            buildConfigString(impulseReportSupabaseUrl.get())
        )
        buildConfigField(
            "String",
            "IMPULSE_REPORT_SUPABASE_PUBLISHABLE_KEY",
            buildConfigString(impulseReportSupabasePublishableKey.get())
        )
        buildConfigField(
            "String",
            "IMPULSE_REPORT_FUNCTION_NAME",
            buildConfigString(impulseReportFunctionName.get())
        )
        buildConfigField(
            "boolean",
            "IMPULSE_REPORT_DIAGNOSTICS_ENABLED",
            impulseReportDiagnosticsEnabled.get().toString()
        )
        buildConfigField("String", "POSTHOG_API_KEY", buildConfigString(posthogApiKey.get()))
        buildConfigField("String", "POSTHOG_HOST", buildConfigString(posthogHost.get()))
        buildConfigField(
            "String",
            "TELEMETRY_VIN_SALT",
            buildConfigString(telemetryVinSalt.get())
        )
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
    }

    buildTypes {
        named("debug") {
            buildConfigField("boolean", "EMBED_FRIDA_TOOLS", "true")
            buildConfigField("boolean", "IMPULSE_REPORT_DIAGNOSTICS_ENABLED", "true")
            buildConfigField("boolean", "SIMULATOR_MODE", "false")
        }
        create("leanDebug") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "EMBED_FRIDA_TOOLS", "false")
            buildConfigField("boolean", "IMPULSE_REPORT_DIAGNOSTICS_ENABLED", "true")
            buildConfigField("boolean", "SIMULATOR_MODE", "false")
        }
        create("simulator") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "EMBED_FRIDA_TOOLS", "false")
            buildConfigField("boolean", "IMPULSE_REPORT_DIAGNOSTICS_ENABLED", "true")
            buildConfigField("boolean", "SIMULATOR_MODE", "true")
        }
        named("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources  = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "EMBED_FRIDA_TOOLS", "true")
            buildConfigField("boolean", "SIMULATOR_MODE", "false")
        }
    }

    sourceSets {
        getByName("debug") {
            java.srcDir("src/internalDebug/java")
            assets.srcDir(layout.buildDirectory.dir("generated/carplayBridgeAssets/debug").get().asFile)
        }
        getByName("release") {
            assets.srcDir(layout.buildDirectory.dir("generated/carplayBridgeAssets/release").get().asFile)
        }
        getByName("leanDebug") {
            java.srcDir("src/internalDebug/java")
        }
        getByName("simulator") {
            java.srcDir("src/simulator/java")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }

    packaging {
        jniLibs.excludes += "**/libcarplay_video_bridge.so"
    }
}

kotlin {
    jvmToolchain(11)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.core.ktx)
    implementation(libs.material)
    implementation(libs.shizuku)
    implementation(libs.shizuku.provider)
    implementation(libs.hiddenapibypass)
    implementation(libs.commons.net)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.service)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.gson)
    implementation(libs.coil.compose)
    implementation(libs.material.icons.extended)
    // TLS 1.3 for the radar data downloads only (RadarUpdater): Android 9 stops at TLS 1.2 and
    // overpass-api.de refuses it (checked 2026-10-17). Not installed as the global provider.
    implementation("org.conscrypt:conscrypt-android:2.5.2")
    annotationProcessor(libs.annotation.processor)
    compileOnly(libs.annotation)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20231013")
    androidTestImplementation(libs.junit)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // Older Espresso calls InputManager.getInstance, gone on Android 16 (the emulator image).
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
}

val buildCarPlayColdGatePatch by tasks.registering(Exec::class) {
    group = "verification"
    description = "Builds the staged, default-off CarPlay cold-start gate service patch"
    val baseline = layout.projectDirectory.file("src/main/assets/carplay_patches/TsCarPlayService.apk")
    val patchScript = rootProject.layout.projectDirectory.file("scripts/carplay-patches/patch_cold_start_gate.py")
    val buildScript = rootProject.layout.projectDirectory.file("scripts/carplay-patches/build_cold_gate_service_patch.sh")
    val output = layout.buildDirectory.file("outputs/carplay-patches/TsCarPlayService-cold-gate.apk")
    val work = layout.buildDirectory.dir("intermediates/carplay-cold-gate")

    inputs.files(baseline, patchScript, buildScript)
    outputs.file(output)
    dependsOn("testCarPlayColdTools")
    commandLine(
        "bash",
        buildScript.asFile.absolutePath,
        baseline.asFile.absolutePath,
        output.get().asFile.absolutePath,
        work.get().asFile.absolutePath
    )
}

val testCarPlayColdTools by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs deterministic cold-gate and launcher tooling tests"
    workingDir(rootProject.layout.projectDirectory)
    commandLine(
        "python3",
        "-m",
        "unittest",
        "scripts/carplay-patches/test_patch_cold_start_gate.py",
        "scripts/carplay-native-analysis/test_make_cold_launcher.py"
    )
}

// The persistent bridge is compiled only on the authorized build host. It is
// packaged as an OEM-side asset and is never loaded as an Impulse JNI library.
val carPlayAgentDirectory = rootProject.layout.projectDirectory.dir("scripts/carplay-native-analysis/agent")
val installCarPlayAgentDependencies by tasks.registering(Exec::class) {
    inputs.files(carPlayAgentDirectory.file("package.json"), carPlayAgentDirectory.file("package-lock.json"))
    outputs.dir(carPlayAgentDirectory.dir("node_modules"))
    commandLine(
        "npm", "ci", "--prefix", carPlayAgentDirectory.asFile.absolutePath,
        "--ignore-scripts", "--no-audit", "--no-fund"
    )
}

// AGP names Release's native configuration RelWithDebInfo (and Debug's Debug).
mapOf("Debug" to "Debug", "Release" to "RelWithDebInfo").forEach { (buildType, nativeConfig) ->
    val lower = buildType.lowercase()
    val generated = layout.buildDirectory.dir("generated/carplayBridgeAssets/$lower")
    val generate = tasks.register<Exec>("generateCarPlayBridgeAssets$buildType") {
        dependsOn(installCarPlayAgentDependencies)
        dependsOn(tasks.matching { it.name.startsWith("buildCMake$nativeConfig[") })
        inputs.files(rootProject.fileTree("scripts/carplay-native-analysis") {
            include("*.py", "*.js", "testdata/*.json", "agent/package.json", "agent/package-lock.json")
        })
        inputs.file(layout.projectDirectory.file("src/main/res/raw/fridainject"))
        inputs.files(fileTree(layout.projectDirectory.dir("src/main/java/br/com/redesurftank/havalshisuku")) {
            include(
                "managers/CarPlayBridgeBootstrapManager.kt",
                "managers/CarPlayBridgeQuiescence.kt",
                "managers/CarPlayBluetoothQuiescence.kt",
                "managers/CarPlayQuiescenceReading.kt",
                "experimental/CarPlayBootstrapRoot.kt",
                "experimental/CarPlayBridgeBootstrapPolicy.kt",
                "experimental/CarPlaySafetyPublicationGate.kt",
                "experimental/CarPlayResidentProof.kt",
                "experimental/CarPlayProjectionVisibilityProbe.kt"
            )
        })
        inputs.files(fileTree(layout.buildDirectory.dir("intermediates/cxx/$nativeConfig")) {
            include("**/obj/arm64-v8a/libcarplay_video_bridge.so")
        })
        inputs.property("versionCode", appVersionCode)
        outputs.dir(generated)
        workingDir(rootProject.layout.projectDirectory)
        doFirst {
            val candidates = fileTree(layout.buildDirectory.dir("intermediates/cxx/$nativeConfig")) {
                include("**/obj/arm64-v8a/libcarplay_video_bridge.so")
            }.files.sortedBy { it.absolutePath }
            check(candidates.size == 1) {
                "Expected one authorized-host $buildType ARM64 bridge, found ${candidates.size}"
            }
            generated.get().dir("carplay_bridge").asFile.deleteRecursively()
            commandLine(
                "python3", "scripts/carplay-native-analysis/build_persistent_carplay_assets.py",
                "--native-lib", candidates.single().absolutePath,
                "--output", generated.get().asFile.absolutePath,
                "--version-code", appVersionCode.get().toString()
            )
        }
    }
    tasks.matching {
        it.name == "merge${buildType}Assets" ||
            (it.name.contains(buildType) && it.name.contains("lint", ignoreCase = true))
    }.configureEach { dependsOn(generate) }
}
