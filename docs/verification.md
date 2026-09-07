# Verification

## Location map — 0.1.4

- Core: 23 passing tests. Android unit tests: 23 passing tests. New SQLite
  checks verify that location history survives a busy mixed feed, uses fix time
  rather than receipt order, stays separate per source, expires and is removed
  with consent/peer withdrawal. A version-1 database migrates in place while
  preserving events, paired phones, cursors, Telegram jobs and sequence counters.
- Android 17 / API 37.1 ARM64 emulator with 16 KB pages: five location UI tests
  cover the embedded map, history selection, switching phones, an older-update
  link with matching sequence numbers on different phones, missing data, peer
  removal, background/resume and offline selection. Wi-Fi/mobile data settings
  are restored after the offline test. The selection workflow also passes at
  font scale 2.0; map controls wrap for large text.
- Visually inspected real raster tiles and the latest/previous selection markers
  using synthetic locations in Bratislava, plus the offline message and large
  text layout. Production screenshot protection remains enabled.
- Debug and instrumentation APKs build successfully. Lint has zero errors;
  APK signature and native ABI/16 KB ELF and ZIP alignment checks pass, including
  the new MapLibre native library on all three supported ABIs.
- Updated the AndroidX UI test dependencies to stable Espresso 3.7.0 and matching
  runner/rules to fix the old runner's removed InputManager reflection on Android 17.
  No collection permissions were added; caregiver maps use only received fixes.
- The 0.1.4 APK (version code 5) has the same signing certificate as the published
  0.1.3 APK. The unchanged iroh binaries match that release on all three ABIs.
  Reconstructed the APK from its six archive parts in an isolated directory and
  verified that its bytes and SHA-256 match the tested APK.

This build is installed on the Android 17 test emulator. Map tiles require internet
for uncached areas; saved coordinates and history remain available offline.
Physical-phone verification remains outstanding.

## Startup update check — 0.1.3

- Core: 23 passing tests. Android unit tests: 20 passing tests, including 11 new
  checks for semantic version ordering, published pilot prereleases, APK selection,
  trusted download URLs, HTTP/offline/timeout failures, bounded responses,
  cancellation, asynchronous startup and dismissal across activity recreation.
- Android 15 / API 35 ARM64 emulator: all four selected tests pass (three update
  prompt tests and the existing Start/check-in/Pause workflow). The update tests
  verify that only accepting opens the exact APK URL, Later dismisses, and a missing
  browser leaves a copyable download link. All three update tests also pass with
  Android's system font scale set to 2.0. Normal and large-font captures were
  inspected; the text fits and both actions remain accessible.
- Debug APK and instrumentation APK build successfully. Lint has zero errors and
  the same 17 existing warnings; native ABI and 16 KB alignment packaging checks pass.
- The 0.1.3 APK (version code 4) has the same signing certificate as the published
  0.1.2 APK. An in-place emulator update preserves settings, encrypted identity and
  synthetic check-in history; a new check-in uses the same permanent identity.
- Reconstructed the 0.1.3 APK from its five synced archive parts in a separate
  directory and verified that its bytes and SHA-256 match the tested APK.
- The public GitHub release list was reachable without authentication and contained
  the existing 0.1.0–0.1.2 pilot prereleases and their APK download assets. The newer
  version UI scenarios use synthetic releases and intercepted browser intents;
  they do not download or install a future release.

## Single-scan pairing — 0.1.2

- Core: 23 passing tests, including 16 connection tests with real Ed25519 signatures.
  Coverage includes matching codes, QR nonce commitments, signature/identity/request
  tampering, request pinning, host and caregiver rejection, late cancellation, expiry,
  lost replies, failed persistence, and revocation during a retry.
- Android unit tests: 9 passing tests, including offline decoding of the larger,
  temporary invitation QR, scanner configuration and the existing SQLite coverage.
- Android 15 / API 35 ARM64 emulator: the full 10-test suite passes. The native
  pairing test keeps synchronization on separate permanent endpoints, refuses access
  before approval, recovers after lost pairing responses, and receives a check-in
  after a single invitation. Existing sync, camera and monitoring tests also pass.
- The full UI connection test uses the production controllers on both sides and
  real iroh networking. It rejects one request, creates a fresh invitation, compares
  both displayed codes, rotates the sharing activity, then confirms and verifies
  both databases have the connection.
- A separate fresh caregiver-mode run passes scanner cancellation, invalid/legacy
  QR handling, camera denial, manual fallback and form recreation.
- Four pairing UI tests also pass with Android's system font scale set to 2.0,
  including the complete connection flow. Screenshots were inspected: the code
  stays on one line and the confirmation/rejection buttons remain separate.
  Captures use synthetic data and temporarily permit screenshots only in test code.
- Lint has zero errors (17 existing dependency/style/resource warnings). APK build,
  native ABI/16 KB alignment checks and APK signature verification pass.
- Native libraries were reused unchanged from the checksum-verified, checked-in
  0.1.1 pilot APK. The iroh dependency and native transport versions were not changed.
- Installed the published 0.1.1 APK, created a synthetic check-in and peer, then
  installed 0.1.2 in place. Settings, encrypted identity, paired phone and history
  survived; the updated app added another check-in using the same permanent identity.
