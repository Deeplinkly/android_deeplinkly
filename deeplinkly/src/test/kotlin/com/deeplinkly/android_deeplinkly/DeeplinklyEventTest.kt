package com.deeplinkly.android_deeplinkly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * These rules used to live in Dart and applied only to Flutter callers. They
 * are asserted here now because this is the copy every host runs, and because
 * the backend enforces the same limits - a change on one side without the other
 * starts silently truncating.
 */
@RunWith(RobolectricTestRunner::class)
class DeeplinklyEventTest {

    private fun accepts(name: String, params: Map<String, Any?> = emptyMap()) =
        assertNull(
            "expected '$name' with $params to be accepted",
            DeeplinklyEvent.validate(name, params)
        )

    private fun rejects(name: String, params: Map<String, Any?> = emptyMap()) =
        assertNotNull(
            "expected '$name' with $params to be rejected",
            DeeplinklyEvent.validate(name, params)
        )

    // ------------------------------------------------------------------- name

    @Test
    fun `a plain name is accepted`() = accepts("purchase")

    @Test
    fun `a blank name is rejected`() {
        rejects("")
        rejects("   ")
        rejects("\t\n")
    }

    @Test
    fun `a name is measured after trimming`() {
        accepts("  purchase  ")
        // 64 is the limit, and it is the trimmed length that counts.
        accepts("  " + "a".repeat(64) + "  ")
        rejects("a".repeat(65))
    }

    @Test
    fun `the name length boundary is inclusive`() {
        accepts("a".repeat(64))
        rejects("a".repeat(65))
    }

    @Test
    fun `normalizeName trims what is actually sent`() {
        assertEquals("purchase", DeeplinklyEvent.normalizeName("  purchase  "))
    }

    // ----------------------------------------------------------------- counts

    @Test
    fun `the parameter count boundary is inclusive`() {
        accepts("e", (1..25).associate { "k$it" to it })
        rejects("e", (1..26).associate { "k$it" to it })
    }

    // ------------------------------------------------------------------- keys

    @Test
    fun `a blank key is rejected`() {
        rejects("e", mapOf("" to "v"))
        rejects("e", mapOf("   " to "v"))
    }

    @Test
    fun `the key length boundary is inclusive`() {
        accepts("e", mapOf("k".repeat(64) to "v"))
        rejects("e", mapOf("k".repeat(65) to "v"))
    }

    /**
     * The SDK writes its own bookkeeping under this prefix and the backend
     * excludes it from the caller's budget, so a caller-supplied one would both
     * collide and smuggle parameters past the count limit.
     */
    @Test
    fun `the reserved prefix is rejected`() {
        rejects("e", mapOf("_dl_event_seq" to "1"))
        rejects("e", mapOf("_dl_" to "x"))
        // Checked after trimming, so leading space does not sneak it through.
        rejects("e", mapOf("  _dl_session_id" to "x"))
    }

    @Test
    fun `a key that merely contains the reserved prefix is fine`() =
        accepts("e", mapOf("my_dl_field" to "x"))

    // ----------------------------------------------------------------- values

    @Test
    fun `scalar values are accepted`() = accepts(
        "e",
        mapOf(
            "s" to "text",
            "i" to 42,
            "l" to 42L,
            "d" to 49.99,
            "b" to true,
        )
    )

    @Test
    fun `the string value boundary is inclusive`() {
        accepts("e", mapOf("k" to "v".repeat(256)))
        rejects("e", mapOf("k" to "v".repeat(257)))
    }

    @Test
    fun `an unsupported value type is rejected`() {
        rejects("e", mapOf("k" to Any()))
        rejects("e", mapOf("k" to java.util.Date()))
    }

    @Test
    fun `a null value is rejected`() = rejects("e", mapOf("k" to null))

    // ------------------------------------------------------- container values

    @Test
    fun `small containers are accepted`() {
        accepts("e", mapOf("list" to listOf(1, 2, 3)))
        accepts("e", mapOf("map" to mapOf("a" to 1, "b" to "two")))
        accepts("e", mapOf("nested" to mapOf("a" to listOf(1, mapOf("b" to true)))))
    }

    /**
     * The limit applies to the *encoded* form, because that is what the backend
     * stores and truncates - not to the element count.
     */
    @Test
    fun `a container is measured by its encoded length`() {
        // A handful of elements, but each one long: well over 256 encoded.
        rejects("e", mapOf("k" to List(10) { "x".repeat(40) }))
        // Many elements, but tiny: comfortably under.
        accepts("e", mapOf("k" to List(50) { 1 }))
    }

    @Test
    fun `a container holding an unencodable value is rejected`() {
        rejects("e", mapOf("k" to listOf(Any())))
        rejects("e", mapOf("k" to mapOf("a" to Any())))
    }

    @Test
    fun `a map with non-string keys is rejected`() =
        rejects("e", mapOf("k" to mapOf(1 to "a")))

    @Test
    fun `nulls inside a container are allowed`() =
        accepts("e", mapOf("k" to listOf(1, null, "a")))
}
