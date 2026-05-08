# V2X2MAP — See the cars talk.

**V2X2MAP** is an open-source receiver and live map for **ITS-G5 / V2X** traffic — the 5.9 GHz IEEE 802.11p messages cars and roadside infrastructure send to coordinate.

Plug a $20 ESP32-C5 dev board into your phone, drive somewhere with modern infrastructure, watch the CAMs, DENMs and SPATEMs roll in.

![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)

<table>
<tr>
<td><img src="docs/screenshot-dashboard.png" alt="Python bridge dashboard — live OSM map + frame log" width="420"/></td>
<td><img src="docs/android-live.png"         alt="Android app — live frames rolling in"              width="200"/></td>
<td><img src="docs/android-map.png"          alt="Android app — map view"                            width="200"/></td>
</tr>
<tr>
<td align="center"><em>Python bridge dashboard</em></td>
<td align="center"><em>Android — live data</em></td>
<td align="center"><em>Android — map</em></td>
</tr>
</table>

## Acknowledgements

Big thanks to the team behind [**opentrafficmap/its-g5-receiver-firmware**](https://codeberg.org/opentrafficmap/its-g5-receiver-firmware) on Codeberg — without their foundational work this project would not exist. V2X2MAP is a fork of their firmware adapted for the Waveshare ESP32-C5-WIFI6-KIT devboard, extended with BLE streaming, the Android app, and the Windows installer.

---

## What it is

Modern cars and roadside units (RSUs) broadcast standardised safety messages on the dedicated 5.9 GHz V2X band:

- **CAM** — Cooperative Awareness: "I'm here, going X km/h"
- **DENM** — Decentralised Environmental Notification: "hazard ahead!"
- **SPATEM** — Signal Phase + Timing: traffic-light countdown
- **MAPEM** — intersection geometry

V2X2MAP captures these in promiscuous mode, decodes the GeoNetworking headers locally, and plots each message as a colour-coded marker on an OSM map. No cloud round-trip required — everything runs on the phone.

---

## Hardware

One **Waveshare ESP32-C5-WIFI6-KIT** dev board and any Android phone with USB-OTG or Bluetooth LE.

The board supports 5.9 GHz IEEE 802.11p out of the box; the firmware drives it as a sniffer and forwards captured frames to your phone.

<img src="docs/hardware.jpg" alt="Waveshare ESP32-C5-WIFI6-KIT dev board" width="340"/>

- **Amazon:** [Waveshare ESP32-C5-WROOM-1 dev board](https://www.amazon.de/dp/B0GXVKM7PH?***REMOVED***) *
- **AliExpress:** [Waveshare Official Store](https://s.click.aliexpress.com/e/_c3pGqqLN) *

---

## Features

| Feature | Description |
|---|---|
| **Live map** | OSM map, colour-coded markers per ITS message type, heading-aware arrows for CAMs, auto-fade |
| **Auto-follow** | Speed-adaptive zoom: 18 standing still, 14 above 80 km/h |
| **Geiger-counter mode** | Audio + haptic tick on every frame, higher beep + buzz on DENM hazard |
| **BLE + USB** | Raw 802.11 frames over USB-Serial-JTAG and BLE GATT, time-sliced automatically |
| **Offline maps** | OSMdroid tile cache up to 600 MB |
| **Recording & replay** | One tap records every frame to `.itsg5`; replay with the Python bridge |
| **MQTT bridge** | Optional re-publish to the public OpenTrafficMap broker or your own |
| **Localised UI** | English default, German for German devices |

---

## Architecture

```
+---------------+     5.9 GHz 802.11p      +------------+
|  Vehicles &   |   CAM / DENM / SPATEM    |  ESP32-C5  |
|  RSUs         |  ----------------------> |  sniffer   |
+---------------+                          +-----+------+
                                                 |
                                  USB-Serial-JTAG | BLE-GATT
                                                 v
                                        +--------+--------+
                                        | Android app /   |
                                        | Python bridge   |
                                        +--------+--------+
                                                 |
                                                 | optional
                                                 v
                                          MQTT (cits1.opentrafficmap.org
                                                 or your own)
```

---

## Install

### Windows — one-click installer (easiest)

1. Download **ITS-G5 Receiver Setup** from the [Releases page](../../releases/latest)
2. Connect the ESP32-C5 via USB
3. Run the EXE and follow three steps:

<table>
<tr>
<td align="center"><strong>Step 1 — Select COM port</strong></td>
<td align="center"><strong>Step 2 — Flash firmware</strong></td>
<td align="center"><strong>Step 3 — Set Node-ID</strong></td>
</tr>
<tr>
<td><img src="docs/installer-1-port.png"   alt="Installer step 1: select COM port"   width="260"/></td>
<td><img src="docs/installer-2-flash.jpg"  alt="Installer step 2: flash firmware"    width="260"/></td>
<td><img src="docs/installer-3-nodeid.png" alt="Installer step 3: set Node-ID"       width="260"/></td>
</tr>
<tr>
<td>The installer detects the board automatically. Pick the right port and click <em>Weiter</em>.</td>
<td>The installer writes bootloader, partition table and application to the C5. Takes 30–60 seconds.</td>
<td>The installer reads the MAC from the chip and pre-fills the Node-ID. Hit <em>Fertig – Bridge starten</em>.</td>
</tr>
</table>

---

### Manual build from source

<details>
<summary>Firmware (ESP-IDF)</summary>

```powershell
# once per shell — activate ESP-IDF toolchain
. .\esp-idf\export.ps1

cd V2X2MAP\firmware
idf.py build
idf.py -p COMx -b 921600 flash
```

</details>

<details>
<summary>Android app</summary>

```powershell
cd V2X2MAP\android
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Or open `V2X2MAP/android/` in Android Studio. Min SDK 24 (Android 7.0).

</details>

<details>
<summary>Python bridge + dashboard</summary>

```powershell
cd V2X2MAP\bridge
python its_g5_bridge.py --port COMx --node-id <mac-without-colons>
```

Dashboard at `http://127.0.0.1:8080`. Default MQTT broker: `mqtts://cits1.opentrafficmap.org:8883`.

</details>

---

## Legal

Receiving and forwarding ITS-G5 radio data may be subject to national telecommunications law and data-protection law. The Android app shows a disclaimer on first launch. Use at your own risk.

Code is published under the **MIT License** — see [`LICENSE`](LICENSE).

\* affiliate link (no extra cost for you)