- Reconstructed the 0.1.2 APK from its five archive parts in an isolated directory;
  its bytes and SHA-256 match the tested, originally signed APK.

The release APK uses the original pilot signing key. Its certificate matches the
published 0.1.0 and 0.1.1 APKs, allowing in-place installation without clearing data.
Signing material remains local and is not included in the repository or release.
Physical phone-to-phone optical scans, manufacturer-specific behavior and network
transitions still require the real-phone acceptance checklist.

## QR pairing update — 0.1.1

- Core: 7 passing tests, including shared scanned/pasted-code validation and rejection
  of self, unrelated and malformed codes.
- Android unit tests: 9 passing tests, including the bundled QR decoder reading
  the existing pairing-code format from a synthetic camera-luminance frame, and
  scanner options restricting decoding to QR without saving images.
- Android 15 / API 35 ARM64 emulator: all 6 instrumented tests pass. New tests
  cover scan result delivery, explicit approval, manual code edits resetting
  approval, cancellation, invalid/self QR, permission-denied recovery, draft
  restoration after recreation, and real camera preview shutdown on background/cancel.
- The pairing UI test also passes in a separate fresh caregiver-mode run:
  `:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=family.seniorlink.PairingUiTest -Pandroid.testInstrumentationRunnerArguments.pairingRole=CAREGIVER`.
- The caregiver cold-start run exposed a Compose slot-table failure. Loading,
  role setup and main content now use explicit branches instead of early returns
  from an inline composable; both role workflows pass after that change.
- Manually exercised the actual Android Camera prompt: deny returns to the form
  with settings/manual fallback; retry and allow opens the embedded scanner.
- Verified APK ABI/alignment packaging and signature. The signing certificate is
  identical to the published 0.1.0 APK.
- Installed 0.1.0 on the emulator, chose a role, seeded a synthetic peer/check-in,
  then installed 0.1.1 in place. Settings, encrypted identity, public pairing code,
  peer and history survived without uninstalling.
- Reconstructed the 0.1.1 APK from its synced parts in a separate directory and
  verified its checksum. Invalid manifests, a missing part and checksum mismatch
  are rejected.

UI tests inject synthetic scan results; the decoder and actual camera lifecycle
are tested separately. Optical phone-to-phone QR capture, camera behavior on
physical family phones, low-light performance, and landscape/large-font usability
remain on the real-phone acceptance checklist. The APK and checksum are available
in the [v0.1.1 prerelease](https://github.com/Horkyze/SeniorLink/releases/tag/v0.1.1).

## Initial 0.1.0 pilot — build and automated checks

Environment: Apple Silicon macOS, JDK 21, Android SDK 35, Rust 1.94.0 and NDK
28.2.13676358. Native libraries built for ARM64, ARMv7 and x86-64.

- `:core:test`: 6 passing tests.
- `:app:testDebugUnitTest`: 7 passing Android SQLite/Robolectric tests.
- `:app:connectedDebugAndroidTest`: 4 passing tests on an Android 15 / API 35
  ARM64 emulator with 4 KB pages.
- `:app:lintDebug`: zero errors. Remaining warnings are newer dependency-version
  suggestions and optional Kotlin extension-style suggestions.
- `scripts/verify-apk.py`: all required ABIs and both native libraries present;
  16 KB ELF alignment for 64-bit libraries; 16 KB APK entry alignment;
  no bundled desktop native resources.
- Android `apksigner verify`: APK signature verified.
- Reconstructed the APK from the five synced archive parts in a separate directory;
  its bytes and SHA-256 match the tested APK. A missing archive part is rejected.

The on-device tests cover native FFI initialization, real QUIC streams, public-key-only
discovery using public iroh infrastructure, independent history for two caregivers,
restart/deduplication behavior, rejection of unapproved/revoked identities, encrypted
Keystore-backed storage, and the real UI/foreground-service check-in and pause flow.

## Manual emulator workflows

On the same emulator, using only synthetic data:

- Installed and launched the APK through Android's normal application entry point.
- Configured unlock, location and selected-sender SMS through the UI.
- Injected an SMS from allowed sender `12345` and one from unapproved sender `99999`.
  Only the allowed sender was stored, with a null body while body sharing was off.
- Injected an Android network-provider location (`50.08, 14.43`, accuracy 12 m).
  The actual service callback saved the coordinates and accuracy.
- Enabled the emulator's keyguard, locked the screen, and dismissed keyguard.
  Verified an `UNLOCK` event in the on-device database. This caught and fixed the
  registration flag issue where a non-exported receiver missed SystemUI broadcasts.
- Paused sharing through the UI, injected another allowed-sender SMS, and verified
  that no additional event was collected.
- Restored the emulator's temporary keyguard and mock-provider settings.

## Not established by these checks

- No physical family phones were attached. Manufacturer-specific battery behavior,
  overnight reliability, mobile-network transitions, ARMv7/x86-64 runtime behavior,
  and operation on a 16 KB-page device still need the acceptance checklist.
- Telegram has no supplied credentials, so no real bot/channel message was sent.
  Use the app's explicit test button after entering credentials on the sharing phone.
- No claim of guaranteed unlock capture, instant delivery, emergency detection,
  medical reliability, or complete filtering of sensitive SMS content.

The delivered APK is a debug-signed pilot. Production signing/key stewardship and
long-term deployment validation remain separate from this initial working build.
