// FILE: com/deeplinkly/android_deeplinkly/attribution/EnrichmentSender.kt
package com.deeplinkly.android_deeplinkly.attribution

import com.deeplinkly.android_deeplinkly.core.ConsentStore
import com.deeplinkly.android_deeplinkly.core.DeeplinklyUtils
import com.deeplinkly.android_deeplinkly.core.DeviceProfile
import com.deeplinkly.android_deeplinkly.core.DynamicSignals
import com.deeplinkly.android_deeplinkly.core.Logger
import com.deeplinkly.android_deeplinkly.core.Prefs
import com.deeplinkly.android_deeplinkly.DeeplinklyUserData
import com.deeplinkly.android_deeplinkly.core.UserDataStore
import com.deeplinkly.android_deeplinkly.privacy.PIIHashing
import com.deeplinkly.android_deeplinkly.network.DeeplinklyNetwork
import com.deeplinkly.android_deeplinkly.privacy.AttributionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The one place an enrichment payload is assembled.
 *
 * Callers pass only the link identity — which click, which campaign, which
 * source. The device description is added here, at send time, from the cached
 * static profile plus a fresh dynamic sample. Nothing device-shaped is carried
 * in from a caller or a queue, so a payload replayed from storage days later
 * still describes the device as it is now rather than as it was.
 */
object EnrichmentSender {

    /** Sources that describe a lifecycle moment rather than a link. */
    private val LIFECYCLE_SOURCES = setOf("app_start", "app_open")

    /** Any one of these makes a payload worth sending on its own. */
    private val ATTRIBUTION_KEYS = listOf(
        "click_id", "code", "utm_source", "utm_medium", "utm_campaign",
        "gclid", "fbclid", "ttclid", "gbraid", "wbraid",
        "gad_source", "gad_campaignid",
    )

    /** What makes one enrichment a different report from another. */
    private val IDENTITY_KEYS = listOf("click_id", "code", "custom_user_id")

    /** The source `setUserData`/`clearUserData` report under. */
    internal const val USER_DATA_SOURCE = "user_data"

    /** The source `setConsent` reports under. */
    internal const val CONSENT_SOURCE = "consent"

    /** The source `setPushToken` reports under. */
    internal const val PUSH_TOKEN_SOURCE = "push_token"

    /**
     * Sources whose dedupe key must include the payload's own content, and
     * which keys make up that content.
     *
     * A source is in here when the thing being reported is the payload rather
     * than the link — see [dedupeKey]. Derived names rather than literals
     * wherever the owning type already publishes them, so a twelfth user field
     * or a fourth consent answer joins this automatically.
     */
    private val CONTENT_KEYED_SOURCES: Map<String, Set<String>> = mapOf(
        USER_DATA_SOURCE to DeeplinklyUserData.KEYS,
        CONSENT_SOURCE to setOf(
            ConsentStore.KEY_AD_USER_DATA,
            ConsentStore.KEY_AD_PERSONALIZATION,
            ConsentStore.KEY_IS_EEA,
        ),
        PUSH_TOKEN_SOURCE to setOf("push_token", "push_provider"),
    )

    /**
     * How a payload leaves the device, and whether it arrived.
     *
     * Indirection for one reason: the dedupe latch below closes on the answer,
     * and "latched a payload that never arrived" is the exact bug iOS shipped
     * once already. A test can only pin that down if it can decide what the
     * transport reports. Production never reassigns this.
     */
    internal var transport: (Map<String, String?>, String) -> Boolean =
        DeeplinklyNetwork::sendEnrichment

