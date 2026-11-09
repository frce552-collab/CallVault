/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present kitsumed (Med)
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

// S5 (Ploshadki fork): ABP Call is now a thin host around the :recorder library.
// All code, resources and the full manifest live in app/src/main (owned by S1) and are compiled by
// :recorder (recorder/build.gradle.kts). This module only gives the APK its applicationId, version,
// signing and the recorder provider authority. The CRM app embeds :recorder the same way.

plugins {
    alias(libs.plugins.android.application)
}

val ciVersionCode = providers.gradleProperty("versionCode").map { it.toIntOrNull() }.orElse(20500)
val ciVersionName = providers.gradleProperty("versionName").orElse("2.5.0")
val isEnvironmentGithubCI = providers.environmentVariable("GITHUB_ACTIONS").isPresent
// Library tasks (:recorder, :capacitor-abp-recorder release AAR publish) do not sign anything.
val isReleaseBuildRequested = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) && !it.startsWith(":recorder:") && !it.startsWith(":capacitor-abp-recorder:") }
val isolateTestApp = providers.gradleProperty("isolateTestApp").isPresent

android {
    namespace = "kz.ploshadki.recorder.host"
    compileSdk = 36

    defaultConfig {
        applicationId = "kz.ploshadki.recorder"
        minSdk = 30
        targetSdk = 36
        versionCode = ciVersionCode.get()
        versionName = ciVersionName.get()
        ndk { abiFilters += "arm64-v8a" }
        // Recorder provider authority is "${'$'}{applicationId}.recorder" straight in the library manifest
        // (HostPackage.recorderAuthority), so every host — ABP Call, CRM, the .instrtest build — gets its own.
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("host/AndroidManifest.xml")
            java.setSrcDirs(emptyList<String>())
            kotlin.setSrcDirs(emptyList<String>())
            res.setSrcDirs(listOf("host/res"))
            assets.setSrcDirs(emptyList<String>())
            aidl.setSrcDirs(emptyList<String>())
        }
        getByName("test") {
            java.setSrcDirs(emptyList<String>())
            kotlin.setSrcDirs(emptyList<String>())
            resources.setSrcDirs(emptyList<String>())
        }
        getByName("androidTest") {
            java.setSrcDirs(emptyList<String>())
            kotlin.setSrcDirs(emptyList<String>())
            assets.setSrcDirs(emptyList<String>())
        }
    }

    val localReleaseKeystore = rootProject.file("signing/callvault-signing.keystore")
    signingConfigs {
        create("ci-release") {
            if (isEnvironmentGithubCI && isReleaseBuildRequested) {
                storeFile = file(System.getenv("KEYSTORE_FILE") ?: throw GradleException("KEYSTORE_FILE not set"))
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: throw GradleException("KEYSTORE_PASSWORD not set")
                keyAlias = System.getenv("KEY_ALIAS") ?: throw GradleException("KEY_ALIAS not set")
                keyPassword = System.getenv("KEY_PASSWORD") ?: throw GradleException("KEY_PASSWORD not set")
            }
        }
        create("local-release") {
            if (localReleaseKeystore.exists()) {
                storeFile = localReleaseKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false // the app_process daemon is reached by a string classpath; R8 would strip it
            isShrinkResources = false
            when {
                isEnvironmentGithubCI -> signingConfig = signingConfigs.getByName("ci-release")
                localReleaseKeystore.exists() -> signingConfig = signingConfigs.getByName("local-release")
                else -> println("No release keystore found; release APK will be unsigned.")
            }
        }
        debug {
            if (isolateTestApp) {
                applicationIdSuffix = ".instrtest"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes.add("com/google/i18n/phonenumbers/data/**")
        jniLibs {
            // The privileged daemon and libaudiohandoff need the .so files extracted on disk.
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
    }
    androidResources {
        generateLocaleConfig = true
    }
    lint {
        warningsAsErrors = false
        error += setOf("MissingTranslation", "ExtraTranslation")
    }
}

// Translation coverage still checks app/src/main/res (the real resource tree).
apply(from = rootProject.file("gradle/translation-coverage.gradle.kts"))

dependencies {
    implementation(project(":recorder"))
}
