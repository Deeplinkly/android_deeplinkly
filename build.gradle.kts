// Versions are pinned to match the Flutter plugin's own build, so the AAR this
// repo publishes and the one Flutter used to build from source are compiled by
// the same toolchain. Changing one without the other is how the two drift.
plugins {
    id("com.android.library") version "8.7.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
    id("com.vanniktech.maven.publish") version "0.30.0" apply false
}
