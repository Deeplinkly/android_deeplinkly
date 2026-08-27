package com.deeplinkly.android_deeplinkly

/**
 * The parameter contract behind [Deeplinkly.logPurchase].
 *
 * Split out of `Deeplinkly` for the same reason [DeeplinklyEvent] was: the
 * rules have to be identical for a native caller and a Flutter or React Native
 * one, and the only way to guarantee that is for all four to reach the same
 * code rather than each re-implementing it a layer up.
 *
 * The keys here are reserved but *not* `_dl_`-prefixed, and that is deliberate.
 * A prefixed key is hidden from the tenant's dashboard and exempt from the
 * parameter budget, which is right for the SDK's own bookkeeping and wrong for
 * revenue: the amount of a sale is the first thing someone reading their own
 * purchase events wants to see. They cost a parameter each and they show up,
 * like any other parameter — the backend simply also lifts `value` and
 * `currency` into typed columns on the way in.
 */
object DeeplinklyPurchase {

    /**
     * Meta's standard event is `Purchase` and Google's is `purchase`. Lowercase
     * here; a forwarder maps to each destination's spelling.
     */
    const val EVENT_NAME = "purchase"

    const val KEY_VALUE = "value"
    const val KEY_CURRENCY = "currency"
    const val KEY_ORDER_ID = "order_id"
    const val KEY_QUANTITY = "quantity"
    const val KEY_PRODUCT_ID = "product_id"

    /** Keys [build] writes, which a caller's own parameters may not collide with. */
    val RESERVED_KEYS = setOf(
        KEY_VALUE, KEY_CURRENCY, KEY_ORDER_ID, KEY_QUANTITY, KEY_PRODUCT_ID,
    )

    /** Why a purchase was rejected. Surfaced only in debug logs. */
    class Rejection(val reason: String)

    /** Either the assembled parameters or the reason there are none. */
    class Result(val parameters: Map<String, Any?>?, val rejection: Rejection?)

    private val CURRENCY = Regex("^[A-Za-z]{3}$")

    fun build(
        value: Double,
        currency: String,
        orderId: String? = null,
        quantity: Int? = null,
        productId: String? = null,
        parameters: Map<String, Any?> = emptyMap(),
    ): Result {
        if (value.isNaN() || value.isInfinite()) {
            return Result(null, Rejection("value must be a finite number"))
        }
        // Zero is allowed — a free trial conversion is worth reporting — but
        // negative is not. A refund is a different event, and sending it as a
        // purchase would net it off the campaign's revenue in a way no
        // destination expects.
        if (value < 0) {
            return Result(null, Rejection("value must not be negative; got $value"))
        }

        val code = currency.trim()
        if (!CURRENCY.matches(code)) {
            return Result(
                null,
                Rejection("currency must be a 3-letter ISO-4217 code; got \"$currency\"")
            )
        }

        if (quantity != null && quantity < 0) {
            return Result(null, Rejection("quantity must not be negative; got $quantity"))
        }

        val collisions = parameters.keys.filter { it.trim() in RESERVED_KEYS }
        if (collisions.isNotEmpty()) {
            return Result(
                null,
                Rejection(
                    "parameters may not contain ${collisions.joinToString(", ")}; " +
                        "pass them as arguments instead"
                )
            )
        }

        val out = LinkedHashMap<String, Any?>(parameters)
        out[KEY_VALUE] = value
        out[KEY_CURRENCY] = code.uppercase()
        orderId?.trim()?.takeIf { it.isNotEmpty() }?.let { out[KEY_ORDER_ID] = it }
        quantity?.let { out[KEY_QUANTITY] = it }
        productId?.trim()?.takeIf { it.isNotEmpty() }?.let { out[KEY_PRODUCT_ID] = it }
        return Result(out, null)
    }
}
