package com.deeplinkly.android_deeplinkly

/**
 * Validation and normalisation for [Deeplinkly.setUserData].
 *
 * These are the fields a conversion is matched on once it reaches Meta's
 * Conversions API or Google's enhanced conversions. On an iOS device where ATT
 * was denied — the majority case — a hashed email is the *only* match key that
 * still exists, so a wrong or dropped value here is a conversion that goes
 * unattributed rather than a cosmetic problem.
 *
 * ## Nothing is hashed here
 *
 * Values are stored and sent as the host app supplied them, and hashed only
 * when a conversion is forwarded. Hashing on device would look like the safer
 * choice and buy nothing: SHA-256 of a normalised email is not one-way against
 * an address someone already has — it is precisely the value Meta matches on,
 * so anyone holding the digest holds the match key. Keeping the plaintext is
 * what lets the service normalise per destination (Meta and Google disagree
 * about phone formatting) and re-derive when a platform changes its rules.
 *
 * ## Normalisation is deliberately shallow
 *
 * Trimming only, except where a value has to fit a column that cannot hold the
 * alternative. Lowercasing an email, or stripping punctuation out of a name, is
 * a destination's rule rather than a fact about the value, and doing it here
 * would throw away what the app actually knows before the service can decide
 * what each destination wants.
 *
 * The three constrained fields are the exception. `user_gender` is one
 * character, `user_country` is two, and `user_date_of_birth` is ten; a value
 * that does not fit is rejected rather than truncated, because the truncation
 * of "non-binary" is "n", which is a value Meta would happily match on and
 * would be wrong.
 */
object DeeplinklyUserData {

    /** Wire key for the host app's own identifier for the person. */
    const val KEY_USER_ID = "custom_user_id"

    const val KEY_EMAIL = "user_email"
    const val KEY_PHONE = "user_phone"
    const val KEY_FIRST_NAME = "user_first_name"
    const val KEY_LAST_NAME = "user_last_name"
    const val KEY_DATE_OF_BIRTH = "user_date_of_birth"
    const val KEY_GENDER = "user_gender"
    const val KEY_STREET = "user_street"
    const val KEY_CITY = "user_city"
    const val KEY_STATE = "user_state"
    const val KEY_ZIP = "user_zip"
    const val KEY_COUNTRY = "user_country"

    /**
     * Host-supplied identifiers that are not one of the twelve typed fields,
     * carried as one JSON object.
     *
     * This exists because an app binary is frozen for as long as its release
     * cycle, and the typed field list is not. When a customer needs to join
     * attribution to a product-analytics tool — a Mixpanel distinct id, an
     * Amplitude device id, a CleverTap id — adding a thirteenth named field
     * would mean waiting for every host app to ship again. A single open field
     * moves that from an SDK release to a service deploy.
     *
     * One JSON key rather than `user_custom_*` wire keys on purpose: the
     * catalogue is a closed set that the published inventory and the
     * `ErrorLog` redaction both derive from, and letting callers invent wire
     * keys would make it neither closed nor generated.
     */
    const val KEY_CUSTOM_DATA = "user_custom_data"

    /**
     * Every `user_*` key, and the length the catalogue gives it.
     *
     * `custom_user_id` is absent on purpose: it is user-scoped in the catalogue
     * too, but it has always been stored in its own preference and read from
     * there by the header path, so [UserDataStore] does not own a second copy.
     * See [Deeplinkly.setUserData].
     */
    val MAX_LENGTHS: Map<String, Int> = mapOf(
        KEY_EMAIL to 320,
        // 64, not the 32 a phone number needs: with PIIHashing on this field
        // carries a SHA-256 hex digest instead. Matches the catalogue's max_len
        // and the service column.
        KEY_PHONE to 64,
        KEY_FIRST_NAME to 128,
        KEY_LAST_NAME to 128,
        KEY_DATE_OF_BIRTH to 10,
        KEY_GENDER to 1,
        KEY_STREET to 256,
        KEY_CITY to 128,
        KEY_STATE to 128,
        KEY_ZIP to 32,
        KEY_COUNTRY to 2,
        KEY_CUSTOM_DATA to 4096,
    )

    /** Caps on the map [KEY_CUSTOM_DATA] is built from. */
    const val MAX_CUSTOM_ENTRIES = 10
    const val MAX_CUSTOM_KEY_LENGTH = 64
    const val MAX_CUSTOM_VALUE_LENGTH = 256

    /** The keys [UserDataStore] may hold. */
    val KEYS: Set<String> get() = MAX_LENGTHS.keys

