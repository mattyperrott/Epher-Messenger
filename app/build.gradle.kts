import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun configuredRoomRelayUrl(): String {
    val explicit = System.getenv("EPHER_ROOM_RELAY_URL")?.trim().orEmpty()
    return explicit
}

fun configuredMixnetRelayUrl(): String {
    val explicit = System.getenv("EPHER_MIXNET_RELAY_URL")?.trim().orEmpty()
    return explicit
}

fun configuredMixnetProviderUrl(mixnetRelayUrl: String): String {
    val explicit = System.getenv("EPHER_MIXNET_PROVIDER_URL")?.trim().orEmpty()
    if (explicit.isNotEmpty()) return explicit
    if (mixnetRelayUrl.isBlank()) return ""

    return runCatching {
        val relayUri = URI(mixnetRelayUrl)
        val scheme = if (relayUri.scheme.equals("wss", ignoreCase = true)) "https" else "http"
        val host = relayUri.host ?: return ""
        val providerPort = if (relayUri.port > 0) relayUri.port + 1 else 9798
        "$scheme://$host:$providerPort/provider"
    }.getOrDefault("")
}

fun configuredHyperswarmBootstrapNodes(): String {
    return System.getenv("EPHER_HYPERSWARM_BOOTSTRAP_NODES")?.trim().orEmpty()
}

fun configuredHyperswarmRelayPublicKeys(): String {
    return System.getenv("EPHER_HYPERSWARM_RELAY_PUBLIC_KEYS")?.trim().orEmpty()
}

fun configuredEnableBareTransport(): Boolean {
    return System.getenv("EPHER_ENABLE_BARE_TRANSPORT")
        ?.trim()
        ?.lowercase()
        ?.let { it == "1" || it == "true" || it == "yes" }
        ?: true
}

fun configuredKeepNetworkingAliveInBackground(): Boolean {
    return System.getenv("EPHER_KEEP_NETWORKING_ALIVE_IN_BACKGROUND")
        ?.trim()
        ?.lowercase()
        ?.let { it == "1" || it == "true" || it == "yes" }
        ?: false
}

fun configuredTargetAbis(): List<String> {
    val explicit = System.getenv("EPHER_ABIS")
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()

    return if (explicit.isNotEmpty()) explicit else listOf("arm64-v8a")
}

fun bareHostForAbi(abi: String): String = when (abi) {
    "arm64-v8a" -> "android-arm64"
    "armeabi-v7a" -> "android-arm"
    "x86" -> "android-ia32"
    "x86_64" -> "android-x64"
    else -> error("Unsupported ABI for Bare host mapping: $abi")
}

val configuredRoomRelayUrl = configuredRoomRelayUrl()
val configuredMixnetRelayUrl = configuredMixnetRelayUrl()
val configuredMixnetProviderUrl = configuredMixnetProviderUrl(configuredMixnetRelayUrl)
val configuredHyperswarmBootstrapNodes = configuredHyperswarmBootstrapNodes()
val configuredHyperswarmRelayPublicKeys = configuredHyperswarmRelayPublicKeys()
val configuredEnableBareTransport = configuredEnableBareTransport()
val configuredKeepNetworkingAliveInBackground = configuredKeepNetworkingAliveInBackground()
val targetAbis = configuredTargetAbis()
val barePackHost = bareHostForAbi(targetAbis.first())
val releaseKeystorePath = System.getenv("EPHER_RELEASE_KEYSTORE_PATH")?.trim().orEmpty()
val releaseKeystorePassword = System.getenv("EPHER_RELEASE_KEYSTORE_PASSWORD")?.trim().orEmpty()
val releaseKeyAlias = System.getenv("EPHER_RELEASE_KEY_ALIAS")?.trim().orEmpty()
val releaseKeyPassword = System.getenv("EPHER_RELEASE_KEY_PASSWORD")?.trim().orEmpty()
val hasReleaseSigningConfig = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it.isNotBlank() }

