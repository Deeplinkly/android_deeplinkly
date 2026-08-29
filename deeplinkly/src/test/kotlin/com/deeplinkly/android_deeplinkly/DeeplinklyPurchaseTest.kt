package com.deeplinkly.android_deeplinkly

import org.junit.Assert.*
import org.junit.Test

class DeeplinklyPurchaseTest {

    private fun build(
        value: Double = 49.99,
        currency: String = "usd",
        orderId: String? = null,
        quantity: Int? = null,
        productId: String? = null,
        parameters: Map<String, Any?> = emptyMap(),
    ) = DeeplinklyPurchase.build(value, currency, orderId, quantity, productId, parameters)

    @Test
    fun `uppercases the currency and keeps the value a number`() {
        val result = build()
        assertNull(result.rejection)
        assertEquals("USD", result.parameters!![DeeplinklyPurchase.KEY_CURRENCY])
        // Not "49.99". The service stores this as a Decimal, and a value that
        // arrives as a string is one the JSON has already made ambiguous.
        assertEquals(49.99, result.parameters!![DeeplinklyPurchase.KEY_VALUE])
    }

    @Test
    fun `omits the optional fields that were not supplied`() {
        val params = build().parameters!!
        assertFalse(DeeplinklyPurchase.KEY_ORDER_ID in params)
        assertFalse(DeeplinklyPurchase.KEY_QUANTITY in params)
        assertFalse(DeeplinklyPurchase.KEY_PRODUCT_ID in params)
    }

    @Test
    fun `carries the caller's own parameters through`() {
        val params = build(parameters = mapOf("coupon" to "SPRING")).parameters!!
        assertEquals("SPRING", params["coupon"])
        assertEquals("USD", params[DeeplinklyPurchase.KEY_CURRENCY])
    }

    /** Zero is a real conversion — a free trial that converted. */
    @Test
    fun `accepts a zero value`() {
        assertNull(build(value = 0.0).rejection)
    }

    /**
     * A refund is a different event. Sent as a purchase it would net off the
     * campaign's revenue, which is not what any destination does with it.
     */
    @Test
    fun `rejects a negative value`() {
        assertNotNull(build(value = -1.0).rejection)
    }

    @Test
    fun `rejects a value that is not finite`() {
        assertNotNull(build(value = Double.NaN).rejection)
        assertNotNull(build(value = Double.POSITIVE_INFINITY).rejection)
    }

    @Test
    fun `rejects a currency that is not three letters`() {
        assertNotNull(build(currency = "US").rejection)
        assertNotNull(build(currency = "US$").rejection)
        assertNotNull(build(currency = "").rejection)
    }

    @Test
    fun `rejects a negative quantity`() {
        assertNotNull(build(quantity = -1).rejection)
    }

    /**
     * Silently letting the caller's map win would send a purchase whose value
     * is not the value they passed; silently overwriting it would discard data
     * they meant to keep. Neither is recoverable, so the call is refused.
     */
    @Test
    fun `rejects a caller parameter that collides with a reserved key`() {
        val result = build(parameters = mapOf("value" to 1.0))
        assertNotNull(result.rejection)
        assertNull(result.parameters)
    }

    /** What every event this produces is named, on both platforms. */
    @Test
    fun `is named purchase`() {
        assertEquals("purchase", DeeplinklyPurchase.EVENT_NAME)
    }
}
