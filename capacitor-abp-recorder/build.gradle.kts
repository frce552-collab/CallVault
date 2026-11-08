/*
 * ABP Call — Ploshadki.kz fork of CallVault. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 * S5: Capacitor plugin "AbpRecorder" over the :recorder library, for the CRM APK. Published as
 * kz.ploshadki:abp-recorder-capacitor (depends on kz.ploshadki:abp-recorder). Capacitor itself is
 * compileOnly: the host app already ships it.
 */
plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "kz.ploshadki.recorder.capacitor"
    compileSdk = 36
    defaultConfig {
        minSdk = 30
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing {
        singleVariant("release") { }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":recorder"))
    compileOnly("com.capacitorjs:core:7.6.9")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "kz.ploshadki"
                artifactId = "abp-recorder-capacitor"
                version = providers.gradleProperty("recorderVersion").getOrElse("0.1.0-local")
            }
        }
        repositories {
            maven {
                name = "recorderRepo"
                url = uri(providers.gradleProperty("recorderRepo").getOrElse("${rootDir}/build/recorder-repo"))
            }
        }
    }
}
