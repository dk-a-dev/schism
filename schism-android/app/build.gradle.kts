import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Backend base URL is build/env config, not a user setting: override with the SCHISM_BACKEND_URL
// env var or a `schism.backendUrl` Gradle property; defaults to the Android emulator's host loopback.
val backendUrl: String = System.getenv("SCHISM_BACKEND_URL")
    ?: (project.findProperty("schism.backendUrl") as String?)
    ?: "https://api.schism.182116111.xyz"

// Release signing is read from keystore.properties (gitignored) so secrets never enter git.
// Format:
//   storeFile=/absolute/path/to/keystore.jks
//   storePassword=...
//   keyAlias=...
//   keyPassword=...
// AdMob ids default to Google's published *test* ids so no build ever requests production ads by
// accident; the signed release supplies the real ones via env or Gradle properties.
val admobAppId: String = System.getenv("SCHISM_ADMOB_APP_ID")
    ?: (project.findProperty("schism.admobAppId") as String?)
    ?: "ca-app-pub-3940256099942544~3347511713"
val admobBannerUnitId: String = System.getenv("SCHISM_ADMOB_BANNER_UNIT_ID")
    ?: (project.findProperty("schism.admobBannerUnitId") as String?)
    ?: "ca-app-pub-3940256099942544/9214589741"

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseSigning = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "ai.schism.split"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.schism.split"
        minSdk = 26
        targetSdk = 36
        versionCode = 10300
        versionName = "1.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")
        // AdMob: Google's official *test* ids unless real ones are supplied by the release build
        // (env or -Pschism.admob*). Never ship a debug/CI build that requests production ads.
        manifestPlaceholders["admobAppId"] = admobAppId
        buildConfigField("String", "ADMOB_BANNER_UNIT_ID", "\"$admobBannerUnitId\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            ndk {
                // Play and the standalone release target modern 64-bit Android devices. Debug keeps
                // host x86/x86_64 so emulator development remains unaffected.
                abiFilters += setOf("arm64-v8a")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // Play Billing 9 / Mobile Ads 25 / UMP 4 ship Kotlin 2.3 metadata while this project builds
        // on Kotlin 2.0.21. We only call their Java APIs, so relax the metadata check rather than
        // dragging the whole app onto a new Kotlin. Drop this when the project moves to Kotlin 2.3+.
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    lint {
        checkReleaseBuilds = true
        abortOnError = true
    }
}

gradle.taskGraph.whenReady {
    val createsReleaseArtifact = allTasks.any { task ->
        task.project == project &&
            (task.name.startsWith("bundle") || task.name.startsWith("assemble") || task.name.startsWith("package")) &&
            task.name.endsWith("Release")
    }
    check(!createsReleaseArtifact || hasReleaseSigning) {
        "Release signing is required. Configure the ignored keystore.properties before building a release artifact."
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(project(":ocr-contract"))
    implementation(project(":ocr-impl"))
    implementation(libs.androidx.exifinterface)
    implementation(libs.billing)
    implementation(libs.play.services.ads)
    implementation(libs.user.messaging.platform)
    implementation(libs.mediapipe.tasks.genai)
    implementation(libs.play.services.code.scanner)
    implementation(libs.zxing.core)
    implementation(libs.mlkit.document.scanner)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(project(":parser-core"))
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit.ktx)
}
