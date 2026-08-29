package com.deeplinkly.android_deeplinkly

/**
 * What a generated link points at.
 *
 * [toPayload] produces the same snake_case wire keys the Dart models produce,
 * so a link generated natively and one generated through Flutter are
 * indistinguishable to the service.
 */
data class DeeplinklyContent(
    /** Stable identifier for the thing being linked, e.g. `product/sku_42`. */
    val canonicalIdentifier: String,
    val title: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    /** Arbitrary values delivered back to the app when the link opens. */
    val metadata: Map<String, Any?> = emptyMap(),
) {
    fun toPayload(): Map<String, Any?> = buildMap {
        put("canonical_identifier", canonicalIdentifier)
        title?.let { put("title", it) }
        description?.let { put("description", it) }
        imageUrl?.let { put("image_url", it) }
        put("metadata", metadata)
    }
}

/** Campaign attribution for a generated link. */
data class DeeplinklyLinkOptions(
    val channel: String,
    val feature: String,
    /** Omitted from the payload when null or empty, matching the Dart model. */
    val tags: List<String>? = null,
) {
    fun toPayload(): Map<String, Any?> = buildMap {
        put("channel", channel)
        put("feature", feature)
        tags?.takeIf { it.isNotEmpty() }?.let { put("tags", it) }
    }
}

/** The outcome of a link generation request. */
data class DeeplinklyResult(
    val success: Boolean,
    val url: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {
    companion object {
        /**
         * Reads the flat map the network layer returns.
         *
         * Same keys the method channel carries, so the Flutter bridge and a
         * native caller read one shape.
         */
        fun fromMap(map: Map<*, *>): DeeplinklyResult = DeeplinklyResult(
            success = map["success"] as? Boolean ?: false,
            url = map["url"] as? String,
            errorCode = map["error_code"] as? String,
            errorMessage = map["error_message"] as? String,
        )

        /** The answer when the SDK has no API key. */
        fun disabled(): DeeplinklyResult = DeeplinklyResult(
            success = false,
            errorCode = "SDK_DISABLED",
            errorMessage = "Deeplinkly SDK is disabled (missing API key).",
        )
    }
}
