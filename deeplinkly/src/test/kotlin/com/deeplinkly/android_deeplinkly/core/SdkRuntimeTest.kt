package com.deeplinkly.android_deeplinkly.core

import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.deeplinkly.android_deeplinkly.DeeplinklyDeepLink
import com.deeplinkly.android_deeplinkly.DeeplinklyDeepLinkListener
import com.deeplinkly.android_deeplinkly.queue.DeepLinkQueue
import kotlinx.coroutines.*
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Handler/Looper are Android framework classes; they need a Robolectric runtime.
@RunWith(RobolectricTestRunner::class)
class SdkRuntimeTest {

    /**
     * Records what was delivered, and can be told to throw.
     *
     * A recording fake rather than a mock so the assertions can check the
     * payload the host actually sees, which is the thing that has to stay
     * stable.
     */
    private class RecordingListener(
        private val throwOnDeliver: Boolean = false,
    ) : DeeplinklyDeepLinkListener {
        val received = mutableListOf<DeeplinklyDeepLink>()

        override fun onDeepLink(link: DeeplinklyDeepLink) {
            received += link
            if (throwOnDeliver) throw RuntimeException("Test exception")
        }
    }

    private lateinit var listener: RecordingListener

    @Before
    fun setUp() {
        listener = RecordingListener()
        // The queue reads through DeeplinklyContext.app, which the plugin
        // normally populates in onAttachedToEngine.
        DeeplinklyContext.app = ApplicationProvider.getApplicationContext()
        SdkRuntime.ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        SdkRuntime.mainHandler = Handler(Looper.getMainLooper())
        SdkRuntime.clearListener()
    }

    @Test
    fun `setListener marks the runtime as able to deliver`() {
        SdkRuntime.setListener(listener)

        assertTrue(SdkRuntime.hasListener())
    }

    @Test
    fun `clearListener marks the runtime as unable to deliver`() {
        SdkRuntime.setListener(listener)
        SdkRuntime.clearListener()

        assertFalse(SdkRuntime.hasListener())
    }

    @Test
    fun `hasListener returns false when no listener was ever attached`() {
        SdkRuntime.clearListener()

        assertFalse(SdkRuntime.hasListener())
    }

    @Test
    fun `deliverDeepLink hands the link over and clears the entry when a listener is attached`() {
        DeepLinkQueue.clearAll()
        SdkRuntime.setListener(listener)

        val pending = delivery(mapOf("click_id" to "test_123"))
        DeepLinkQueue.enqueueDelivery(pending)

        SdkRuntime.deliverDeepLink(pending)

        // deliverDeepLink hands the call to the main Handler. Robolectric's main
        // looper is PAUSED by default, so sleeping never drains it - the queued
        // runnable has to be idled explicitly.
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, listener.received.size)
        // The payload must cross unchanged: the Flutter bridge forwards `raw`
        // straight onto the method channel, so any transformation here would
        // silently change Dart's envelope.
        assertEquals(pending.resolvedData, listener.received.single().raw)
        assertEquals(pending.source, listener.received.single().source)
        assertTrue(
            "a delivered link must not stay queued for the processor to send again",
            DeepLinkQueue.getDeliveryQueue().none { it.id == pending.id }
        )
        assertFalse(DeepLinkQueue.isInFlight(pending.id))
    }

    @Test
    fun `deliverDeepLink keeps the entry queued when no listener is attached`() {
        DeepLinkQueue.clearAll()
        SdkRuntime.clearListener()

        val pending = delivery(mapOf("click_id" to "test_123"))
        DeepLinkQueue.enqueueDelivery(pending)

        SdkRuntime.deliverDeepLink(pending)
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(listener.received.isEmpty())
        assertTrue(
            "an undelivered link must survive for the processor to retry",
            DeepLinkQueue.getDeliveryQueue().any { it.id == pending.id }
        )
        assertFalse(DeepLinkQueue.isInFlight(pending.id))
    }

    /**
     * The claim is what stops the periodic processor from sending a link that is
     * already on its way to the listener.
     */
    @Test
    fun `a delivery in flight is not offered to the processor`() {
        DeepLinkQueue.clearAll()
        SdkRuntime.setListener(listener)

        val pending = delivery(mapOf("click_id" to "in_flight"))
        DeepLinkQueue.enqueueDelivery(pending)

        // Posted but not yet run: the looper is still paused.
        SdkRuntime.deliverDeepLink(pending)

        assertTrue(DeepLinkQueue.isInFlight(pending.id))
        assertTrue(
            "the processor must not pick up a delivery already in flight",
            DeepLinkQueue.getDeliverableQueue().none { it.id == pending.id }
        )

        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(DeepLinkQueue.isInFlight(pending.id))
    }

    private fun delivery(data: Map<String, Any?>) = DeepLinkQueue.PendingDelivery(
        resolvedData = data,
        attributionData = emptyMap(),
        source = "deep_link"
    )

    @Test
    fun `ioLaunch executes coroutine on IO dispatcher`() = runBlocking {
        var executed = false

        SdkRuntime.ioLaunch {
            executed = true
        }

        delay(100)
        assertTrue(executed)
    }

    @Test
    fun `deliverDeepLink keeps the entry queued when the listener throws`() {
        DeepLinkQueue.clearAll()
        val throwing = RecordingListener(throwOnDeliver = true)
        SdkRuntime.setListener(throwing)

        val pending = delivery(mapOf("click_id" to "test"))
        DeepLinkQueue.enqueueDelivery(pending)

        SdkRuntime.deliverDeepLink(pending)
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Should not crash, and the link is still there to retry
        assertEquals(1, throwing.received.size)
        assertTrue(
            DeepLinkQueue.getDeliveryQueue().any { it.id == pending.id }
        )
        assertFalse(DeepLinkQueue.isInFlight(pending.id))
    }
}
