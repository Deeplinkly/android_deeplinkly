package com.deeplinkly.android_deeplinkly

/**
 * A resolved deep link, handed to a [DeeplinklyDeepLinkListener].
 *
 * [raw] is the payload exactly as it was resolved and persisted, and the
 * accessors below only read from it. That indirection is deliberate: the
 * Flutter bridge forwards [raw] over the method channel unchanged, so Dart's
 * `{click_id, params}` envelope stays byte-identical to what it
 * was before this type existed. Parsing into fields and re-serialising would
 * put a lossy round trip in the one path that has to be exact.
 *
 * The same map is what [com.deeplinkly.android_deeplinkly.queue.DeepLinkQueue]
 * stores, so a link recovered from the queue after a process death is
 * indistinguishable from one delivered live.
 */
class DeeplinklyDeepLink internal constructor(
    /** The resolved payload. Forwarded verbatim by the Flutter bridge. */
    val raw: Map<String, Any?>,
    /**
     * How this link reached the SDK: `deep_link`, `deep_link_fallback`, or
     * `install_referrer`. Matches `attribution_source` in reporting.
     */
    val source: String,
) {
    /** Service click id, or null when the service did not recognise the click. */
    val clickId: String?
        get() = raw["click_id"] as? String

    /**
     * The link's own parameters.
     *
     * Populated from the service when `/resolve` answered, and from the URL
     * itself when it could not be reached, so a single read path covers both.
     */
    @Suppress("UNCHECKED_CAST")
    val params: Map<String, Any?>
        get() = raw["params"] as? Map<String, Any?> ?: emptyMap()

    override fun toString(): String =
        "DeeplinklyDeepLink(source=$source, clickId=$clickId, params=$params)"
}
