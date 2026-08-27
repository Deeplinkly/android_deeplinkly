# Changelog

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
