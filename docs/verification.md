# Verification

## Release packaging — 0.1.11 (17 September 2026)

- Version 0.1.11 (version code 12) passes all **41 core and 79 Android JVM
  tests**, without failures or skips. Debug and instrumentation APK builds pass;
  lint reports zero errors and 17 warnings.
- **Fourteen final-APK instrumentation tests pass**, without skips, on the
  Android 17 ARM64 emulator: live settings, backgrounded edits and deferred
  location, phone-battery collection and delivery, battery/health UI, wearable
  service lifecycle, native catch-up/access withdrawal and public discovery.
- The local 0.1.10 APK matches the published release asset digest
  `250ad5310efe52b85b9bfb743a0b389e860fa23a14146693171b6800408fbfa6`.
  Both APK signatures verify with certificate SHA-256
  `7e4f9088dfb6e1a7175ed42dc7a9d1f32717a21d28165cead7b6adfa04cbc302`.
  Installing 0.1.10 followed by 0.1.11 using `adb install -r` succeeds.
- All 12 native libraries are byte-identical to published 0.1.10. ABI and 16 KB
  ELF/ZIP alignment checks pass. Six archive parts reconstruct the tested APK
  byte for byte in an isolated temporary directory. SHA-256:
  `568c1c6df51ccb11110f12617fece9b58ed3fd2bd6a5a48ed3f6d0aedaa6b7ca`.

Commands performed:

```sh
. ./scripts/env.sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/verify-apk.py
adb install -r dist/SeniorLink-0.1.10-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class family.seniorlink.LiveSettingsDeviceTest,family.seniorlink.PhoneBatteryServiceTest,family.seniorlink.HealthOverviewUiTest,family.seniorlink.WearableServiceTest,family.seniorlink.IrohDeviceTest,family.seniorlink.PublicDiscoveryTest family.seniorlink.test/androidx.test.runner.AndroidJUnitRunner
python3 scripts/package-pilot.py
# Ran unpack-pilot.py in a temporary copy containing only SHA256SUMS and six parts.
git diff --check
```

Protocol 3 remains compatible with 0.1.7–0.1.10; both phones need 0.1.11 for
connection reuse. Physical-phone Wi-Fi performance, battery behavior and wearable
compatibility remain on the acceptance checklist.

## Battery visibility and live settings — source build (17 September 2026)

- New sharing setup selects phone battery, unlock activity and location with an
  explanation before pairing. Caregiver defaults collect nothing; SMS, wearable
  and Telegram setup remain separate. Legacy JSON defaults and explicitly saved
  choices are unchanged, so updating does not re-enable disabled features.
- Settings can be saved while sharing. Collectors are replaced without changing
  the sharing authorization or sync endpoint. Permission requests for proposed
  settings happen before committing; denial retains the running configuration.
  Pause cancels a pending edit and cannot be undone by a late permission callback.
  The service observes saved settings independently of the editing Activity;
  newly enabled background location is deferred when Android requires it.
- Unsaved switches survive tab navigation and rotation. Compact battery cards
  explain missing data and show charging state. A reconnect merges new wearable
  metrics with saved metrics by timestamp, and the first supported battery
  reading is saved promptly. Missing watch percentages are never inferred.
- All **41 core and 79 Android JVM tests** pass. Debug/instrumentation builds,
  lint and APK ABI/16 KB alignment checks pass. Fourteen selected instrumentation
  tests passed on the Android 17 ARM64 emulator; after the final service observer
  change, all five live-settings/battery/wearable service tests passed again.
  Checks include actual Android battery sampling, dashboard display, delivery
  through the production caregiver receiver, active feature changes, permission
  denial, Pause, saved settings applied after backgrounding and deferred location.
- Two additional fresh-install checks passed in a temporary emulator user:
  default selected features and approval-triggered startup, plus the real Android
  notification permission denial/grant flow. The temporary test user's app data
  was reset between those checks; the original emulator user's data was not used
  for these fresh-install scenarios. Lint reports zero errors and 17 warnings.
- Visually inspected the compact missing-battery cards with synthetic data at
  normal font size. These checks do not establish physical watch compatibility
  or manufacturer-specific service behavior. No release was published.

Commands performed include:

