package com.deeplinkly.android_deeplinkly.core

import android.os.Handler
import android.os.Looper
import com.deeplinkly.android_deeplinkly.DeeplinklyDeepLink
import com.deeplinkly.android_deeplinkly.DeeplinklyDeepLinkListener
import com.deeplinkly.android_deeplinkly.queue.DeepLinkQueue
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

object SdkRuntime {
    lateinit var ioScope: CoroutineScope
    lateinit var mainHandler: Handler

    /**
     * Whether somebody is listening for deep links.
     *
     * Kept as a flag beside the reference rather than derived from it because
     * both are read from the main thread inside [deliverDeepLink] and written
     * from wherever a host attaches, and the pair is only ever set or cleared
     * together.
     */
    private val hasListener = AtomicBoolean(false)
    private var listener: DeeplinklyDeepLinkListener? = null

    fun ioLaunch(block: suspend CoroutineScope.() -> Unit) =
        (if (::ioScope.isInitialized) ioScope else CoroutineScope(Dispatchers.IO)).launch(block = block)

    /**
     * Whether [ioScope] has been assigned.
     *
     * `::ioScope.isInitialized` is only legal inside this object, so teardown
     * paths elsewhere have to ask rather than check for themselves.
     */
    val isScopeInitialized: Boolean get() = ::ioScope.isInitialized

    /** Whether [mainHandler] has been assigned. */
    val isMainHandlerInitialized: Boolean get() = ::mainHandler.isInitialized

    /**
     * Runs [block] on the main thread.
     *
     * Falls back to building a handler on demand, so a caller that answers a
     * callback before [init] finished cannot hit an uninitialised lateinit.
     */
    fun postToMain(block: () -> Unit) {
        if (!::mainHandler.isInitialized) {
            mainHandler = Handler(Looper.getMainLooper())
        }
        mainHandler.post(block)
    }

    /**
     * Attach the listener that receives resolved deep links.
     *
     * Anything already queued is delivered by the next [DeepLinkQueue] drain,
     * which callers trigger via `QueueProcessor.processNow`.
     */
    fun setListener(listener: DeeplinklyDeepLinkListener) {
        this.listener = listener
        hasListener.set(true)
        Logger.d("Deep link listener attached")
    }

    /**
     * Detach the listener (e.g. on Flutter engine detach, or host teardown).
     *
     * Links resolved after this point stay in the queue rather than being
     * dropped.
     */
    fun clearListener() {
        hasListener.set(false)
        listener = null
        Logger.d("Deep link listener detached")
    }

    /**
     * Whether a listener is attached and able to receive deep links.
     */
    fun hasListener(): Boolean = hasListener.get() && listener != null

    /**
     * Hands a queued deep link to the listener, removing it only once the
     * listener has accepted it.
     *
     * The caller enqueues first and calls this second, so the queue is the one
     * record of the link from the moment it is resolved until the host has it.
     * What this replaced tried to express the same thing by enqueueing, posting,
     * and then removing the entry 100ms later on a timer, which went wrong in
     * three ways: the periodic processor could tick inside that window and
     * deliver the link twice; the removal was keyed on click_id, which a link
     * resolved by code does not have, so for those nothing was ever removed and
     * the second delivery was guaranteed; and a failed delivery re-queued a
     * synthesized copy labelled "deep_link" whatever the original source was,
     * stripped of its enrichment and no longer matching the removal its own
     * caller would attempt.
     *
     * Delivery is claimed before the post and released after, so a processor
     * tick that lands mid-flight skips the item instead of sending it again.
     */
    fun deliverDeepLink(pending: DeepLinkQueue.PendingDelivery) {
        if (!::mainHandler.isInitialized) {
            mainHandler = Handler(Looper.getMainLooper())
        }
        DeepLinkQueue.markInFlight(pending.id)
        mainHandler.post {
            try {
                val target = listener
                if (hasListener.get() && target != null) {
                    target.onDeepLink(
                        DeeplinklyDeepLink(pending.resolvedData, pending.source)
                    )
                    DeepLinkQueue.removeDelivery(pending)
                    Logger.d("Delivered deep link to listener: source=${pending.source}")
                } else {
                    // Left in the queue exactly as it is; the processor picks it
                    // up once a listener attaches.
                    Logger.w("No listener attached, leaving deep link queued")
                }
            } catch (e: Exception) {
                Logger.e("Listener threw, leaving deep link queued: ${e.message}", e)
            } finally {
                DeepLinkQueue.clearInFlight(pending.id)
            }
        }
    }
}
