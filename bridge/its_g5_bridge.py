"""
ITS-G5 Receiver USB-to-MQTT bridge  (V2X2MAP).

Reads framed packets from an ESP32-C5 connected via USB-Serial-JTAG and
republishes them to one or more MQTT brokers.

Frame format on the wire (little-endian, no padding):
    magic[4]  = b"ITS5"
    sec       u32   epoch seconds (from rx_ctrl)
    usec      u32   microseconds
    len       u16   payload length in bytes
    payload   <len> raw 802.11 MAC frame

Run:
    python its_g5_bridge.py --node-id V2X2MAP:d2cf13ed6293
    python its_g5_bridge.py --node-id V2X2MAP:d2cf13ed6293 --broker mqtts://broker1:8883 --broker mqtt://broker2:1883
"""

import argparse
import json
import logging
import os
import re
import ssl
import struct
import sys
import threading
import time
from collections import deque
from urllib.parse import urlparse

import paho.mqtt.client as mqtt
import serial
import serial.tools.list_ports

import dashboard_server

MAGIC      = b"ITS5"
HEADER_FMT = "<4sIIH"
HEADER_LEN = struct.calcsize(HEADER_FMT)  # 14

DEFAULT_BROKER = "mqtts://cits1.opentrafficmap.org:8883"

ESP_VID      = 0x303A         # Espressif Systems
FALLBACK_VIDS = {0x10C4, 0x1A86}  # CP210x, CH340

HDR_LENGTHS = (24, 26, 30, 32)

MSG_TYPE_FROM_BTP = {
    2001: "CAM",
    2002: "DENM",
    2003: "MAPEM",
    2004: "SPATEM",
    2006: "IVIM",
    2007: "SREM",
    2008: "SSEM",
    2010: "TLM",
    2012: "RTCMEM",
}

STATION_TYPES = {
    0: "unknown", 1: "pedestrian", 2: "cyclist", 3: "moped",
    4: "motorcycle", 5: "passenger car", 6: "bus", 7: "light truck",
    8: "heavy truck", 9: "trailer", 10: "special vehicle", 11: "tram", 15: "RSU",
}


# ---------------------------------------------------------------------------
# Frame decoding
# ---------------------------------------------------------------------------

def sniff_ether_type(payload: bytes) -> int | None:
    for hdr_len in HDR_LENGTHS:
        if len(payload) < hdr_len + 8:
            continue
        snap = payload[hdr_len:hdr_len + 8]
        if snap[:6] == b"\xaa\xaa\x03\x00\x00\x00":
            return (snap[6] << 8) | snap[7]
    return None