```sh
. ./scripts/env.sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=family.seniorlink.LiveSettingsDeviceTest,family.seniorlink.PhoneBatteryServiceTest,family.seniorlink.HealthOverviewUiTest,family.seniorlink.WearableServiceTest,family.seniorlink.IrohDeviceTest,family.seniorlink.PublicDiscoveryTest
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=family.seniorlink.LiveSettingsDeviceTest,family.seniorlink.PhoneBatteryServiceTest,family.seniorlink.WearableServiceTest
python3 scripts/verify-apk.py
git diff --check
# In a temporary emulator user with a fresh app installation for each check:
adb shell am instrument --user 10 -w -r -e class family.seniorlink.DefaultSharingPermissionTest family.seniorlink.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument --user 10 -w -r -e class family.seniorlink.MonitoringUiTest family.seniorlink.test/androidx.test.runner.AndroidJUnitRunner
```

## Faster retained-history sync — source build (17 September 2026)

- History catch-up reuses its encrypted connection across 20-record pages without
  an inter-page sleep. Each page retains its timeout, approval/Pause checks,
  validation and durable save before ACK. Polling intervals are unchanged.
- Wire version, payloads and ALPN remain protocol 3. Older caregivers can close
  after each page; new caregivers reconnect from their saved cursor when an older
  sharing phone closes. Both phones need the source update for connection reuse.
- All 40 core and 76 Android JVM tests pass. Seven new catch-up tests cover 10,000
  events, legacy-style connection closure, reconnect failures, page timeouts,
  histories lasting over 35 seconds, cancellation and failed persistence.
- Four native instrumentation tests pass on the Android 17 ARM64 emulator:
  multi-page connection reuse, Pause/revocation during catch-up, independent
  caregivers, older client behavior, Keystore persistence and public discovery.
  The synthetic 1,000-event loopback comparison measured **6,261 ms / 50
  connections** for the old client pattern and **1,754 ms / one connection** for
  reuse. These are emulator measurements, not physical-phone Wi-Fi results.
- Debug/instrumentation builds, lint and APK ABI/16 KB alignment checks pass.
  Native libraries and stored data formats are unchanged. No release was published.

Commands performed:

```sh
. ./scripts/env.sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=family.seniorlink.IrohDeviceTest,family.seniorlink.PublicDiscoveryTest
python3 scripts/verify-apk.py
git diff --check
```

## Release packaging — 0.1.10 (17 September 2026)

- Version 0.1.10 (version code 11) passes all **40 core and 69 Android JVM tests**,
  with no failures or skips. Debug and instrumentation APK builds pass. Lint
  reports zero errors and 17 warnings.
- **Eight final-APK instrumentation tests pass**, with no skips, on the Android
  17 / API 37 ARM64 emulator with 16 KB pages: three chart gesture tests, four
  health-overview tests and the daily-summary workflow with sheet scrubbing.
- Downloaded published 0.1.9 and verified its SHA-256 against its checksum manifest
  and GitHub asset digest. Both APK signatures verify with certificate SHA-256
  `7e4f9088dfb6e1a7175ed42dc7a9d1f32717a21d28165cead7b6adfa04cbc302`.
  Installing published 0.1.9 and then 0.1.10 using `adb install -r` succeeds on
  the test emulator. This does not establish physical family-device behavior.
- All 12 native libraries are byte-identical to published 0.1.9. ABI and 16 KB
  ELF/ZIP alignment checks pass. Six archive parts reconstruct the tested APK
  byte for byte in a temporary directory. SHA-256:
  `250ad5310efe52b85b9bfb743a0b389e860fa23a14146693171b6800408fbfa6`.

Commands included:

```sh
. ./scripts/env.sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/verify-apk.py
# Installed the downloaded 0.1.9 APK first, then updated in place:
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class family.seniorlink.HeartGraphUiTest,family.seniorlink.HealthOverviewUiTest,family.seniorlink.DailySummaryUiTest -e seniorlink.dashboardFixture true family.seniorlink.test/androidx.test.runner.AndroidJUnitRunner
python3 scripts/package-pilot.py
# Ran unpack-pilot.py in a temporary copy containing only the manifest and parts.
git diff --check
```

Protocol 3 remains compatible with 0.1.7–0.1.9. Physical touch and TalkBack checks
remain on the acceptance checklist. Earlier source-build checks below cover
2× text size and visual review of synthetic chart screenshots.

## Interactive heart-rate charts — source build (17 September 2026)

- Expanded Wearable and daily-history charts support pinch zoom down to a
  one-minute window, two-finger time panning, and one-finger tap/drag inspection.
  The dotted marker selects an actual saved reading and displays its full local
  date and time including seconds. Reset chart restores the original range.
  Compact dashboard previews still open the daily-history sheet.
- All **40 core and 69 Android JVM tests** pass, including four new tests for
  zoom anchoring, pan/zoom limits, missing readings and a stable time window during
  refresh. Debug and instrumentation APK builds pass. Lint reports zero errors
  and 17 warnings. APK ABI and native ELF/ZIP alignment checks pass.
