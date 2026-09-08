# Working on SeniorLink

SeniorLink is an Android family check-in app. The sharing phone explicitly
approves caregivers and collects only the features its user enables. Caregiver
phones receive retained updates through encrypted iroh connections. Read
[README.md](README.md) for the user workflow and current limitations.

## Repository map

- `app/src/main/`: Compose UI, role/settings state, SQLite storage, foreground
  collection, direct Bluetooth, pairing, iroh synchronization and optional Telegram.
- `core/src/main/`: wire models, validation, pairing/SMS policy, Bluetooth packet
  decoding and wearable summaries.
- `app/src/test/` and `core/src/test/`: Android JVM/Robolectric and core unit tests.
- `app/src/androidTest/`: emulator/device UI, service and native transport tests.
- `native/` and `scripts/`: pinned native transport build, tool setup and APK packaging.
- `docs/`: setup details, acceptance checklists, verification records and release notes.
- `dist/`: current pilot APK archive parts and checksum manifest. Full APKs and
  local build tools are ignored.

## Implementation rules

- Preserve explicit consent, caregiver approval and the sharing/Pause gate.
  Caregiver mode must not collect the caregiver's own data. Never add hidden
  collection or remote activation as a side effect of another change.
- Treat timestamps and missing data honestly. Cached readings are not live
  measurements, and simulated-peripheral/emulator tests do not establish physical
  wearable compatibility. Do not invent readings or medical conclusions.
- Preserve independent caregiver cursors, validation of untrusted input and the
  rule that received events are durably stored before acknowledgment.
- Retain permission-withdrawal and peer-revocation behavior. Keep secrets and
  personal data out of source, logs and test fixtures; use synthetic test data.
- Coordinate wire-format changes with protocol versioning and document required
  upgrades on paired phones. Preserve stored identity, pairing and history.
- Keep Kotlin/native iroh versions compatible. Follow
  [native/README.md](native/README.md) for dependency or native library changes,
  including all supported ABIs and 16 KB alignment checks.
- Keep user-facing documentation plain and distinguish implemented capabilities,
  verified results and outstanding device checks.

## Verification

Run commands from the repository root. On a configured local checkout, source
`scripts/env.sh`; first-time setup is documented in the README. Rebuild native
libraries with `sh scripts/build-native.sh` when missing or changed.

```sh
. ./scripts/env.sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 scripts/verify-apk.py

# When the change needs UI, service or native transport verification:
./gradlew :app:connectedDebugAndroidTest
```

Use checks proportionate to the change. For documentation-only edits, review the
rendered structure, local links, commands and `git diff --check`; an APK rebuild
or device test run is unnecessary. For behavior changes, run relevant automated
checks and use [docs/acceptance.md](docs/acceptance.md) and
[docs/wearables.md](docs/wearables.md) for device-specific follow-up. Record only
checks actually performed in [docs/verification.md](docs/verification.md).

## Commits and releases

Keep commits focused on the requested task and preserve unrelated working-tree
changes. When asked to push, verify the intended branch reached the remote.
Documentation-only work does not need a version bump, regenerated APK archives,
a tag or a release. Publish releases only when the task requests one; use the
existing release notes and packaging workflow, verify the APK checksum and signing
compatibility, and never commit signing keys or credentials.
