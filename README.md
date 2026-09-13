# Fire TV BLE Logger

Minimal BLE advertisement logger for Fire TV / Android.

Purpose:
- Observe BLE advertisements from an iPhone using a third-party receiver.
- Record timestamp, RSSI, manufacturer data, service data and raw scan-record hex.
- Highlight Apple Bluetooth Company ID `0x004C`.
- Export a CSV for later Link / residual / adjoint analysis.

## Target

Initial target: Fire TV Stick (3rd generation), Fire OS 7 / Android 9 family. Android 12+ Bluetooth permissions are also included for newer Android devices.

## Build

GitHub Actions builds the debug APK automatically. Open `Actions -> Build APK -> latest run -> Artifacts`, then download `firetv-ble-logger-debug`. The archive contains `app-debug.apk`.

## Experiment

1. Start capture.
2. Leave iPhone Personal Hotspot off for ~15 s.
3. Turn Personal Hotspot on for ~15 s.
4. Turn it off for ~15 s.
5. Repeat 3-5 times.
6. Stop/save.
7. Compare rows where `is_apple=1` and inspect `raw_scan_record_hex`.

This does not emulate an Apple device and does not bypass authentication. It is a passive BLE advertisement logger.

## CSV columns

`timestamp_utc`, `scan_timestamp_nanos`, `rssi`, `address`, `name`, `company_id`, `manufacturer_hex`, `service_data_hex`, `raw_scan_record_hex`, `is_apple`.

Output is stored in the app's external files directory shown on screen after capture.
