# Changelog

## 1.1.1 - 2026-08-16

- Makes tracking opt-out strict for reporting: pending retries are purged,
  disabled requests cannot be requeued, and retry drains re-check consent.
- Omits stable Deeplinkly and custom-user identity headers from functional
  resolve/generate requests while tracking is disabled.
- Adds `resetPrivacyData()` to delete local identifiers, attribution, device,
  session, and queue state while leaving tracking disabled.
