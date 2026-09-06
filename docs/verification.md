# Verification of the initial pilot

## Build and automated checks

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
