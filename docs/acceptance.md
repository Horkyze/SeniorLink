# Real-phone acceptance checklist

Use synthetic messages and locations until the family has reviewed sharing settings.
The emulator tests do not establish manufacturer-specific battery behavior.

## Installation and consent

- Install the same APK on all three phones; record Android versions and CPU ABIs.
- Choose sharing mode only on the grandfather's phone. Verify that caregiver mode
  requests no location, SMS or notification permissions and starts no service.
- Pair both caregivers using one QR scan and matching verification codes. An unapproved phone must not
  receive even an empty history/status response.
- Start monitoring with all optional features off; verify the visible notification
  and manual check-in. Pause from both the app and notification.
- Deny each requested permission and confirm the app does not silently enable it.
- Withdrawing a feature removes its retained local events and queued Telegram output.
- Remove one caregiver during a connection; subsequent access must stop. Copies
  already delivered to that phone cannot be recalled.

## In-app QR pairing

- On the sharing phone, tap **Phones → Connect a caregiver**. Scan once from the
  caregiver's **Phones → Scan QR**. Compare the four-character code and confirm
  only on the sharing phone. Verify both phones connect and receive a check-in.
- Repeat for a second caregiver while sharing is already active.
- Scan in portrait and landscape, under normal and dim lighting, and with larger
  Android font/display settings. Check that QR, code and action buttons remain usable.
- Verify a scan alone grants no access. Reject from each phone in turn, including
  while an approval is arriving. A fresh connection should not be saved.
- Disconnect briefly before confirmation and during the final save. Reconnection
  must retain the code and avoid duplicate phones; after expiry use a new QR.
- Cancel/back, scan an unrelated or old QR, and try a self-invitation. No existing
  paired phones should change. Wait five minutes and verify the old QR expires.
- Deny Camera, then deny permanently. Copy/share/paste must still work, followed by
  the same code comparison. No camera permission is needed on the sharing phone.
- Background/lock the scanner, rotate, and return. The camera must stop in the
  background; rotation must preserve the name, invitation and active request.
- After installation with the same signing key, verify existing identities, peers
  and history survive. Never uninstall a family phone to bypass a signing mismatch.

## Three-phone catch-up

- Keep caregiver B closed. Generate several manual check-ins and unlocks while A
  is open. A should receive them without B.
- Close A, open B, and verify B catches up independently without duplicate events.
- Reopen each caregiver after killing its process; already received history and
  synchronization progress must survive.
- Turn off the sharing phone's network. Caregivers must show cached information,
  old timestamps and an unreachable status, not a false fresh update.
- Reconnect and cross from Wi-Fi to mobile data, including different networks.
- Test screen-off and overnight Doze, then check actual battery consumption.
- Close and swipe away the app while sharing; verify the visible foreground
  service continues. Kill the process without Force stop and verify Android's
  sticky recreation resumes the previously enabled session and collected history.
- Reboot and update in place while a session is enabled; check recovery after
  unlock. Repeat while paused and verify no collection or sharing restarts.
- Force-stop the sharing phone in Android Settings. Verify it stays stopped until
  reopened. Separately use Android's Active apps Stop and run the recovery job;
  verify it does not undo an observed user stop, including after a later job-only
  process dies. Reopening allows the saved enabled session to resume.
- Disable location services while monitoring, then re-enable/restart and check
  provider behavior. Approximate location must be accepted and labeled with accuracy.

## Background recovery and battery — 0.1.9

- On fresh sharing and caregiver installations, approve a phone connection and
  confirm background work starts without a dashboard Start/Enable button. Reject
  an invitation and confirm it does not enable sharing. Optional collection
  settings must remain unchanged; caregivers must never collect their own data.
- Deny a required Android permission during automatic startup. Confirm the toggle
  is off and reopening or adding a peer does not repeatedly prompt or enable it.
  Turn the Settings toggle on, grant permission, and confirm startup continues
  without another tap. Rotate during a permission prompt and recheck the result.
- Turn off the Settings toggle, reconnect/add another phone, and reopen/reboot.
  Confirm the pause persists. Existing paired installations without a saved
  preference should adopt the default when opened; a stored Pause must survive.
- After automatic connection startup on a caregiver, close its screen and confirm retained
  updates arrive without collecting that phone's own battery, SMS, location or
  wearable data. Pause background updates; receiving while open should still work.
- Switch between foreground and background receiving repeatedly. Confirm only
  one transport receiver is active and each caregiver retains its own durable
  cursor. Revoke a peer while it is connecting and confirm access remains closed.
- Test Wi-Fi/mobile handover, airplane mode and Data Saver/UID blocking. Verify
  reconnection when permitted and no false fresh status while blocked or offline.
