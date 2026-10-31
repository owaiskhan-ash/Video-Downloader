import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Signing values live in signing/signing.properties, which unlocks the
// committed signing/teal-release.p12. CI overwrites that file with the
// TEAL_KEYSTORE_B64 secret when one is configured, so moving to a private key
// later needs no change here. See signing/README.md.
val signingProps = Properties()
val signingPropsFile = rootProject.file("signing/signing.properties")
if (signingPropsFile.exists()) {
    signingPropsFile.inputStream().use { signingProps.load(it) }
}

android {
    namespace = "com.teal.downloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.teal.downloader"
        minSdk = 24
        targetSdk = 35
        versionCode = 6
        versionName = "0.4.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Ship every language we actually translate rather than all of AOSP's.
        resourceConfigurations += listOf("en")
    }

    // One APK per ABI, like Seal. The yt-dlp/ffmpeg/python native libraries are
    // the bulk of the download, and a universal APK would carry all of them for
    // a device that can only ever load one.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    signingConfigs {
        create("release") {
            // storeFile is resolved against the project directory, so point it
            // at the copy the workflow may have just replaced.
            storeFile = rootProject.file(
                signingProps.getProperty("storeFile", "signing/teal-release.p12"),
            )
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
            // The keystore is PKCS12; naming the type keeps older JDKs from
            // guessing wrong.
            val configuredStoreType = signingProps.getProperty("storeType")
            if (configuredStoreType != null) storeType = configuredStoreType
        }
    }

    buildTypes {
        debug {
            // Debug is what CI can produce without a keystore, and it is
            // sideloadable, so keep it fully functional rather than crippled.
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Minification stays off on purpose. R8 renames classes, and
            // youtubedl-android crosses a JNI boundary where native code looks
            // classes up by name — a rename there fails at runtime, not at build
            // time, and no device is available here to catch it. The saving
            // would be a few MB off an APK whose size is dominated by the
            // Python/ffmpeg/aria2c native libraries that R8 cannot touch.
            // proguard-rules.pro already carries the keep rules for when this is
            // switched on against a device that can verify it.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "META-INF/*.kotlin_module",
            )
        }
        // The yt-dlp and ffmpeg artifacts each ship a libpython/libytdlp variant;
        // without pickFirsts the merge step fails on duplicates.
        jniLibs {
            pickFirsts += setOf(
                "lib/**/libpython*.so",
                "lib/**/libytdlp*.so",
                "lib/**/libffmpeg*.so",
            )
            // MUST stay true. youtubedl-android does not merely load these
            // libraries, it *reads them as files*: YoutubeDL.init() resolves
            // applicationInfo.nativeLibraryDir and unzips libpython.zip.so from
            // there to build the Python runtime. With legacy packaging off the
            // .so files stay inside the APK and are never extracted, that path
            // does not exist, and every download dies with the library's
            // "failed to initialize". Costs install size; there is no alternative.
            useLegacyPackaging = true
        }
    }

    // Lint findings should not fail a build; they are reported, not enforced.
    // The text report is what CI publishes, since it is the only static check
    // available without a device — NewApi in particular matters here, because
    // minSdk is 24 while the MediaStore publish path uses Q+ APIs.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        warningsAsErrors = false
        textReport = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    // Keep Room schemas in the repo so migrations are reviewable.
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.coil.compose)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // yt-dlp + ffmpeg + aria2c as JNI artifacts. GPL-3.0.
    implementation(libs.youtubedl.android.library)
    implementation(libs.youtubedl.android.ffmpeg)
    implementation(libs.youtubedl.android.aria2c)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