- **Eight instrumentation tests pass with no skips** on the Android 17 / API 37
  ARM64 emulator with 16 KB pages: three new chart gesture tests, four existing
  health-overview tests and the daily-summary workflow. The daily-summary test
  then passed again with an added assertion for scrubbing inside its preview
  sheet. The three gesture tests also pass at 2× system text size.
- Gesture checks cover actual timestamp/value selection, pinch, two-finger pan,
  reset, selection removal when its reading disappears, and vertical scrolling
  through the chart. Synthetic-data screenshots of the selected marker, zoomed
  chart, daily-history sheet and large-text chart were visually reviewed.
- Physical finger gestures on family phones and TalkBack behavior still need
  the acceptance checks. No physical wearable compatibility was established.
  These checks preceded the 0.1.10 release packaging above.

Commands included:

```sh
. ./scripts/env.sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/verify-apk.py
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=family.seniorlink.HeartGraphUiTest,family.seniorlink.HealthOverviewUiTest,family.seniorlink.DailySummaryUiTest -Pandroid.testInstrumentationRunnerArguments.seniorlink.dashboardFixture=true
# After adding the sheet-scrubbing assertion, rebuilt and installed the test APK:
./gradlew :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class family.seniorlink.DailySummaryUiTest -e seniorlink.dashboardFixture true family.seniorlink.test/androidx.test.runner.AndroidJUnitRunner
# Gesture tests also ran at font_scale 2.0; the original setting was restored.
adb shell am instrument -w -r -e class family.seniorlink.HeartGraphUiTest family.seniorlink.test/androidx.test.runner.AndroidJUnitRunner
git diff --check
```

## Release packaging — 0.1.9 (13 September 2026)

- Version 0.1.9 (version code 10) passes all **40 core and 65 Android JVM tests**,
  with no failures or skips. Debug and instrumentation APK builds pass. Lint
  reports zero errors and 17 warnings.
- **Six final-APK instrumentation tests pass**, with no skips, on an isolated
  Android 17 ARM64 emulator with 16 KB pages: the daily-summary workflow, two
  background recovery tests, caregiver background receiving over real iroh,
  automatic sharing/check-in with Settings pause, and notification permission
  denial followed by grant and automatic startup. Each class used a fresh
  synthetic installation. Earlier source-build checks below cover the dashboard
  at 2× text size and adb-driven process, reboot, user-stop and Doze scenarios.
- Downloaded the published 0.1.8 APK and verified its SHA-256 against its checksum
  manifest and GitHub's asset digest. Both APK signatures verify with certificate
  SHA-256 `7e4f9088dfb6e1a7175ed42dc7a9d1f32717a21d28165cead7b6adfa04cbc302`.
  Installing 0.1.9 over 0.1.8 with `adb install -r` succeeds. This release-stage
  install check used a fresh fixture, not a physical family's existing history.
- All 12 bundled native libraries are byte-identical to published 0.1.8. Native
  ABI and 16 KB ELF/ZIP alignment checks pass. Six archive parts reconstruct the
  APK in an isolated temporary directory; the restored bytes match the tested
  APK. SHA-256:
  `8a54d3858ebcdc1235b99f9e8d95b1545ad9e6b8d17d71b739ab532cc49440ae`.

Commands included:

```sh
. ./scripts/env.sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/verify-apk.py
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Each class ran separately on a fresh synthetic installation.
adb -s emulator-5556 shell am instrument -w -r -e class family.seniorlink.DailySummaryUiTest -e seniorlink.dashboardFixture true family.seniorlink.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5556 shell am instrument -w -r -e class family.seniorlink.BackgroundRecoveryServiceTest family.seniorlink.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5556 shell am instrument -w -r -e class family.seniorlink.CaregiverBackgroundServiceTest -e seniorlink.caregiverFixture true family.seniorlink.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5556 shell am instrument -w -r -e class family.seniorlink.MonitoringUiTest family.seniorlink.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5556 shell am instrument -w -r -e class family.seniorlink.DefaultSharingPermissionTest family.seniorlink.test/androidx.test.runner.AndroidJUnitRunner
python3 scripts/package-pilot.py
# Also ran unpack-pilot.py with only the manifest and six parts in a temporary copy.
git diff --check
```

Protocol 3 remains compatible with 0.1.7 and 0.1.8. Physical-device battery drain,
manufacturer-specific recovery, natural overnight reliability and wearable
compatibility remain on the [device checklist](acceptance.md).

