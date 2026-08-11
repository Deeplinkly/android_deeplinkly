package com.deeplinkly.sample

import android.app.Application
import com.deeplinkly.android_deeplinkly.Deeplinkly

/**
 * The whole native integration, in two calls.
 *
 * `Application.onCreate` rather than an activity, so the SDK is running before
 * any deep link can arrive and a link that resolves during a cold start has
 * somewhere to go.
 */
class SampleApp : Application() {
    /**
     * Links seen so far, newest first.
     *
     * A real app would route instead of collecting. This one keeps them so the
     * UI can show that delivery happened at all, including links that arrived
     * before any activity existed.
     */
    val links = mutableListOf<String>()

    /** Notified when [links] changes, so a visible activity can redraw. */
    var onLinksChanged: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()

        Deeplinkly.setDebugMode(true)
        Deeplinkly.init(this)

        Deeplinkly.setDeepLinkListener { link ->
            links.add(
                0,
                buildString {
                    appendLine("source:      ${link.source}")
                    appendLine("click_id:    ${link.clickId ?: "(none)"}")
                    appendLine("params:      ${link.params}")
                    append("probability: ${link.probability ?: "(none)"}")
                }
            )
            onLinksChanged?.invoke()
        }
    }
}
