package com.deeplinkly.android_deeplinkly.core

import com.deeplinkly.android_deeplinkly.attribution.EnrichmentSender

object UserIdManager {
    fun updateCustomUserId(newId: String?, apiKey: String) {
        val previous = DeeplinklyUtils.getCustomUserId()
        if (previous == newId) return
        DeeplinklyUtils.setCustomUserId(newId)
        Logger.d("UserIdManager: updated custom user ID → ${newId ?: "nil"}")
        SdkRuntime.ioLaunch {
            // The new id is already stored, so the sender reads it back with
            // the rest of the payload. Nothing to pass but the source.
            //
            // force: linking a login has nothing to do with attribution, so it
            // must not be gated on a UTM being present — a user who installed
            // the app organically would otherwise never be linked at all. That
            // gate is exactly what used to drop this call on Android while iOS
            // sent it.
            EnrichmentSender.sendOnce(emptyMap(), "custom_user_id", apiKey, force = true)
        }
    }
}