## Daily summary dashboard — source build (13 September 2026)

- Implemented selected concept B: compact latest readings and device batteries,
  daily counts grouped by update type, a four-record preview sheet, and full
  history with date/type filters and explicit 40-record loading. The dashboard
  no longer appends the mixed event feed or loads its latest 100 records.
- Counts query all retained records for the selected source and recorded local
  day. Six new storage tests cover busy feeds, source/day isolation, stable
  pagination with equal timestamps, expiry/withdrawal, 23/25-hour daylight-saving
  days, revocation/reapproval, and lookup of locations outside the latest 1,000.
- All **40 core and 65 Android JVM tests pass**. Debug and instrumentation APK
  builds pass. Lint has zero errors and 17 warnings. Native ABI/16 KB alignment
  checks and `git diff --check` pass.
- **Seven distinct Android instrumentation tests pass**, with eight executions:
  the new daily-summary workflow at both 1× and 2× text size, four existing
  health/navigation checks, automatic sharing with Settings pause, and permission
  denial followed by automatic startup after grant. Each class ran on a fresh
  isolated Android 17 emulator installation; no tests were skipped in these runs.
- The daily-summary workflow checks more than 100 same-day events, group counts,
  the wearable preview/chart, filtering, loading 40 then 80 unique records without
  losing the visible position, opening a saved fix on the map, day changes,
  family selection across recreation, and removal of the selected peer.
- Normal and large-text views were visually reviewed. Battery cards stack and
  navigation wraps at large text; controls and history remain scrollable. The
  fixture asserts secure window flags and renders its own synthetic Android view
  trees for previews, rather than disabling production screenshot protection.
  Preview renders are linked from the [selected design](design/dashboard-concepts/README.md).
- Debug APK SHA-256:
  `2e5550f7bc3ed7d80b6d0d1ed181b26a8f3df29fd6bec7a870c0f60d3fc1db63`.

Commands included:

```sh
. ./scripts/env.sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
# Each class used a fresh synthetic installation on the isolated test emulator.
adb -s emulator-5556 shell am instrument -w -r -e class family.seniorlink.DailySummaryUiTest -e seniorlink.dashboardFixture true family.seniorlink.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5556 shell settings put system font_scale 2.0
# Repeat DailySummaryUiTest, then restore font_scale to 1.0.
adb -s emulator-5556 shell am instrument -w -r -e class family.seniorlink.HealthOverviewUiTest family.seniorlink.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5556 shell am instrument -w -r -e class family.seniorlink.MonitoringUiTest family.seniorlink.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5556 shell am instrument -w -r -e class family.seniorlink.DefaultSharingPermissionTest family.seniorlink.test/androidx.test.runner.AndroidJUnitRunner
python3 scripts/verify-apk.py
git diff --check
```

No physical family device or wearable was used. This change adds no collection,
changes no background sampling interval, and preserves protocol 3, stored identity,
pairing and history. No release was published.

## Automatic background defaults and Settings controls — source build (13 September 2026)

- An approved phone connection now enables sharing/background receiving by default.
  Existing paired installations with no saved background choice adopt the default
  when opened. A stored Pause or permission-denial pause survives further
  connections and reopening. Optional collection settings remain unchanged.
- Dashboard Start/Resume/Pause/Enable controls are replaced by small Settings
  switches: **Sharing** on the sharing phone and **Receive in background** on
  caregivers. Pairing screens explain the default before approval. Granting the
  required Android permissions continues startup without a second tap.
- All **40 core and 59 Android JVM tests pass**. New policy tests cover both roles,
  no activation before approval, existing paired installations, and durable pauses
  made before or after pairing. Lint has zero errors and 17 warnings.
- **Six Android instrumentation tests pass** on an isolated Android 17 emulator:
  automatic sharing/check-in and Settings pause across reopening/additional peers;
  real notification-permission denial followed by grant and automatic startup;
  real iroh pairing rejection/approval; two service recovery tests; and automatic
  caregiver background delivery. Each class used a fresh synthetic installation
  so the default checks ran rather than skipping an already configured session.
- The caregiver test received a synthetic check-in over real iroh transport while
  its activity was stopped, collected no local caregiver events, and retained its
  Settings pause after recreation. Recovery tests verify deferred location and
  visible resumption, saved-session restoration, and that Pause prevents recovery.
- Debug and instrumentation APK builds, native ABI/16 KB alignment checks, and
  `git diff --check` pass. Debug APK SHA-256:
  `80cb1a1a504b93bee314466ed351161ce08392dbe7e3421b350f60c453058632`.

Commands included:

