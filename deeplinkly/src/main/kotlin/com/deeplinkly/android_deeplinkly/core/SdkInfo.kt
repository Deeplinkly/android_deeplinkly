package com.deeplinkly.android_deeplinkly.core

import com.deeplinkly.android_deeplinkly.BuildConfig

/**
 * Identity of the SDK build itself.
 *
 * Reported as `sdk_version` and folded into the static-profile stamp, so an SDK
 * upgrade re-collects the device profile — which is what makes a signal added
 * in a new release actually get collected on existing installs.
 */
object SdkInfo {
    /**
     * The published artifact version, from `VERSION_NAME` in gradle.properties.
     *
     * Read from BuildConfig rather than hand-maintained. It was a literal back
     * when this was a module inside the Flutter plugin — where the AAR's own
     * BuildConfig carried the *host app's* version, not ours, so a constant was
     * the only option and pubspec.yaml owned the number. Publishing this as its
     * own artifact makes BuildConfig ours and that reasoning obsolete, which
     * matters because the constant had already drifted: 1.0.0 shipped reporting
     * itself as 1.9.0.
     *
     * Note this is the *native* SDK version. An app using the Flutter plugin
     * reports the version of the SDK actually running, not the plugin version
     * wrapping it; the two version independently now and only one of them is
     * the code that produced the payload.
     */
    val VERSION: String = BuildConfig.SDK_VERSION

    const val PLATFORM = "android"

    /**
     * Monotonic reference captured when the SDK attached, held in memory only.
     *
     * Events report [elapsedSinceInit] — milliseconds since this point — rather
     * than a raw monotonic clock reading. The delta is what orders events from
     * a device whose wall clock is wrong; the absolute reading additionally
     * revealed how long the device had been booted, which is a device
     * correlator we have no use for.
     *
     * Reset per process, deliberately. It is meaningful within a session and
     * meaningless across one, which matches what it is for.
     */
    private val initElapsedRealtime: Long = android.os.SystemClock.elapsedRealtime()

    /** Milliseconds since the SDK initialised in this process. */
    fun elapsedSinceInit(): Long =
        android.os.SystemClock.elapsedRealtime() - initElapsedRealtime
}
