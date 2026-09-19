# Privacy policy: Symbifox Mobile

**Last updated: 3 September 2026**
**Controller: Blue Fox Inc.**, <bonjour@symbifox.com>

Symbifox Mobile is a client for **your own** Odoo instance. It has no account,
no server and no service of its own.

*English version of `PRIVACY.md`, published on 18 September 2026 and carrying
the date of the French policy it translates (3 September 2026). Both say the same
thing; where they differ, the French one governs, because it is the one Blue Fox
Inc. writes first.*

## In one sentence

The app collects nothing, transmits nothing to Blue Fox or to any third party,
and talks only to the Odoo instance you point it at yourself.

## What the app reaches

- **Your Odoo instance**, the one whose address you enter on first launch. It
  reads and writes there whatever your Odoo account already allows you to: your
  email, the texts of the business lines, your calendar, your tasks.
- **The microphone**, only during a call or a dictation, and only after you have
  allowed it. It is never opened outside those two actions.
- **Your company's brand name and colours**, read from the same instance, to
  adapt the appearance.

## 🔴 What it does not reach

The app requests **none** of the permissions that would give access to the
contents of your phone:

- no reading of the **SMS or MMS** on your SIM card;
- no reading of the **call log**;
- no reading of your **contacts**;
- no **location**, no camera, no shared storage.

The texts it shows are those of your Odoo instance's business lines, not those
of your phone. A separate app, **BF SMS Relay**, does that job; it is separate
precisely so that installing Symbifox Mobile forces nobody to hand over access
to their personal texts.

The declared permissions can be checked: internet, notifications, microphone,
foreground service, wake lock, and showing an incoming call on the lock screen.

## What the app keeps, and where

Everything stays **on your device**, in its private storage, and nowhere else:

| What is kept | How |
|---|---|
| The access token for your instance | encrypted (Android Keystore) |
| A cache of your mailbox: the first page of each folder and recently opened conversations, **message bodies included** | files private to the app |
| The instance address, its name and its colours | files private to the app |
| Your preferences: theme, gestures, sorting | files private to the app |

The cache exists so the app opens on your mailbox rather than on an error
message when the network is missing. It is not separately encrypted: it is
protected by Android's sandboxing, like any app's data, and by your device lock.

⚠️ If the phone's keystore declines, the token falls back to the app's ordinary
private storage rather than being encrypted. It stays out of reach of other
apps, but we would rather say so.

**Android's automatic backup is turned off**: nothing goes to Google. Exchanges
with your instance happen over `https` only, cleartext traffic being refused by
the app.

## Notifications

Notifications go through **UnifiedPush**, not through Google's services. No
Google Play services, no Firebase.

In practice: you install on your phone an app called a *distributor*, ntfy for
example. It gives you a receiving address, which Symbifox Mobile passes to your
Odoo instance so that it knows where to push.

⚠️ **What you should know**: a pushed notification carries a title and a
preview, for example the sender and the start of a message. That content
**travels through the server of the distributor you chose**. If that server
belongs to a public service, it is a third party in the path. A distributor
pointing at a server you or your organisation hosts avoids that third party.

You may install no distributor at all: the app works, it simply re-reads its
data when you open it instead of being told.

## What the app transmits, and to whom

Only to **the Odoo instance you designated**, and, for calls, to the telephony
server that this instance points it at.

The app contacts **no other server**. There is no analytics, no telemetry, no
crash reporting, no advertising, no remotely loaded code. Blue Fox Inc. receives
no data about your use of it.

No password passes through the app: sign-in happens on your instance's own web
page, which returns a one-time token.

## What the app does not do

- It does not sell, rent or share any data.
- It connects to no Blue Fox service.
- It observes nothing of what you do elsewhere on the phone.

## Retention and deletion

Your data lives on your Odoo instance, and its retention there is set by your
organisation. On the device:

- **uninstalling** the app erases everything it was keeping;
- **signing out** in the app removes the token and the cache;
- from your instance, the **"My devices"** page (`/my/appareils`) lists the
  paired devices and lets you remove one, or all of them.

⚠️ Removing a device does not wipe it: it keeps its token, that token simply
opens nothing any more.

## Your rights

Since no personal data is collected by Blue Fox Inc., there is nothing on our
side to consult, correct or delete. Your data stays under your control, on your
device and on your Odoo instance. For any question: <bonjour@symbifox.com>.

## Changes

Any change to this policy will be published at this address, with its date.