```sh
. ./scripts/env.sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
# Each class ran separately on the isolated emulator with a fresh test installation.
adb -s emulator-5556 shell am instrument -w -r -e class family.seniorlink.DefaultSharingPermissionTest family.seniorlink.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5556 shell am instrument -w -r -e class family.seniorlink.MonitoringUiTest family.seniorlink.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5556 shell am instrument -w -r -e class family.seniorlink.ConnectionFlowUiTest family.seniorlink.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5556 shell am instrument -w -r -e class family.seniorlink.BackgroundRecoveryServiceTest family.seniorlink.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5556 shell am instrument -w -r -e class family.seniorlink.CaregiverBackgroundServiceTest -e seniorlink.caregiverFixture true family.seniorlink.test/androidx.test.runner.AndroidJUnitRunner
python3 scripts/verify-apk.py
git diff --check
```

These checks do not measure physical-device battery drain. Polling/collection
intervals and Android restart limits are unchanged from the background-recovery
source build below. Protocol 3, identity, pairing and stored history are preserved.
No release was published.

## Background recovery — source build (13 September 2026)

- Start sharing and Enable background updates persist local authorization for the
  selected role. The visible foreground service requests sticky recreation,
  recovers from boot/app replacement when permitted, and schedules a best-effort
  recovery job. Pause closes the collection gate and cancels recovery. Observed
  Android user stops suppress automatic recovery until visible reopening.
- All **40 core and 57 Android JVM tests pass**. New checks cover durable enable/
  Pause state, user-stop suppression across process recreation, location restart
  permission rules, a single caregiver receiver across screen/service handoffs,
  slower background polling, and network validation/blocking/handover/idle changes.
- **Eight distinct Android instrumentation tests pass** on an isolated Android 17
  emulator: two background-service tests, monitoring UI, phone-battery service,
  unavailable-wearable service, two native transport/storage tests, and caregiver
  background delivery. The caregiver test receives a synthetic check-in over real
  iroh discovery/transport with its activity stopped and verifies that it collects
  no local caregiver events. Its fixture must be explicitly selected on an
  isolated installation; it does not change a sharing installation's role.
- Runtime location checks verify that a fresh recovery without background location
  permission defers location, and visible reopening starts it without replacing
  the collection session. They do not establish physical location accuracy.
- Manual adb checks killed the app process without Force stop and observed a new
  foreground-service process with the screen closed, for both roles. Reboot/
  unlock recovery and Force stop followed by visible reopening were checked.
  Android Active apps Stop suppressed the recovery job; reinstalling the APK in
  place did not undo the persisted suppression.
- Forced **deep and light Doze** deferred the sharing transport while leaving the
  service active. Stepping into maintenance windows and exiting idle restored
  endpoint readiness. These checks used adb and state-only service diagnostics;
  they do not establish natural overnight timing or end-to-end delivery between
  two independently sleeping physical phones. An initial reboot harness race was
  corrected to wait for a changed kernel boot ID before testing post-boot state.
- Core/JVM tests, lint, debug and instrumentation APK builds pass. Lint has zero
  errors and 17 warnings, including the battery-exemption API's policy reminder.
  The exemption remains a user-operated Android dialog for this family check-in
  use case. All native ABIs and 16 KB ELF/ZIP alignment checks pass. Debug APK
  SHA-256: `e6a86ed676da13d0a29eec8f61c3786a4d6583af5affbc18363dd039dd53cf85`.

Commands included:

```sh
. ./scripts/env.sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=family.seniorlink.BackgroundRecoveryServiceTest,family.seniorlink.MonitoringUiTest,family.seniorlink.PhoneBatteryServiceTest,family.seniorlink.WearableServiceTest,family.seniorlink.IrohDeviceTest
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=family.seniorlink.BackgroundRecoveryServiceTest
# The next command used a separate, isolated caregiver fixture on the test emulator.
adb -s emulator-5556 shell am instrument -w -r -e class family.seniorlink.CaregiverBackgroundServiceTest -e seniorlink.caregiverFixture true family.seniorlink.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5556 shell dumpsys activity service family.seniorlink/.monitor.MonitorService
python3 scripts/verify-apk.py
git diff --check
```

No physical family phone or wearable was used. Manufacturer task-killing behavior,
Android 14/15-specific location recovery, natural overnight reliability and actual
battery drain remain device checks in [acceptance.md](acceptance.md). This change
does not alter protocol 3 or stored identity/history and does not publish a release.

## Release packaging — 0.1.8 (13 September 2026)