def decode_itsg5_full(payload: bytes) -> dict:
    """
    Full ITS-G5 decode — mirrors Android ItsG5Decoder.decodeFull().
    Returns dict with ether_type, msg_type, station_id (hex str),
    gn_addr, station_type[_label], secured, and optionally
    lat/lon/speed_mps/heading_deg.
    """
    et = sniff_ether_type(payload)
    result: dict = {"ether_type": et, "msg_type": "UNKNOWN"}
    if et != 0x8947:
        return result

    for hdr_len in HDR_LENGTHS:
        if len(payload) < hdr_len + 8 + 4 + 8:
            continue
        if payload[hdr_len:hdr_len + 6] != b"\xaa\xaa\x03\x00\x00\x00":
            continue

        basic_off  = hdr_len + 8
        common_off = basic_off + 4
        if len(payload) < common_off + 8:
            continue

        ht_hst = payload[common_off + 1]
        ht  = (ht_hst >> 4) & 0x0F
        hst =  ht_hst & 0x0F

        ext_hdr_len = {1: 4, 2: 48, 3: 56, 4: 44, 5: 28, 6: 36}.get(ht, 28)
        src_in_ext  = 0 if (ht == 5 and hst == 0) else 4
        src_off     = common_off + 8 + src_in_ext
        btp_off     = common_off + 8 + ext_hdr_len

        if len(payload) < btp_off + 4 or len(payload) < src_off + 24:
            continue

        gn_addr    = payload[src_off:src_off + 8]
        st         = (gn_addr[0] >> 2) & 0x1F
        mac        = gn_addr[2:8]
        station_id = int.from_bytes(gn_addr, "big")

        lat_raw = int.from_bytes(payload[src_off + 12:src_off + 16], "big", signed=True)
        lon_raw = int.from_bytes(payload[src_off + 16:src_off + 20], "big", signed=True)
        psh_off = src_off + 20

        if not (-90.0 <= lat_raw / 1e7 <= 90.0) and len(payload) >= src_off + 28:
            lat2 = int.from_bytes(payload[src_off + 16:src_off + 20], "big", signed=True)
            lon2 = int.from_bytes(payload[src_off + 20:src_off + 24], "big", signed=True)
            if -90.0 <= lat2 / 1e7 <= 90.0 and -180.0 <= lon2 / 1e7 <= 180.0:
                lat_raw, lon_raw, psh_off = lat2, lon2, src_off + 24

        psh_raw    = int.from_bytes(payload[psh_off:psh_off + 4], "big")
        speed_raw  = (psh_raw >> 16) & 0x7FFF
        if speed_raw >= 0x4000:
            speed_raw -= 0x8000
        heading_raw = psh_raw & 0xFFFF

        lat = lat_raw / 1e7
        lon = lon_raw / 1e7
        dst_port = (payload[btp_off] << 8) | payload[btp_off + 1]
        msg_type = MSG_TYPE_FROM_BTP.get(dst_port, "UNKNOWN")
        secured  = (payload[basic_off] & 0x0F) == 2

        result.update({
            "msg_type":           msg_type,
            "station_id":         format(station_id, "016x"),
            "gn_addr":            ":".join(f"{b:02x}" for b in mac),
            "station_type":       st,
            "station_type_label": STATION_TYPES.get(st, f"type {st}"),
            "secured":            secured,
            "btp_dst_port":       dst_port,
        })

        if -90.0 <= lat <= 90.0 and -180.0 <= lon <= 180.0 and not (lat == 0.0 and lon == 0.0):
            result["lat"] = lat
            result["lon"] = lon
            result["speed_mps"] = speed_raw * 0.01
            if heading_raw != 0xFFFF:
                result["heading_deg"] = heading_raw * 0.1

        return result

    return result


# ---------------------------------------------------------------------------
# PCAP writer
# ---------------------------------------------------------------------------

class PcapWriter:
    """Minimal libpcap writer — DLT_IEEE802_11 (105), open in Wireshark directly."""
    _GLOBAL = struct.pack("<IHHiIII", 0xA1B2C3D4, 2, 4, 0, 0, 65535, 105)

    def __init__(self, path: str):
        self._f = open(path, "wb")
        self._f.write(self._GLOBAL)

    def write(self, sec: int, usec: int, data: bytes):
        n = len(data)
        self._f.write(struct.pack("<IIII", sec, usec, n, n))
        self._f.write(data)
        self._f.flush()

    def close(self):
        try: self._f.close()
        except Exception: pass


# ---------------------------------------------------------------------------
# Frame parser
# ---------------------------------------------------------------------------

class FrameReader:
    """Stateful resync parser: scans a byte stream for ITS5 frames."""
    def __init__(self):
        self._buf = bytearray()

    def feed(self, chunk: bytes):
        self._buf.extend(chunk)
        while True:
            idx = self._buf.find(MAGIC)
            if idx < 0:
                if len(self._buf) > 3:
                    del self._buf[: len(self._buf) - 3]
                return
            if idx > 0:
                del self._buf[:idx]
            if len(self._buf) < HEADER_LEN:
                return
            _, sec, usec, plen = struct.unpack_from(HEADER_FMT, self._buf, 0)
            if plen > 4096:
                del self._buf[:4]
                continue
            if len(self._buf) < HEADER_LEN + plen:
                return
            payload = bytes(self._buf[HEADER_LEN: HEADER_LEN + plen])
            del self._buf[: HEADER_LEN + plen]
            yield sec, usec, payload


# ---------------------------------------------------------------------------
# Serial helpers
# ---------------------------------------------------------------------------

def _find_esp_port() -> str | None:
    for p in serial.tools.list_ports.comports():
        if p.vid == ESP_VID:
            return p.device
    for p in serial.tools.list_ports.comports():
        if p.vid in FALLBACK_VIDS:
            return p.device
    return None


