# Direct Bluetooth wearables

SeniorLink 0.1.6 can connect directly to a selected Bluetooth LE peripheral on the
sharing phone. Samsung Health and Health Connect are not part of this data path:

```text
Wearable → Bluetooth LE → sharing phone's local summaries → encrypted iroh → caregiver phones
```

## Install and start

1. Install the 0.1.6 APK on the sharing phone **and every caregiver phone**. Sync
   protocol 2 prevents older versions from attempting to decode the new health
   payload. Updating in place preserves identity, pairing, settings and history.
2. On the sharing phone, pause sharing and open **Settings → Bluetooth wearable**.
3. Tap **Choose wearable**. Allow Nearby devices on Android 12 or newer. Android
   8–11 require fine Location permission and the system Location setting to scan;
   this does not enable SeniorLink location sharing. Previously paired BLE/dual-mode
   devices appear alongside nearby advertisements. Scanning stops after 15 seconds,
   when the chooser closes, or when the app is backgrounded.
4. Select the band by name and local Bluetooth address. Selection does not start
   a connection or change its bond. Enable **Share wearable readings**, then
   **Save settings**.
5. On a Galaxy Fit3, enable continuous heart-rate measurement on the band. Open
   **Updates → Start sharing** in SeniorLink. The existing visible service owns
   Bluetooth collection and reconnects while sharing remains enabled.
6. Open **Wearable** to see connection status, values and receipt timestamps. On a
   caregiver phone, open the same tab after synchronization. Each family member's
   data and each replacement band's data stay separate.

**Pause sharing** immediately closes the collection permission gate and stops the
connection. No scan or collection runs in caregiver mode. Disabling wearable
sharing or selecting another band and saving removes retained wearable events
and their queued Telegram messages on the sharing phone; previously delivered
caregiver history cannot be recalled. If optional Telegram forwarding is enabled,
wearable summaries use that existing output as well.

## Supported data

Support is discovered from **both** the Bluetooth service and characteristic IDs.
These are client capabilities, not a promise that a particular band exposes them.

| Service / characteristic | Data collected |
| --- | --- |
| Heart Rate `180D / 2A37` | 8- or 16-bit bpm; skin contact when supported; optional accumulated energy (kJ); optional RR intervals (ms) |
| Heart Rate `180D / 2A38` | Reported sensor location |
| Battery `180F / 2A19` | Valid battery percentage, read and subscribed where available |
| Device Information `180A / 2A24, 2A26–2A29` | Model, firmware, hardware, software and manufacturer strings |
| Health Thermometer `1809 / 2A1C, 2A1E` | Final/intermediate temperature, normalized to °C; measurement site and unverified device time when supplied |
| Pulse Oximeter `1822 / 2A5E, 2A5F, 2A60` | Spot/continuous SpO₂ and pulse; optional fast/slow values and pulse amplitude index; quality notes and feature bytes |
| Blood Pressure `1810 / 2A35, 2A49` | Systolic, diastolic, mean arterial pressure, optional pulse; normalized mmHg; quality notes and feature bytes |

No clinical conclusions or automatic health alerts are produced. Contact-loss
packets do not supply a current heart rate. Invalid/non-numeric measurements are
not shown as zero or normal values. Oximeter test/demo/calibration, unavailable,
questionable and known bad-sensor states withhold measurements. Blood-pressure
motion/cuff/position failures and explicitly numbered device users are withheld;
multi-user attribution is not implemented. Missing values are not filled in.

The integration reads recognized characteristics and writes only their standard
notification/indication configuration descriptors. It does not send calibration,
energy-reset, history-retrieval, firmware or proprietary control commands. It does
not attempt to replace initial Samsung band setup, firmware updates, notifications,
fall detection or the other companion-app functions.

## Fit3 expectations and verification

The community [Fit3Free Bluetooth reference](https://github.com/Astroisback/Fit3Free)
reports the standard heart-rate service on a Fit3, with skin-contact flags and
device-information characteristics. Its reported packets omit RR intervals and
energy, and it reports no standard battery service. Some device-information
strings are Bluetooth-stack defaults rather than useful Samsung model/firmware
identifiers. Those observations informed the investigation; **this implementation
has not yet been tested against the grandfather's physical Fit3**.

Expect heart rate/contact to be the first useful Fit3 data. Sleep, steps, stress,
ECG, stored history and any other data only available through proprietary services
are not collected. Other standard-compatible devices can expose more of the
supported measurements without changes to the app.

Under **Wearable → Available Bluetooth data**, the app distinguishes a detected
field that is being listened to/read from a field whose access failed. **Show
Bluetooth services** lists the discovered service/characteristic IDs and read,
notify or indicate properties, including unknown services, for further development.
It does not upload raw unknown characteristics or the device's Bluetooth address.
The wire device ID is a random ID generated when the selected device changes.

For the first real-device check:

- Compare the first received heart-rate reading with the band and verify contact
  loss when it is removed. Record exactly which capabilities are present.
- Keep the phone screen off for at least 15 minutes; check continued collection,
  reconnect after leaving Bluetooth range, and Bluetooth off/on recovery.
- Check whether Galaxy Wearable/Fit3 Plugin holds the connection. Close its active
  connection if necessary; don't reset or unpair the band merely to test SeniorLink.
- Confirm two caregiver phones catch up independently, including after one has
  been offline, and show original phone receipt times.
- Pause sharing and verify Bluetooth collection stops; revoke Nearby devices and
  verify collection stops without making up readings. Measure battery use on both
  the band and phone over a day.
- Devices using rotating unbonded Bluetooth addresses may need selection again;
  this version reconnects to the selected address and does not automatically bond.

## Storage and time

The first health reading is saved promptly. Subsequent observations are combined
into approximately two-minute summaries with latest/minimum/maximum/average/count
and first/last receipt times per metric. High-frequency raw samples and individual
RR sequences are not retained; RR summaries are not an HRV calculation. Device
details and quality notes are bounded. Readable measurements are refreshed every
five minutes in addition to any notifications.

This produces about 5,040 streaming summaries over seven days, leaving room under
the existing 10,000-event cap for other updates. The Wearable tab queries its own
latest 100 summaries so a busy mixed update feed cannot displace them; it expands
the latest 20 for inspection. The normal seven-day retention and per-caregiver
durable synchronization rules still apply.

Times on displayed metrics are **receipt times on the sharing phone**, not a
verified watch clock. Optional device timestamps have no timezone and are shown
separately as unverified text. A recent battery reading does not make an older
heart-rate value fresh. Bluetooth Heart Rate Service has no historical catch-up;
missed readings during a disconnection remain missing. Internet delivery can be
delayed independently of Bluetooth collection.

## Implementation references

- [Android BLE data transfer](https://developer.android.com/develop/connectivity/bluetooth/ble/transfer-ble-data)
- [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [Android background BLE communication](https://developer.android.com/develop/connectivity/bluetooth/ble/background)
- [Bluetooth Heart Rate Service](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/HRS_v1.0/out/en/index-en.html)
- [Bluetooth Pulse Oximeter Service](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/PLXS_v1.0.1/out/en/index-en.html)
- [Bluetooth Blood Pressure Service](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/BLS_v1.1.1/out/en/index-en.html)
- [Bluetooth GATT Specification Supplement](https://btprodspecificationrefs.blob.core.windows.net/gatt-specification-supplement/GATT_Specification_Supplement.pdf)
- [IEEE-11073 decimal float encodings](https://www.bluetooth.com/wp-content/uploads/2019/03/PHD_Transcoding_WP_v16.pdf)

The production implementation adds no third-party dependency for Bluetooth.
