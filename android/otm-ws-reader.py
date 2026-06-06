#!/usr/bin/env python3
"""
otm-ws-reader.py — OpenTrafficMap WebSocket Stream Reader
wss://opentrafficmap.org/ws_ext?gzip=true

Verwendung:
  python3 otm-ws-reader.py                        # Alle Nachrichten
  python3 otm-ws-reader.py --filter rsu           # Nur RSUs
  python3 otm-ws-reader.py --filter vehicle       # Nur Fahrzeuge
  python3 otm-ws-reader.py --filter tram          # Nur Straßenbahnen
  python3 otm-ws-reader.py --filter traffic-light # Nur Ampeln
  python3 otm-ws-reader.py --stats                # Nur Statistik
  python3 otm-ws-reader.py --out log.jsonl        # In Datei speichern
  python3 otm-ws-reader.py --raw                  # Rohdaten
"""

import asyncio
import gzip
import json
import argparse
import sys
import datetime

try:
    import websockets
except ImportError:
    print("websockets fehlt: pip install websockets")
    sys.exit(1)

WS_URL = "wss://opentrafficmap.org/ws_ext?gzip=true"

KIND_LABELS = {
    "rsu":           "🛣️  RSU        ",
    "vehicle":       "🚗 Fahrzeug   ",
    "tram":          "🚋 Straßenbahn",
    "bus":           "🚌 Bus        ",
    "emergency":     "🚨 Einsatz    ",
    "traffic-light": "🚦 Ampel      ",
    "cyclist":       "🚴 Fahrrad    ",
    "pedestrian":    "🚶 Fußgänger  ",
}

MSG_TYPE_LABELS = {
    "hello":                  "👋 Verbindung",
    "snapshot":               "📊 Snapshot  ",
    "delta":                  "🔄 Delta     ",
    "traffic-light-map-batch":"🗺️  MAPEM     ",
    "management-nodes":       "📡 Nodes     ",
    "node-statuses":          "📶 Status    ",
}

def format_delta(data: dict, filter_kind: str = None) -> list:
    """Formatiert delta-Nachrichten als Liste von Zeilen."""
    lines = []
    ts = data.get("now", "")[:19].replace("T", " ")
    stats = data.get("stats", {})

    for point in data.get("upsertPoints", []):
        props = point.get("properties", {})
        kind  = props.get("kind", "unknown")

        if filter_kind and filter_kind.lower() not in kind.lower():
            continue

        coords = point.get("geometry", {}).get("coordinates", [None, None])
        lon, lat = coords[0], coords[1]

        label     = KIND_LABELS.get(kind, f"❓ {kind:12}")
        mac       = props.get("shortMac") or props.get("mac", "?")
        speed     = props.get("speedKmh")
        heading   = props.get("headingDeg")
        source    = props.get("sourceSubject", "?").split(".")[1] if props.get("sourceSubject") else "?"
        tl_name   = props.get("trafficLightName") or props.get("stationOverrideName") or ""
        spat      = props.get("trafficLightSpat")
        line_disp = props.get("transitLineDisplay", "")

        pos_str   = f"{lat:.5f}, {lon:.5f}" if lat and lon else "??, ??"
        speed_str = f" {speed:.0f}km/h" if speed is not None else ""
        head_str  = f" {heading:.0f}°" if heading is not None else ""
        name_str  = f" {tl_name}" if tl_name else ""
        line_str  = f" [{line_disp}]" if line_disp else ""
        # SPAT kompakt zusammenfassen
        spat_summary = ""
        if spat and isinstance(spat, dict):
            groups = spat.get("groups", [])
            states = {g["signalGroup"]: g["eventState"] for g in groups}
            green = [k for k, v in states.items() if v == 6]
            red   = [k for k, v in states.items() if v == 3]
            spat_summary = f" 🟢{len(green)}/🔴{len(red)}"
        spat_str  = spat_summary
        node_str  = f" via {source}"

        lines.append(
            f"[{ts}] {label} | {mac}{line_str}{name_str}{speed_str}{head_str}{spat_str} | {pos_str}{node_str}"
        )

    for mac in data.get("deletePoints", []):
        if not filter_kind:
            lines.append(f"[{ts}] 💨 Verschwunden | {mac}")

    return lines


