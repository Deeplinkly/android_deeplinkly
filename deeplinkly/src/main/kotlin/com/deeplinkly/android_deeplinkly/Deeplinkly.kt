package com.deeplinkly.android_deeplinkly

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.deeplinkly.android_deeplinkly.core.AppOpenReporter
import com.deeplinkly.android_deeplinkly.core.DeeplinklyContext
import com.deeplinkly.android_deeplinkly.core.DeeplinklyUtils
import com.deeplinkly.android_deeplinkly.core.DeviceProfile
import com.deeplinkly.android_deeplinkly.core.Logger
import com.deeplinkly.android_deeplinkly.core.Prefs
import com.deeplinkly.android_deeplinkly.core.SdkInfo
import com.deeplinkly.android_deeplinkly.core.SdkRuntime
import com.deeplinkly.android_deeplinkly.core.SessionManager
import com.deeplinkly.android_deeplinkly.core.UserIdManager
import com.deeplinkly.android_deeplinkly.enrichment.StartupEnrichment
import com.deeplinkly.android_deeplinkly.handlers.DeepLinkHandler
import com.deeplinkly.android_deeplinkly.handlers.InstallReferrerHandler
import com.deeplinkly.android_deeplinkly.network.DeeplinklyNetwork
import com.deeplinkly.android_deeplinkly.privacy.AttributionLevel
import com.deeplinkly.android_deeplinkly.privacy.TrackingPreferences
import com.deeplinkly.android_deeplinkly.queue.QueueProcessor
import com.deeplinkly.android_deeplinkly.queue.DeepLinkQueue
import com.deeplinkly.android_deeplinkly.retry.SdkRetryQueue
import com.deeplinkly.android_deeplinkly.storage.AttributionStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Deeplinkly SDK.
 *
 * Typical native integration:
 *
 * ```kotlin
 * class App : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         Deeplinkly.init(this)
 *         Deeplinkly.setDeepLinkListener { link -> router.open(link.params) }
 *     }
 * }
 * ```
 *
 * and, in any activity that can receive a link with `launchMode="singleTop"`:
 *
 * ```kotlin
 * override fun onNewIntent(intent: Intent) {
 *     super.onNewIntent(intent)
 *     Deeplinkly.onNewIntent(this, intent)
 * }
 * ```
 *
 * That one override is required and cannot be automated away:
 * `ActivityLifecycleCallbacks` has no `onNewIntent` hook, so a warm link
 * arriving at an already-running activity is invisible to the SDK without it.
 * Cold starts are captured automatically.
 *
 * There is no auto-initialisation. An `androidx.startup` `Initializer` or a
 * `ContentProvider` would merge a component into every host app's manifest,
 * which this SDK deliberately avoids - the same reason `androidx.work` was
 * dropped and `play-services-ads-identifier` is `compileOnly`.
 */
object Deeplinkly {

    /** Manifest meta-data naming the API key. */
    private const val MANIFEST_API_KEY = "com.deeplinkly.sdk.api_key"

    private val initialized = AtomicBoolean(false)

    @Volatile
    private var apiKey: String = ""

    /**
     * Whether the SDK has an API key and is doing anything.
     *
     * False means the manifest meta-data was missing or unreadable. Deep link
     * delivery and every reporting call become no-ops; identity still works.
     */
    @Volatile
    var isEnabled: Boolean = false
        private set

    /** Guards the read-modify-write of the event sequence counter. */
    private val eventSeqLock = Any()

    private val coroutineErrorHandler = CoroutineExceptionHandler { _, e ->
        try {
            DeeplinklyNetwork.reportError(apiKey, "Coroutine crash", e.stackTraceToString())
        } catch (_: Exception) {
        }
    }

