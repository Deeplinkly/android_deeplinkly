import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

android {
    namespace = "com.deeplinkly.android_deeplinkly"

    compileSdk = 35

    buildFeatures {
        // Only for SDK_VERSION below. Off by default in AGP 8, and nothing else
        // here needs it.
        buildConfig = true
    }

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")

        // The single source of truth for the version the SDK reports as
        // `sdk_version`. It used to be a hand-maintained constant kept in step
        // with pubspec.yaml, which stopped being possible the moment this
        // library started versioning independently of the Flutter plugin - and
        // promptly drifted, shipping as 1.0.0 while reporting 1.9.0.
        buildConfigField(
            "String",
            "SDK_VERSION",
            "\"${providers.gradleProperty("VERSION_NAME").get()}\"",
        )
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
        named("test") { java.srcDirs("src/test/kotlin") }
    }

    testOptions {
        // Robolectric resolves resources/SharedPreferences for the tests that
        // need a real Context via ApplicationProvider.
        unitTests.isIncludeAndroidResources = true

        unitTests.all {
            // The suite is JUnit 4 (org.junit.Test / org.junit.Assert), so the
            // JUnit Platform runner would collect nothing.
            it.useJUnit()
            it.testLogging {
                events("passed", "skipped", "failed", "standardOut", "standardError")
                showStandardStreams = true
            }
        }
    }

    // No `publishing { singleVariant(...) }` here: the maven-publish plugin
    // below configures the release variant itself, including the sources and
    // javadoc jars Central requires, and declaring it twice fails at
    // configuration time.
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("androidx.core:core-ktx:1.12.0")

    // Bundled: deferred deep linking does not work without it, and its AAR
    // declares no permissions of its own.
    implementation("com.android.installreferrer:installreferrer:2.2")

    // App Set ID: a per-developer device id that, unlike the advertising id,
    // survives ad-tracking opt-out because it may not be used for advertising.
    // Bundled - its AAR declares no permissions.
    implementation("com.google.android.gms:play-services-appset:16.1.0")

    // NOT bundled, deliberately. This library's own AAR manifest declares
    // com.google.android.gms.permission.AD_ID, so depending on it normally
    // merges that permission into every host app - including apps under Play's
    // Families policy, which may not collect an advertising ID at all. Marking
    // it compileOnly means the library is never packaged, its manifest is never
    // merged, and a host app opts in by adding the dependency itself:
    //
    //     implementation 'com.google.android.gms:play-services-ads-identifier:18.2.0'
    //
    // DynamicSignals guards every use with Dependencies.classExists, so an app
    // that does not opt in simply reports no advertising_id. Same approach
    // Branch takes for this and its other optional integrations.
    compileOnly("com.google.android.gms:play-services-ads-identifier:18.2.0")

    // androidx.work is deliberately absent. It was declared but never used -
    // there is no Worker, no WorkRequest, no WorkManager call anywhere in the
    // SDK - while its manifest merged four permissions into every host app:
    // WAKE_LOCK, ACCESS_NETWORK_STATE, RECEIVE_BOOT_COMPLETED and
    // FOREGROUND_SERVICE. Retrying is handled by SdkRetryQueue and
    // QueueProcessor, both of which run in-process.

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.17.0")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("com.google.android.gms:play-services-ads-identifier:18.2.0")
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = false)

    // Signing is on unless explicitly disabled, so the release path cannot
    // forget it. It is disableable because `publishToMavenLocal` and a
    // contributor without the release key should still work, and because the
    // POM is worth validating without a signatory.
    //
    // Skipping it cannot leak an unsigned release: Central rejects unsigned
    // artifacts at validation, so the worst case is a failed upload.
    if (providers.gradleProperty("signingEnabled").getOrElse("true").toBoolean()) {
        signAllPublications()
    }
}
