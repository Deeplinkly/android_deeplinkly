package com.deeplinkly.android_deeplinkly.attribution

import androidx.test.core.app.ApplicationProvider
import com.deeplinkly.android_deeplinkly.core.DeeplinklyContext
import com.deeplinkly.android_deeplinkly.core.DeeplinklyUtils
import com.deeplinkly.android_deeplinkly.core.Prefs
import com.deeplinkly.android_deeplinkly.network.DeeplinklyNetwork
import com.deeplinkly.android_deeplinkly.privacy.AttributionLevel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The dedupe half of [EnrichmentSender].
 *
 * Android had no latch at all until now: every path that can fire twice for
 * one report — a queued resolve replayed after the live one, a warm start
 * re-reading stored attribution — sent a duplicate, while iOS collapsed it.
 * The asymmetry showed up in the backend as inflated enrichment counts on
 * Android only.
 */
@RunWith(RobolectricTestRunner::class)
class EnrichmentSenderTest {

    /** Payloads the fake transport was handed, in order. */
    private val sent = mutableListOf<Map<String, String?>>()

    /** What the fake transport claims about delivery. */
    private var deliver = true

    @Before
    fun setUp() {
        DeeplinklyContext.app = ApplicationProvider.getApplicationContext()
        Prefs.of().edit().clear().apply()
        AttributionLevel.set(AttributionLevel.FULL)
        sent.clear()
        deliver = true
        EnrichmentSender.transport = { payload, _ ->
            sent += payload
            deliver
        }
    }

    @After
    fun tearDown() {
        EnrichmentSender.transport = DeeplinklyNetwork::sendEnrichment
        DeeplinklyUtils.setCustomUserId(null)
    }

    private fun send(
        attribution: Map<String, String?>,
        source: String = "deep_link",
        force: Boolean = false,
    ) = runBlocking {
        EnrichmentSender.sendOnce(attribution, source, apiKey = "test-key", force = force)
    }

    // --- the latch --------------------------------------------------------

    @Test
    fun `the same link reported twice is sent once`() {
        val link = mapOf<String, String?>("click_id" to "abc123")

        send(link)
        send(link)

        assertEquals(1, sent.size)
    }

    @Test
    fun `a second, different link is still sent`() {
        send(mapOf("click_id" to "abc123"))
        send(mapOf("click_id" to "def456"))

        assertEquals(2, sent.size)
    }

    /**
     * The latch used to be keyed on source alone on iOS, which made every
     * source once-per-install *forever*. Keying on what is reported is what
     * lets the second link through above; this pins the other half — that a
     * different source reporting the same link is not collapsed into it.
     */
    @Test
    fun `the same link from a different source is sent again`() {
        val link = mapOf<String, String?>("click_id" to "abc123")

        send(link, source = "deep_link")
        send(link, source = "install_referrer")

        assertEquals(2, sent.size)
    }

    /**
     * Latching on a payload that never arrived marks a permanently failing
     * enrichment as sent, and nothing ever retries it at this level.
     */
    @Test
    fun `an undelivered payload does not close the latch`() {
        deliver = false
        send(mapOf("click_id" to "abc123"))
        assertEquals(1, sent.size)

        deliver = true
        send(mapOf("click_id" to "abc123"))
        assertEquals(2, sent.size)

        // And once it has landed, the latch does close.
        send(mapOf("click_id" to "abc123"))
        assertEquals(2, sent.size)
    }

    /**
     * Lifecycle sources are rate-limited by their own callers
     * ([com.deeplinkly.android_deeplinkly.core.AppOpenReporter] by session
     * window, StartupEnrichment once per process). A latch here would drop a
     * fresh dynamic sample rather than merely collapse a duplicate.
     */
    @Test
    fun `lifecycle sources are exempt from the latch`() {
        send(emptyMap(), source = "app_open")
        send(emptyMap(), source = "app_open")
        send(emptyMap(), source = "app_start")

        assertEquals(3, sent.size)
    }

    // --- the attribution gate ---------------------------------------------

    @Test
    fun `a payload with no attribution is not sent`() {
        send(emptyMap())

        assertTrue(sent.isEmpty())
    }

    /**
     * setUserId is the reason `force` exists. Linking a login has nothing to
     * do with attribution, so gating it on a UTM meant an organically
     * installed user was never linked at all — which is what Android did
     * before this parameter existed here.
     */
    @Test
    fun `a forced payload is sent without attribution`() {
        DeeplinklyUtils.setCustomUserId("user-42")

        send(emptyMap(), source = "custom_user_id", force = true)

        assertEquals(1, sent.size)
        assertEquals("user-42", sent.single()["custom_user_id"])
    }

    /**
     * A login and a later re-login with a different id are two different
     * reports; the same id twice is one.
     */
    @Test
    fun `a forced payload still dedupes on the id it carries`() {
        DeeplinklyUtils.setCustomUserId("user-42")
        send(emptyMap(), source = "custom_user_id", force = true)
        send(emptyMap(), source = "custom_user_id", force = true)
        assertEquals(1, sent.size)

        DeeplinklyUtils.setCustomUserId("user-99")
        send(emptyMap(), source = "custom_user_id", force = true)
        assertEquals(2, sent.size)
    }

    // --- the key itself ---------------------------------------------------

    @Test
    fun `the key is stable across processes`() {
        val data = mapOf<String, String?>("click_id" to "abc123")

        assertEquals("deep_link_enriched_click_id=abc123", EnrichmentSender.dedupeKey(data, "deep_link"))
    }

    @Test
    fun `blank identity values do not appear in the key`() {
        val data = mapOf<String, String?>("click_id" to "", "code" to null)

        assertEquals("deep_link_enriched", EnrichmentSender.dedupeKey(data, "deep_link"))
    }

    /**
     * The latch is computed from the *filtered* payload, so a level that
     * stripped the identity keys would collapse every report of that source
     * into one. All three are `minimal` tier in the shared catalogue, and this
     * is the test that fails if that classification is ever changed.
     */
    @Test
    fun `identity keys survive the strictest level that still sends`() {
        val payload = mapOf(
            "click_id" to "abc123",
            "code" to "spring",
            "custom_user_id" to "user-42",
        )

        val filtered = AttributionLevel.MINIMAL.filter(payload)

        assertEquals(payload, filtered)
    }
}