    /**
     * Starts the SDK.
     *
     * Idempotent: the second and later calls return immediately, so a host that
     * initialises from both `Application.onCreate` and a plugin attach cannot
     * double-register anything.
     *
     * Takes a [Context] rather than an [Application] so no caller has to do the
     * cast. The application context is always recorded — which is what the
     * install id and the prefs-backed APIs need — and the two pieces that
     * genuinely require an [Application], app-open reporting and automatic
     * launch-intent capture, are simply skipped if one cannot be reached. That
     * is a degraded SDK rather than a broken one, and it is a case a host
     * embedding us in an unusual context should not have to think about.
     *
     * @param autoCaptureLaunchIntents when true (the default) the SDK watches
     *   activity creation and reads launch intents itself. Hosts with a more
     *   precise signal - the Flutter bridge, which drives this from
     *   `ActivityAware` and must not replay the intent across a configuration
     *   change - pass false and call [onActivityLaunch] themselves.
     */
    @JvmOverloads
    fun init(context: Context, autoCaptureLaunchIntents: Boolean = true) {
        if (!initialized.compareAndSet(false, true)) {
            Logger.d("init() called again; ignoring")
            return
        }

        val appContext = context.applicationContext
        DeeplinklyContext.app = appContext
        val application = appContext as? Application
        if (application == null) {
            Logger.w("Application unavailable; app-open reporting and automatic launch-intent capture are off")
        }

        SdkRuntime.ioScope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO + coroutineErrorHandler)
        SdkRuntime.mainHandler = Handler(Looper.getMainLooper())

        isEnabled = try {
            val appInfo = appContext.packageManager.getApplicationInfo(
                appContext.packageName, PackageManager.GET_META_DATA
            )
            apiKey = appInfo.metaData?.getString(MANIFEST_API_KEY).orEmpty()
            StartupEnrichment.schedule(apiKey)
            if (apiKey.isBlank()) {
                Logger.e("Missing API key in AndroidManifest.xml ($MANIFEST_API_KEY)")
                false
            } else {
                // Fires on the 0->1 started-activity transition, before any
                // host-driven lifecycle signal arrives.
                application?.let { AppOpenReporter.register(it, apiKey) }
                true
            }
        } catch (e: Exception) {
            Logger.e("Failed to read API key from manifest", e)
            false
        }