    /**
     * @param attributionData link identity only: click_id/code, source, the
     *   UTMs, the ad-click ids, the install referrer. Device signals passed
     *   here would be overwritten.
     * @param force send even without attribution evidence. Used when the thing
     *   being reported is not a link — linking a login, or a startup wait that
     *   timed out. An install with no link behind it is still an install.
     */
    suspend fun sendOnce(
        attributionData: Map<String, String?>,
        source: String,
        apiKey: String,
        force: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        DeeplinklyUtils.guardTracking {
            val level = AttributionLevel.current
            if (!level.allowsEnrichment) {
                Logger.d("Attribution level is none; not sending enrichment.")
                return@guardTracking
            }

            val payload = DynamicSignals.assemble(DeviceProfile.get()).toMutableMap()
            payload["custom_user_id"] = DeeplinklyUtils.getCustomUserId()
            // What the host app told us about the person, if anything. Read
            // here rather than passed in for the same reason the device profile
            // is: a payload replayed out of the retry queue days later should
            // carry what we know now, and a caller has no business supplying
            // someone else's details on one particular enrichment.
            //
            // Empty values are meaningful here and must survive — see
            // UserDataStore.clear.
            // Hashed here, if the app asked for it, rather than in the store:
            // what is kept on the device stays as supplied so the switch can be
            // turned back off. The raw value still never leaves the device.
            payload.putAll(PIIHashing.apply(UserDataStore.get()))
            // Consent and the push token are not read here: they are `dynamic`
            // scope and come from DynamicSignals.assemble above, which is what
            // keeps the catalogue's scope and the producing collector the same
            // fact. iOS pins that with SignalCoverageTests.
            payload.putAll(attributionData)

            // Reported so the service can tell a thin payload from a missing
            // one. Both must survive MINIMAL — explaining why a payload is
            // small is the one thing that stays useful at every level.
            payload["collected_at"] = isoUtc(System.currentTimeMillis())
            payload["attribution_level"] = level.wireName

            // Filter last, so what we test below is exactly what goes out.
            val filtered = level.filter(payload).toMutableMap()

            // Deduped on what is being reported, not on the source alone: a
            // second deep link is a genuinely new event and must go out, while
            // the same link arriving twice — a queued resolve replayed after
            // the live one, say — must not. This is the latch iOS has had
            // since its source-keyed version was fixed; Android had none, so
            // every retry path sent a duplicate.
            //
            // Lifecycle sources are exempt: they are rate-limited by their own
            // callers, and latching here would drop a fresh dynamic sample on
            // the floor rather than merely collapsing a duplicate.
            val isLifecycle = source in LIFECYCLE_SOURCES
            val key = dedupeKey(filtered, source)
            if (!isLifecycle && Prefs.of().getBoolean(key, false)) {
                Logger.d("Skipping enrichment: already sent for $key")
                return@guardTracking
            }

            val hasAttribution = ATTRIBUTION_KEYS.any { !filtered[it].isNullOrBlank() }
            if (!hasAttribution && !force && !isLifecycle) {
                Logger.d("Skipping enrichment: no attribution data")
                return@guardTracking
            }

            Logger.d("Sending enrichment for $source at level ${level.wireName}")

            // Latched only once the payload is actually delivered. Latching up
            // front marks a permanently failing enrichment as sent.
            val delivered = transport(filtered, apiKey)
            if (delivered && !isLifecycle) {
                Prefs.of().edit().putBoolean(key, true).apply()
            }
        }
    }

    /**
     * Identity of this enrichment: the source plus whatever attribution it
     * carries. Two calls that would report the same thing collapse to one.
     *
     * Built from the filtered payload, so the key describes what actually goes
     * out. All three identity keys are `minimal` tier in the catalogue, so the
     * key stays as specific at MINIMAL as it is at FULL.
     */
    internal fun dedupeKey(data: Map<String, String?>, source: String): String {
        val identity = IDENTITY_KEYS
            .mapNotNull { key -> data[key]?.takeIf { it.isNotEmpty() }?.let { "$key=$it" } }
            .joinToString("&")
        // Not hashCode(): parity with iOS, where per-process String hash seeding
        // made a hashed key differ on every launch and dedupe nothing.
        val base =
            if (identity.isEmpty()) "${source}_enriched" else "${source}_enriched_$identity"
        // For these sources the payload *is* what is being reported, so its
        // content has to be part of what makes two reports different. Without
        // it, a second setUserData call under the same custom_user_id — adding
        // an address to an email already sent, the common case — produces the
        // same key as the first and is latched away, never reaching us. The
        // same is true of a consent answer that changes from granted to denied
        // and of a rotated push token: exactly the updates that must not be
        // dropped are the ones the identity-only key cannot tell apart.
        //
        // A digest rather than the values, because this string becomes the name
        // of a SharedPreferences key: writing someone's email address — or a
        // push token — into one would put it somewhere neither clearUserData
        // nor the tombstone can reach.
        val contentKeys = CONTENT_KEYED_SOURCES[source] ?: return base
        val fingerprint = contentKeys
            .sorted()
            .mapNotNull { key -> data[key]?.let { "$key=$it" } }
            .joinToString("&")
        return if (fingerprint.isEmpty()) base else "${base}_${stableDigest(fingerprint)}"
    }

    /**
     * FNV-1a, 64-bit, rendered hex.
     *
     * Written out rather than reached for because the two things a JVM offers
     * are both wrong here: `String.hashCode` is 32-bit and collides at this
     * size, and `MessageDigest` is a heavier dependency than a dedupe key
     * warrants. What matters is that it is stable across launches and identical
     * to the Swift twin, and it is both.
     */
    internal fun stableDigest(value: String): String {
        var hash = -0x340d631b7bdddcdbL // 14695981039346656037 unsigned
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= 0x100000001b3L
        }
        return java.lang.Long.toHexString(hash)
    }

    private fun isoUtc(millis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(millis))
    }
}
