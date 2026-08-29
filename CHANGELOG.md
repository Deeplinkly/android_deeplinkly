# Changelog

## 1.3.0 - 2026-08-27

- Signal catalogue version 13. `setPIIHashingEnabled(true)` hashes the
  identifying fields on the device with SHA-256 before they are sent, so
  plaintext never reaches Deeplinkly. Off by default, reported to the service
  as `pii_hashing_enabled` so it knows whether the columns hold digests.

  Only email, phone, first and last name are hashed. Gender, country and date
  of birth are not: their value ranges are small enough that a digest is
  reversed by enumerating them, so hashing them would be protection in
  appearance only while costing the storage width to hold it.

  It costs attribution quality and the trade is the customer's. A digest is
  computed once, under one normalisation, and advertising destinations disagree
  about phone formatting -- so a conversion forwarded to a destination whose
  rules differ will not match, and the service can no longer re-derive per
  destination because the value it would need is gone. Enable it when a
  compliance requirement says plaintext must not reach a processor.

  Hashing happens at send time, not in the store, so the switch is reversible.
  `user_phone` widened from 32 to 64 characters everywhere -- catalogue, both
  SDKs and the service column -- because that field now has to be able to hold
  a digest.

- Signal catalogue version 12. `setUserData` takes a `customData` map, carried
  as one JSON object under the new `user_custom_data` signal. It exists because
  a host app's binary is frozen for its whole release cycle while the list of
  identifiers a customer needs is not: attaching a Mixpanel distinct id, an
  Amplitude device id or a CleverTap id previously meant waiting for a new
  named field and a new app release. Now it is a service change.

  Bounded at 10 entries, 64-character keys and 256-character values, and
  encoded with sorted keys so the same map always produces the same string. One
  catalogue signal rather than open wire keys, so the published inventory and
  the `ErrorLog` redaction stay derived from a closed set. Treated exactly like
  the twelve named identifying fields: user scope, `minimal` tier, erased by
  `clearUserData()` and in scope for the erasure API.

- Signal catalogue version 10. Adds the Google Ads auto-tagging markers
  `gad_source` and `gad_campaignid` (`reduced`/`identity`), which arrive on
  roughly half of Google Ads traffic with no `utm_source` at all — without them
  that traffic is indistinguishable from organic. Both are read out of the Play
  install referrer and off the resolve response.
- Reports `total_storage_gb` (static) and `free_storage_gb` (dynamic), filling
  the last two slots in Meta's `extinfo` array. Whole gigabytes, deliberately:
  a byte count of free space is precise enough to help recognise a device
  across installs, which this SDK does not do. Android only — iOS may not send
  disk space at all.
- Adds `setUserData()`, for the email, phone, name and address a conversion is
  matched on at Meta's Conversions API and Google's enhanced conversions.
  Optional, merging, and validated all-or-nothing: a malformed field rejects the
  call rather than storing part of it.
- Values are stored and sent as supplied, and hashed only when a conversion is
  forwarded. On-device hashing would look safer and buy nothing — the digest of
  a normalised email is exactly the value Meta matches on — and it would stop
  the service normalising per destination, which Meta and Google disagree about.
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
