# Deeplinkly Android SDK

Deep linking, deferred deep linking and attribution for Android.

```groovy
dependencies {
    implementation 'com.deeplinkly:deeplinkly-android:1.0.0'
}
```

minSdk 21. The SDK declares one permission, `INTERNET`.

> Using Flutter? Use [`flutter_deeplinkly`](https://github.com/Deeplinkly/flutter_deeplinkly)
> instead — it wraps this SDK, so both run identical code.

## Configure

In `AndroidManifest.xml`:

```xml
<activity android:name=".MainActivity" android:launchMode="singleTop">
  <!-- App Links. This is the one that matters: it lets a tap on
       https://links.yourapp.com/abc123 open the app directly. Without it
       every link detours through the browser, and in-app browsers that
       block intent:// URLs (Instagram, Facebook, TikTok) never reach your
       app at all — even when it is installed.
       autoVerify only does anything on http/https. -->
  <intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="https" android:host="links.yourapp.com" />
  </intent-filter>

  <!-- Custom scheme. The browser fallback path uses this, so keep it —
       but it is a fallback, not a substitute for the filter above. -->
  <intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="yourapp" android:host="deeplink" />
  </intent-filter>
</activity>

<application ...>
  <meta-data android:name="com.deeplinkly.sdk.api_key"
             android:value="your_api_key_here" />

  <!-- Optional, but set it if the app App Links any host besides its
       Deeplinkly link domain. Comma separated. -->
  <meta-data android:name="com.deeplinkly.sdk.link_domains"
             android:value="links.yourapp.com" />
</application>
```

`launchMode="singleTop"` matters — see [Handle deep links](#handle-deep-links).

### Which links the SDK claims

A link that came through the redirect carries a `click_id`, and the SDK acts on
that whatever the scheme. The ambiguous case is the App Link bypass, where the
OS routes `https://links.yourapp.com/<code>` straight to the app and the first
path segment is the only thing there is to resolve on. So:

- **Custom-scheme URLs without a `click_id` are ignored.** Your own routes
  (`yourapp://settings/notifications`) are yours; the SDK will not resolve them.
- **http(s) URLs** are resolved by code. With `link_domains` set, only those
  hosts are; without it every https link the app handles is.

Set `link_domains` if you App Link anything else — a marketing site, say — or
`https://www.yourapp.com/pricing` is resolved as the code `pricing`.

### Verifying App Links

Android checks `https://<your-link-domain>/.well-known/assetlinks.json` on
install. Deeplinkly serves that file, but only once the dashboard has both your
**package name** and your **SHA-256 signing certificate fingerprint** — with
either missing the endpoint 404s and verification silently fails.

```bash
keytool -list -v -keystore <your-keystore> -alias <your-alias> | grep SHA256
curl https://links.yourapp.com/.well-known/assetlinks.json
adb shell pm get-app-links <your.package.name>
```

The last command should report `verified`. If you use Play App Signing, take
the fingerprint from **Play Console → Setup → App signing**, not your upload
keystore — Google re-signs the APK, so the upload fingerprint will not match.

## Initialize

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Deeplinkly.init(this)
    }
}
```

There is no auto-initialisation, deliberately. An `androidx.startup`
`Initializer` or a `ContentProvider` would merge a component into your
manifest; this SDK does not add anything to your app that it does not have to.

`init` is idempotent, so calling it more than once is harmless.

## Handle deep links

```kotlin
Deeplinkly.setDeepLinkListener { link ->
    // Always called on the main thread.
    Log.d("app", "click=${link.clickId} params=${link.params}")
    router.open(link.params)
}
```

**And forward warm links from every activity that can receive one:**

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    Deeplinkly.onNewIntent(this, intent)
}
```

This one override is required and cannot be automated away.
`ActivityLifecycleCallbacks` has no `onNewIntent` hook, so a link arriving at an
already-running activity is invisible to the SDK without it. **Cold starts are
captured automatically** — you do not need to forward the launch intent.

Without `launchMode="singleTop"` Android creates a *new* activity instance per
link instead of calling `onNewIntent`, which usually is not what you want.

### Links are never lost

You do not have to race the SDK to attach a listener. A link that resolves
before one is attached is held in a persistent queue that survives process
death, and delivered as soon as a listener appears. The same queue covers an
offline first launch: the resolve is retried on the next launch.

A listener that throws leaves the link queued for another attempt rather than
dropping it.

### What you receive

