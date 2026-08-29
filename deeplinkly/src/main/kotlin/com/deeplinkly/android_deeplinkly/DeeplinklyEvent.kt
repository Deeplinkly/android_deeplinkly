package com.deeplinkly.android_deeplinkly

import org.json.JSONArray
import org.json.JSONObject

/**
 * Validation for [Deeplinkly.logEvent].
 *
 * These rules used to live in Dart, which meant they only applied to Flutter
 * callers - a native integrator got none of them, and the service saw payloads
 * the documented contract says are impossible. They live here now so every host
 * gets the same answer, and Dart forwards rather than pre-checking.
 *
 * The limits are the documented ones and are asserted by the service too;
 * changing one here without changing it there will start silently truncating.
 */
object DeeplinklyEvent {
    const val MAX_NAME_LENGTH = 64
    const val MAX_PARAMS_COUNT = 25
    const val MAX_PARAM_KEY_LENGTH = 64
    const val MAX_PARAM_VALUE_LENGTH = 256

    /**
     * Reserved for the SDK's own bookkeeping (`_dl_event_seq`,
     * `_dl_session_id`, ...).
     *
     * The service excludes this prefix from the caller's parameter budget, so
     * letting a caller write one would both collide with the SDK's own values
     * and smuggle parameters past the count limit.
     */
    const val RESERVED_PARAM_PREFIX = "_dl_"

    /**
     * Keys any event may carry that mean something specific to us.
     *
     * Not `_dl_`-prefixed, so they cost a parameter and the tenant sees them in
     * their dashboard, which is the point — the amount of a sale is the first
     * thing someone reads off a purchase event. What the reservation buys is a
     * *shape*: the service lifts these two into typed columns, and Meta's
     * `custom_data.value`/`currency` and Google's conversion value both want a
     * number and a currency code rather than whatever a caller felt like.
     *
     * Checked here rather than only in [DeeplinklyPurchase] because `logEvent`
     * is public and untyped: a caller who spells a purchase out by hand gets
     * the same answer as one who uses the wrapper.
     */
    const val VALUE_PARAM = "value"
    const val CURRENCY_PARAM = "currency"

    private val CURRENCY = Regex("^[A-Za-z]{3}$")

    /** Why an event was rejected. Surfaced only in debug logs. */
    sealed class Rejection(val reason: String) {
        object EmptyName : Rejection("event name is blank")
        object NameTooLong : Rejection("event name exceeds $MAX_NAME_LENGTH characters")
        object TooManyParams : Rejection("more than $MAX_PARAMS_COUNT parameters")
        class BadKey(key: String, why: String) : Rejection("parameter key '$key': $why")
        class BadValue(key: String, why: String) : Rejection("parameter '$key': $why")
    }

    /**
     * Checks [name] and [parameters] against the documented limits.
     *
     * Returns null when the event is acceptable, or the reason it is not.
     * Note that the caller's keys are trimmed *for the check only* - the map is
     * forwarded exactly as supplied, matching what Dart did.
     */
    fun validate(name: String, parameters: Map<String, Any?>): Rejection? {
        val normalized = name.trim()
        if (normalized.isEmpty()) return Rejection.EmptyName
        if (normalized.length > MAX_NAME_LENGTH) return Rejection.NameTooLong
        if (parameters.size > MAX_PARAMS_COUNT) return Rejection.TooManyParams

        for ((rawKey, value) in parameters) {
            val key = rawKey.trim()
            if (key.isEmpty()) return Rejection.BadKey(rawKey, "is blank")
            if (key.length > MAX_PARAM_KEY_LENGTH) {
                return Rejection.BadKey(rawKey, "exceeds $MAX_PARAM_KEY_LENGTH characters")
            }
            if (key.startsWith(RESERVED_PARAM_PREFIX)) {
                return Rejection.BadKey(rawKey, "uses the reserved '$RESERVED_PARAM_PREFIX' prefix")
            }

            if (key == VALUE_PARAM) {
                val number = value as? Number
                    ?: return Rejection.BadValue(rawKey, "must be a number")
                val amount = number.toDouble()
                if (amount.isNaN() || amount.isInfinite()) {
                    return Rejection.BadValue(rawKey, "must be finite")
                }
                if (amount < 0) {
                    return Rejection.BadValue(rawKey, "must not be negative")
                }
                continue
            }
            if (key == CURRENCY_PARAM) {
                val code = value as? String
                    ?: return Rejection.BadValue(rawKey, "must be a string")
                if (!CURRENCY.matches(code.trim())) {
                    return Rejection.BadValue(
                        rawKey, "must be a 3-letter ISO-4217 code"
                    )
                }
                continue
            }

            when (value) {
                is String ->
                    if (value.length > MAX_PARAM_VALUE_LENGTH) {
                        return Rejection.BadValue(
                            rawKey, "exceeds $MAX_PARAM_VALUE_LENGTH characters"
                        )
                    }

                is Number, is Boolean -> Unit

                is List<*>, is Map<*, *> -> {
                    // Containers are stored as compact JSON text, so it is the
                    // encoded length the service measures - and truncates.
                    val encoded = try {
                        encodeCompactJson(value)
                    } catch (_: IllegalArgumentException) {
                        return Rejection.BadValue(rawKey, "is not JSON-encodable")
                    }
                    if (encoded.length > MAX_PARAM_VALUE_LENGTH) {
                        return Rejection.BadValue(
                            rawKey,
                            "encodes to ${encoded.length} characters, over $MAX_PARAM_VALUE_LENGTH"
                        )
                    }
                }

                else -> return Rejection.BadValue(
                    rawKey,
                    "has unsupported type ${value?.let { it::class.java.simpleName } ?: "null"}"
                )
            }
        }
        return null
    }

    /** The trimmed name actually sent, matching what Dart used to normalise. */
    fun normalizeName(name: String): String = name.trim()

    /**
     * Compact JSON, rejecting anything not representable.
     *
     * Written out rather than handed to `JSONObject(Map)` because org.json is
     * lenient: it stringifies types it does not understand instead of failing,
     * which would let a value through here that the service cannot store.
     */
    private fun encodeCompactJson(value: Any?): String =
        when (val wrapped = toJsonValue(value)) {
            is JSONObject -> wrapped.toString()
            is JSONArray -> wrapped.toString()
            else -> JSONArray().put(wrapped).toString().let { it.substring(1, it.length - 1) }
        }

    private fun toJsonValue(value: Any?): Any? = when (value) {
        null -> JSONObject.NULL
        is String, is Number, is Boolean -> value
        is List<*> -> JSONArray().also { arr -> value.forEach { arr.put(toJsonValue(it)) } }
        is Map<*, *> -> JSONObject().also { obj ->
            for ((k, v) in value) {
                if (k !is String) {
                    throw IllegalArgumentException("JSON object keys must be strings")
                }
                obj.put(k, toJsonValue(v))
            }
        }
        else -> throw IllegalArgumentException("unsupported type ${value::class.java.name}")
    }
}
