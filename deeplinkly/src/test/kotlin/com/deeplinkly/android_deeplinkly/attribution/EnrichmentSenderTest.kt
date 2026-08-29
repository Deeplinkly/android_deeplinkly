package com.deeplinkly.android_deeplinkly.attribution

import androidx.test.core.app.ApplicationProvider
import com.deeplinkly.android_deeplinkly.core.ConsentStore
import com.deeplinkly.android_deeplinkly.core.DeeplinklyContext
import com.deeplinkly.android_deeplinkly.core.DeeplinklyUtils
import com.deeplinkly.android_deeplinkly.DeeplinklyUserData
import com.deeplinkly.android_deeplinkly.core.Prefs
import com.deeplinkly.android_deeplinkly.core.PushProvider
import com.deeplinkly.android_deeplinkly.core.PushTokenStore
import com.deeplinkly.android_deeplinkly.core.UserDataStore
import com.deeplinkly.android_deeplinkly.network.DeeplinklyNetwork
import com.deeplinkly.android_deeplinkly.privacy.AttributionLevel
import com.deeplinkly.android_deeplinkly.privacy.ConsentState
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
 * The asymmetry showed up in the service as inflated enrichment counts on
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

    // ---------------------------------------------------------- user data

    /**
     * The whole point of the `user` scope: what the app told us about the
     * person rides along on the enrichment, so a conversion forwarded later can
     * be matched at Meta or Google without the event itself having to carry it.
     */
    @Test
    fun `user data rides along on the payload`() {
        UserDataStore.merge(
            mapOf(
                DeeplinklyUserData.KEY_EMAIL to "ada@example.com",
                DeeplinklyUserData.KEY_COUNTRY to "GB",
            )
        )

        send(mapOf("click_id" to "c1"))

        assertEquals(1, sent.size)
        assertEquals("ada@example.com", sent[0][DeeplinklyUserData.KEY_EMAIL])
        assertEquals("GB", sent[0][DeeplinklyUserData.KEY_COUNTRY])
    }

    /**
     * Empty is a value here, not an absence. `UserDataStore.clear` tombstones
     * each set field to "" and the service reads that as "erase this column";
     * a filter that dropped empties on the way out would turn a deletion into a
     * no-op without anyone noticing.
     */
    @Test
    fun `a tombstoned field is sent as an empty value`() {
        UserDataStore.merge(mapOf(DeeplinklyUserData.KEY_EMAIL to "ada@example.com"))
        UserDataStore.clear()

        send(mapOf("click_id" to "c1"))

        assertEquals(1, sent.size)
        assertEquals("", sent[0][DeeplinklyUserData.KEY_EMAIL])
    }

    /**
     * The latch is keyed on what is being reported. For this source that is the
     * user data itself, so adding an address to an email already sent — the
     * ordinary second call — must not collapse into the first.
     */
    @Test
    fun `a second user_data report with different fields is not deduped`() {
        UserDataStore.merge(mapOf(DeeplinklyUserData.KEY_EMAIL to "ada@example.com"))
        send(emptyMap(), source = "user_data", force = true)

        UserDataStore.merge(mapOf(DeeplinklyUserData.KEY_CITY to "London"))
        send(emptyMap(), source = "user_data", force = true)

        assertEquals(2, sent.size)
        assertEquals("London", sent[1][DeeplinklyUserData.KEY_CITY])
    }

    /** The same call twice is still one report. */
    @Test
    fun `an unchanged user_data report is deduped`() {
        UserDataStore.merge(mapOf(DeeplinklyUserData.KEY_EMAIL to "ada@example.com"))
        send(emptyMap(), source = "user_data", force = true)
        send(emptyMap(), source = "user_data", force = true)

        assertEquals(1, sent.size)
    }

    /**
     * A dedupe key becomes the name of a SharedPreferences entry, and an email
     * address written into one would sit somewhere neither clearUserData nor
     * the tombstone can reach.
     */
    @Test
    fun `the dedupe key does not contain the user data itself`() {
        val key = EnrichmentSender.dedupeKey(
            mapOf(DeeplinklyUserData.KEY_EMAIL to "ada@example.com"),
            "user_data",
        )
        assertFalse(key.contains("ada@example.com"))
    }

    /** Stable across launches, and identical to the Swift implementation. */
    @Test
    fun `the digest is stable for a given input`() {
        assertEquals(
            EnrichmentSender.stableDigest("user_email=ada@example.com"),
            EnrichmentSender.stableDigest("user_email=ada@example.com"),
        )
        assertNotEquals(
            EnrichmentSender.stableDigest("user_email=ada@example.com"),
            EnrichmentSender.stableDigest("user_email=grace@example.com"),
        )
    }

    /**
     * Other sources are unaffected: every enrichment now carries user data, and
     * folding it into their keys too would re-send a deep-link report every
     * time an unrelated field changed.
     */
    @Test
    fun `user data does not change the dedupe key of other sources`() {
        val without = EnrichmentSender.dedupeKey(mapOf("click_id" to "c1"), "deep_link")
        val with = EnrichmentSender.dedupeKey(
            mapOf("click_id" to "c1", DeeplinklyUserData.KEY_EMAIL to "ada@example.com"),
            "deep_link",
        )
        assertEquals(without, with)
    }

    // ------------------------------------------------------------- consent

    /**
     * Consent has to reach the service on the ordinary enrichment path: it is
     * what the forwarder reads when it decides whether a conversion may be
     * uploaded at all.
     */
    @Test
    fun `consent rides along on the payload`() {
        ConsentStore.merge(ConsentState.GRANTED, ConsentState.DENIED, isEea = true)

        send(mapOf("click_id" to "c1"))

        assertEquals(1, sent.size)
        assertEquals("granted", sent[0][ConsentStore.KEY_AD_USER_DATA])
        assertEquals("denied", sent[0][ConsentStore.KEY_AD_PERSONALIZATION])
        assertEquals("true", sent[0][ConsentStore.KEY_IS_EEA])
    }

    /**
     * The failure this guards against. A grant followed by a withdrawal reports
     * twice under one source, and an identity-only key would collapse the
     * withdrawal into the grant — losing precisely the update that must not be
     * lost.
     */
    @Test
    fun `a withdrawal after a grant is not deduped away`() {
        ConsentStore.merge(ConsentState.GRANTED, ConsentState.GRANTED, null)
        send(emptyMap(), source = "consent", force = true)

        ConsentStore.merge(ConsentState.DENIED, ConsentState.DENIED, null)
        send(emptyMap(), source = "consent", force = true)

        assertEquals(2, sent.size)
        assertEquals("denied", sent[1][ConsentStore.KEY_AD_USER_DATA])
    }

    /** A banner re-reporting the same answer is still one report. */
    @Test
    fun `an unchanged consent report is deduped`() {
        ConsentStore.merge(ConsentState.GRANTED, ConsentState.GRANTED, null)
        send(emptyMap(), source = "consent", force = true)
        send(emptyMap(), source = "consent", force = true)

        assertEquals(1, sent.size)
    }

    /**
     * Consent is `minimal` tier, so it survives every level that sends anything
     * at all. A level that stripped it would leave the forwarder unable to tell
     * a denial from an app that never asked.
     */
    @Test
    fun `consent survives the strictest level that still sends`() {
        AttributionLevel.set(AttributionLevel.MINIMAL)
        ConsentStore.merge(ConsentState.DENIED, ConsentState.DENIED, isEea = true)

        send(mapOf("click_id" to "c1"))

        assertEquals(1, sent.size)
        assertEquals("denied", sent[0][ConsentStore.KEY_AD_USER_DATA])
    }

    // ---------------------------------------------------------- push token

    @Test
    fun `the push token rides along on the payload`() {
        PushTokenStore.set("tok-123", PushProvider.FCM)

        send(mapOf("click_id" to "c1"))

        assertEquals(1, sent.size)
        assertEquals("tok-123", sent[0]["push_token"])
        assertEquals("fcm", sent[0]["push_provider"])
    }

    /**
     * Tokens rotate. A rotation reports under the same source and must not
     * collapse into the first report, or the prober keeps pinging a token that
     * no longer resolves and reads the failure as an uninstall.
     */
    @Test
    fun `a rotated push token is not deduped away`() {
        PushTokenStore.set("tok-123", PushProvider.FCM)
        send(emptyMap(), source = "push_token", force = true)

        PushTokenStore.set("tok-456", PushProvider.FCM)
        send(emptyMap(), source = "push_token", force = true)

        assertEquals(2, sent.size)
        assertEquals("tok-456", sent[1]["push_token"])
    }

    /**
     * `push_token` is FULL tier: a unique per-install identifier a server can
     * address. An app at REDUCED does not report it and does not get uninstall
     * measurement. That is the level working, and pinning it here means a
     * future reclassification has to be deliberate.
     */
    @Test
    fun `the push token is dropped below full`() {
        AttributionLevel.set(AttributionLevel.REDUCED)
        PushTokenStore.set("tok-123", PushProvider.FCM)

        send(mapOf("click_id" to "c1"))

        assertEquals(1, sent.size)
        assertNull(sent[0]["push_token"])
        assertNull(sent[0]["push_provider"])
    }

    /**
     * A dedupe key becomes the name of a SharedPreferences entry, and a push
     * token is an addressable identifier. It has to be digested, like the email
     * address above, rather than written in plain.
     */
    @Test
    fun `the dedupe key does not contain the push token itself`() {
        val key = EnrichmentSender.dedupeKey(mapOf("push_token" to "tok-123"), "push_token")
        assertFalse(key.contains("tok-123"))
    }
}
