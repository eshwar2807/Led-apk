plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Where the app looks for updates. The release workflow passes the deployed fly.io app's URL;
// local builds default to the same name as update-server/fly.toml.
val updateUrl = (findProperty("reelplayUpdateUrl") as String?) ?: "https://reelplay-updates.fly.dev/reelplay/"

// Releases published for auto-update must all carry the same signature, or Android refuses
// to install one over another. CI provides the release key through these variables; without
// them, builds fall back to this machine's debug key (fine for trying things out locally).
val releaseStoreFile: String? = System.getenv("RELEASE_STORE_FILE")

android {
    namespace = "com.eshwar.reelplay"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.eshwar.reelplay"
        minSdk = 26
        targetSdk = 37
        versionCode = 10
        versionName = "1.9"
        buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseStoreFile != null) "release" else "debug")
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

    packaging {
        // libtorrent is ~16 MB per ABI uncompressed; store it compressed so the APK stays
        // small to download, at the cost of extracting it once at install time.
        jniLibs.useLegacyPackaging = true
    }
}

// libtorrent's native library installs its own signal handlers, which replaces the desktop
// JVM's SIGSEGV handler (the JVM relies on it internally), so the libtorrent tests crashed the
// test JVM at random. libjsig chains the handlers. Android's runtime does this by itself.
tasks.withType<Test>().configureEach {
    val jsig = javaLauncher.map { it.metadata.installationPath.file("lib/libjsig.so").asFile }
    doFirst {
        val lib = jsig.get()
        if (lib.exists()) environment("LD_PRELOAD", lib.absolutePath)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.exifinterface)
    // Background update checks.
    implementation(libs.androidx.work.runtime)

    // BitTorrent engine for streaming torrents and magnet links. Native builds for phones
    // only (arm64 and 32-bit arm); add the x86_64 artifact to run it on an emulator.
    implementation(libs.libtorrent4j)
    implementation(libs.libtorrent4j.android.arm64)
    implementation(libs.libtorrent4j.android.arm)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // JVM tests stream a real torrent between two local libtorrent sessions.
    testImplementation(libs.junit)
    testImplementation(libs.libtorrent4j.linux)
    // Android's org.json is a stub on the JVM; the real one for the registry round-trip test.
    testImplementation(libs.org.json)
}
