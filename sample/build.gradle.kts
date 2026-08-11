plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.deeplinkly.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.deeplinkly.sample"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets {
        named("main") { java.srcDirs("src/main/kotlin") }
    }

    buildTypes {
        named("debug") {
            // Signed with the debug key, so App Link verification will not pass
            // against a dashboard configured for the release fingerprint. The
            // custom scheme works regardless, which is what the README's adb
            // commands use.
            isMinifyEnabled = false
        }
    }
}

dependencies {
    // The whole point of this module: depend on the SDK exactly as a customer
    // does, by project reference so it always tests the working tree.
    implementation(project(":deeplinkly"))

    implementation("androidx.appcompat:appcompat:1.7.0")

    // Not declared by the SDK, deliberately - it carries the AD_ID permission.
    // Present here so the sample exercises the opt-in path, which is the one
    // most likely to break silently.
    implementation("com.google.android.gms:play-services-ads-identifier:18.2.0")
}
