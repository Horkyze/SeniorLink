# SeniorLink

SeniorLink is an Android app for voluntary family check-ins. Your family member
chooses which updates to share from their phone and approves each caregiver.
Caregivers open the same app on their own phones to see check-ins, recent phone
activity, locations, selected SMS and readings from compatible wearables. Updates
travel over an encrypted phone-to-phone connection; you don't need to run a server.

**[Download the Android app](https://github.com/Horkyze/SeniorLink/releases/download/v0.1.6/SeniorLink-0.1.6-debug.apk)**
· [Release notes](https://github.com/Horkyze/SeniorLink/releases/tag/v0.1.6)
· [Wearable setup](docs/wearables.md)
· [Build from source](#building-and-contributing)

For example, your grandfather can share an “I'm okay” check-in and readings from a
nearby Bluetooth band. His phone saves the updates, and your phone catches up when
you open SeniorLink while his app is sharing and reachable. Other approved family
members can catch up independently on their own phones.

This is an early **family pilot**, not a medical device or emergency-response
service. It does not send emergency alerts or guarantee continuous monitoring.

## Get started

You need **Android 8.0 or newer** on the sharing phone and every caregiver phone.
Both roles use the same APK (the Android installation file).

1. **Install SeniorLink on each phone.** Download the APK above and open it.
   Android may ask you to allow installation from the browser or file app.
2. **Choose each phone's role.** Your family member chooses **Share my information**;
   everyone receiving updates chooses **I'm a caregiver**.
3. **Connect a caregiver.** Keep both apps open and online. On the sharing phone,
   open **Phones → Connect a caregiver**. On the caregiver phone, open
   **Phones → Scan QR** and scan the code.
4. **Confirm together.** Compare the four-character code on both phones. If it
   matches, tap **Codes match — connect** on the sharing phone. Repeat for each
   caregiver. Approval gives access to retained history for enabled features while
   sharing is on.
5. **Choose what to share.** On the sharing phone, open **Settings**, select the
   features and tap **Save settings**. Then open **Updates → Start sharing**,
   grant the requested permissions and tap Start again.
6. **Try a check-in.** Tap **I'm okay — check in** on the sharing phone. Open the
   caregiver app to see it arrive.

A notification stays visible while sharing. Use **Pause sharing** in the app or
notification to stop collecting and sharing. Pause before changing settings.
Removing a caregiver stops future access, but cannot erase updates already received.

**Updating an existing installation?** Install 0.1.6 on **all paired phones**;
its synchronization protocol cannot communicate with older versions. The published
APK uses the same signing key as versions 0.1.0–0.1.5, so install it as an update
without uninstalling to keep pairing and history. Versions 0.1.3 onward check for
updates when opened; 0.1.5 onward also offers **Settings → App updates → Check for updates**.
The release includes `SHA256SUMS` if you want to verify the download.

## What can be shared?

Your family member controls the optional features. Caregiver mode only receives
updates; it does not monitor the caregiver's own phone or wearable.

| Update | What caregivers see |
| --- | --- |
| **“I'm okay” check-in** | A timestamped message sent by your family member tapping a button. |
| **Phone activity** | When Android reports that the phone was unlocked; this is a best-effort signal. |
| **Location** | The latest saved location and previous fixes on a map, with times and accuracy. Updates are approximately every 15 minutes when available. |
| **Selected SMS** | Messages from an exact list of allowed senders. Message bodies are off by default. |
| **Wearable readings** | Supported Bluetooth measurements, receipt times and summaries. Available data depends on the device. |

Open **Updates** for recent activity, **Location** for the map and **Wearable** for
wearable readings. Always check the timestamps: saved information may be old.

### Connect a wearable

On the sharing phone, pause sharing and open **Settings → Bluetooth wearable →
Choose wearable**. Select the nearby band, enable **Share wearable readings**,
save and start sharing again. Allow Nearby devices permission when asked; Android
8–11 require Location permission and the system Location setting for scanning.

The band sends readings directly to the sharing phone over Bluetooth. The phone
forwards summaries to caregivers. Samsung Health and Health Connect are not part of this
collection path. Supported standard measurements include heart rate, skin contact,
battery, temperature, oxygen saturation and blood pressure **where the device
exposes them**. This does not replace initial band setup or firmware management.

**Galaxy Fit3 compatibility still needs testing on a physical band.** Heart rate
and skin contact are the expected first useful readings; standard battery data may
not be available. Proprietary sleep, steps, stress, ECG and stored history are not
collected. The band must stay within Bluetooth range of the sharing phone; readings
missed during a disconnection cannot be recovered later.

See [wearable setup, supported data and the real-device checklist](docs/wearables.md).

### Optional Telegram forwarding

The sharing phone can also forward enabled updates to a Telegram chat or channel.
Create a bot through Telegram's BotFather, give it permission to post to the
destination, and enter its token and chat/channel ID in SeniorLink's settings.
Save, use **Send test to saved destination**, then enable forwarding if wanted.
Only newly collected updates are queued; disabling forwarding clears the queue.

Telegram bot messages are **not end-to-end encrypted**. Keep sensitive SMS bodies
disabled; the app's verification-code filtering cannot catch every secret. Network
retries can occasionally duplicate a Telegram post.

## When will updates arrive?

- **Both phones must be reachable at the same time.** The sharing app must be
  running, and the caregiver app checks for updates while open. A caregiver cannot
  remotely wake a stopped sharing app. There are no closed-app push alerts.
- **Missed deliveries can catch up.** Collected history stays on the phones for up
  to seven days, capped at 10,000 events per source. Each caregiver catches up
  independently. Events that were never collected cannot be recovered.
- **Android can interrupt sharing.** After a reboot or force-stop, open SeniorLink
  on the sharing phone and tap Start again. Battery restrictions, revoked
  permissions or disabled Bluetooth/location can also interrupt collection.
- **Offline views show saved data.** Read the recorded times and connection status.
  Map tiles need internet for uncached areas; saved coordinates remain readable.

Test behavior and battery use on your family's actual devices before using it for
routine check-ins. See the [real-phone acceptance checklist](docs/acceptance.md)
and [completed verification](docs/verification.md).

## Privacy and control

Phone-to-phone sharing uses **iroh** with end-to-end encryption and explicit
caregiver approval. Public discovery and relay services help phones connect;
relays cannot read the updates and do not store messages for offline phones.
Connection metadata is not hidden, and public infrastructure has no availability
guarantee. The optional Telegram output has different privacy properties, as noted above.

History is stored in each app's private database. Cloud/device-transfer backups
are disabled, and the UI blocks screenshots and recent-app thumbnails. Clearing
app storage or uninstalling deletes local identity, pairing and history.
Disabling a feature withdraws its retained local events and queued Telegram output;
copies already delivered to caregivers cannot be recalled.

Maps use OpenStreetMap tiles. The tile service receives the viewer's IP address
and requested map areas; family names, timestamps and the location history are
rendered on the phone. Maps need no API key or Google Play Services. See the
[OpenStreetMap tile policy](https://operations.osmfoundation.org/policies/tiles/).

## Troubleshooting

| Problem | What to try |
| --- | --- |
| No updates arrive | Check that sharing is started, both phones are online, the caregiver app is open and all phones run 0.1.6. Check permissions and battery restrictions on the sharing phone. |
| Camera is unavailable for pairing | Use **Copy invitation / Share invitation** on the sharing phone and **Use a shared invitation instead** on the caregiver. Compare the verification code through a trusted conversation. |
| Pairing expired or the codes differ | Start a new invitation on the sharing phone and keep both apps open. Invitations expire after five minutes. |
| Wearable is connected but has no readings | Enable measurement on the band, check skin contact and inspect **Wearable → Available Bluetooth data**. Follow the [device checklist](docs/wearables.md#fit3-expectations-and-verification). |
| An APK cannot update the installed app | It may use a different signing key. Use the published APK; do not uninstall merely to bypass a signing mismatch if you need to keep history and pairing. |

## Building and contributing

Project instructions are in [AGENTS.md](AGENTS.md). The main components are
[`app/`](app/) (Android UI, storage, collection and networking), [`core/`](core/)
(protocol, validation and Bluetooth decoding) and [`native/`](native/) (iroh native
build). See [pairing internals](docs/pairing.md) and [native build notes](native/README.md).

<details>
<summary><strong>Build, test and package the Android app</strong></summary>

The build uses JDK 21, Android SDK 35, build tools 35.0.0, NDK 28.2.13676358 and
Rust 1.94.0. Gradle and native dependencies are pinned. ARM64, ARMv7 and x86-64
are included in the normal APK.

On an Apple Silicon Mac, run from the repository root:

```sh
# Installs tools into ignored .build-tools/ and accepts the Android SDK terms.
sh scripts/bootstrap-macos.sh
. ./scripts/env.sh
sh scripts/build-native.sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 scripts/verify-apk.py
```

The native script needs `rustup` on PATH or in `~/.cargo/bin`. It installs its Rust
toolchains and dependencies inside `.build-tools/`. On Linux, provide JDK 21,
`ANDROID_HOME`, the NDK and rustup, then run the native build, Gradle and APK checks.
The installable output is `app/build/outputs/apk/debug/app-debug.apk`.

For Android UI, service and native transport checks, connect an emulator or an
authorized test device and run:

```sh
./gradlew :app:connectedDebugAndroidTest
```

Use synthetic data in tests and never put real Telegram credentials in fixtures.
Some transport tests require internet and the public iroh discovery/relay services.
Record actual results separately from unverified physical-device behavior in
[docs/verification.md](docs/verification.md).

`sh scripts/build-pilot.sh` builds, checks and packages the APK into `dist/`.
The checkout carries small archive parts of the published pilot APK, which can
also be restored without an Android toolchain using Python 3:

```sh
python3 scripts/unpack-pilot.py
```

This reconstructs and checksum-verifies `dist/SeniorLink-0.1.6-debug.apk`.
The archive parts are not themselves installable. Full APKs, signing material and
build output remain ignored. CI builds an APK and uploads test reports; it does
not publish a release automatically.

These are **debug-signed pilot builds**. An APK built on another machine or in CI
may have a different signing key and cannot update an existing installation.
Preserve the original signing key for compatible updates. Keep the Kotlin iroh API
and native FFI versions aligned; follow [native/README.md](native/README.md) when
changing either.

</details>