def _open_serial(port: str, baud: int) -> serial.Serial:
    ser = serial.Serial(port, baud, timeout=0.5)
    ser.dtr = False
    ser.rts = False
    time.sleep(0.05)
    return ser


# ---------------------------------------------------------------------------
# MQTT helpers — multiple brokers
# ---------------------------------------------------------------------------

def _parse_broker(url: str) -> tuple[str, int, bool]:
    """Return (host, port, use_tls) from a broker URL."""
    url = url.strip()
    if "://" not in url:
        url = "mqtts://" + url
    parsed = urlparse(url)
    tls = parsed.scheme in ("mqtts", "ssl")
    host = parsed.hostname or "localhost"
    port = parsed.port or (8883 if tls else 1883)
    return host, port, tls


def build_mqtt_clients(node_id: str, broker_urls: list[str], status_topic: str,
                       connected_count: list, count_lock: threading.Lock):
    """
    Build one Paho client per broker URL.
    connected_count is a shared mutable [int] list; count_lock guards it.
    Returns the list of clients (already started with loop_start).
    """
    clients = []
    for url in broker_urls:
        host, port, use_tls = _parse_broker(url)
        safe_id = re.sub(r"[^a-zA-Z0-9-]", "-", node_id)[:20]
        client = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id=f"its-g5-{safe_id}-{host[:12]}",
            protocol=mqtt.MQTTv311,
        )
        client.will_set(status_topic, payload=b"offline", qos=1, retain=True)
        if use_tls:
            client.tls_set_context(ssl.create_default_context())

        def on_connect(c, userdata, flags, rc, props=None,
                       _host=host, _port=port, _st=status_topic):
            if rc == 0:
                logging.info("MQTT connected to %s:%d", _host, _port)
                c.publish(_st, b"online", qos=1, retain=True)
                with count_lock:
                    connected_count[0] += 1
                dashboard_server.set_mqtt_state(available=connected_count[0] > 0)
                dashboard_server.update_broker_status(_host, _port, True)
            else:
                logging.error("MQTT connect failed %s:%d  rc=%s", _host, _port, rc)
                dashboard_server.update_broker_status(_host, _port, False)

        def on_disconnect(c, userdata, flags, rc, props=None,
                          _host=host, _port=port):
            logging.warning("MQTT disconnected %s:%d  rc=%s", _host, _port, rc)
            with count_lock:
                connected_count[0] = max(0, connected_count[0] - 1)
            dashboard_server.set_mqtt_state(available=connected_count[0] > 0)
            dashboard_server.update_broker_status(_host, _port, False)

        client.on_connect    = on_connect
        client.on_disconnect = on_disconnect
        client.connect_async(host, port, keepalive=60)
        client.loop_start()
        clients.append(client)
        logging.info("MQTT broker configured: %s:%d  tls=%s", host, port, use_tls)
    return clients


# ---------------------------------------------------------------------------
# Paths  —  config/ and recordings/ next to the executable
# ---------------------------------------------------------------------------

def _base_dir() -> str:
    return os.path.dirname(sys.executable if getattr(sys, "frozen", False)
                           else os.path.abspath(__file__))


def _config_path() -> str:
    d = os.path.join(_base_dir(), "config")
    os.makedirs(d, exist_ok=True)
    return os.path.join(d, "v2x2map.cfg")


def _recordings_dir() -> str:
    d = os.path.join(_base_dir(), "recordings")
    os.makedirs(d, exist_ok=True)
    return d