- Version 0.1.8 (version code 9) passes the core/Android JVM checks: 40 core and
  48 Android tests, with no failures. Debug and instrumentation APK builds pass;
  lint reports zero errors and 14 warnings.
- The final APK passes all six update-dialog/Settings checks on the Android 17
  emulator and all three dialog checks at 2× text size. The large-text prompt was
  visually checked and font scale restored to 1.0. Browser intents were intercepted
  using synthetic release data; physical-browser download behavior remains a
  device check.
- Downloaded the published 0.1.7 APK and verified its SHA-256 against both GitHub's
  release digest and its checksum manifest. Both APK signatures verify and have
  certificate SHA-256
  `7e4f9088dfb6e1a7175ed42dc7a9d1f32717a21d28165cead7b6adfa04cbc302`.
  All 12 bundled native libraries are byte-identical to that published APK.
- Native ABI and 16 KB ELF/ZIP alignment checks pass. Packaged six archive parts
  and reconstructed the APK with `scripts/unpack-pilot.py` in an isolated temporary
  directory. Its bytes match both the tested APK and the release asset. SHA-256:
  `7c6294c7e977261d3b491f8e75bfda452c0db030c1def6ab94a195e9b431c7c5`.

The update-link change does not alter sync protocol 3, consent, identity, pairing,
settings or history. Version 0.1.8 remains compatible with 0.1.7.

```sh
. ./scripts/env.sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/verify-apk.py
adb -e install -r app/build/outputs/apk/debug/app-debug.apk
adb -e install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -e shell am instrument -w -r -e class family.seniorlink.UpdateUiTest,family.seniorlink.UpdateSettingsUiTest family.seniorlink.test/androidx.test.runner.AndroidJUnitRunner
adb -e shell settings put system font_scale 2.0
trap 'adb -e shell settings put system font_scale 1.0 >/dev/null' EXIT
adb -e shell am instrument -w -r -e class family.seniorlink.UpdateUiTest family.seniorlink.test/androidx.test.runner.AndroidJUnitRunner
python3 scripts/package-pilot.py
```

## Update prompt release-page link — source build (13 September 2026)

- The update prompt opens the GitHub page for the newest detected release,
  including published pilot prereleases. The button says **View release** and
  explains how to download the APK from Assets. The missing-browser fallback
  provides the same release-page link.
- All 48 Android JVM tests pass. All six update-dialog and Settings tests pass
  on the Android 17 emulator, including the exact browser URL after acceptance,
  dismissal and missing-browser fallback. Browser intents use synthetic release
  data and are intercepted; these checks do not download an APK.
- All three dialog tests also pass at 2× system font scale. The captured prompt
  was visually checked: the instructions and both buttons fit. Font scale was
  restored to 1.0 afterwards.
- Debug and instrumentation APK builds pass. Lint reports zero errors and 14
  warnings. Native ABI and 16 KB ELF/ZIP alignment checks pass.

```sh
. ./scripts/env.sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=family.seniorlink.UpdateUiTest,family.seniorlink.UpdateSettingsUiTest
python3 scripts/verify-apk.py
adb -e install -r app/build/outputs/apk/debug/app-debug.apk
adb -e install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -e shell settings put system font_scale 2.0
trap 'adb -e shell settings put system font_scale 1.0 >/dev/null' EXIT
adb -e shell am instrument -w -r -e class family.seniorlink.UpdateUiTest family.seniorlink.test/androidx.test.runner.AndroidJUnitRunner
git diff --check
```

## Release packaging — 0.1.7 (12 September 2026)

- Rechecked the core/Android JVM tests, lint and debug build using the commands
  below; all pass. The final APK is the same artifact used for the final Calm
  screen checks. Its SHA-256 is
  `9bec31235857e61428c48f9d12a8ae558f9bab3277b4cc5332d249cd0b1a93af`.
- Downloaded the published 0.1.6 APK, verified its release digest and signature,
  and compared certificates with 0.1.7. Both signatures verify and use certificate
  SHA-256 `7e4f9088dfb6e1a7175ed42dc7a9d1f32717a21d28165cead7b6adfa04cbc302`.
  All bundled native libraries are byte-identical to the verified 0.1.6 APK.
- Packaged six archive parts with `scripts/package-pilot.py`, reconstructed them
  with `scripts/unpack-pilot.py` in an isolated temporary directory, and verified
  byte equality with the tested APK and `dist/SHA256SUMS`. Signing material and
  full APKs remain ignored.

```sh
. ./scripts/env.sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 scripts/verify-apk.py
python3 scripts/package-pilot.py
```

## Selected Calm design — 0.1.7 source build (12 September 2026)

