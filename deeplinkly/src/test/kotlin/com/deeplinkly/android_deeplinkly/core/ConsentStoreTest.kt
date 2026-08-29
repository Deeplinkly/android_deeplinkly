package com.deeplinkly.android_deeplinkly.core

import androidx.test.core.app.ApplicationProvider
import com.deeplinkly.android_deeplinkly.privacy.ConsentState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConsentStoreTest {

    @Before
    fun setUp() {
        DeeplinklyContext.app = ApplicationProvider.getApplicationContext()
        Prefs.of().edit().clear().apply()
    }

    @Test
    fun `starts empty`() {
        assertTrue(ConsentStore.isEmpty())
        assertEquals(emptyMap<String, String>(), ConsentStore.get())
    }

    @Test
    fun `stores the wire names Google expects, not the enum names`() {
        ConsentStore.merge(ConsentState.GRANTED, ConsentState.DENIED, isEea = true)

        assertEquals(
            mapOf(
                ConsentStore.KEY_AD_USER_DATA to "granted",
                ConsentStore.KEY_AD_PERSONALIZATION to "denied",
                ConsentStore.KEY_IS_EEA to "true",
            ),
            ConsentStore.get(),
        )
    }

    /**
     * The documented shape of the API: an app can settle the EEA question at
     * launch and the two answers when its banner is answered, and the second
     * call must not blank the first.
     */
    @Test
    fun `merges rather than replacing`() {
        ConsentStore.merge(null, null, isEea = true)
        ConsentStore.merge(ConsentState.GRANTED, ConsentState.GRANTED, null)

        assertEquals(
            mapOf(
                ConsentStore.KEY_IS_EEA to "true",
                ConsentStore.KEY_AD_USER_DATA to "granted",
                ConsentStore.KEY_AD_PERSONALIZATION to "granted",
            ),
            ConsentStore.get(),
        )
    }

    /**
     * A banner that re-reports the same answer on every launch is the common
     * case. It must not produce an enrichment every time, which is what the
     * return value is for.
     */
    @Test
    fun `reports whether anything actually changed`() {
        assertTrue(ConsentStore.merge(ConsentState.GRANTED, null, null))
        assertFalse(ConsentStore.merge(ConsentState.GRANTED, null, null))
        assertTrue(ConsentStore.merge(ConsentState.DENIED, null, null))
    }

    @Test
    fun `an all-null call changes nothing and says so`() {
        assertFalse(ConsentStore.merge(null, null, null))
        assertTrue(ConsentStore.isEmpty())
    }

    /**
     * Withdrawal is DENIED, not absence. The forwarder has to be able to tell
     * "this person said no" from "this app has no consent model", and deleting
     * the record collapses the first into the second.
     */
    @Test
    fun `withdrawal is recorded as a value rather than as a deletion`() {
        ConsentStore.merge(ConsentState.GRANTED, ConsentState.GRANTED, null)
        ConsentStore.merge(ConsentState.DENIED, ConsentState.DENIED, null)

        assertFalse(ConsentStore.isEmpty())
        assertEquals("denied", ConsentStore.get()[ConsentStore.KEY_AD_USER_DATA])
        assertEquals("denied", ConsentStore.get()[ConsentStore.KEY_AD_PERSONALIZATION])
    }

    @Test
    fun `an unreadable blob is discarded rather than crashing a send`() {
        Prefs.of().edit().putString(ConsentStore.KEY, "{not json").apply()

        assertEquals(emptyMap<String, String>(), ConsentStore.get())
        assertNull(Prefs.of().getString(ConsentStore.KEY, null))
    }

    @Test
    fun `every state round-trips through its wire name`() {
        for (state in ConsentState.entries) {
            assertEquals(state, ConsentState.fromWireName(state.wireName))
        }
        assertEquals(ConsentState.GRANTED, ConsentState.fromWireName("  GRANTED "))
        assertNull(ConsentState.fromWireName("maybe"))
        assertNull(ConsentState.fromWireName(null))
    }
}
