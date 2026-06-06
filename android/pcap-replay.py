#!/usr/bin/env python3
"""
pcap-replay.py — Lädt eine .pcap Aufzeichnung per MQTT an OpenTrafficMap hoch
Kompatibel mit dem V2X2MAP/its-g5-bridge Format

Verwendung:
  python3 pcap-replay.py --pcap fahrt.pcap --node-id studiojustinbraun-mobil
  python3 pcap-replay.py --pcap fahrt.pcap --node-id studiojustinbraun-mobil --realtime
"""

import argparse
import time
import sys
import ssl
import struct
import os

try:
    from scapy.all import rdpcap, raw
except ImportError:
    print("scapy fehlt: pip install scapy")
    sys.exit(1)

try:
    import paho.mqtt.client as mqtt
except ImportError:
    print("paho-mqtt fehlt: pip install paho-mqtt")
    sys.exit(1)

# ── Konfiguration ─────────────────────────────────────────────────────────────

DEFAULT_BROKER   = "cits1.opentrafficmap.org"
DEFAULT_PORT     = 8883
DEFAULT_NODE_ID  = "studiojustinbraun-mobil"

# ── MQTT Callbacks ─────────────────────────────────────────────────────────────

connected = False

def on_connect(client, userdata, flags, rc, properties=None):
    global connected
    if rc == 0:
        connected = True
        print(f"[MQTT] Verbunden mit {DEFAULT_BROKER}:{DEFAULT_PORT}")
    else:
        print(f"[MQTT] Verbindung fehlgeschlagen: rc={rc}")

def on_disconnect(client, userdata, rc, properties=None, reasoncode=None):
    global connected
    connected = False
    print(f"[MQTT] Getrennt")

# ── Hauptprogramm ──────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="ITS-G5 PCAP Replay → MQTT")
    parser.add_argument("--pcap",    required=True,          help="Pfad zur .pcap Datei")
    parser.add_argument("--node-id", default=DEFAULT_NODE_ID, help="Node-ID")
    parser.add_argument("--broker",  default=DEFAULT_BROKER,  help="MQTT Broker Host")
    parser.add_argument("--port",    default=DEFAULT_PORT, type=int, help="MQTT Port")
    parser.add_argument("--realtime", action="store_true",    help="Pakete mit Original-Zeitabständen senden")
    parser.add_argument("--delay",   default=0.05, type=float, help="Pause zwischen Paketen in Sekunden (default: 0.05)")
    parser.add_argument("--no-tls",  action="store_true",     help="TLS deaktivieren")
    parser.add_argument("--dry-run", action="store_true",     help="Nur parsen, nicht senden")
    args = parser.parse_args()

    # PCAP laden
    if not os.path.exists(args.pcap):
        print(f"Datei nicht gefunden: {args.pcap}")
        sys.exit(1)

    print(f"[PCAP] Lade {args.pcap} ...")
    pkts = rdpcap(args.pcap)
    print(f"[PCAP] {len(pkts)} Pakete geladen")

    # Topic: its/<node-id>/packet  (V2X2MAP Format)
    topic = f"its/{args.node_id}/packet"
    print(f"[MQTT] Topic: {topic}")

    if args.dry_run:
        print("[DRY-RUN] Kein MQTT — nur Statistik:")
        sizes = [len(raw(p)) for p in pkts]
        print(f"  Min: {min(sizes)} bytes, Max: {max(sizes)} bytes, Avg: {sum(sizes)//len(sizes)} bytes")
        return

    # MQTT verbinden
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=f"pcap-replay-{args.node_id}")
    client.on_connect    = on_connect
    client.on_disconnect = on_disconnect

    if not args.no_tls:
        tls_ctx = ssl.create_default_context()
        client.tls_set_context(tls_ctx)

    print(f"[MQTT] Verbinde mit {args.broker}:{args.port} ...")
    client.connect(args.broker, args.port, keepalive=60)
    client.loop_start()

    # Warte auf Verbindung
    for _ in range(20):
        if connected:
            break
        time.sleep(0.5)

    if not connected:
        print("[MQTT] Verbindung fehlgeschlagen!")
        sys.exit(1)

    # Pakete senden
    print(f"[REPLAY] Sende {len(pkts)} Pakete ...")
    prev_time = None
    sent = 0
    errors = 0

    for i, pkt in enumerate(pkts):
        payload = raw(pkt)

        # Realtime-Modus: Originalzeitabstände einhalten
        if args.realtime and prev_time is not None:
            delta = float(pkt.time) - prev_time
            if 0 < delta < 5.0:  # max 5s warten
                time.sleep(delta)

        prev_time = float(pkt.time)

        result = client.publish(topic, payload, qos=0)
        if result.rc == mqtt.MQTT_ERR_SUCCESS:
            sent += 1
        else:
            errors += 1

        if (i + 1) % 50 == 0:
            print(f"  [{i+1}/{len(pkts)}] gesendet={sent} fehler={errors}")

        if not args.realtime:
            time.sleep(args.delay)

    print(f"\n[FERTIG] {sent}/{len(pkts)} Pakete gesendet, {errors} Fehler")
    client.loop_stop()
    client.disconnect()


if __name__ == "__main__":
    main()