- Implemented proposal 1 with the green app header, pink heart-rate chart, separate
  phone/watch battery cards and five bottom navigation destinations. Low battery,
  missing readings and recording times remain explicit. The family selection is
  shared between Updates and Wearable.
- All 40 core and 48 Android JVM tests pass. Debug and instrumentation APKs build;
  lint reports zero errors and 14 warnings. Native ABI and 16 KB ELF/ZIP alignment
  checks pass for all three supported ABIs.
- All 13 selected Android 17 / API 37.1 ARM64 emulator tests pass: four dashboard
  checks, five wearable UI checks, three update/Settings checks and the existing
  Start/check-in/background/Pause workflow. Navigation coverage reaches every tab,
  switches family members, retains selection across tabs and removes withdrawn
  peers' displayed readings.
- After the final shared family-selection change, all nine dashboard/wearable
  tests pass again. All four dashboard tests also pass at 2× system font scale,
  including every navigation destination and Settings access. Large-text captures
  were visually inspected: battery cards stack, navigation wraps into two rows,
  and content remains scrollable. The emulator's font scale was restored to 1.0.
- Normal-size captures use synthetic visible state and were visually inspected.
  The heart-rate chart and both battery cards fit above the navigation bar on the
  test emulator. Tests leave stored family data intact, and production screenshot
  protection remains enabled.

The design changes are included in the 0.1.7 pilot release.

Additional Calm UI commands performed after installing both APKs:

```sh
adb shell am instrument -w -r -e class family.seniorlink.HealthOverviewUiTest,family.seniorlink.WearableUiTest,family.seniorlink.UpdateSettingsUiTest,family.seniorlink.MonitoringUiTest family.seniorlink.test/androidx.test.runner.AndroidJUnitRunner
# Final family-selection check:
adb shell am instrument -w -r -e class family.seniorlink.HealthOverviewUiTest,family.seniorlink.WearableUiTest family.seniorlink.test/androidx.test.runner.AndroidJUnitRunner
# Large-text check:
adb shell settings put system font_scale 2.0
trap 'adb shell settings put system font_scale 1.0 >/dev/null' EXIT
adb shell am instrument -w -r -e class family.seniorlink.HealthOverviewUiTest family.seniorlink.test/androidx.test.runner.AndroidJUnitRunner
```

## Heart-rate graph and device batteries — 0.1.7 source build (12 September 2026)

- All 40 core and 48 Android JVM tests pass. Coverage includes phone battery
  validation and opt-in defaults, legacy settings/event decoding, durable receipt,
  independent source lookup despite a busy feed, expiry and consent/peer withdrawal.
  The sampler tests cover missing/invalid Android values, percentage conversion,
  cadence, Pause, withdrawal during a read and cancellation before publication.
- Graph tests cover receipt-time ordering, disconnected gaps, contact-loss summary
  intervals, replacement watches, separate family sources and caregiver isolation
  from local live values. Charts show saved pulse values, not an ECG.
- All 12 selected tests pass on the Android 17 / API 37.1 ARM64 emulator: three new
  dashboard checks, five wearable UI checks, the opt-in phone battery service,
  Start/check-in/background/Pause, and two native transport/Keystore checks. The
  native test delivers a synthetic phone battery event and wearable summary to two
  independent caregivers and rejects unapproved/revoked peers.
- All eight dashboard/wearable UI tests also pass at 2× system font scale.
  Synthetic captures at normal and large text sizes were visually inspected;
  battery cards stack at large sizes. Font scale was restored to 1.0 afterwards.
  Production screenshot protection remains enabled.
- Debug and instrumentation APKs build, lint has zero errors (14 warnings), and
  ABI plus 16 KB ELF/ZIP alignment checks pass for all three supported ABIs. The
  final APK was installed in place on the test emulator.

