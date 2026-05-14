"""
Local HTTP+SSE dashboard server for the ITS-G5 bridge  (V2X2MAP).

Routes:
  GET  /              → dashboard.html
  GET  /events        → SSE stream
  GET  /api/mqtt      → MQTT toggle state
  GET  /api/stats     → last stats snapshot
  GET  /api/record    → PCAP recording state
  GET  /api/brokers   → configured broker list + status
  POST /api/mqtt      → toggle MQTT publishing  {"enabled": bool}
  POST /api/record    → start/stop recording    {"action": "start"|"stop", "file": "optional.pcap"}

Pure stdlib: http.server + threading + queue.
"""

from __future__ import annotations

import collections
import json
import logging
import os
import queue
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

_HERE      = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
_HTML_PATH = os.path.join(_HERE, "dashboard.html")

_HISTORY_MAX = 200

_subs_lock   = threading.Lock()
_subscribers: list[queue.Queue] = []
_history: collections.deque[bytes] = collections.deque(maxlen=_HISTORY_MAX)
_node_id = "?"

# MQTT state
_mqtt_enabled   = False   # off by default — user activates from dashboard
_mqtt_available = False

# Broker status  {url: {"host": h, "port": p, "connected": bool}}
_broker_status: dict[str, dict] = {}

# Serial state
_serial_connected = False
_serial_port      = ""

# Stats
_last_stats: dict = {}

# PCAP recording state (updated by bridge via set_record_callback)
_record_state: dict = {"recording": False, "file": "", "frames": 0}
_record_callback = None   # callable(action: str, filename: str) → dict


# ---------------------------------------------------------------------------
# Public API used by the bridge
# ---------------------------------------------------------------------------

def is_mqtt_enabled() -> bool:
    return _mqtt_enabled and _mqtt_available


def get_mqtt_state() -> dict:
    return {"enabled": _mqtt_enabled, "available": _mqtt_available}


def set_mqtt_state(enabled: bool | None = None, available: bool | None = None) -> dict:
    global _mqtt_enabled, _mqtt_available
    if available is not None:
        _mqtt_available = bool(available)
    if enabled is not None:
        _mqtt_enabled = bool(enabled)
    _broadcast(_format_event("mqtt_state", get_mqtt_state()), persist=False)
    return get_mqtt_state()


def register_brokers(urls: list[str]) -> None:
    """Call once at startup with the configured broker URLs."""
    global _broker_status
    _broker_status = {}
    for url in urls:
        _broker_status[url] = {"url": url, "connected": False}


def update_broker_status(host: str, port: int, connected: bool) -> None:
    for entry in _broker_status.values():
        if host in entry["url"] and str(port) in entry["url"]:
            entry["connected"] = connected
    _broadcast(_format_event("broker_status", list(_broker_status.values())), persist=False)


def broadcast_frame(frame: dict) -> None:
    _broadcast(_format_event("frame", frame), persist=True)


def broadcast_stats(stats: dict) -> None:
    global _last_stats
    _last_stats = stats
    _broadcast(_format_event("stats", stats), persist=False)


def broadcast_serial_state(connected: bool, port: str = "") -> None:
    global _serial_connected, _serial_port
    _serial_connected = connected
    _serial_port      = port
    _broadcast(_format_event("serial_state", {"connected": connected, "port": port}),
               persist=False)


def set_record_callback(fn) -> None:
    global _record_callback
    _record_callback = fn


def update_record_state(state: dict) -> None:
    global _record_state
    _record_state = state
    _broadcast(_format_event("record_state", state), persist=False)


def get_record_state() -> dict:
    return _record_state


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

def _format_event(name: str, data) -> bytes:
    payload = json.dumps(data, separators=(",", ":"))
    return f"event: {name}\ndata: {payload}\n\n".encode("utf-8")


def _broadcast(msg: bytes, persist: bool) -> None:
    with _subs_lock:
        if persist:
            _history.append(msg)
        dead = []
        for q in _subscribers:
            try:
                q.put_nowait(msg)
            except queue.Full:
                dead.append(q)
        for q in dead:
            _subscribers.remove(q)


# ---------------------------------------------------------------------------
# HTTP handler
# ---------------------------------------------------------------------------

class _Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        logging.debug("dashboard %s - %s", self.address_string(), fmt % args)

    def do_GET(self):  # noqa: N802
        if self.path in ("/", "/index.html"):
            self._serve_html()
        elif self.path == "/events":
            self._serve_sse()
        elif self.path == "/api/mqtt":
            self._serve_json(get_mqtt_state())
        elif self.path == "/api/stats":
            self._serve_json(_last_stats)
        elif self.path == "/api/record":
            self._serve_json(_record_state)
        elif self.path == "/api/brokers":
            self._serve_json(list(_broker_status.values()))
        else:
            self.send_error(404)

    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length") or 0)
        raw    = self.rfile.read(length) if length > 0 else b"{}"
        try:
            body = json.loads(raw.decode("utf-8") or "{}")
        except (UnicodeDecodeError, json.JSONDecodeError):
            self.send_error(400, "invalid JSON"); return

        if self.path == "/api/mqtt":
            if "enabled" not in body:
                self.send_error(400, "missing 'enabled'"); return
            self._serve_json(set_mqtt_state(enabled=bool(body["enabled"])))

        elif self.path == "/api/record":
            action   = body.get("action", "")
            filename = body.get("file", "")
            if action not in ("start", "stop"):
                self.send_error(400, "action must be 'start' or 'stop'"); return
            if _record_callback:
                result = _record_callback(action, filename)
                self._serve_json(result)
            else:
                self.send_error(503, "recording not available")

        else:
            self.send_error(404)

    def _serve_json(self, data):
        body = json.dumps(data).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.write(body)

    def _serve_html(self):
        try:
            with open(_HTML_PATH, "rb") as f:
                body = f.read()
        except OSError:
            self.send_error(500, "dashboard.html not found"); return
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.write(body)

    def _serve_sse(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.send_header("X-Accel-Buffering", "no")
        self.end_headers()

        q: queue.Queue = queue.Queue(maxsize=1000)
        with _subs_lock:
            backlog = list(_history)
            _subscribers.append(q)

        try:
            self.wfile.write(_format_event("hello", {
                "node_id": _node_id,
                "mqtt":    get_mqtt_state(),
                "serial":  {"connected": _serial_connected, "port": _serial_port},
                "stats":   _last_stats,
                "brokers": list(_broker_status.values()),
                "record":  _record_state,
            }))
            for msg in backlog:
                self.wfile.write(msg)
            self.wfile.flush()

            while True:
                try:
                    msg = q.get(timeout=15)
                except queue.Empty:
                    self.wfile.write(b": ping\n\n")
                    self.wfile.flush()
                    continue
                self.wfile.write(msg)
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError):
            pass
        finally:
            with _subs_lock:
                if q in _subscribers:
                    _subscribers.remove(q)


# ---------------------------------------------------------------------------
# Server startup
# ---------------------------------------------------------------------------

def start(port: int, node_id: str = "?") -> ThreadingHTTPServer:
    global _node_id
    _node_id = node_id
    server   = ThreadingHTTPServer(("127.0.0.1", port), _Handler)
    t = threading.Thread(target=server.serve_forever, name="dashboard-http", daemon=True)
    t.start()
    logging.info("dashboard listening on http://127.0.0.1:%d", port)
    return server
