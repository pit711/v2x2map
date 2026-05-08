# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

V2X2MAP is a full-stack ITS-G5 / V2X sniffer targeting an **ESP32-C5-WIFI6-KIT (Waveshare)** devboard. Three components share a common binary wire format:

- `firmware/` — ESP-IDF C firmware: puts the C5 into Wi-Fi promiscuous mode, streams captured 802.11 MAC frames over USB-Serial-JTAG and BLE GATT.
- `bridge/` — Python USB→MQTT bridge + HTTP/SSE Leaflet dashboard + Windows EXE installer (PyInstaller).
- `android/` — Android app (Kotlin, min SDK 24, AGP 8.2): receives frames via USB-OTG or BLE, decodes GeoNetworking, shows live OSM map.

## Wire protocol — central contract

All three components parse the same 14-byte framed binary format:

```
magic[4]  = b"ITS5"
sec       u32 LE   (rx_ctrl.timestamp / 1e6)
usec      u32 LE
len       u16 LE
payload   <len> bytes  (raw 802.11 MAC frame, no radiotap)
```

**Any change to this format must be applied in all three places:**
- `firmware/main/usb_stream.c` (writer, via `usb_serial_jtag_write_bytes()`)
- `bridge/its_g5_bridge.py` → `FrameReader`
- `android/…/FrameReader.kt`

## Build commands

### Firmware (ESP-IDF)
```powershell
# Activate toolchain once per shell session (toolchain at %USERPROFILE%\.espressif)
. .\esp-idf\export.ps1

idf.py build
idf.py -p COM7 -b 921600 flash     # COM7 = C5 USB-Serial-JTAG
idf.py -p COM7 monitor              # Ctrl+] to exit; stop bridge first
```

### Android app
```powershell
cd android
.\gradlew.bat assembleDebug         # APK → app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat assembleRelease       # signed release APK (needs keystore.properties)
adb install -r app\build\outputs\apk\debug\app-debug.apk
```
If the device has a release-signed APK installed, you must uninstall first before installing a debug build (signature mismatch).

### Bridge (Python)
```powershell
python bridge\its_g5_bridge.py --port COM7 --node-id d2cf13ed6293
# Useful flags: --reset-on-start  --no-mqtt  --dashboard-port 0  --exit-after N
```

### Bridge Windows EXE (PyInstaller)
```powershell
cd bridge
.\build_exe.ps1     # builds dist\its-g5-bridge.exe; bundles firmware bins from ..\firmware\build\
```

## Devboard-specific sdkconfig patches

Upstream targets the i5r-r1 board. If re-syncing against upstream always re-apply:
- `CONFIG_SPIRAM` **not set** (no Quad-PSRAM → boot-loop otherwise)
- `CONFIG_ESPTOOLPY_FLASHSIZE_4MB=y` (was 8 MB)
- `CONFIG_PARTITION_TABLE_CUSTOM_FILENAME="partitions_4M.csv"`
- `CONFIG_ESP_CONSOLE_SECONDARY_NONE=y` — USB-Serial-JTAG must be exclusive for the binary stream
- `CONFIG_HW_VARIANT="custom"` — silences upstream OTA server

Source-level patches beyond sdkconfig:
- `ethernet.c` `initialize_ethernet()` — soft-fail instead of `ESP_ERROR_CHECK` (no W5500 attached)
- `cmd_sniffer.c` — calls `usb_stream_publish_packet()` after `mqtt_handle_packet()`
- `main.c` `app_main()` — calls `usb_stream_init()` + `usb_stream_send_test_frame()` on boot (DEADBEEF heartbeat — intentional)

## Android architecture

`FrameReader.kt` → `ItsG5Decoder.kt` → `Frame.kt` is the decode pipeline, called from both `UsbSerialController` and `BluetoothController` via the shared `onSerialBytes` callback in `MainActivity`.

Key non-obvious points:

**ItsG5Decoder position fallback** — Some RSUs use an 8-byte TAI timestamp instead of the standard 4-byte, shifting lat/lon 4 bytes later in the LPV. The decoder tries `srcPosOff+12/+16` first; if lat > 90° it retries at `+16/+20`. Do not remove this fallback.

**USB prober** — `UsbSerialController.findDrivers()` first tries `UsbSerialProber.getDefaultProber()`, then falls back to a custom `ProbeTable` with Espressif VID `0x303A` + PIDs `0x1001/0x0002/0x8001` using `CdcAcmSerialDriver`. The default prober does not include Espressif devices.

**BLE reconnect** — `BluetoothController` caches the `BluetoothDevice` after first scan (reconnects skip the 15 s scan), caches the negotiated MTU (skips renegotiation), requests `CONNECTION_PRIORITY_HIGH` immediately on connect, and runs a 6 s CCCD watchdog. The ESP32-C5 sets a 5 s BLE supervision timeout; the watchdog must fire before it. Retry uses exponential backoff: 1 → 2 → 4 → 8 → 30 s.

**DTR/RTS** — both `UsbSerialController` and `BluetoothController` force `dtr=false; rts=false` after opening. Leaving DTR high puts the C5 into download mode silently.

**MarkerLayer** reads `Prefs.markerTtlMinutes()` on every `prune()` call (every second). TTL applies uniformly to all marker types and to CAM path polylines.

**ReceiverForegroundService** starts when the first USB or BLE connect button is pressed; holds a `PARTIAL_WAKE_LOCK` (max 8 h); stops in `MainActivity.onDestroy()`.

## Bridge architecture

`its_g5_bridge.py` owns the serial read loop and MQTT publishing. `dashboard_server.py` is imported and provides the HTTP+SSE server with a 200-frame ringbuffer (replayed on every new SSE connection — required because the boot test-frame fires before any browser connects). `dashboard.html` uses Leaflet + EventSource.

`sniff_ether_type` in the bridge mirrors `ItsG5Decoder.sniffEtherType` in Kotlin — keep in sync (0x8947 = ITS-G5).

## Website (https://v2x2map.com)

Static HTML/CSS/JS on Namecheap shared hosting (LiteSpeed, server386.web-hosting.com:2083, user `wriauetx`). No build step. Edit files directly via cPanel FileManager API or SFTP. HTTPS via Let's Encrypt (cert expires 2026-08-06; renewal script at `get_cert.py` + `account.key` + `domain.key` — these are gitignored).

## Android emulator (itsg5_test AVD)

```powershell
$env:ANDROID_AVD_HOME = "D:\android-sdk-images\avd"
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd itsg5_test -no-snapshot-save -no-audio
```
`system-images` and `avd` are NTFS junctions to `D:\android-sdk-images\`. USB and BLE are not available in the emulator.

## ADB over WiFi

Device IP: `10.0.0.180`, port `45959` (wireless debug port may change — check device settings).
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" connect 10.0.0.180:45959
```
