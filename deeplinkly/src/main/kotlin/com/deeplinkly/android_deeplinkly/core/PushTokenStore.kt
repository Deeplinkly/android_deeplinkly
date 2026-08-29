package com.deeplinkly.android_deeplinkly.core

/**
 * The push token the host app last handed us, used to measure uninstalls.
 *
 * ## How the measurement works, and why the token is the whole of the SDK's part
 *
 * There is no uninstall callback on either platform. Every MMP detects one the
 * same way: send a silent, contentless push to the device periodically, and
 * read the failure. FCM answers `UNREGISTERED`/`NotRegistered` and APNs answers
 * 410 `Unregistered` once the app is gone. That is a server-side probe against
 * a stored token, so the only thing that must be compiled into the app is
 * getting the token out — which is why this exists in a build that ships months
 * before the prober does.
 *
 * ## Why the token is FULL tier
 *
 * A push token is a unique, stable, per-install identifier that a server can
 * address. That is the definition of the tier, and classifying it lower because
 * uninstall measurement is a nice feature would be the exact misclassification
 * the catalogue exists to prevent. Apps running at REDUCED or MINIMAL do not
 * report it and do not get uninstall numbers; that is the level working, not a
 * bug.
 *
 * ## Why it is not preserved across a backup restore
 *
 * [InstallIdentity] clears everything not on its allow-list, and this key is
 * deliberately absent from it. A restored token addresses the *old* device: it
 * either fails, and manufactures an uninstall that did not happen, or worse,
 * still resolves and attributes a second install to the first device's row.
 * The host app re-registers on first launch anyway, so the correct value
 * arrives within seconds of the wrong one being dropped.
 */
object PushTokenStore {
    internal const val KEY_TOKEN = "dl_push_token"
    internal const val KEY_PROVIDER = "dl_push_provider"

    /**
     * Longest token accepted. Must match `push_token`'s `max_len` in
     * `tool/signals.json`, which is what the service truncates at — the
     * generated Kotlin catalogue carries tier and scope but not lengths, so
     * this is restated here the same way [com.deeplinkly.android_deeplinkly.DeeplinklyUserData]
     * restates its own. Pinned by `PushTokenStoreTest`.
     *
     * 512 is generous on purpose: FCM tokens run ~160-260 characters and APNs
     * device tokens are 64 hex, but neither is specified as bounded and a
     * token that grows is better truncated by the service than silently
     * dropped here.
     */
    internal const val MAX_LENGTH = 512

    /**
     * Stores a token. A blank token removes what is held rather than storing
     * an empty string — the host app calling this with null is saying the
     * device has no token, and an empty value on the wire would be read by the
     * service as an erasure of a column that should simply stop being sent.
     *
     * @return true if the stored value changed. Tokens rotate rarely, and an
     *   app that re-reports the same one on every launch must not produce an
     *   enrichment each time.
     */
    fun set(token: String?, provider: PushProvider): Boolean {
        val trimmed = token?.trim().orEmpty()
        val prefs = Prefs.of()

        if (trimmed.isEmpty()) {
            if (prefs.getString(KEY_TOKEN, null) == null) return false
            prefs.edit().remove(KEY_TOKEN).remove(KEY_PROVIDER).apply()
            return true
        }

        if (trimmed.length > MAX_LENGTH) {
            Logger.w(
                "PushTokenStore: token is ${trimmed.length} chars, over the " +
                    "$MAX_LENGTH the catalogue allows; ignoring it"
            )
            return false
        }

        if (prefs.getString(KEY_TOKEN, null) == trimmed &&
            prefs.getString(KEY_PROVIDER, null) == provider.wireName
        ) {
            return false
        }

        prefs.edit()
            .putString(KEY_TOKEN, trimmed)
            .putString(KEY_PROVIDER, provider.wireName)
            .apply()
        return true
    }

    /** Fields to merge into the enrichment payload. Empty when unset. */
    fun get(): Map<String, String> {
        val prefs = Prefs.of()
        val token = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
            ?: return emptyMap()
        val provider = prefs.getString(KEY_PROVIDER, null)
            ?: PushProvider.FCM.wireName
        return mapOf("push_token" to token, "push_provider" to provider)
    }

    internal fun resetForTesting() {
        Prefs.of().edit().remove(KEY_TOKEN).remove(KEY_PROVIDER).apply()
    }
}

/**
 * Which push service a token addresses, so the prober knows what to speak.
 *
 * An Android app is normally [FCM], but a Flutter or React Native app shares
 * this store across both platforms, so the value is explicit rather than
 * inferred from the SDK it was set through.
 */
enum class PushProvider(val wireName: String) {
    /** Firebase Cloud Messaging. */
    FCM("fcm"),

    /** Apple Push Notification service. */
    APNS("apns");

    companion object {
        fun fromWireName(value: String?): PushProvider? {
            val text = value?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wireName == text }
        }
    }
}