This is version code 8. Install it on **all paired
phones**: protocol 3 cannot synchronize with the published 0.1.6/protocol-2 pilot.
Existing settings, identity, pairing and event history remain readable. Phone
battery sharing stays off until explicitly enabled. Physical smartwatch battery
support, including Galaxy Fit3, and real-phone background/battery behavior remain
unverified; follow [the device checklist](acceptance.md#heart-rate-graph-and-batteries).

Commands performed:

```sh
. ./scripts/env.sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=family.seniorlink.HealthOverviewUiTest,family.seniorlink.WearableUiTest,family.seniorlink.PhoneBatteryServiceTest,family.seniorlink.MonitoringUiTest,family.seniorlink.IrohDeviceTest
# After the final build, install both APKs and repeat the selected tests:
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class family.seniorlink.HealthOverviewUiTest,family.seniorlink.WearableUiTest,family.seniorlink.PhoneBatteryServiceTest,family.seniorlink.MonitoringUiTest,family.seniorlink.IrohDeviceTest family.seniorlink.test/androidx.test.runner.AndroidJUnitRunner
# Large-text UI check; restore the emulator's original setting on exit:
adb shell settings put system font_scale 2.0
trap 'adb shell settings put system font_scale 1.0 >/dev/null' EXIT
adb shell am instrument -w -r -e class family.seniorlink.HealthOverviewUiTest,family.seniorlink.WearableUiTest family.seniorlink.test/androidx.test.runner.AndroidJUnitRunner
python3 scripts/verify-apk.py
git diff --check
```

## Direct Bluetooth wearable collection — 0.1.6

- 39 core and 37 Android unit tests pass. New coverage includes Fit3-shaped HR
  packets, every HR/PLX/BP optional-field combination and truncation, signed
  IEEE-11073 decimal floats and special values, contact loss, invalid quality
  indicators, normalized units, bounded summaries and protocol-2 validation.
- Simulated-peripheral collector tests cover known service/characteristic pairs,
  optional read failures, malformed notifications, reconnect with a fresh link,
  permission revocation, and discarding buffered data on Pause. Permission tests
  exercise Android 11 and Android 15 rules. SQLite tests cover durable wearable
  catch-up, busy-feed isolation, consent withdrawal, Telegram queue removal and
  replacement-device IDs without exposing Bluetooth addresses.
- Nine selected Android 17 / API 37.1 ARM64 emulator tests pass: five wearable UI
  checks, the connected-device service's unavailable-band/background/Pause flow,
  two native iroh/Keystore checks and the existing Start/check-in/background/Pause
  workflow. Real native encrypted connections deliver a synthetic wearable event
  independently to two caregivers and reject unapproved/revoked peers.
- All five wearable UI tests also pass at 2× font scale. Normal and large-text
  captures were visually inspected. They use synthetic data; production screenshot
  protection remains enabled. The emulator's font scale was restored afterwards.
- Debug and instrumentation APK builds and lint pass (zero errors). The APK's
  debug signing certificate matches the existing 0.1.5 APK. Native ABI and 16 KB
  ELF/ZIP alignment verification passes for ARM64, ARMv7 and x86-64.
- Reconstructed the 0.1.6 APK from six archive parts in an isolated directory.
  Its bytes and SHA-256 match the tested APK; `dist/SHA256SUMS` records the hash.

Update **all paired phones** to the protocol-2 build. Existing settings, identity,
pairing and history remain readable; no database reset or migration is needed.
Physical Fit3 service discovery, actual sensor readings, background reliability and battery impact have
**not** been verified: no physical band/phone was connected to this workspace.
Follow the [real-device checklist](wearables.md#fit3-expectations-and-verification).

Commands:

```sh
. ./scripts/env.sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
# Install both built APKs on the emulator, then:
adb shell am instrument -w -r -e class family.seniorlink.WearableUiTest,family.seniorlink.WearableServiceTest,family.seniorlink.IrohDeviceTest,family.seniorlink.MonitoringUiTest family.seniorlink.test/androidx.test.runner.AndroidJUnitRunner
python3 scripts/verify-apk.py
```

## Version and manual update checks in Settings — 0.1.5

- Core: 23 passing tests. Android unit tests: 28 passing tests. Update checks
  distinguish newer releases, successful checks without an update, and network,
  HTTP or invalid-response failures. Tests cover retrying, checking again after
  Later, duplicate taps during startup/manual checks, retained state and cancellation.
- Android 17 / API 37.1 ARM64 emulator: six Settings and update-dialog tests pass
  at normal and 2× font scale. The actual Settings screen shows version 0.1.5 and
  the check button in both roles. Tests verify checking/disabled state, failure
  feedback, retry, the existing download prompt, explicit approval and browser
  fallback. Result scenarios use synthetic responses and intercepted download
  intents; no future APK is downloaded or installed by tests.
- Settings captures at normal and large font sizes were visually inspected.
  Screenshots use synthetic visible state and leave stored settings/history intact;
  production screenshot protection remains enabled.
- Debug and instrumentation APK builds and lint pass. The version 0.1.5 APK
  (version code 6) matches the published 0.1.4 signing certificate. Native ABI,
  16 KB ELF/ZIP alignment and checksum-verified reconstruction from six archive
  parts pass. The update is installed on the Android 17 emulator.

No new collection permissions or storage migration are required. Physical-phone
verification remains outstanding.

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
