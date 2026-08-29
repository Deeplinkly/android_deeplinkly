package com.deeplinkly.android_deeplinkly.privacy

/**
 * A single advertising-consent answer, in Google's vocabulary.
 *
 * These are not Deeplinkly's invention and are deliberately not renamed: they
 * are the values Google Ads accepts on an uploaded conversion (`GRANTED`,
 * `DENIED`, `UNKNOWN`), so carrying them verbatim means the forwarder does no
 * translation and there is no mapping table to get backwards.
 *
 * ## Why UNKNOWN is not the same as never calling setConsent
 *
 * [UNKNOWN] is a positive statement: the app asked, or had the chance to ask,
 * and does not have an answer — a banner dismissed without a choice, a returning
 * user whose stored decision has expired. Never calling
 * `Deeplinkly.setConsent` at all leaves the field absent, which says the app has
 * no consent model wired up.
 *
 * Google treats them differently (absent is `CONSENT_UNSPECIFIED`), so the
 * distinction survives all the way to the wire rather than being flattened
 * here. That is also why there is no `NOT_SET` case: absence is expressed by
 * not setting the field, not by a fourth enum value that would have to be
 * special-cased in every layer.
 */
enum class ConsentState(val wireName: String) {
    /** The person agreed. */
    GRANTED("granted"),

    /** The person declined. */
    DENIED("denied"),

    /** Asked, no answer. See the note above on why this is not absence. */
    UNKNOWN("unknown");

    companion object {
        /** Parses a wire name back, case-insensitively. Null if unrecognised. */
        fun fromWireName(value: String?): ConsentState? {
            val text = value?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wireName == text }
        }
    }
}