def format_snapshot(data: dict) -> str:
    s = data.get("stats", {})
    online = sum(1 for v in data.get("nodeStatuses", {}).values() if v == "online")
    total  = len(data.get("nodeStatuses", {}))
    return (
        f"[SNAPSHOT] Aktive Geräte: {s.get('activeDevices',0)} | "
        f"Tracks: {s.get('tracks',0)} | "
        f"Pakete empfangen: {s.get('receivedPackets',0):,} | "
        f"Nodes online: {online}/{total}"
    )


def format_hello(data: dict) -> str:
    auth = data.get("auth", {})
    user = auth.get("username") or "anonym"
    return f"[HELLO] Verbunden als: {user} | Server-Zeit: {data.get('now','?')}"


def format_nodes(data: dict) -> str:
    nodes = data.get("nodes", [])
    online = [n for n in nodes if n.get("nodeStatus") == "online"]
    return f"[NODES] {len(online)}/{len(nodes)} online: " + ", ".join(
        f"{n.get('nodeName', n.get('nodeId','?'))}" for n in online[:10]
    )


async def listen(args):
    print(f"Verbinde mit {WS_URL} ...")
    print("Strg+C zum Beenden\n")

    outfile = None
    if args.out:
        outfile = open(args.out, "a", encoding="utf-8")
        print(f"Schreibe nach: {args.out}\n")

    count = 0
    headers = {
        "Origin": "https://opentrafficmap.org",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    }

    async with websockets.connect(
        WS_URL,
        additional_headers=headers,
        ping_interval=30,
        ping_timeout=10,
        max_size=10 * 1024 * 1024,
    ) as ws:
        print("Verbunden!\n")

        async for message in ws:
            try:
                if isinstance(message, bytes):
                    try:
                        message = gzip.decompress(message).decode("utf-8")
                    except Exception:
                        message = message.decode("utf-8", errors="replace")

                data = json.loads(message)
                msg_type = data.get("type", "unknown")

                if outfile:
                    outfile.write(message + "\n")
                    outfile.flush()

                if args.raw:
                    print(message)
                    continue

                if args.stats:
                    if msg_type in ("snapshot", "delta"):
                        s = data.get("stats", {})
                        ts = data.get("now", "")[:19].replace("T", " ")
                        print(f"[{ts}] Geräte: {s.get('activeDevices',0):4} | Tracks: {s.get('tracks',0):3} | Pakete: {s.get('receivedPackets',0):,}")
                    continue

                if msg_type == "hello":
                    print(format_hello(data))
                elif msg_type == "snapshot":
                    print(format_snapshot(data))
                elif msg_type == "management-nodes":
                    print(format_nodes(data))
                elif msg_type == "delta":
                    for line in format_delta(data, args.filter):
                        print(line)
                        count += 1
                elif msg_type == "traffic-light-map-batch":
                    entries = data.get("entries", [])
                    if not args.filter or "ampel" in args.filter.lower() or "map" in args.filter.lower():
                        for e in entries:
                            name = e.get("map", {}).get("name", "?")
                            lat  = e.get("map", {}).get("refLat", "?")
                            lon  = e.get("map", {}).get("refLon", "?")
                            print(f"[MAPEM] 🗺️  {name} | {lat}, {lon}")
                # node-statuses: still

            except json.JSONDecodeError:
                pass
            except Exception as e:
                print(f"[ERROR] {e}")


def main():
    parser = argparse.ArgumentParser(description="OpenTrafficMap WebSocket Reader")
    parser.add_argument("--raw",    action="store_true", help="Rohdaten (JSON) ausgeben")
    parser.add_argument("--stats",  action="store_true", help="Nur Statistik anzeigen")
    parser.add_argument("--filter", metavar="KIND",
                        help="Nur Objekte eines Typs: vehicle, rsu, tram, bus, emergency, traffic-light")
    parser.add_argument("--out",    metavar="DATEI",     help="Nachrichten in JSONL-Datei speichern")
    args = parser.parse_args()

    try:
        asyncio.run(listen(args))
    except KeyboardInterrupt:
        print("\nBeendet.")


if __name__ == "__main__":
    main()