package com.deeplinkly.android_deeplinkly.privacy

import androidx.test.core.app.ApplicationProvider
import com.deeplinkly.android_deeplinkly.DeeplinklyUserData
import com.deeplinkly.android_deeplinkly.core.DeeplinklyContext
import com.deeplinkly.android_deeplinkly.core.Prefs
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * On-device PII hashing.
 *
 * The digests here have to match the ones the service computes from
 * an erasure request, character for character, or a data subject who asks to be
 * forgotten is not found and the request reports success anyway. That is why
 * the expected values below are written out rather than computed by calling the
 * same function the code under test uses.
 */
@RunWith(RobolectricTestRunner::class)
class PIIHashingTest {

    @Before
    fun setUp() {
        DeeplinklyContext.app = ApplicationProvider.getApplicationContext()
        Prefs.of().edit().clear().apply()
    }

    @Test
    fun `is off unless it is turned on`() {
        assertFalse(PIIHashing.isEnabled())
        PIIHashing.setEnabled(true)
        assertTrue(PIIHashing.isEnabled())
        PIIHashing.setEnabled(false)
        assertFalse(PIIHashing.isEnabled())
    }

    @Test
    fun `off leaves the payload exactly as supplied`() {
        val fields = mapOf(DeeplinklyUserData.KEY_EMAIL to "Ada@Example.com")
        assertEquals(fields, PIIHashing.apply(fields))
    }

    /// The cross-language contract. SHA-256 of "ada@example.com".
    @Test
    fun `email is trimmed and lowercased before hashing`() {
        assertEquals(
            "b5fc85e55755f9e0d030a10ab4429b6b2944855f9a0d60077fe832becbc41d72",
            PIIHashing.digest(DeeplinklyUserData.KEY_EMAIL, "  Ada@Example.com "),
        )
    }

    @Test
    fun `phone keeps only digits`() {
        assertEquals(
            PIIHashing.digest(DeeplinklyUserData.KEY_PHONE, "442079460000"),
            PIIHashing.digest(DeeplinklyUserData.KEY_PHONE, "+44 20 7946 0000"),
        )
    }

    @Test
    fun `only the four hashable fields change`() {
        PIIHashing.setEnabled(true)
        val out = PIIHashing.apply(
            mapOf(
                DeeplinklyUserData.KEY_EMAIL to "ada@example.com",
                DeeplinklyUserData.KEY_PHONE to "442079460000",
                DeeplinklyUserData.KEY_FIRST_NAME to "Ada",
                DeeplinklyUserData.KEY_LAST_NAME to "Lovelace",
                // Not hashed: a two-value domain is enumerated in two guesses,
                // and the column could not hold a digest anyway.
                DeeplinklyUserData.KEY_GENDER to "f",
                DeeplinklyUserData.KEY_COUNTRY to "GB",
                DeeplinklyUserData.KEY_CITY to "London",
            )
        )
        for (field in PIIHashing.HASHED_FIELDS) {
            assertEquals("$field should be a digest", 64, out[field]!!.length)
        }
        assertEquals("f", out[DeeplinklyUserData.KEY_GENDER])
        assertEquals("GB", out[DeeplinklyUserData.KEY_COUNTRY])
        assertEquals("London", out[DeeplinklyUserData.KEY_CITY])
    }

    @Test
    fun `an empty tombstone is never hashed`() {
        // The one that would be catastrophic and silent. An empty string is
        // clearUserData saying "null this column"; hashing it would send a
        // digest, the service would store it as a value, and the erasure would
        // simply not have happened.
        PIIHashing.setEnabled(true)
        val out = PIIHashing.apply(
            mapOf(
                DeeplinklyUserData.KEY_EMAIL to "",
                DeeplinklyUserData.KEY_PHONE to "",
            )
        )
        assertEquals("", out[DeeplinklyUserData.KEY_EMAIL])
        assertEquals("", out[DeeplinklyUserData.KEY_PHONE])
    }

    @Test
    fun `every hashed field can hold a digest in its catalogue length`() {
        // If a hashable field's max_len were below 64 the service would
        // truncate the digest on the way into the column, and it would match
        // nothing forever after.
        for (field in PIIHashing.HASHED_FIELDS) {
            val limit = DeeplinklyUserData.MAX_LENGTHS[field]
            assertNotNull("$field has no catalogue length", limit)
            assertTrue("$field max_len $limit cannot hold a digest", limit!! >= 64)
        }
    }
}