android {
    namespace = "com.epher.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.epher.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("releaseFromEnv") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "ROOM_RELAY_URL", "\"$configuredRoomRelayUrl\"")
            buildConfigField("String", "MIXNET_RELAY_URL", "\"$configuredMixnetRelayUrl\"")
            buildConfigField("String", "MIXNET_PROVIDER_URL", "\"$configuredMixnetProviderUrl\"")
            buildConfigField("String", "HYPERSWARM_BOOTSTRAP_NODES", "\"$configuredHyperswarmBootstrapNodes\"")
            buildConfigField("String", "HYPERSWARM_RELAY_PUBLIC_KEYS", "\"$configuredHyperswarmRelayPublicKeys\"")
            buildConfigField("boolean", "ENABLE_BARE_TRANSPORT", configuredEnableBareTransport.toString())
            buildConfigField(
                "boolean",
                "KEEP_NETWORKING_ALIVE_IN_BACKGROUND",
                if (configuredMixnetRelayUrl.isNotEmpty() || configuredKeepNetworkingAliveInBackground) "true" else "false",
            )
        }
        create("peer") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".peer"
            versionNameSuffix = "-peer"
            matchingFallbacks += listOf("debug")
            buildConfigField("String", "ROOM_RELAY_URL", "\"$configuredRoomRelayUrl\"")
            buildConfigField("String", "MIXNET_RELAY_URL", "\"$configuredMixnetRelayUrl\"")
            buildConfigField("String", "MIXNET_PROVIDER_URL", "\"$configuredMixnetProviderUrl\"")
            buildConfigField("String", "HYPERSWARM_BOOTSTRAP_NODES", "\"$configuredHyperswarmBootstrapNodes\"")
            buildConfigField("String", "HYPERSWARM_RELAY_PUBLIC_KEYS", "\"$configuredHyperswarmRelayPublicKeys\"")
            buildConfigField("boolean", "ENABLE_BARE_TRANSPORT", configuredEnableBareTransport.toString())
            buildConfigField(
                "boolean",
                "KEEP_NETWORKING_ALIVE_IN_BACKGROUND",
                if (configuredMixnetRelayUrl.isNotEmpty() || configuredKeepNetworkingAliveInBackground) "true" else "false",
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Optimize for smaller build output: remove unused resources and code
            isDebuggable = false
            signingConfig = if (hasReleaseSigningConfig) {
                signingConfigs.getByName("releaseFromEnv")
            } else {
                null
            }
            buildConfigField("String", "ROOM_RELAY_URL", "\"$configuredRoomRelayUrl\"")
            buildConfigField("String", "MIXNET_RELAY_URL", "\"$configuredMixnetRelayUrl\"")
            buildConfigField("String", "MIXNET_PROVIDER_URL", "\"$configuredMixnetProviderUrl\"")
            buildConfigField("String", "HYPERSWARM_BOOTSTRAP_NODES", "\"$configuredHyperswarmBootstrapNodes\"")
            buildConfigField("String", "HYPERSWARM_RELAY_PUBLIC_KEYS", "\"$configuredHyperswarmRelayPublicKeys\"")
            buildConfigField("boolean", "ENABLE_BARE_TRANSPORT", configuredEnableBareTransport.toString())
            buildConfigField(
                "boolean",
                "KEEP_NETWORKING_ALIVE_IN_BACKGROUND",
                configuredKeepNetworkingAliveInBackground.toString(),
            )
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*targetAbis.toTypedArray())
            isUniversalApk = false
        }
    }

    bundle {
        abi {
            // App Bundle automatically generates optimized APKs per ABI.
            // This enables smaller downloads for users (app shrinks ~50% for arm64-v8a).
            enableSplit = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/addons")
        }
        getByName("peer") {
            java.srcDir("src/debug/java")
            kotlin.srcDir("src/debug/java")
            assets.srcDir("src/debug/assets")
        }
    }
}

val runtimeToolsDir = layout.projectDirectory.dir("../runtime/node_modules/.bin")

tasks.register<Exec>("linkBareAddons") {
    workingDir = file("../runtime")
    commandLine(
        "sh",
        "-c",
        "rm -rf ../app/src/main/addons && mkdir -p ../app/src/main/addons && ${runtimeToolsDir.asFile.absolutePath}/bare-link --preset android --out ../app/src/main/addons"
    )
}

tasks.register<Exec>("packBareRuntime") {
    workingDir = file("../runtime")
    commandLine(
        runtimeToolsDir.file("bare-pack").asFile.absolutePath,
        "--host", barePackHost,
        "--linked",
        "--base", ".",
        "--out", "../app/src/main/assets/p2p.runtime.bundle",
        "src/p2p-runtime.js",
    )
}

tasks.register<Exec>("packBareProbeRuntime") {
    workingDir = file("../runtime")
    commandLine(
        runtimeToolsDir.file("bare-pack").asFile.absolutePath,
        "--host", barePackHost,
        "--linked",
        "--base", ".",
        "--out", "../app/src/debug/assets/p2p.probe.runtime.bundle",
        "src/p2p-probe-runtime.js",
    )
}

tasks.named("preBuild").configure {
    dependsOn("linkBareAddons", "packBareRuntime")
}

tasks.matching { it.name == "preDebugBuild" || it.name == "prePeerBuild" }.configureEach {
    dependsOn("packBareProbeRuntime")
}

dependencies {
    // BareKit — local AAR providing the Hyperswarm Bare runtime for Android
    implementation(fileTree(mapOf("dir" to "libs/android", "include" to listOf("*.aar", "*.jar"))))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.google.tink.android)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.squareup.okhttp)
    implementation(libs.androidx.work.runtime.ktx)

    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.tooling)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
}
