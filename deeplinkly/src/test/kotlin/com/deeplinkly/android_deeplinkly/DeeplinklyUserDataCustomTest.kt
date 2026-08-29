package com.deeplinkly.android_deeplinkly

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `user_custom_data`, the open field behind `Deeplinkly.setUserData`.
 *
 * Separate from [DeeplinklyUserDataTest] because this needs Robolectric for a
 * real `org.json`, and the rest of that contract is pure enough to run without
 * it. Splitting keeps the fast class fast.
 *
 * The field exists so a thirteenth identifier — a Mixpanel distinct id, a
 * CleverTap id — does not need every host app to ship again. That is exactly
 * why its bounds are worth testing: an open field that is also unbounded is a
 * different problem from the one it was added to solve.
 */
@RunWith(RobolectricTestRunner::class)
class DeeplinklyUserDataCustomTest {

    @Test
    fun `encodes custom data as a json object`() {
        val (encoded, rejection) = DeeplinklyUserData.encodeCustomData(
            mapOf("mixpanel_distinct_id" to "abc123", "clevertap_id" to "  xyz  ")
        )
        assertNull(rejection)
        val json = JSONObject(encoded!!)
        assertEquals("abc123", json.getString("mixpanel_distinct_id"))
        // Trimmed, like every other field.
        assertEquals("xyz", json.getString("clevertap_id"))
    }

    @Test
    fun `absent custom data is absent rather than empty`() {
        // null, an empty map, and a map of only blanks all mean "said nothing
        // about custom data", which merges as leave-alone. An empty JSON object
        // would instead overwrite whatever was stored with nothing.
        val inputs: List<Map<String, String?>?> = listOf(
            null,
            emptyMap(),
            mapOf("k" to "   "),
            mapOf("" to "v"),
        )
        for (input in inputs) {
            val (encoded, rejection) = DeeplinklyUserData.encodeCustomData(input)
            assertNull(rejection)
            assertNull(encoded)
        }
    }

    @Test
    fun `rejects an over-long custom key or value`() {
        val longKey = "k".repeat(DeeplinklyUserData.MAX_CUSTOM_KEY_LENGTH + 1)
        assertNotNull(DeeplinklyUserData.encodeCustomData(mapOf(longKey to "v")).second)

        val longValue = "v".repeat(DeeplinklyUserData.MAX_CUSTOM_VALUE_LENGTH + 1)
        assertNotNull(DeeplinklyUserData.encodeCustomData(mapOf("k" to longValue)).second)
    }

    @Test
    fun `rejects more entries than the cap`() {
        val tooMany = (0..DeeplinklyUserData.MAX_CUSTOM_ENTRIES)
            .associate { "key$it" to "value$it" }
        val (encoded, rejection) = DeeplinklyUserData.encodeCustomData(tooMany)
        assertNull(encoded)
        assertNotNull(rejection)
    }

    @Test
    fun `the worst legal blob still fits the catalogue length`() {
        // The largest thing a caller can legally build, against the max_len the
        // catalogue gives user_custom_data. If this ever fails, the field is
        // accepted here and then rejected in normalize(), which is a confusing
        // way to lose data — the caps and the max_len have to agree.
        val keyBody = "k".repeat(DeeplinklyUserData.MAX_CUSTOM_KEY_LENGTH - 2)
        val worst = (10 until 10 + DeeplinklyUserData.MAX_CUSTOM_ENTRIES).associate {
            "$keyBody$it" to "v".repeat(DeeplinklyUserData.MAX_CUSTOM_VALUE_LENGTH)
        }
        val (encoded, rejection) = DeeplinklyUserData.encodeCustomData(worst)
        assertNull(rejection)
        assertNotNull(encoded)

        val (normalized, normalizeRejection) =
            DeeplinklyUserData.normalize(DeeplinklyUserData.KEY_CUSTOM_DATA, encoded)
        assertNull(normalizeRejection)
        assertNotNull(normalized)
    }

    @Test
    fun `custom data survives a store round trip`() {
        val (encoded, _) = DeeplinklyUserData.encodeCustomData(mapOf("posthog_id" to "p1"))
        val (normalized, rejection) =
            DeeplinklyUserData.normalize(DeeplinklyUserData.KEY_CUSTOM_DATA, encoded)
        assertNull(rejection)
        // The key has to be one UserDataStore will keep, or the blob is
        // silently dropped on the way to the wire.
        assertTrue(DeeplinklyUserData.KEY_CUSTOM_DATA in DeeplinklyUserData.KEYS)
        assertEquals(encoded, normalized)
    }
}
