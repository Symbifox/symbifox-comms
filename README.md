# Symbifox Comms

A mobile client that puts the email and SMS of an [Odoo](https://odoo.com)
instance into a single inbox, with triage, filing and routing done server-side
rather than on the phone.

Android 8.0 (API 26) and later. No Google Play Services, no Firebase — push
notifications go through [UnifiedPush](https://unifiedpush.org).

## What it does

- **One unified inbox**, email and SMS, in two tabs
- Reply, reply-all, forward and compose, with attachments
- Archive, snooze, and a five-second undo on either
- **Route a message into Odoo**: attach it to a record, or spawn a task,
  helpdesk ticket, lead, invoice, bill or expense straight from an email
- Configurable swipe gestures and quick-action buttons
- Offline cache, so a train tunnel doesn't empty the screen
- Brand colours picked up from the Odoo instance it connects to

The app never sees your password. Sign-in opens your Odoo instance's own web
login, which hands back a single-use code that the app trades for a token.

## Server requirements

This is a client. It talks to a REST API served by two Odoo modules, which are
**not** free software — they are licensed under the Business Source License 1.1:

| Module | Provides |
| --- | --- |
| `bf_email_management` | the unified mailbox, routing, snoozing, the mobile API |
| `bf_sms_archive` | SMS threads and sending |

Either module may be absent; the app simply hides the tab it can't reach. The
instance URL is asked for on first launch and can be changed at any time, so the
app is not tied to any particular server.

## Building

Requires JDK 17 and the Android SDK (compileSdk 34).

```
./gradlew :app:assembleRelease
```

`build_apk.sh` does the same inside a `gradle:8.10.2-jdk17` container, which is
how release builds are produced. Signing credentials are read from
`signing.env`, which is not in this repository.

Unit tests:

```
./gradlew :app:testReleaseUnitTest
```

## Installing

Releases are published to a self-hosted F-Droid repository. Add it in F-Droid
under Settings → Repositories, or install the APK directly from the releases.

## Licence

GPL-3.0-or-later. See [LICENSE](LICENSE).

The Odoo modules this app talks to are separate programs under a separate
licence, as noted above.
