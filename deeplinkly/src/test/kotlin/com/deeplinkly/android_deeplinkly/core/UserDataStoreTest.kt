package com.deeplinkly.android_deeplinkly.core

import androidx.test.core.app.ApplicationProvider
import com.deeplinkly.android_deeplinkly.DeeplinklyUserData
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UserDataStoreTest {

    @Before
    fun setUp() {
        DeeplinklyContext.app = ApplicationProvider.getApplicationContext()
        Prefs.of().edit().clear().apply()
    }

    @Test
    fun `starts empty`() {
        assertTrue(UserDataStore.isEmpty())
        assertEquals(emptyMap<String, String>(), UserDataStore.get())
    }

    /**
     * The behaviour the public API is documented on: an app learns an email at
     * sign-up and an address at checkout, and the second call must not erase
     * the first.
     */
    @Test
    fun `merges rather than replacing`() {
        UserDataStore.merge(mapOf(DeeplinklyUserData.KEY_EMAIL to "ada@example.com"))
        UserDataStore.merge(mapOf(DeeplinklyUserData.KEY_CITY to "London"))

        assertEquals(
            mapOf(
                DeeplinklyUserData.KEY_EMAIL to "ada@example.com",
                DeeplinklyUserData.KEY_CITY to "London",
            ),
            UserDataStore.get(),
        )
    }

    @Test
    fun `a later value replaces an earlier one for the same field`() {
        UserDataStore.merge(mapOf(DeeplinklyUserData.KEY_EMAIL to "old@example.com"))
        UserDataStore.merge(mapOf(DeeplinklyUserData.KEY_EMAIL to "new@example.com"))
        assertEquals("new@example.com", UserDataStore.get()[DeeplinklyUserData.KEY_EMAIL])
    }

    @Test
    fun `ignores a key that is not a user-data field`() {
        UserDataStore.merge(mapOf("advertising_id" to "should-not-be-here"))
        assertTrue(UserDataStore.isEmpty())
    }

    /**
     * The whole reason clearing is not a delete. An absent key reads, at the
     * service, as "not reported" and is skipped — so dropping the blob would
     * leave the row on our side holding the email forever. An empty value is
     * what says "erase this".
     */
    @Test
    fun `clearing tombstones the fields that were set`() {
        UserDataStore.merge(
            mapOf(
                DeeplinklyUserData.KEY_EMAIL to "ada@example.com",
                DeeplinklyUserData.KEY_CITY to "London",
            )
        )
        UserDataStore.clear()

        assertEquals(
            mapOf(
                DeeplinklyUserData.KEY_EMAIL to "",
                DeeplinklyUserData.KEY_CITY to "",
            ),
            UserDataStore.get(),
        )
    }

    /** Nothing was ever set, so there is nothing to ask the service to erase. */
    @Test
    fun `clearing an empty store leaves it empty`() {
        UserDataStore.clear()
        assertTrue(UserDataStore.isEmpty())
    }

    /**
     * The tombstone outlives the send that carries it. Delivery is not
     * observable from the store, and a clear lost because the device happened
     * to be offline is the one failure this must not have.
     */
    @Test
    fun `the tombstone survives a later read`() {
        UserDataStore.merge(mapOf(DeeplinklyUserData.KEY_EMAIL to "ada@example.com"))
        UserDataStore.clear()
        UserDataStore.get()
        assertEquals("", UserDataStore.get()[DeeplinklyUserData.KEY_EMAIL])
    }

    @Test
    fun `setting a value after a clear replaces the tombstone for that field`() {
        UserDataStore.merge(
            mapOf(
                DeeplinklyUserData.KEY_EMAIL to "ada@example.com",
                DeeplinklyUserData.KEY_CITY to "London",
            )
        )
        UserDataStore.clear()
        UserDataStore.merge(mapOf(DeeplinklyUserData.KEY_EMAIL to "grace@example.com"))

        assertEquals("grace@example.com", UserDataStore.get()[DeeplinklyUserData.KEY_EMAIL])
        // Still erasing the one that was not re-set.
        assertEquals("", UserDataStore.get()[DeeplinklyUserData.KEY_CITY])
    }

    @Test
    fun `discards a blob it cannot parse`() {
        Prefs.of().edit().putString("dl_user_data", "{not json").apply()
        assertTrue(UserDataStore.isEmpty())
        assertNull(Prefs.of().getString("dl_user_data", null))
    }
}
