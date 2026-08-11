package com.deeplinkly.android_deeplinkly.core

import com.deeplinkly.android_deeplinkly.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The version the SDK reports is the version that shipped.
 *
 * This existed as a hand-maintained constant and drifted: the artifact
 * published as 1.0.0 while every payload claimed 1.9.0, so a native install
 * would have been attributed to a release it was not running. The constant is
 * derived from `VERSION_NAME` now, and these assertions are what stop somebody
 * "helpfully" turning it back into a literal.
 */
@RunWith(RobolectricTestRunner::class)
class SdkInfoTest {

    @Test
    fun `the reported version is the one Gradle published`() {
        assertEquals(BuildConfig.SDK_VERSION, SdkInfo.VERSION)
    }

    @Test
    fun `the version is a real version, not a placeholder`() {
        assertTrue(
            "sdk_version must look like x.y.z but was '${SdkInfo.VERSION}'",
            Regex("""^\d+\.\d+\.\d+""").containsMatchIn(SdkInfo.VERSION),
        )
        // A -SNAPSHOT build must never be what a device reports, and Central
        // will not accept one either.
        assertTrue(
            "sdk_version must not be a snapshot: '${SdkInfo.VERSION}'",
            !SdkInfo.VERSION.contains("SNAPSHOT", ignoreCase = true),
        )
    }

    @Test
    fun `the platform is android`() {
        assertEquals("android", SdkInfo.PLATFORM)
    }

    @Test
    fun `elapsed since init advances and is never negative`() {
        val first = SdkInfo.elapsedSinceInit()
        assertTrue("elapsed must not be negative, was $first", first >= 0)
        Thread.sleep(5)
        assertTrue("elapsed must advance", SdkInfo.elapsedSinceInit() >= first)
    }
}