        if (isEnabled && autoCaptureLaunchIntents) {
            application?.let { registerLaunchIntentCapture(it) }
        }
    }

    /**
     * Watches activity creation so a native host does not have to forward cold
     * starts by hand.
     *
     * Safe against the same intent being seen twice: [DeepLinkHandler] marks an
     * intent consumed with an extra and also honours
     * `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`.
     */
    private fun registerLaunchIntentCapture(app: Application) {
        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedState: Bundle?) {
                    onActivityLaunch(activity)
                }

                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, out: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }
        )
    }

    /**
     * Attaches the listener that receives resolved deep links, and drains
     * anything that queued up before it arrived.
     *
     * Pass null to detach.
     */
    fun setDeepLinkListener(listener: DeeplinklyDeepLinkListener?) {
        if (listener == null) {
            SdkRuntime.clearListener()
            return
        }
        SdkRuntime.setListener(listener)
        if (isEnabled) {
            QueueProcessor.processNow(apiKey)
        }
    }

    /**
     * Runs the cold-start work for an activity: its launch intent, the Play
     * install referrer, and a drain of both retry queues.
     *
     * Called automatically unless [init] was told not to. Safe to call more
     * than once - every step it triggers is individually guarded.
     */
    fun onActivityLaunch(activity: Activity) {
        if (!isEnabled) {
            Logger.w("SDK disabled (missing API key). Skipping initialization.")
            return
        }
        try {
            activity.intent
                ?.takeIf { it.data != null }
                ?.let { DeepLinkHandler.handleIntent(activity, it, apiKey) }

            InstallReferrerHandler.checkInstallReferrer(
                activity.applicationContext, activity, apiKey
            )

            SdkRuntime.ioLaunch {
                SdkRetryQueue.retryAll(apiKey)
                QueueProcessor.startProcessing(apiKey)
            }
        } catch (e: Exception) {
            Logger.e("Startup failure", e)
            DeeplinklyNetwork.reportError(apiKey, "Plugin startup failure", e.stackTraceToString())
        }
    }

    /**
     * Hands the SDK a link that arrived at an already-running activity.
     *
     * Call this from `Activity.onNewIntent`.
     */
    fun onNewIntent(context: Context, intent: Intent?) {
        if (!isEnabled) return
        if (intent == null) {
            Logger.w("onNewIntent called with a null intent")
            return
        }
        DeepLinkHandler.handleIntent(context, intent, apiKey)
    }

    /**
     * Called when the app returns to the foreground.
     *
     * Optional for native hosts - [AppOpenReporter] already sees the activity
     * transition. Provided because the Flutter bridge has its own foreground
     * signal from Dart, and both route through the same rate limit, so a double
     * trigger is a no-op rather than a duplicate send.
     */
    fun onForeground() {
        if (!isEnabled) return
        AppOpenReporter.report(apiKey)
        QueueProcessor.processNow(apiKey)
    }

    /** Stops queue processing and detaches the listener. */
    fun shutdown() {
        SdkRuntime.clearListener()
        QueueProcessor.stopProcessing()
        if (SdkRuntime.isScopeInitialized) {
            SdkRuntime.ioScope.cancel()
        }
    }

    // ---------------------------------------------------------------- identity

    /** First-touch attribution for this install. Empty until a link resolves. */
    fun getInstallAttribution(): Map<String, String> = AttributionStore.get()

    /**
     * The stable Deeplinkly id for this install.
     *
     * Works whether or not the SDK is enabled - it is generated locally and
     * needs no API key.
     */
    fun getDeeplinklyId(): String = DeeplinklyUtils.getOrCreateDeviceId()

    /** Sets your own user id, reported as `custom_user_id`. */
    fun setUserId(userId: String?) {
        if (!isEnabled) return
        UserIdManager.updateCustomUserId(userId, apiKey)
    }

    // ------------------------------------------------------------------ events

    /**
     * Logs a custom event.
     *
     * [callback] receives true only when the backend accepted it. Validation
     * failures answer false without a network call; see [DeeplinklyEvent] for
     * the rules.
     */
    @JvmOverloads
    fun logEvent(
        name: String,
        parameters: Map<String, Any?> = emptyMap(),
        callback: ((Boolean) -> Unit)? = null,
    ) {
        if (!isEnabled) {
            callback?.let { SdkRuntime.postToMain { it(false) } }
            return
        }

        DeeplinklyEvent.validate(name, parameters)?.let { rejection ->
            Logger.w("Rejected event '$name': ${rejection.reason}")
            callback?.let { SdkRuntime.postToMain { it(false) } }
            return
        }

        val params = parameters.toMutableMap()
        // Read and write under one lock. Two logEvent calls landing together
        // both read the same value and both wrote it back, so the sequence that
        // exists to order events handed out duplicates - and commit(), not
        // apply(), so a process killed right after the call cannot reissue the
        // number.
        val seq = synchronized(eventSeqLock) {
            val next = Prefs.of().getLong("dl_event_seq", 0L) + 1L
            Prefs.of().edit().putLong("dl_event_seq", next).commit()
            next
        }
        params["_dl_event_seq"] = seq.toString()
        // Milliseconds since the SDK initialised, not a raw monotonic clock
        // reading. Same ordering power for events from a device with a wrong
        // wall clock, without also reporting how long the device has been
        // booted.
        params["_dl_client_elapsed_ms"] = SdkInfo.elapsedSinceInit().toString()
        params["_dl_client_wall_epoch_ms"] = System.currentTimeMillis().toString()
        val offsetMin = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000
        params["_dl_tz_offset_min"] = offsetMin.toString()
        // Joins this event to the device sample taken in the same visit. Free:
        // _dl_-prefixed keys are reserved and do not count against the caller's
        // parameter budget.
        params["_dl_session_id"] = SessionManager.currentSessionId()

        SdkRuntime.ioLaunch {
            val ok = DeeplinklyNetwork.logEvent(DeeplinklyEvent.normalizeName(name), params, apiKey)
            callback?.let { SdkRuntime.postToMain { it(ok) } }
        }
    }

    // ------------------------------------------------------------------- links

    /** Creates a Deeplinkly link. */
    fun generateLink(
        content: DeeplinklyContent,
        options: DeeplinklyLinkOptions,
        callback: (DeeplinklyResult) -> Unit,
    ) = generateLink(content.toPayload() + options.toPayload(), callback)

    /**
     * Creates a Deeplinkly link from an already-flattened payload.
     *
     * The Flutter bridge uses this so the map Dart built crosses to the backend
     * untouched, rather than being parsed into models and rebuilt.
     */
    fun generateLink(payload: Map<String, Any?>, callback: (DeeplinklyResult) -> Unit) {
        if (!isEnabled) {
            SdkRuntime.postToMain { callback(DeeplinklyResult.disabled()) }
            return
        }
        SdkRuntime.ioLaunch {
            val response = try {
                DeeplinklyNetwork.generateLink(payload, apiKey)
            } catch (e: Exception) {
                Logger.e("generateLink failed", e)
                mapOf(
                    "success" to false,
                    "error_code" to "LINK_ERROR",
                    "error_message" to (e.message ?: "generateLink failed"),
                )
            }
            SdkRuntime.postToMain { callback(DeeplinklyResult.fromMap(response)) }
        }
    }

    // ----------------------------------------------------------------- privacy

    /**
     * Turns reporting off entirely.
     *
     * Deep links still resolve and still deliver; this restricts what is
     * reported, not what works. Behaves as [AttributionLevel.NONE] and wins over
     * any level set.
     */
    fun setTrackingEnabled(enabled: Boolean) {
        TrackingPreferences.setTrackingDisabled(!enabled)
    }

    /**
     * Deletes Deeplinkly's locally stored identifiers, attribution, device
     * profile, event/session state, and pending queues.
     *
     * The SDK remains disabled after the reset. This prevents a reset made for
     * consent withdrawal or deletion from immediately minting and reporting a
     * replacement identity. Call [setTrackingEnabled] explicitly if the user
     * later opts back in.
     */
    fun resetPrivacyData(): Boolean {
        // Persist opt-out first so an in-flight failure cannot recreate a
        // reporting queue while deletion is in progress.
        TrackingPreferences.setTrackingDisabled(true)
        DeepLinkQueue.clearAll()
        SdkRetryQueue.clearAll()
        DeviceProfile.invalidate()

        // This preferences file belongs exclusively to Deeplinkly. Keep the
        // opt-out bit in the same atomic commit that removes everything else.
        return Prefs.of().edit()
            .clear()
            .putBoolean("tracking_disabled", true)
            .commit()
    }

    /** Whether reporting is currently on. */
    fun isTrackingEnabled(): Boolean = !TrackingPreferences.isTrackingDisabled()

    /** Restricts which device signals are reported. */
    fun setAttributionLevel(level: AttributionLevel): Boolean {
        AttributionLevel.set(level)
        return true
    }

    /** The level currently in force. */
    fun getAttributionLevel(): AttributionLevel = AttributionLevel.current

    // ------------------------------------------------------------------- debug

    /** Turns on verbose logcat output under the `Deeplinkly` tag. */
    fun setDebugMode(enabled: Boolean) = Logger.setDebugMode(enabled)

    /** The SDK version, e.g. `1.9.0`. */
    val version: String get() = SdkInfo.VERSION

    /**
     * Test seam: forgets that [init] ran.
     *
     * Robolectric builds a fresh Application per test while this object, being
     * a singleton, survives - so without this the second test in a class would
     * skip initialisation entirely.
     */
    internal fun resetForTesting() {
        initialized.set(false)
        isEnabled = false
        apiKey = ""
    }
}
