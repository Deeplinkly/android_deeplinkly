package com.deeplinkly.android_deeplinkly.privacy

import com.deeplinkly.android_deeplinkly.DeeplinklyUserData
import com.deeplinkly.android_deeplinkly.core.Prefs
import java.security.MessageDigest

/**
 * On-device SHA-256 hashing of the identifying fields, off by default.
 *
 * Off by default deliberately, and the default is the interesting decision.
 * Hashing on the device is not obviously safer: a digest of a normalised email
 * is exactly the value Meta matches on, so anyone holding it holds the match
 * key. What it does buy is that plaintext never reaches our servers, which is a
 * requirement some compliance teams state outright and will not negotiate.
 *
 * The cost is real and falls on attribution quality. A digest is computed once,
 * here, under one normalisation — and Meta and Google do not agree about phone
 * formatting. With hashing on, a conversion forwarded to a destination whose
 * rules differ from the ones below will not match. The service can no longer
 * re-derive per destination, because the value it would need is gone. That
 * trade is the customer's to make, which is why this is a switch and not a
 * default.
 *
 * ## Which fields
 *
 * Only [HASHED_FIELDS] — email, phone, first and last name.
 *
 * Hashing the others would be theatre. `user_gender` has two permitted values,
 * `user_country` about 250 and `user_date_of_birth` a few tens of thousands;
 * a digest over a domain that small is reversed by enumerating it. It would
 * cost real column width to store and buy no confidentiality at all.
 *
 * ## Where it happens
 *
 * At send time, not at store time. [com.deeplinkly.android_deeplinkly.core.UserDataStore]
 * keeps what the app supplied so that turning the switch back off is possible
 * and so a value can still be normalised differently later if it was never
 * hashed. The raw value still never leaves the device, which is the whole
 * promise.
 *
 * The Swift twin is `PIIHashing`. Both must normalise identically, and so must
 * whatever resolves an erasure request, or a digest computed on this device
 * will not match the one an erasure is looked up by — and that failure is
 * silent. Change the rules below only together.
 */
object PIIHashing {

    private const val KEY = "dl_pii_hashing_enabled"

    /** Wire key reporting the mode to the service. `dynamic` in the catalogue. */
    const val KEY_PII_HASHING_ENABLED = "pii_hashing_enabled"

    /**
     * The fields hashed when the switch is on.
     *
     * Derived names rather than literals so a rename cannot leave this pointing
     * at a key that no longer exists.
     */
    val HASHED_FIELDS: Set<String> = setOf(
        DeeplinklyUserData.KEY_EMAIL,
        DeeplinklyUserData.KEY_PHONE,
        DeeplinklyUserData.KEY_FIRST_NAME,
        DeeplinklyUserData.KEY_LAST_NAME,
    )

    fun isEnabled(): Boolean = Prefs.of().getBoolean(KEY, false)

    fun setEnabled(enabled: Boolean) {
        Prefs.of().edit().putBoolean(KEY, enabled).apply()
    }

    /**
     * The value a digest is taken over, or null when there is nothing to hash.
     *
     * Minimal on purpose. Phone strips every non-digit, which folds
     * `+44 20 7946 0000` and `442079460000` together but does *not* understand
     * country codes or trunk prefixes — `+44 (0)20 ...` keeps that `0` and
     * hashes differently. Doing better needs a phone-number library and a
     * default region, and there is no way to have identical ones in Kotlin,
     * Swift, Dart and Python. An app turning this on must send one consistent
     * format.
     */
    internal fun normalize(field: String, value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        if (field == DeeplinklyUserData.KEY_PHONE) {
            val digits = trimmed.filter { it.isDigit() }
            return digits.ifEmpty { null }
        }
        return trimmed.lowercase()
    }

    /** SHA-256 of the normalised value, lowercase hex, or null. */
    internal fun digest(field: String, value: String): String? {
        val normalized = normalize(field, value) ?: return null
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) out.append("%02x".format(byte))
        return out.toString()
    }

    /**
     * Replaces the hashable fields of an outgoing payload with their digests.
     *
     * Returns [fields] unchanged when the switch is off.
     *
     * **Empty values are never hashed.** An empty string is a tombstone written
     * by `clearUserData()` and read by the service as "null this column";
     * hashing it would produce a digest of nothing, which the service would
     * store as a value and the erasure would silently not have happened.
     */
    fun apply(fields: Map<String, String>): Map<String, String> {
        if (!isEnabled()) return fields
        val out = LinkedHashMap<String, String>(fields.size)
        for ((key, value) in fields) {
            out[key] = if (key in HASHED_FIELDS && value.isNotEmpty()) {
                digest(key, value) ?: value
            } else {
                value
            }
        }
        return out
    }
}