```kotlin
class DeeplinklyDeepLink {
    val clickId: String?            // null if the backend did not recognise the click
    val params: Map<String, Any?>   // the link's own parameters
    val probability: Double?        // deferred-match confidence, when sent
    val source: String              // deep_link | deep_link_fallback | install_referrer
    val raw: Map<String, Any?>      // the payload exactly as resolved
}
```

`params` carries the link's parameters whether they came back from the backend
or, when it could not be reached, from the URL itself — so a single read path
covers both.

## Deferred deep linking

Works out of the box through the Play Install Referrer: a visitor who taps a
link, installs from Play, and opens the app gets the link on first launch,
stamped `source = "install_referrer"`. No permission and no user gesture.

## Identity and attribution

```kotlin
Deeplinkly.getInstallAttribution()   // first-touch attribution, write-once
Deeplinkly.getDeeplinklyId()         // stable install id
Deeplinkly.setUserId("user_123")     // your own id, reported as custom_user_id
```

## Events

```kotlin
Deeplinkly.logEvent(
    "purchase",
    mapOf("order_id" to "ord_42", "amount" to 49.99, "currency" to "USD"),
) { accepted -> /* optional */ }
```

`num` and `bool` keep their JSON types end to end — `49.99` is stored as a
number, not `"49.99"`. Constraints, enforced before anything is sent:

- event name ≤ 64 characters
- ≤ 25 parameters (the SDK's own `_dl_*` keys do not count, and passing a key
  with that prefix is rejected)
- parameter key ≤ 64 characters
- string value ≤ 256 characters
- `List`/`Map` values are stored as compact JSON; the 256 limit applies to that
  encoded form

## Generate links

```kotlin
Deeplinkly.generateLink(
    content = DeeplinklyContent(
        canonicalIdentifier = "product/sku_42",
        title = "Pro Plan",
        metadata = mapOf("plan" to "pro"),
    ),
    options = DeeplinklyLinkOptions(channel = "email", feature = "upgrade_campaign"),
) { result ->
    if (result.success) share(result.url!!)
}
```

## Privacy

```kotlin
Deeplinkly.setTrackingEnabled(false)                       // off entirely
Deeplinkly.setAttributionLevel(AttributionLevel.REDUCED)   // middle ground
```

| Level | What is sent |
|---|---|
| `FULL` | Everything. The default |
| `REDUCED` | Drops every high-entropy hardware signal: screen geometry, model, CPU, local IP, WebView user agent, advertising ID / Android ID. Keeps the coarse context campaign reporting reads |
| `MINIMAL` | Only the install id, app build, and the link being reported on |
| `NONE` | No enrichment at all. Links still resolve and still deliver |

Each level is a strict subset of the one above. **Deep link delivery works at
every level, including `NONE`** — this restricts reporting, not functionality.
Resolving a link never sends anything describing the device, at any level.

To start restricted before any of your code runs:

```xml
<meta-data android:name="com.deeplinkly.sdk.attribution_level"
           android:value="reduced" />
```

`setTrackingEnabled(false)` still wins and behaves as `NONE`.

The SDK does **not** do probabilistic ("fingerprint") matching. Device signals
are collected for reporting, never to derive an identifier linking a click to an
install — matching is deterministic, on the click id or the install referrer.

### Advertising ID (opt-in)

The SDK compiles against `play-services-ads-identifier` but does **not** bundle
it. That library's manifest declares `com.google.android.gms.permission.AD_ID`,
so bundling it would add that permission to every app embedding this SDK —
including apps under Play's Families policy, which may not collect an
advertising ID at all.

To report `advertising_id`, add it yourself:

```groovy
implementation 'com.google.android.gms:play-services-ads-identifier:18.2.0'
```

Without it everything else works unchanged; attribution still resolves
deterministically. `ACCESS_NETWORK_STATE` is likewise not declared: if your app
already holds it the SDK reports `connection_type`, and if not, that one field
is omitted.

## Debugging

```kotlin
Deeplinkly.setDebugMode(true)   // verbose logcat under the "Deeplinkly" tag
```

```bash
# Simulate a deep link
adb shell am start -a android.intent.action.VIEW -d "https://links.yourapp.com/abc123"

# Simulate an install referrer
adb shell am broadcast -a com.android.vending.INSTALL_REFERRER \
  --es "referrer" "click_id=test123"
```

## License

MIT — see [LICENSE](LICENSE).
