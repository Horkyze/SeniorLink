# SeniorLink

A directly installed Android app for voluntary family safety sharing. Install the
same APK on the senior's phone and each caregiver's phone. The sharing phone
explicitly approves every caregiver; caregivers catch up when they open the app.

## Get the APK without building

Download **[SeniorLink-0.1.4-debug.apk](https://github.com/Horkyze/SeniorLink/releases/download/v0.1.4/SeniorLink-0.1.4-debug.apk)**
from the **[v0.1.4 prerelease](https://github.com/Horkyze/SeniorLink/releases/tag/v0.1.4)**.
The release adds an interactive map for the latest known location and previous
fixes, with timestamps, accuracy and separate history for each sharing phone.
The release assets include `SHA256SUMS` for verifying the download.

For a synced checkout, the same APK is also carried as small archive parts because
the full APK exceeds the thread's binary-file sync limit. Restore and checksum-verify
it to `dist/SeniorLink-0.1.4-debug.apk` with:

```sh
python3 scripts/unpack-pilot.py
```

This needs only Python 3, not Java, Rust or Android Studio. Copy the resulting APK
to the phones. The archive parts themselves are not installable Android packages.

This is a **debug-signed family pilot**, not a production or emergency-response app.
Install the same APK on the sharing phone and each caregiver's phone. The published
0.1.4 APK uses the same signing certificate as the published 0.1.0–0.1.3 APKs,
so it can update those installations without uninstalling or clearing pairing/history.
Version 0.1.3 checks for this update when you open the app. For versions 0.1.0–0.1.2,
install 0.1.4 manually using the link above to enable prompts for future updates.

## Features

- **Two roles:** sharing phone and caregiver. Caregiver mode never monitors its own
  SMS, location or unlock activity.
- **iroh peer connections:** persistent public-key identities, single-scan
  pairing, encrypted delivery, and an approved-peer list.
- **In-app QR scanner:** bundled offline decoding, no external camera app or Google
  Play Services needed. Both phones display the same four-character verification
  code; the sharing phone explicitly confirms access.
- **Independent catch-up:** each caregiver has its own durable synchronization
  cursor. One offline caregiver does not block another.
- **Phone unlock activity:** best-effort observation of Android's unlock broadcast.
- **Location:** opt-in, approximately 15-minute updates when a provider supplies a
  fix, with accuracy and timestamps. Network location is preferred; GPS is a fallback.
- **Location map:** open **Location** or **Updates → View location map** for an
  interactive map of the latest known position and previous fixes. Choose a phone,
  tap a map marker or history entry, and use **Latest location** or **Show history**
  to frame the map. Teal marks the latest fix; amber marks previous fixes. Each
  selection shows its recorded time and accuracy. This is saved history, not live tracking.
- **SMS:** separately enabled, exact sender allowlist required, multipart messages
  assembled. Bodies are off by default; likely verification codes are withheld.
- **Manual “I'm okay” check-in.**
- **Optional Telegram forwarding:** bot token plus chat/channel ID, test message,
  persistent retry queue, rate-limit handling. Only the sharing phone posts.
- **Visible monitoring:** foreground notification with a Pause action. No hidden
  collection, remote activation, boot receiver or automatic restart after force-stop.
- **Update prompt on startup:** checks the public GitHub releases in the background,
  including family pilot prereleases. A newer version with an APK offers **Download
  update** or **Later**. The download opens in your browser; install the APK to update.
  Offline or failed checks are silent. Dismissing lasts until the next app launch;
  rotating the phone or returning from another app does not repeat the check.

This is an initial family pilot, not an emergency-response or medical device.

## Install and pair

Requires Android 8.0 (API 26) or later on ARM64, ARMv7 or x86-64.

1. Download the APK above (or build it below) and install the same version on all phones.
   Android may ask you to allow installation from the app opening the APK.
2. On your grandfather's phone choose **Share my information**. On the other
   phones choose **I'm a caregiver**.
3. Keep both apps open and online. On your grandfather's phone, open **Phones →
   Connect a caregiver**. On the caregiver's phone, open **Phones → Scan QR** and
   scan it once. Each phone has an optional name field, such as Grandad or Anna.
4. Both phones display the same four-character code, for example **K7MP**. Compare
   them together. If they match, tap **Codes match — connect** on your grandfather's
   phone. Both phones show **You're connected**. If they differ, tap **Codes don't
   match** and start again. Repeat this one-scan process for each caregiver.
   Approval includes retained history for currently enabled features while sharing
   is on. No sharing starts automatically.
5. On his phone, use **Settings** to choose the features, then **Save settings**.
   For SMS, enter full sender numbers (including country code) or exact sender
   names, one per line. No wildcard matching.
6. Go to **Updates → Start sharing**, grant the selected Android permissions,
   then tap Start again. A notification remains visible while sharing.
7. Tap **I'm okay — check in**. Open the caregiver app to receive it. Missed
   retained events arrive automatically; current updates are polled every 15 seconds
   while the caregiver app is visible.

Camera is requested only when the caregiver taps Scan QR. Cancel/back or a camera
error does not add a phone. No camera frames are saved or uploaded. If Camera is
denied or unavailable, the sharing phone can use **Copy invitation / Share invitation**;
the caregiver opens **Use a shared invitation instead**, pastes it, and taps Connect.
Compare the four-character code through a trusted conversation before confirming.

Invitations expire after five minutes. A rejected request requires a new QR, and a
reconnect keeps the exact same verification code. Keep both apps open until connected.
If an attempt expires or a phone restarts, show a new QR and try again. Existing paired
phones continue working; old public-key QR codes ask you to update both apps.

To stop collecting and sharing, use **Pause sharing** in the app or notification.
Pause before editing feature settings. Removing a paired phone stops future access;
it cannot recall information already received.

### Telegram

Create a bot through Telegram's BotFather and grant it posting permission in the
destination channel. On the **sharing phone only**, enter the bot token and a
numeric chat/channel ID (often negative) or `@channel` name. Save, then use
**Send test to saved destination**. The test is an explicit outbound message even
when automatic forwarding is disabled.

Enable **Forward enabled updates to Telegram** to forward newly collected events
while sharing is active. Old events are not retroactively queued when enabling it.
Disabling Telegram clears its pending queue. Tokens are encrypted locally, never
embedded in source, logs, pairing codes or backups.

Telegram bot/channel messages are **not end-to-end encrypted**. SMS body filtering
is only a precaution, not a guarantee that every secret will be detected. Keep
bodies disabled for banking, verification and other sensitive senders. A retry
after an ambiguous network failure can occasionally duplicate a Telegram post.

## Catch-up and reliability

- Your grandfather's app must be running and reachable at the same time as a
  caregiver app for synchronization. Opening a caregiver app cannot wake a
  suspended sharing app through iroh.
- If unreachable, the caregiver keeps its cached information, timestamps and last
  contact. This information may be stale. There are no closed-app push alerts.
- Android battery restrictions, Doze, revoked permissions, a disabled location
  provider, force-stop or a reboot can interrupt collection/networking. After
  reboot or force-stop, open the sharing app and tap Start again.
- A foreground service reduces interruptions; it is not an always-online guarantee.
  It uses the `specialUse` type for user-enabled safety sharing and additionally
  the `location` type when location is enabled, not a perpetual `dataSync` service.
- Unlock observation is not a complete audit log. Permission denial is respected.
- Retention is seven days, capped at 10,000 events per source; pruning occurs
  during use/synchronization. Updates shows the latest 100 events; Location
  independently shows up to 1,000 retained fixes per phone, ordered by the time
  Android recorded them. Busy SMS/unlock activity cannot crowd locations out of
  the map. Caregivers are
  told when history expired or was withdrawn before receipt.
- Map tiles require internet for uncached areas. Coordinates and history remain
  readable without tiles. OpenStreetMap receives the viewer's IP address and
  requested map areas; names, event timestamps and the location history itself
  are rendered on-device. The map needs no API key, Google Play Services or
  location permission on a caregiver phone. Tiles use normal HTTP caching, an
  app-specific User-Agent, visible attribution and no area prefetch/downloads,
  following the [OpenStreetMap tile policy](https://operations.osmfoundation.org/policies/tiles/).
- The sharing phone sends each caregiver's batch independently. Events and cursors
  are committed atomically before acknowledgment; retries do not create duplicate
  caregiver events.
- Iroh uses public discovery/relays as needed. Relays cannot read payloads, but
  connection metadata is not hidden. Relays do not store messages for offline phones.
  Public infrastructure has no availability guarantee.

Verify behavior and battery use on the actual phones before relying on it for
routine family check-ins. Missing contact alone is neither proof of safety nor
proof of an emergency.

## Build

The normal build uses JDK 21, Android SDK 35, build tools 35.0.0, Android NDK
28.2.13676358 and Rust 1.94.0. Gradle is pinned by the checked-in wrapper, including
its distribution checksum. Native Cargo dependencies are locked.

On an Apple Silicon Mac, dependencies can be installed inside this checkout:

```sh
# Downloads tools into ignored .build-tools/ and accepts the Android SDK terms.
sh scripts/bootstrap-macos.sh
. ./scripts/env.sh
sh scripts/build-native.sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The native script requires `rustup` on PATH or in `~/.cargo/bin`; it installs
toolchains/targets and Cargo downloads into `.build-tools/`, not the global
toolchain. On Linux, supply JDK 21, `ANDROID_HOME`, NDK and rustup, then run the last
two commands. CI in `.github/workflows/android.yml` builds all supported ABIs and
uploads an installable pilot APK.

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

`sh scripts/build-pilot.sh` runs the native build, checks, and APK packaging, then
copies the installable file and checksum to `dist/`. This initial handoff includes
that versioned pilot APK as compressed archive parts so it can be installed without
setting up a build machine. The full APK, other generated output and all signing
material remain ignored. Nothing is
automatically published to an app store or release service.

The APK is a **debug-signed pilot build**, not a production signing setup. Keep
the same signing key for updates; APKs from another machine or CI may use a
different debug key and require uninstalling, which deletes history and pairing.
Do not uninstall an existing installation merely to update it without considering
this data loss.

The official iroh Android AAR was evaluated, but its bundled native libraries
were 4 KB-aligned and used an older transport. We rebuild the matching FFI source
with **iroh 1.1.0 security fixes and 16 KB ELF alignment**. See
[`native/README.md`](native/README.md) for the source pin and upgrade rule.
Do not mix unrelated Kotlin and native FFI versions.

### Tests

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug

# With an emulator or authorized test device connected:
./gradlew :app:connectedDebugAndroidTest
```

- Core tests cover protocol validation, forged source IDs, bounded payloads,
  durable-before-ACK ordering, reconnect after a lost ACK, and SMS policy.
- Android SQLite tests cover two caregivers, restart recovery, invalid batches,
  removal, retention, consent withdrawal and Telegram queue cleanup.
- Instrumented tests use real native QUIC connections and Android SQLite to
  synchronize two caregivers and reject unapproved/revoked identities. They also
  exercise Android Keystore persistence and the actual Compose Start/check-in/Pause
  workflow, including continued sharing while the sharing screen is backgrounded.
- `PublicDiscoveryTest` dials using only a public key and requires Internet plus
  the public iroh discovery/relay infrastructure.
- Pairing tests cover signed identity verification, matching codes, nonce commitments,
  forged or changed requests, expiry, rejection, lost replies, storage failures and
  revocation. A native transport test pairs once and then receives a check-in using
  the permanent identities. UI tests cover role-specific setup, confirmation screens,
  scanner cancellation, permission denial, manual fallback and activity recreation.
  QR decoding and camera lifecycle are tested separately.

Tests use synthetic events. Do not enter real Telegram credentials into tests.
The completed checks are recorded in [`docs/verification.md`](docs/verification.md).
The remaining real-phone acceptance checklist is in [`docs/acceptance.md`](docs/acceptance.md).

## Code map

- `core/`: versioned wire models, message validation, pairing/SMS policy and the
  durable-before-ACK synchronization rule.
- `app/.../data/Store.kt`: SQLite transactions, retention, peer cursors and outbox.
  SQLiteOpenHelper is used directly rather than introducing a Room code-generation
  pipeline for this small schema.
- `app/.../data/Secrets.kt`: Keystore-backed AES-GCM storage in Android's no-backup
  directory. History stays in the app-private database.
- `app/.../net/IrohSync.kt`: authenticated peer authorization and lifecycle-owned
  network connections.
- `app/.../monitor/`: foreground monitoring and SMS receiver.
- `app/.../net/Telegram.kt`: optional HTTPS output, isolated from peer sync.
- `app/.../pairing/`: temporary connection endpoints and the single-scan UI.
  [`docs/pairing.md`](docs/pairing.md) describes the signed handshake and verification code.
- `MainActivity` / `MainViewModel`: Compose UI, role setup and explicit controls.

Cloud/device-transfer backups are disabled. The UI blocks screenshots and recent-app
thumbnails to reduce accidental disclosure. Resetting Android app storage changes
the identity and requires pairing again.
