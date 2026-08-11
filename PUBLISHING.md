# Publishing the native Android SDK

Setup for releasing `com.deeplinkly:deeplinkly-android` to Maven Central.

This is Phase 0 of the native SDK extraction. It is deliberately done first
because namespace verification is the only step with an external wait on it,
and nothing else in the extraction depends on it finishing.

> now so the steps are not lost between the two.

## Which portal

Publish through the **Central Publisher Portal** at
[central.sonatype.com](https://central.sonatype.com). The older OSSRH /
`oss.sonatype.org` and `s01.oss.sonatype.org` route is decommissioned — any
guide that tells you to open a JIRA ticket to claim a namespace is out of date.

## 1. Claim the `com.deeplinkly` namespace

1. Sign in at [central.sonatype.com](https://central.sonatype.com) (GitHub or
   Google sign-in is fine).
2. **Namespaces → Add Namespace** → enter `com.deeplinkly`.
3. The portal shows a **verification key** — a short random string.
4. Add it as a DNS **TXT record** on the apex domain:

   | Field | Value |
   |---|---|
   | Type | `TXT` |
   | Host / Name | `@` (i.e. `deeplinkly.com` itself) |
   | Value | the verification key from the portal |

   The check reads the **exact domain the namespace inverts to**. For
   `com.deeplinkly` that is `deeplinkly.com` — not `com.deeplinkly.com`, not a
   `maven` subdomain. Getting this wrong is the usual reason verification
   silently never passes.

5. Back in the portal, press **Verify Namespace**.

Verification is normally minutes once DNS has propagated, but propagation
itself can take up to a few hours depending on your registrar's TTL. Confirm
the record is actually live before blaming the portal:

```bash
dig +short TXT deeplinkly.com
```

## 2. Generate a publishing user token

Do **not** use your account password in CI — the portal issues a separate token
pair for publishing.

**Account → Generate User Token.** It returns a username and password. These
become `SONATYPE_USERNAME` and `SONATYPE_PASSWORD`. Generating a new token
invalidates the previous one, so store it immediately.

## 3. Create the GPG signing key

Every artifact on Central must be signed. Run these yourself — the passphrase
is a credential and should not pass through anything but your own terminal.

macOS needs this once so the passphrase prompt can reach your terminal:

```bash
echo 'export GPG_TTY=$(tty)' >> ~/.zshrc && source ~/.zshrc
```

Generate the key:

```bash
gpg --full-generate-key
```

Answer:

- **Key type**: `RSA and RSA` (option 1). RSA is the safe choice; ed25519
  support across the verification tooling has been inconsistent.
- **Key size**: `4096`
- **Expiry**: `2y` is a reasonable default. It can be extended later, and an
  expired key is a much better failure mode than one that never expires.
- **Real name / email**: use a role address you will still control in two
  years, e.g. `Deeplinkly` / `dev@deeplinkly.com` — not a personal address.
- **Passphrase**: pick a strong one and store it in your password manager. It
  becomes `SIGNING_PASSWORD`.

Find the key id:

```bash
gpg --list-secret-keys --keyid-format=long
# sec   rsa4096/A1B2C3D4E5F6A7B8 2026-08-11 [SC]
#                ^^^^^^^^^^^^^^^^ this is the key id
```

**Publish the public half.** Central verifies each signature against public
keyservers, so a release signed by a key nobody can fetch is rejected:

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
gpg --keyserver keys.openpgp.org     --send-keys <KEY_ID>
```

Keyserver propagation is not instant. Give it an hour before the first release.

**Export the private half for CI:**

```bash
gpg --armor --export-secret-keys <KEY_ID>
```

The full block, `-----BEGIN PGP PRIVATE KEY BLOCK-----` through
`-----END PGP PRIVATE KEY BLOCK-----`, is `SIGNING_KEY`. GitHub secrets handle
multi-line values, so paste it as-is rather than base64-encoding it.

## 4. Store the CI secrets

On the `android_deeplinkly` repo: **Settings → Secrets and variables → Actions**.

| Secret | Value |
|---|---|
| `SIGNING_KEY` | armored private key block from step 3 |
| `SIGNING_PASSWORD` | the GPG passphrase |
| `SONATYPE_USERNAME` | user token username from step 2 |
| `SONATYPE_PASSWORD` | user token password from step 2 |

With the `gh` CLI (`brew install gh`) that is:

```bash
gh secret set SIGNING_KEY      --repo Deeplinkly/android_deeplinkly < private-key.asc
gh secret set SIGNING_PASSWORD --repo Deeplinkly/android_deeplinkly
gh secret set SONATYPE_USERNAME --repo Deeplinkly/android_deeplinkly
gh secret set SONATYPE_PASSWORD --repo Deeplinkly/android_deeplinkly
```

Delete `private-key.asc` afterwards. Never commit it.

## 5. Coordinate

```groovy
implementation 'com.deeplinkly:deeplinkly-android:<version>'
```

- `groupId` — `com.deeplinkly`
- `artifactId` — `deeplinkly-android`
- first release — `1.0.0`, versioned independently of the Flutter plugin

## Requirements Central enforces on every artifact

Worth knowing before Phase 2 wires up the Gradle config, because a release that
misses any of these is rejected at validation rather than at build:

- `-sources.jar` and `-javadoc.jar` alongside the main artifact
- a `.asc` signature for every file, including the POM
- POM metadata: `name`, `description`, `url`, at least one `license`, at least
  one `developer`, and `scm` connection/url
- a real version, not a `-SNAPSHOT`

**A published version is immutable.** Central does not allow overwriting or
deleting a release, so a bad `1.0.0` is fixed by shipping `1.0.1`, never by
replacing it. Verify against a local build first.

## Checklist

- [ ] Sonatype account created
- [ ] `com.deeplinkly` namespace requested
- [ ] DNS TXT record added on `deeplinkly.com` and confirmed with `dig`
- [ ] Namespace shows **Verified** in the portal
- [ ] User token generated and stored
- [ ] GPG key generated, passphrase in password manager
- [ ] Public key pushed to `keyserver.ubuntu.com` and `keys.openpgp.org`
- [ ] Four secrets set on the `android_deeplinkly` repo