- Exercise deep and light Doze, their maintenance windows and final idle exit,
  with and without battery exemption. Verify overlapping network windows deliver
  saved events and timestamps remain honest. No continuous wake lock is expected.
- On Android 14/15 or newer, test sticky location-service recreation with only
  foreground location permission. Separately reboot/update with and without
  background location permission. Without it, other enabled features recover and
  location resumes on visible reopening; with it, location may resume at boot.
- Revoke each required permission while running and while the process is absent.
  Confirm the session pauses and does not silently reactivate when permission is
  restored. Declining optional background location must leave other features usable.
- Test the periodic recovery job with battery exemption on/off. If Android rejects
  startup, verify the actionable resume notification instead of repeated crashes.
- Compare similar 8–24-hour unplugged runs with sharing paused/enabled, then with
  location and wearable features individually enabled. Record device/OS, network,
  battery optimization, charging intervals, update delays and Android's per-app
  battery estimate. Whole-device battery percentages do not establish app drain.
- Inspect the background controls and notification at normal and large font sizes.
  Verify Pause cancels the recovery job and stale collection callbacks cannot
  publish into a newly started session. Do not clear personal data for these tests.

## Heart-rate graph and batteries

- Install 0.1.10 on all paired phones. Verify in-place updates preserve pairing,
  settings, history and independent caregiver catch-up.
- Enable Share phone battery only on the sharing phone, save and start. Compare
  its percentage and charging label with Android, then plug/unplug the charger
  and allow the next five-minute sample to arrive on both caregivers.
- Pause and confirm collection stops. Disable phone battery and verify local
  battery history and queued Telegram output are removed. Confirm caregiver mode
  does not collect its own battery.
- Compare pulse and smartwatch battery with the physical wearable. If its battery
  service is absent, confirm the card stays Unknown; record actual exposed services.
- Switch family phones and replace the selected watch. Values and chart points
  must stay separate. Remove a paired source and confirm its cards and graph clear.
- Check the last-hour dashboard trend and selected-day wearable chart, a single
  sample, disconnected gaps, contact loss, stale readings and empty history.
  Check normal and large fonts on family phones.
- In 0.1.10, open the wearable chart from the dashboard
  or Wearable screen. Pinch to zoom, move two fingers to browse time, and drag one
  finger horizontally to move the dotted marker. Confirm the value and full date/
  time (including seconds) match a saved reading; gaps must not invent values.
  Zoom reaches a one-minute window, and Reset chart restores the full range.
- Check vertical page/sheet scrolling from the chart, zoom/pan at both time
  boundaries, an empty zoomed window, and switching dates, phones and 1/24-hour
  ranges. Check TalkBack's chart actions for zoom, time movement and readings.

## SMS and Telegram

- Test a permitted sender, a non-permitted sender, and a multipart SMS.
- With bodies disabled, only sender/timestamp should be stored and shared.
- With bodies enabled, test a benign body and a likely verification code.
  Do not treat the heuristic as complete protection.
- Configure a test-only Telegram channel on the sharing phone, grant bot posting
  permission, and explicitly send a test. Confirm only the sharing phone posts.
- Disconnect and reconnect; inspect queued delivery and retries. Account for
  possible duplicates after uncertain HTTPS completion.
- Disable Telegram or change destination while paused; pending old-destination
  posts must not subsequently be sent.

## Supported-platform coverage

- Exercise at least one ARM64 phone and any ARMv7 phone actually used by the family.
- Test on a 16 KB-page device/emulator when available. ELF alignment is checked at
  build time, but alignment alone is not complete runtime validation.
- Decide on a stable release signing key before ongoing deployment. A different
  signer cannot update an existing install without uninstalling and losing data.

## Daily summary — 0.1.9

- Verify the Updates dashboard has a bounded set of daily groups and no appended
  event feed. Keep the sharing phone's I'm okay action and Settings pause controls.
- Select different family phones. Heart rate, batteries, daily counts, preview
  records and full history must all follow the selection; removing a peer must
  remove its data from every view.
- Check counts with more than 100 events in one day. Group by recorded local date,
  including late deliveries and daylight-saving days. Counts describe retained
  observations, not a complete activity log.
- Open each group, expand a record, and open full history. Change dates/types,
  load earlier records, and verify the visible position is preserved while loading
  more. Check the empty state and zero/unknown battery distinction.
- Open a saved location from history, including a fix outside the map's latest
  1,000 entries. Verify its source, sequence and coordinates. Expiry, feature
  withdrawal and revocation must not leave a separate cached copy in this route.
- Check normal and 2× text size: family selection, date controls, groups, preview
  sheets, Close, filters and Load earlier must stay reachable without clipped text.
- Verify the dashboard, preview sheet and history still protect screenshots and
  recent-app thumbnails. Use only synthetic fixtures for captured verification.
