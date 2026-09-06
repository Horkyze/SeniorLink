# Real-phone acceptance checklist

Use synthetic messages and locations until the family has reviewed sharing settings.
The emulator tests do not establish manufacturer-specific battery behavior.

## Installation and consent

- Install the same APK on all three phones; record Android versions and CPU ABIs.
- Choose sharing mode only on the grandfather's phone. Verify that caregiver mode
  requests no location, SMS or notification permissions and starts no service.
- Pair both caregivers using trusted public codes. An unapproved phone must not
  receive even an empty history/status response.
- Start monitoring with all optional features off; verify the visible notification
  and manual check-in. Pause from both the app and notification.
- Deny each requested permission and confirm the app does not silently enable it.
- Withdrawing a feature removes its retained local events and queued Telegram output.
- Remove one caregiver during a connection; subsequent access must stop. Copies
  already delivered to that phone cannot be recalled.

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
- Reboot and force-stop the sharing phone. Monitoring must remain stopped until
  explicitly started again; follow the restart instructions in the UI.
- Disable location services while monitoring, then re-enable/restart and check
  provider behavior. Approximate location must be accepted and labeled with accuracy.

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
