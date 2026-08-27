# Changelog

## 1.3.0 - 2026-08-27

- Adds `setUserData()`, for the email, phone, name and address a conversion is
  matched on at Meta's Conversions API and Google's enhanced conversions.
  Optional, merging, and validated all-or-nothing: a malformed field rejects the
  call rather than storing part of it.
- Values are stored and sent as supplied, and hashed only when a conversion is
  forwarded. On-device hashing would look safer and buy nothing — the digest of
  a normalised email is exactly the value Meta matches on — and it would stop
  the backend normalising per destination, which Meta and Google disagree about.
- Adds `clearUserData()`, which erases those fields here *and* on the server:
  each previously-set field is reported empty until the erasure is delivered,
  so a clear on an offline device still takes effect.
- Adds `logPurchase(value:, currency:, ...)`, a typed wrapper over `logEvent`
  that sends the `purchase` event with the one spelling of `value`/`currency`
  both destinations can be built from. `logEvent` now validates those two keys
  wherever they appear, so a hand-rolled purchase gets the same answer.
- Every event now carries a client-generated `_dl_event_id`. It is Meta CAPI's
  `event_id`, and it makes a replay off the retry queue idempotent: an event
  delivered whose response was lost is refused rather than counted twice.
- Signal catalogue version 9, with a new `user` scope. `custom_user_id` moves
  into it from `identity` — which is what keeps personal data off the
  pending-resolve queue by construction rather than by a hand-kept exclusion.
- User data is classified `minimal`, so it survives a `REDUCED` downgrade. The
  levels gate what we *observe* about a device; an email the person typed into
  your app is not an observation.

## 1.2.0 - 2026-08-27

- Collects the Google Ads `gbraid` and `wbraid` click identifiers. Signal
  catalogue version 8; both are classified `reduced`/`identity`, so they ship
  at every level except `none`.
- Parses `gbraid`/`wbraid` out of the Play install referrer and carries them on
  the attribution snapshot, alongside `gclid`.

## 1.1.1 - 2026-08-16

- Makes tracking opt-out strict for reporting: pending retries are purged,
  disabled requests cannot be requeued, and retry drains re-check consent.
- Omits stable Deeplinkly and custom-user identity headers from functional
  resolve/generate requests while tracking is disabled.
- Adds `resetPrivacyData()` to delete local identifiers, attribution, device,
  session, and queue state while leaving tracking disabled.
