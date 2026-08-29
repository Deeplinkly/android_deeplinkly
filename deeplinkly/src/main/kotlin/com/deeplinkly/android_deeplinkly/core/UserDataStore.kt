package com.deeplinkly.android_deeplinkly.core

import com.deeplinkly.android_deeplinkly.DeeplinklyUserData
import org.json.JSONObject

/**
 * The person's own details, as the host app reported them.
 *
 * One preference key holding one JSON object, rather than a preference per
 * field. That is not only tidiness: [InstallIdentity] preserves an explicit
 * list of keys across a backup restore and [PrivacyData] on iOS deletes one,
 * and eleven separately-named fields would be eleven chances for the next
 * person adding a twelfth to forget one of those lists. One key is one entry.
 *
 * ## Clearing
 *
 * [clear] does not simply delete the blob. A key that stops being sent is
 * indistinguishable, at the service, from a key that was never sent — the
 * enrichment path skips absent values so a phone that failed to read its
 * carrier cannot blank the carrier we already know. So clearing writes a
 * tombstone instead: every key that currently holds a value is rewritten to the
 * empty string, which the service reads as "erase this column".
 *
 * The tombstone is kept rather than dropped after one send. Delivery is not
 * observable from here, and a clear that is silently lost because the device
 * was offline at that moment is the one failure this must not have. Empty keys
 * are idempotent on the far side, so re-sending them costs a few bytes and
 * nothing else.
 */
object UserDataStore {
    private const val KEY = "dl_user_data"

    /** Fields to merge into the enrichment payload. Empty when nothing is set. */
    fun get(): Map<String, String> {
        val raw = Prefs.of().getString(KEY, null) ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            val out = LinkedHashMap<String, String>()
            for (key in DeeplinklyUserData.KEYS) {
                if (json.has(key)) out[key] = json.optString(key, "")
            }
            out
        } catch (e: Exception) {
            // A blob we cannot parse is a blob we cannot honour a clear from
            // either, so it is not worth keeping.
            Logger.w("UserDataStore: unreadable payload, discarding (${e.message})")
            Prefs.of().edit().remove(KEY).apply()
            emptyMap()
        }
    }

    /**
     * Merges [fields] over what is stored.
     *
     * Merge, not replace: an app learns an email at sign-up and an address at
     * checkout, and the second call must not erase the first. Clearing one
     * field is deliberately not expressible — that is what [clear] is for.
     */
    fun merge(fields: Map<String, String>) {
        if (fields.isEmpty()) return
        val json = JSONObject()
        for ((key, value) in get()) json.put(key, value)
        for ((key, value) in fields) {
            if (key in DeeplinklyUserData.KEYS) json.put(key, value)
        }
        Prefs.of().edit().putString(KEY, json.toString()).apply()
    }

    /** Replaces every set field with the empty string. See the class note. */
    fun clear() {
        val tombstoned = get().keys.associateWith { "" }
        if (tombstoned.isEmpty()) {
            Prefs.of().edit().remove(KEY).apply()
            return
        }
        val json = JSONObject()
        for (key in tombstoned.keys) json.put(key, "")
        Prefs.of().edit().putString(KEY, json.toString()).apply()
    }

    /** Whether anything has ever been set, tombstones included. */
    fun isEmpty(): Boolean = get().isEmpty()
}