    /** Why a call to [Deeplinkly.setUserData] was rejected. Debug logs only. */
    class Rejection(val reason: String)

    private val DATE_OF_BIRTH = Regex("""^\d{4}-\d{2}-\d{2}$""")

    /**
     * Normalises one field, or explains why it cannot be stored.
     *
     * A blank value answers `null to null`: the caller passed nothing for this
     * field, which merges as "leave whatever is there alone".
     */
    internal fun normalize(key: String, raw: String?): Pair<String?, Rejection?> {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null to null

        val normalized = when (key) {
            // Uppercased rather than rejected for case: an app that stores "us"
            // is not making a mistake, and ISO-3166-1 alpha-2 is defined
            // uppercase.
            KEY_COUNTRY -> value.uppercase()
            // Meta's `ge` is "m" or "f". Anything else is not a narrower
            // vocabulary we can coerce into, so it is refused rather than
            // mangled into a letter that means something we were not told.
            KEY_GENDER -> value.lowercase().also {
                if (it != "m" && it != "f") {
                    return null to Rejection(
                        "$key must be \"m\" or \"f\"; got \"$value\""
                    )
                }
            }
            KEY_DATE_OF_BIRTH -> value.also {
                if (!DATE_OF_BIRTH.matches(it)) {
                    return null to Rejection("$key must be YYYY-MM-DD; got \"$value\"")
                }
            }
            else -> value
        }

        val limit = MAX_LENGTHS[key]
            ?: return null to Rejection("$key is not a user-data field")
        if (normalized.length > limit) {
            return null to Rejection(
                "$key exceeds $limit characters (${normalized.length})"
            )
        }
        return normalized to null
    }

    /**
     * Normalises a whole call.
     *
     * All or nothing: one bad field rejects the call rather than storing the
     * rest, so a caller is never left guessing which of the twelve values
     * actually took. Keys whose value is null or blank are simply absent from
     * the result, which is what makes [Deeplinkly.setUserData] merge.
     */
    fun normalizeAll(fields: Map<String, String?>): Result {
        val out = LinkedHashMap<String, String>()
        for ((key, raw) in fields) {
            val (value, rejection) = normalize(key, raw)
            if (rejection != null) return Result(null, rejection)
            if (value != null) out[key] = value
        }
        return Result(out, null)
    }

    /**
     * Encodes [custom] into the JSON object [KEY_CUSTOM_DATA] holds.
     *
     * Bounded on entry count, key length and value length so a caller cannot
     * turn an open field into an unbounded one. The caps mirror event
     * parameters, which is the other place a host app hands us arbitrary keys.
     *
     * Values are trimmed and otherwise sent as supplied. Nothing here is
     * hashed, for the reason in the class note: what the service needs is the
     * value the app actually holds, so it can normalise per destination.
     *
     * An empty or null map answers `null to null` — absent, which merges as
     * "leave whatever is there alone", matching every other field.
     */
    internal fun encodeCustomData(
        custom: Map<String, String?>?,
    ): Pair<String?, Rejection?> {
        if (custom.isNullOrEmpty()) return null to null

        val kept = LinkedHashMap<String, String>()
        for ((rawKey, rawValue) in custom) {
            val key = rawKey.trim()
            val value = rawValue?.trim().orEmpty()
            // A blank value is a caller saying nothing about this key, not a
            // request to erase it. clearUserData() is what erases.
            if (key.isEmpty() || value.isEmpty()) continue
            if (key.length > MAX_CUSTOM_KEY_LENGTH) {
                return null to Rejection(
                    "custom data key \"$key\" exceeds $MAX_CUSTOM_KEY_LENGTH characters"
                )
            }
            if (value.length > MAX_CUSTOM_VALUE_LENGTH) {
                return null to Rejection(
                    "custom data value for \"$key\" exceeds " +
                        "$MAX_CUSTOM_VALUE_LENGTH characters"
                )
            }
            kept[key] = value
        }
        if (kept.isEmpty()) return null to null
        if (kept.size > MAX_CUSTOM_ENTRIES) {
            return null to Rejection(
                "custom data holds more than $MAX_CUSTOM_ENTRIES entries (${kept.size})"
            )
        }

        // Sorted so the same map always encodes to the same string. An
        // unstable blob would look like a changed value to the merge on the far
        // side and rewrite a column that did not change. iOS sorts too.
        val json = org.json.JSONObject()
        for (key in kept.keys.sorted()) json.put(key, kept[key])
        return json.toString() to null
    }

    /** Either the normalised fields or the reason there are none. */
    class Result(val fields: Map<String, String>?, val rejection: Rejection?)
}
