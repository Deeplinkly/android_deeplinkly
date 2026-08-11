package com.deeplinkly.android_deeplinkly

/**
 * Receives resolved deep links.
 *
 * Always called on the main thread.
 *
 * Attaching a listener is what makes the SDK deliver: until one is set, links
 * resolve and then sit in the persistent queue, surviving process death. They
 * are handed over as soon as a listener appears. That is what makes a link
 * tapped before the app finished starting - or before a Flutter engine
 * attached - arrive rather than vanish, so there is no need to race the SDK to
 * register.
 *
 * A throw from [onDeepLink] leaves the link queued for a later attempt rather
 * than dropping it, so a transient failure in a host app's routing code cannot
 * lose an install's attribution.
 */
fun interface DeeplinklyDeepLinkListener {
    fun onDeepLink(link: DeeplinklyDeepLink)
}