def _load_config() -> dict:
    try:
        with open(_config_path(), encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def _save_config(cfg: dict) -> None:
    try:
        with open(_config_path(), "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2, ensure_ascii=False)
    except Exception:
        pass


def _pause_exit(code: int) -> int:
    """On frozen exe, pause before exit so the user can read error messages."""
    if getattr(sys, "frozen", False):
        input("\nPress Enter to quit…")
    return code


# ---------------------------------------------------------------------------
# Legal disclaimer  (shown once on first run, acceptance stored in config)
# ---------------------------------------------------------------------------

_DISCLAIMER = """\
[English]
This software captures IEEE 802.11p / ITS-G5 radio frames in promiscuous mode
and may optionally forward data to an MQTT server.

IMPORTANT:
  - Operation is only permitted in environments for which the user holds the
    necessary authorisation.
  - Intercepting radio signals without permission may violate national law
    (e.g. wiretapping statutes, §89 TKG in Germany).
  - This software is intended solely for research and development purposes.
    Use on public roads or in regulated frequency bands without a regulatory
    permit is not allowed.
  - Any use is entirely at the user's own risk. The developers accept no
    liability for damages or legal violations.

By continuing you confirm that you are aware of and will comply with all
applicable laws.

[Deutsch]
Diese Software erfasst IEEE 802.11p / ITS-G5 Funksignale im Promiscuous-Modus
und kann Daten optional an einen MQTT-Server übertragen.

WICHTIG:
  - Der Betrieb ist ausschließlich in Umgebungen gestattet, für die der
    Anwender die erforderliche Berechtigung besitzt.
  - Das unbefugte Abhören von Funksignalen kann gegen nationales Recht
    verstoßen (z. B. § 89 TKG in Deutschland).
  - Die Software dient ausschließlich Forschungs- und Entwicklungszwecken.
    Eine Nutzung ohne behördliche Genehmigung ist unzulässig.
  - Jegliche Nutzung liegt in der alleinigen Verantwortung des Anwenders.
    Die Entwickler übernehmen keine Haftung für Schäden oder Verstöße.

Mit der Fortsetzung bestätigen Sie, alle geltenden Gesetze zu kennen
und einzuhalten.\
"""


def _check_disclaimer(cfg: dict) -> bool:
    """Return True if disclaimer is accepted. Shows it on first run."""
    if cfg.get("legal_accepted"):
        return True

    sep = "=" * 66
    print(f"\n{sep}")
    print("  V2X2MAP ITS-G5 Bridge — Legal Notice / Rechtlicher Hinweis")
    print(sep)
    print()
    print(_DISCLAIMER)
    print()
    print(sep)
    print("  Press ENTER to accept and continue.")
    print("  Any other input + ENTER, or Ctrl+C, to quit.")
    print("  ENTER druecken zum Akzeptieren, sonstige Eingabe zum Beenden.")
    print(sep)
    try:
        ans = input("  > ").strip()
    except (EOFError, KeyboardInterrupt):
        ans = None

    if ans == "":          # bare Enter = accept
        cfg["legal_accepted"] = True
        _save_config(cfg)
        return True

    print("\n  Not accepted — exiting. / Nicht akzeptiert — Beenden.")
    return False


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def main():
    p = argparse.ArgumentParser(
        description="ITS-G5 USB->MQTT bridge  (V2X2MAP)\n"
                    f"Config: {_config_path()}",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("--port", default=None,
                   help="serial port (e.g. COM7); omit to auto-detect or use config")
    p.add_argument("--list-ports", action="store_true",
                   help="print available serial ports with VID/PID and exit")
    p.add_argument("--baud", type=int, default=115200)
    p.add_argument("--node-id", default=None,
                   help="device ID, e.g. V2X2MAP:d2cf13ed6293  (read from config if omitted)")
    p.add_argument("--broker", action="append", dest="brokers", default=[],
                   metavar="URL",
                   help="MQTT broker URL (mqtts://host:port or mqtt://host:port). "
                        "Can be specified multiple times. "
                        f"Default from config or {DEFAULT_BROKER}")
    p.add_argument("--no-mqtt", action="store_true",
                   help="parse only, don't publish (for testing)")
    p.add_argument("--reset-on-start", action="store_true",
                   help="pulse RTS to reboot the device on first connect")
    p.add_argument("--reconnect-delay", type=float, default=3.0,
                   help="seconds to wait before reconnecting after USB disconnect (0 = exit)")
    p.add_argument("--exit-after", type=float, default=0,
                   help="exit after N seconds (0 = run forever)")
    p.add_argument("--dashboard-port", type=int, default=8080,
                   help="local HTTP port for the live dashboard (0 to disable)")
    # When launched as frozen exe (double-click), open browser automatically
    _frozen = getattr(sys, "frozen", False)
    p.add_argument("--open-browser", action="store_true", default=_frozen,
                   help="open the dashboard URL in the default browser on start "
                        "(default: on when running as .exe)")
    p.add_argument("--no-browser", action="store_true",
                   help="suppress automatic browser opening")
    p.add_argument("--pcap-out", default="",
                   help="write all captured frames to a .pcap file (DLT_IEEE802_11)")
    p.add_argument("--verbose", "-v", action="store_true")
    args = p.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
    )

    if args.list_ports:
        for pt in serial.tools.list_ports.comports():
            vid_pid = f" [{pt.vid:04X}:{pt.pid:04X}]" if pt.vid else ""
            print(f"{pt.device:<12}  {pt.description}{vid_pid}")
        return 0

    # ── Config file + CLI merge (CLI wins) ───────────────────────────────
    cfg = _load_config()
    if cfg:
        logging.info("Config loaded: %s", _config_path())

    # Legal disclaimer — show once, store acceptance in config
    if not _check_disclaimer(cfg):
        return _pause_exit(0)

    node_id     = args.node_id     or cfg.get("node_id",  "")
    port        = args.port        or cfg.get("port")
    broker_urls = args.brokers     or cfg.get("brokers",  [DEFAULT_BROKER])

    if not node_id:
        print("\n" + "=" * 60)
        print("  Error: No node-id configured.")
        print("  Run its-g5-setup.exe  OR  pass")
        print("  --node-id V2X2MAP:xxxxxxxxxxxx")
        print("=" * 60)
        return _pause_exit(1)

    if not port:
        port = _find_esp_port()
        if port:
            logging.info("Port auto-detected: %s", port)
        else:
            print("\n" + "=" * 60)
            print("  Error: No serial port found.")
            print("  Connect the ESP32 or pass --port.")
            print("=" * 60)
            return _pause_exit(1)

    logging.info("Node-ID   : %s", node_id)
    logging.info("Port      : %s", port)
    logging.info("Config    : %s", _config_path())
    logging.info("Recordings: %s", _recordings_dir())

    packet_topic = f"its/{node_id}/packet"
    status_topic = f"its/{node_id}/status"

    # MQTT publishing starts disabled — user activates from dashboard
    dashboard_server.set_mqtt_state(enabled=False, available=False)

    connected_count = [0]
    count_lock      = threading.Lock()
    clients: list   = []

    if not args.no_mqtt:
        dashboard_server.register_brokers(broker_urls)
        clients = build_mqtt_clients(
            node_id, broker_urls, status_topic, connected_count, count_lock
        )

    # ── Dashboard-controlled PCAP recording ─────────────────────────────────
    rec      = {"pcap": None, "file": "", "frames": 0}
    rec_lock = threading.Lock()

    def on_record(action: str, filename: str = "") -> dict:
        with rec_lock:
            if action == "start":
                if rec["pcap"]:
                    rec["pcap"].close()
                fn = filename or os.path.join(
                    _recordings_dir(), f"its5_{time.strftime('%Y%m%d_%H%M%S')}.pcap"
                )
                try:
                    rec["pcap"]   = PcapWriter(fn)
                    rec["file"]   = fn
                    rec["frames"] = 0
                    st = {"recording": True, "file": fn, "frames": 0}
                except Exception as exc:
                    rec["pcap"] = None
                    st = {"recording": False, "file": "", "frames": 0, "error": str(exc)}
            elif action == "stop":
                if rec["pcap"]:
                    rec["pcap"].close()
                    rec["pcap"] = None
                st = {"recording": False, "file": rec["file"], "frames": rec["frames"]}
            else:
                st = {"recording": rec["pcap"] is not None,
                      "file": rec["file"], "frames": rec["frames"]}
        dashboard_server.update_record_state(st)
        return st

    dashboard_server.set_record_callback(on_record)

    if args.dashboard_port:
        dashboard_server.start(args.dashboard_port, node_id=node_id)
        url = f"http://127.0.0.1:{args.dashboard_port}"
        logging.info("Dashboard:  %s", url)
        if args.open_browser and not args.no_browser:
            try:
                import webbrowser
                webbrowser.open(url)
            except Exception as exc:
                logging.warning("Could not open browser: %s", exc)

    # ── Static PCAP output (--pcap-out, always-on) ───────────────────────────
    pcap: PcapWriter | None = None
    if args.pcap_out:
        pcap = PcapWriter(args.pcap_out)
        logging.info("PCAP output: %s", args.pcap_out)

    n_frames    = 0
    n_bytes     = 0
    start_time  = time.monotonic()
    last_stats  = start_time
    rate_window: deque = deque()
    deadline    = time.monotonic() + args.exit_after if args.exit_after > 0 else None
    first_connect = True
    ser: serial.Serial | None = None
    done = False

    try:
        while not done:
            if deadline and time.monotonic() >= deadline:
                logging.info("exit-after deadline reached")
                break

            try:
                logging.info("Opening %s @ %d baud", port, args.baud)
                ser = _open_serial(port, args.baud)
            except serial.SerialException as exc:
                logging.warning("Cannot open %s: %s", port, exc)
                dashboard_server.broadcast_serial_state(connected=False, port=port)
                if args.reconnect_delay <= 0:
                    break
                time.sleep(args.reconnect_delay)
                continue

            if args.reset_on_start and first_connect:
                logging.info("Pulsing RTS to reset device")
                ser.rts = True
                time.sleep(0.1)
                ser.rts = False
                time.sleep(0.05)
                ser.reset_input_buffer()
            first_connect = False
            dashboard_server.broadcast_serial_state(connected=True, port=port)
            reader = FrameReader()

            try:
                while not done:
                    if deadline and time.monotonic() >= deadline:
                        done = True; break

                    chunk = ser.read(4096)
                    if chunk:
                        n_bytes += len(chunk)
                        for sec, usec, payload in reader.feed(chunk):
                            n_frames += 1
                            now_mono = time.monotonic()
                            rate_window.append(now_mono)
                            while rate_window and rate_window[0] < now_mono - 60.0:
                                rate_window.popleft()

                            decoded = decode_itsg5_full(payload)

                            if args.dashboard_port:
                                evt = {"n": n_frames, "sec": sec, "usec": usec,
                                       "len": len(payload), "hex": payload[:48].hex()}
                                evt.update(decoded)
                                dashboard_server.broadcast_frame(evt)

                            if clients and dashboard_server.is_mqtt_enabled():
                                for c in clients:
                                    try:
                                        c.publish(packet_topic, payload, qos=0, retain=False)
                                    except Exception:
                                        pass

                            if pcap:
                                pcap.write(sec, usec, payload)

                            with rec_lock:
                                if rec["pcap"]:
                                    rec["pcap"].write(sec, usec, payload)
                                    rec["frames"] += 1
                                    if rec["frames"] % 50 == 0:
                                        dashboard_server.update_record_state({
                                            "recording": True,
                                            "file": rec["file"],
                                            "frames": rec["frames"],
                                        })

                    now_mono = time.monotonic()
                    if now_mono - last_stats >= 5.0:
                        rate   = len(rate_window)
                        uptime = int(now_mono - start_time)
                        logging.info("frames=%d  rate=%d/min  bytes=%d  uptime=%ds",
                                     n_frames, rate, n_bytes, uptime)
                        if args.dashboard_port:
                            dashboard_server.broadcast_stats({
                                "frames":   n_frames,
                                "rate":     rate,
                                "bytes":    n_bytes,
                                "uptime_s": uptime,
                            })
                        last_stats = now_mono

            except serial.SerialException as exc:
                logging.warning("Serial error: %s", exc)
                dashboard_server.broadcast_serial_state(connected=False, port=port)
                try: ser.close()
                except Exception: pass
                ser = None
                if args.reconnect_delay <= 0:
                    break
                logging.info("Reconnecting in %.1f s…", args.reconnect_delay)  # noqa: RUF001
                time.sleep(args.reconnect_delay)

    except KeyboardInterrupt:
        logging.info("Stopping (frames=%d)", n_frames)
    finally:
        for c in clients:
            try:
                c.publish(status_topic, b"offline", qos=1, retain=True)
                c.loop_stop()
                c.disconnect()
            except Exception:
                pass
        if ser is not None:
            try: ser.close()
            except Exception: pass
        if pcap:
            pcap.close()
        with rec_lock:
            if rec["pcap"]:
                rec["pcap"].close()

    return 0


if __name__ == "__main__":
    sys.exit(main())
