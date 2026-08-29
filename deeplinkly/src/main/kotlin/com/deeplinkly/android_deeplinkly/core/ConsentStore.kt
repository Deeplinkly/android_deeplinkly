package com.deeplinkly.android_deeplinkly.core

import com.deeplinkly.android_deeplinkly.privacy.ConsentState
import org.json.JSONObject

/**
 * The advertising-consent state the host app last reported.
 *
 * One preference key holding one JSON object, for the same reason
 * [UserDataStore] does it: [InstallIdentity] keeps an explicit list of keys
 * that survive a backup restore, and three separately-named preferences would
 * be three chances to forget one of them. One key is one entry on that list.
 *
 * ## Why this is not `user` scope in the catalogue
 *
 * Consent is not a fact about the person the way an email address is — it is a
 * decision about *us*. Two consequences follow, and both are why these keys are
 * classified `dynamic` and stored here rather than in [UserDataStore]:
 *
 *  - `clearUserData()` must not wipe it. Signing out is not withdrawing
 *    consent, and a consent record that vanishes on sign-out is the one a
 *    regulator asks about.
 *  - It must not be redacted out of error logs. `PII_KEY_NAMES` on the service
 *    is derived from the catalogue's `user` scope, so anything classified there
 *    disappears from a stored request body — correct for an email address, and
 *    exactly wrong for the field you need to answer "why was this conversion
 *    not forwarded".
 *
 * ## Merging
 *
 * [merge] leaves an argument that was not supplied alone, so an app can report
 * the EEA determination at launch and the two consent answers when the banner
 * is answered, without the second call blanking the first.
 *
 * There is no `clear()`. Withdrawing consent is [ConsentState.DENIED], which is
 * a value the forwarder must see and act on; deleting the record instead would
 * read downstream as "this app has no consent model", which is a different and
 * much weaker statement.
 */
object ConsentStore {
    internal const val KEY = "dl_consent"

    const val KEY_AD_USER_DATA = "consent_ad_user_data"
    const val KEY_AD_PERSONALIZATION = "consent_ad_personalization"
    const val KEY_IS_EEA = "consent_is_eea"

    private val KEYS = setOf(KEY_AD_USER_DATA, KEY_AD_PERSONALIZATION, KEY_IS_EEA)

    /** Fields to merge into the enrichment payload. Empty when nothing is set. */
    fun get(): Map<String, String> {
        val raw = Prefs.of().getString(KEY, null) ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            val out = LinkedHashMap<String, String>()
            for (key in KEYS) {
                if (json.has(key)) out[key] = json.optString(key, "")
            }
            out
        } catch (e: Exception) {
            Logger.w("ConsentStore: unreadable payload, discarding (${e.message})")
            Prefs.of().edit().remove(KEY).apply()
            emptyMap()
        }
    }

    /**
     * Merges the supplied answers over what is stored. Nulls are left alone.
     *
     * @return true if anything changed. The caller uses this to decide whether
     *   a send is worth making: a consent banner that re-reports the same
     *   answer on every launch is the common case, and it must not produce an
     *   enrichment every time.
     */
    fun merge(
        adUserData: ConsentState?,
        adPersonalization: ConsentState?,
        isEea: Boolean?,
    ): Boolean {
        if (adUserData == null && adPersonalization == null && isEea == null) return false

        val current = get()
        val next = LinkedHashMap(current)
        adUserData?.let { next[KEY_AD_USER_DATA] = it.wireName }
        adPersonalization?.let { next[KEY_AD_PERSONALIZATION] = it.wireName }
        isEea?.let { next[KEY_IS_EEA] = it.toString() }

        if (next == current) return false

        val json = JSONObject()
        for ((key, value) in next) json.put(key, value)
        Prefs.of().edit().putString(KEY, json.toString()).apply()
        return true
    }

    /** Whether the host app has ever reported a consent answer. */
    fun isEmpty(): Boolean = get().isEmpty()

    internal fun resetForTesting() {
        Prefs.of().edit().remove(KEY).apply()
    }
}
