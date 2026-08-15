package com.deeplinkly.android_deeplinkly.privacy

import com.deeplinkly.android_deeplinkly.core.Prefs
import com.deeplinkly.android_deeplinkly.retry.SdkRetryQueue

/**
 * Manages tracking preferences and privacy settings
 */
object TrackingPreferences {
    private const val KEY_TRACKING_DISABLED = "tracking_disabled"
    
    /**
     * Check if tracking is disabled
     */
    fun isTrackingDisabled(): Boolean {
        return Prefs.of().getBoolean(KEY_TRACKING_DISABLED, false)
    }
    
    /**
     * Set tracking disabled state
     */
    fun setTrackingDisabled(disabled: Boolean) {
        // Commit before touching the queue. A request already in flight can
        // fail on another thread while opt-out is running; RetryQueue.enqueue
        // checks this persisted flag and must see `true` before the old queue
        // is purged, otherwise the payload can be written back after clearAll.
        Prefs.of().edit().putBoolean(KEY_TRACKING_DISABLED, disabled).commit()
        if (disabled) SdkRetryQueue.clearAll()
    }
}

