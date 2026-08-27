package com.deeplinkly.android_deeplinkly

import org.junit.Assert.*
import org.junit.Test

/**
 * The normalisation contract behind `Deeplinkly.setUserData`.
 *
 * These values become the match keys a conversion is joined on at Meta and
 * Google. A field that is silently mangled here does not fail loudly later — it
 * matches nobody, and the conversion is simply never attributed, which looks
 * exactly like the campaign not working.
 */
class DeeplinklyUserDataTest {

    private fun normalize(key: String, value: String?) =
        DeeplinklyUserData.normalize(key, value)

    @Test
    fun `trims but does not otherwise touch free-text fields`() {
        val (value, rejection) = normalize(DeeplinklyUserData.KEY_EMAIL, "  Ada@Example.COM ")
        assertNull(rejection)
        // Not lowercased. Lowercasing is Meta's rule, not a fact about the
        // address, and the backend can apply it per destination only if we did
        // not already throw the original away.
        assertEquals("Ada@Example.COM", value)
    }

    @Test
    fun `blank means leave alone rather than clear`() {
        val (value, rejection) = normalize(DeeplinklyUserData.KEY_FIRST_NAME, "   ")
        assertNull(rejection)
        assertNull(value)
    }

    @Test
    fun `uppercases a country code`() {
        val (value, rejection) = normalize(DeeplinklyUserData.KEY_COUNTRY, "us")
        assertNull(rejection)
        assertEquals("US", value)
    }

    @Test
    fun `rejects a country that is not two letters`() {
        val (value, rejection) = normalize(DeeplinklyUserData.KEY_COUNTRY, "USA")
        assertNotNull(rejection)
        assertNull(value)
    }

    @Test
    fun `accepts the two gender values Meta matches on`() {
        assertEquals("m", normalize(DeeplinklyUserData.KEY_GENDER, "M").first)
        assertEquals("f", normalize(DeeplinklyUserData.KEY_GENDER, "f").first)
    }

    /**
     * The case that motivates rejecting rather than truncating: the column is
     * one character, so a truncated "non-binary" would be stored as "n" — a
     * value that is not merely lossy but wrong, and that a forwarder would pass
     * on as if we had been told it.
     */
    @Test
    fun `refuses a gender it cannot represent instead of truncating it`() {
        val (value, rejection) = normalize(DeeplinklyUserData.KEY_GENDER, "non-binary")
        assertNotNull(rejection)
        assertNull(value)
    }

    @Test
    fun `requires an ISO date of birth`() {
        assertNull(normalize(DeeplinklyUserData.KEY_DATE_OF_BIRTH, "1990-01-02").second)
        assertNotNull(normalize(DeeplinklyUserData.KEY_DATE_OF_BIRTH, "02/01/1990").second)
    }

    @Test
    fun `rejects a value longer than its column`() {
        val long = "a".repeat(321)
        assertNotNull(normalize(DeeplinklyUserData.KEY_EMAIL, long).second)
        assertNull(normalize(DeeplinklyUserData.KEY_EMAIL, "a".repeat(320)).second)
    }

    @Test
    fun `normalizeAll drops absent fields so a call merges`() {
        val result = DeeplinklyUserData.normalizeAll(
            mapOf(
                DeeplinklyUserData.KEY_EMAIL to "ada@example.com",
                DeeplinklyUserData.KEY_CITY to null,
                DeeplinklyUserData.KEY_ZIP to "",
            )
        )
        assertNull(result.rejection)
        assertEquals(mapOf(DeeplinklyUserData.KEY_EMAIL to "ada@example.com"), result.fields)
    }

    /**
     * All or nothing. A caller who gets `false` back has to be able to assume
     * nothing was stored, or they cannot recover — they would have to guess
     * which of twelve values took.
     */
    @Test
    fun `one bad field rejects the whole call`() {
        val result = DeeplinklyUserData.normalizeAll(
            mapOf(
                DeeplinklyUserData.KEY_EMAIL to "ada@example.com",
                DeeplinklyUserData.KEY_COUNTRY to "United States",
            )
        )
        assertNotNull(result.rejection)
        assertNull(result.fields)
    }

    /**
     * `custom_user_id` is user-scoped in the catalogue but is deliberately not
     * one of these: it has always lived in its own preference, and a second
     * copy here would be a second thing to keep in step.
     */
    @Test
    fun `the custom user id is not a UserDataStore field`() {
        assertFalse(DeeplinklyUserData.KEY_USER_ID in DeeplinklyUserData.KEYS)
    }
}
