package com.deeplinkly.android_deeplinkly.privacy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.deeplinkly.android_deeplinkly.Deeplinkly
import com.deeplinkly.android_deeplinkly.core.DeeplinklyContext
import com.deeplinkly.android_deeplinkly.core.DeeplinklyUtils
import com.deeplinkly.android_deeplinkly.core.Prefs
import com.deeplinkly.android_deeplinkly.retry.SdkRetryQueue
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrivacyResetTest {
    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DeeplinklyContext.app = context
        Prefs.of().edit().clear().commit()
    }

    @Test
    fun `reset deletes sdk data and leaves tracking disabled`() {
        val oldId = DeeplinklyUtils.getOrCreateDeviceId()
        DeeplinklyUtils.setCustomUserId("customer-1")
        Prefs.of().edit()
            .putString("initial_attribution", "{\"click_id\":\"c1\"}")
            .putString("dl_session_id", "session-1")
            .commit()
        SdkRetryQueue.enqueue(JSONObject().put("event_name", "purchase"), "event")

        assertTrue(Deeplinkly.resetPrivacyData())

        assertTrue(TrackingPreferences.isTrackingDisabled())
        assertNull(DeeplinklyUtils.getCustomUserId())
        assertNull(Prefs.of().getString("initial_attribution", null))
        assertNull(Prefs.of().getString("dl_session_id", null))
        assertNull(Prefs.of().getString("dl_pending_retries", null))
        assertNotEquals(oldId, DeeplinklyUtils.getOrCreateDeviceId())
        assertTrue(TrackingPreferences.isTrackingDisabled())
    }
}
