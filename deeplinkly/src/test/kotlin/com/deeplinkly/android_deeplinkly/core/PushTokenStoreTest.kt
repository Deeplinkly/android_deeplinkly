package com.deeplinkly.android_deeplinkly.core

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PushTokenStoreTest {

    @Before
    fun setUp() {
        DeeplinklyContext.app = ApplicationProvider.getApplicationContext()
        Prefs.of().edit().clear().apply()
    }

    @Test
    fun `starts empty`() {
        assertEquals(emptyMap<String, String>(), PushTokenStore.get())
    }

    @Test
    fun `stores the token and the provider it addresses`() {
        assertTrue(PushTokenStore.set("tok-123", PushProvider.FCM))

        assertEquals(
            mapOf("push_token" to "tok-123", "push_provider" to "fcm"),
            PushTokenStore.get(),
        )
    }

    /**
     * A Flutter or React Native app shares this store across both platforms,
     * so the provider is explicit rather than inferred from the SDK it came
     * through.
     */
    @Test
    fun `an apns token is stored as apns even on android`() {
        PushTokenStore.set("apns-tok", PushProvider.APNS)

        assertEquals("apns", PushTokenStore.get()["push_provider"])
    }

    /**
     * Tokens rotate rarely and apps re-report them on every launch. Repeating
     * one must not produce an enrichment.
     */
    @Test
    fun `reports whether the stored value actually changed`() {
        assertTrue(PushTokenStore.set("tok-123", PushProvider.FCM))
        assertFalse(PushTokenStore.set("tok-123", PushProvider.FCM))
        assertTrue(PushTokenStore.set("tok-456", PushProvider.FCM))
        // The provider alone changing is still a change: it decides which
        // service the prober speaks.
        assertTrue(PushTokenStore.set("tok-456", PushProvider.APNS))
    }

    /**
     * Null means "this device has no token", which is an absence. Writing an
     * empty string instead would reach the service as an erasure of a column
     * that should simply stop being reported.
     */
    @Test
    fun `a null token removes the entry rather than storing a blank`() {
        PushTokenStore.set("tok-123", PushProvider.FCM)

        assertTrue(PushTokenStore.set(null, PushProvider.FCM))
        assertEquals(emptyMap<String, String>(), PushTokenStore.get())
        assertFalse("removing twice is not a change", PushTokenStore.set(null, PushProvider.FCM))
    }

    @Test
    fun `a blank token is treated as a removal, not as a value`() {
        PushTokenStore.set("tok-123", PushProvider.FCM)
        PushTokenStore.set("   ", PushProvider.FCM)

        assertEquals(emptyMap<String, String>(), PushTokenStore.get())
    }

    @Test
    fun `whitespace around a real token is trimmed`() {
        PushTokenStore.set("  tok-123\n", PushProvider.FCM)

        assertEquals("tok-123", PushTokenStore.get()["push_token"])
    }

    /**
     * Pins the length against `push_token`'s `max_len` in tool/signals.json.
     * The generated Kotlin catalogue carries tier and scope but not lengths, so
     * this constant is the only thing keeping the two in step.
     */
    @Test
    fun `a token over the catalogue length is refused rather than truncated`() {
        val tooLong = "x".repeat(PushTokenStore.MAX_LENGTH + 1)

        assertFalse(PushTokenStore.set(tooLong, PushProvider.FCM))
        assertEquals(emptyMap<String, String>(), PushTokenStore.get())

        val atLimit = "x".repeat(PushTokenStore.MAX_LENGTH)
        assertTrue(PushTokenStore.set(atLimit, PushProvider.FCM))
    }

    @Test
    fun `an entry written without a provider reads back as fcm`() {
        Prefs.of().edit().putString(PushTokenStore.KEY_TOKEN, "tok-123").apply()

        assertEquals("fcm", PushTokenStore.get()["push_provider"])
    }

    @Test
    fun `every provider round-trips through its wire name`() {
        for (provider in PushProvider.entries) {
            assertEquals(provider, PushProvider.fromWireName(provider.wireName))
        }
        assertNull(PushProvider.fromWireName("wns"))
    }
}
