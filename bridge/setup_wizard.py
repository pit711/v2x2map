"""
Tkinter setup wizard for the V2X2MAP ITS-G5 Receiver bridge.

Steps:
  1. Device        — pick the COM port
  2. Flash         — write bundled firmware (skippable)
  3. Node-ID       — read chip MAC → V2X2MAP<last4>
  4. WiFi Networks — up to 2 SSIDs + "any open AP" fallback
  5. Network       — DHCP or static IP / gateway / DNS
  6. Connection    — BLE only / WiFi / WiFi + BLE
  7. Sniffer mode  — realtime / cycle / individual (cycle timing)
  8. Apply         — write all config to device via USB CFG channel

Returns dict {port, node_id} to the caller (bridge CLI args).
"""

from __future__ import annotations

import io
import os
import re
import sys
import threading
import time
import tkinter as tk
from tkinter import messagebox, scrolledtext, ttk

import serial
import serial.tools.list_ports

FIRMWARE_FILES: list[tuple[str, str]] = [
    ("0x2000",  "bootloader.bin"),
    ("0x8000",  "partition-table.bin"),
    ("0x1e000", "ota_data_initial.bin"),
    ("0x20000", "firmware.bin"),
]
FLASH_BAUD = "921600"
CHIP = "esp32c5"

# Connection mode values (must match firmware CONN_MODE_* constants)
CONN_BLE  = 0
CONN_WIFI = 1
CONN_BOTH = 2

# Sniffer mode values (must match firmware SNIFF_MODE_* constants)
SNIFF_REALTIME   = 0
SNIFF_CYCLE      = 1
SNIFF_INDIVIDUAL = 2


def _resource_path(*parts: str) -> str:
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, *parts)


class _UiWriter:
    def __init__(self, widget: tk.Text, root: tk.Tk):
        self._widget = widget; self._root = root; self._buf = ""

    def write(self, s: str) -> int:
        self._buf += s
        if "\n" in self._buf or "\r" in self._buf:
            chunk, self._buf = self._buf, ""
            self._root.after(0, self._append, chunk)
        return len(s)

    def flush(self) -> None:
        if self._buf:
            chunk, self._buf = self._buf, ""
            self._root.after(0, self._append, chunk)

    def _append(self, chunk: str) -> None:
        self._widget.insert("end", chunk)
        self._widget.see("end")


class SetupWizard:
    def __init__(self) -> None:
        self.result: dict | None = None

        self.root = tk.Tk()
        self.root.title("V2X2MAP — Setup")
        self.root.geometry("640x580")
        self.root.minsize(640, 520)

        # Step 1 / 2
        self.port_var      = tk.StringVar()
        self.skip_flash    = tk.BooleanVar(value=False)
        self._port_label_to_device: dict[str, str] = {}

        # Step 3
        self.node_id_var   = tk.StringVar()

        # Step 4 — WiFi networks
        self.wifi1_ssid    = tk.StringVar()
        self.wifi1_pass    = tk.StringVar()
        self.wifi2_ssid    = tk.StringVar()
        self.wifi2_pass    = tk.StringVar()
        self.wifi_open     = tk.BooleanVar(value=False)

        # Step 5 — Network
        self.use_dhcp      = tk.BooleanVar(value=True)
        self.static_ip     = tk.StringVar()
        self.static_nm     = tk.StringVar(value="255.255.255.0")
        self.static_gw     = tk.StringVar()
        self.static_dns    = tk.StringVar()

        # Step 6 — Connection mode
        self.conn_mode     = tk.IntVar(value=CONN_BLE)  # default: BLE only

        # Step 7 — Sniffer mode
        self.sniff_mode    = tk.IntVar(value=SNIFF_CYCLE)
        self.sniff_ms      = tk.StringVar(value="10000")
        self.wifi_ms_var   = tk.StringVar(value="2000")

        self._busy = False
        self._build()

    # ── UI ──────────────────────────────────────────────────────────────

    def _build(self) -> None:
        nb = ttk.Notebook(self.root)
        nb.pack(fill="both", expand=True, padx=10, pady=10)
        self.nb = nb
        self._build_step1(nb)
        self._build_step2(nb)
        self._build_step3(nb)
        self._build_step4(nb)
        self._build_step5(nb)
        self._build_step6(nb)
        self._build_step7(nb)
        self._build_step8(nb)
        nb.bind("<<NotebookTabChanged>>", lambda e: None)

    # ── Step 1: Device ──────────────────────────────────────────────────
    def _build_step1(self, nb):
        f = ttk.Frame(nb, padding=20); nb.add(f, text="1. Device")
        ttk.Label(f, text="V2X2MAP Setup", font=("Segoe UI", 14, "bold")).pack(anchor="w")
        ttk.Label(f, text="Connect the ESP32-C5 via USB and pick the COM port.",
                  wraplength=560, justify="left").pack(anchor="w", pady=(8, 16))
        row = ttk.Frame(f); row.pack(fill="x")
        ttk.Label(row, text="COM port:").pack(side="left")
        self.port_combo = ttk.Combobox(row, textvariable=self.port_var, state="readonly")
        self.port_combo.pack(side="left", padx=(8,8), fill="x", expand=True)
        ttk.Button(row, text="Refresh", command=self._refresh_ports).pack(side="left")
        ttk.Checkbutton(f, text="Skip firmware flash (already flashed)",
                        variable=self.skip_flash).pack(anchor="w", pady=(20,0))
        bar = ttk.Frame(f); bar.pack(side="bottom", fill="x", pady=(20,0))
        ttk.Button(bar, text="Cancel", command=self._cancel).pack(side="right")
        ttk.Button(bar, text="Next >", command=self._goto_step2).pack(side="right", padx=(0,8))
        self._refresh_ports()

    # ── Step 2: Flash ───────────────────────────────────────────────────
    def _build_step2(self, nb):
        f = ttk.Frame(nb, padding=20); nb.add(f, text="2. Flash")
        ttk.Label(f, text="Flash firmware", font=("Segoe UI", 12, "bold")).pack(anchor="w")
        ttk.Label(f, text="Writes bootloader, partition table and application. Takes 30–60 s.",
                  wraplength=560, justify="left").pack(anchor="w", pady=(4,12))
        self.flash_status   = ttk.Label(f, text="Ready.")
        self.flash_status.pack(anchor="w")
        self.flash_progress = ttk.Progressbar(f, mode="determinate")
        self.flash_progress.pack(fill="x", pady=(0,8))
        self.flash_log = scrolledtext.ScrolledText(f, height=12, font=("Consolas",9), wrap="none")
        self.flash_log.pack(fill="both", expand=True)
        bar = ttk.Frame(f); bar.pack(side="bottom", fill="x", pady=(12,0))
        self.flash_btn      = ttk.Button(bar, text="Start flash", command=self._start_flash)
        self.flash_btn.pack(side="right", padx=(0,8))
        self.flash_next_btn = ttk.Button(bar, text="Next >", command=self._goto_step3, state="disabled")
        self.flash_next_btn.pack(side="right", padx=(0,8))
        ttk.Button(bar, text="< Back", command=lambda: self.nb.select(0)).pack(side="right")

    # ── Step 3: Node-ID ─────────────────────────────────────────────────
    def _build_step3(self, nb):
        f = ttk.Frame(nb, padding=20); nb.add(f, text="3. Node-ID")
        ttk.Label(f, text="Node-ID", font=("Segoe UI", 12, "bold")).pack(anchor="w")
        ttk.Label(f, text="Identifies this device in MQTT topic its/<node-id>/packet.\n"
                          "Default: V2X2MAP + last 4 MAC hex characters.",
                  wraplength=560, justify="left").pack(anchor="w", pady=(4,12))
        self.mac_status = ttk.Label(f, text="MAC not yet read.")
        self.mac_status.pack(anchor="w")
        ttk.Button(f, text="Read MAC from chip", command=self._read_mac).pack(anchor="w", pady=(8,16))
        row = ttk.Frame(f); row.pack(fill="x")
        ttk.Label(row, text="Node-ID:").pack(side="left")
        ttk.Entry(row, textvariable=self.node_id_var, width=24).pack(side="left", padx=(8,0))
        self.mac_log = scrolledtext.ScrolledText(f, height=7, font=("Consolas",9), wrap="none")
        self.mac_log.pack(fill="both", expand=True, pady=(16,0))
        bar = ttk.Frame(f); bar.pack(side="bottom", fill="x", pady=(12,0))
        ttk.Button(bar, text="Next >", command=lambda: self.nb.select(3)).pack(side="right", padx=(0,8))
        ttk.Button(bar, text="< Back", command=lambda: self.nb.select(1)).pack(side="right")

    # ── Step 4: WiFi Networks ───────────────────────────────────────────
    def _build_step4(self, nb):
        f = ttk.Frame(nb, padding=20); nb.add(f, text="4. WiFi")
        ttk.Label(f, text="WiFi networks", font=("Segoe UI", 12, "bold")).pack(anchor="w")
        ttk.Label(f, text="The device tries these in order. Leave SSID blank to skip.",
                  wraplength=560).pack(anchor="w", pady=(4,14))

        def wifi_row(parent, label, ssid_var, pass_var):
            ttk.Label(parent, text=label, font=("Segoe UI", 9, "bold")).pack(anchor="w")
            r = ttk.Frame(parent); r.pack(fill="x", pady=(2,8))
            ttk.Label(r, text="SSID:", width=10).grid(row=0, column=0, sticky="w")
            ttk.Entry(r, textvariable=ssid_var, width=30).grid(row=0, column=1, sticky="w", padx=(4,0))
            ttk.Label(r, text="Password:", width=10).grid(row=1, column=0, sticky="w", pady=(4,0))
            ttk.Entry(r, textvariable=pass_var, show="*", width=30).grid(row=1, column=1, sticky="w", padx=(4,0))

        wifi_row(f, "Network 1 (preferred):", self.wifi1_ssid, self.wifi1_pass)
        wifi_row(f, "Network 2 (fallback):",  self.wifi2_ssid, self.wifi2_pass)
        ttk.Checkbutton(f, text="Connect to any open (unauthenticated) AP as last resort",
                        variable=self.wifi_open).pack(anchor="w", pady=(4,0))

        bar = ttk.Frame(f); bar.pack(side="bottom", fill="x", pady=(12,0))
        ttk.Button(bar, text="Next >", command=lambda: self.nb.select(4)).pack(side="right", padx=(0,8))
        ttk.Button(bar, text="< Back", command=lambda: self.nb.select(2)).pack(side="right")

    # ── Step 5: Network ─────────────────────────────────────────────────
    def _build_step5(self, nb):
        f = ttk.Frame(nb, padding=20); nb.add(f, text="5. Network")
        ttk.Label(f, text="IP settings", font=("Segoe UI", 12, "bold")).pack(anchor="w")
        ttk.Label(f, text="DHCP is recommended for most home networks.",
                  wraplength=560).pack(anchor="w", pady=(4,14))

        ttk.Radiobutton(f, text="DHCP (automatic)", variable=self.use_dhcp, value=True,
                        command=self._on_dhcp_toggle).pack(anchor="w")
        ttk.Radiobutton(f, text="Static IP", variable=self.use_dhcp, value=False,
                        command=self._on_dhcp_toggle).pack(anchor="w", pady=(4,10))

        self.static_frame = ttk.Frame(f)
        self.static_frame.pack(fill="x", padx=(20,0))
        fields = [("IP address:", self.static_ip), ("Subnet mask:", self.static_nm),
                  ("Gateway:", self.static_gw), ("DNS server:", self.static_dns)]
        for i, (label, var) in enumerate(fields):
            ttk.Label(self.static_frame, text=label, width=14).grid(row=i, column=0, sticky="w", pady=3)
            ttk.Entry(self.static_frame, textvariable=var, width=18).grid(row=i, column=1, sticky="w", padx=(4,0))
        self._on_dhcp_toggle()

        bar = ttk.Frame(f); bar.pack(side="bottom", fill="x", pady=(12,0))
        ttk.Button(bar, text="Next >", command=lambda: self.nb.select(5)).pack(side="right", padx=(0,8))
        ttk.Button(bar, text="< Back", command=lambda: self.nb.select(3)).pack(side="right")

    def _on_dhcp_toggle(self):
        state = "disabled" if self.use_dhcp.get() else "normal"
        for child in self.static_frame.winfo_children():
            try: child.configure(state=state)
            except tk.TclError: pass

    # ── Step 6: Connection mode ──────────────────────────────────────────
    def _build_step6(self, nb):
        f = ttk.Frame(nb, padding=20); nb.add(f, text="6. Connection")
        ttk.Label(f, text="Connection mode", font=("Segoe UI", 12, "bold")).pack(anchor="w")
        ttk.Label(f, text="How the device communicates with the app / bridge.",
                  wraplength=560).pack(anchor="w", pady=(4,14))

        opts = [
            (CONN_BLE,  "BLE + USB (default)",
             "Stream frames to Android app or PC via Bluetooth LE and USB-Serial. No WiFi."),
            (CONN_WIFI, "WiFi",
             "Connect to home WiFi and publish frames to MQTT. No BLE stream."),
            (CONN_BOTH, "WiFi + BLE",
             "WiFi MQTT publishing and BLE streaming. Radio is time-shared."),
        ]
        for val, label, hint in opts:
            ttk.Radiobutton(f, text=label, variable=self.conn_mode, value=val,
                            command=self._on_conn_changed).pack(anchor="w", pady=(0,2))
            ttk.Label(f, text="   " + hint, foreground="gray",
                      wraplength=540, justify="left").pack(anchor="w", pady=(0,8))

        bar = ttk.Frame(f); bar.pack(side="bottom", fill="x", pady=(12,0))
        ttk.Button(bar, text="Next >", command=lambda: self.nb.select(6)).pack(side="right", padx=(0,8))
        ttk.Button(bar, text="< Back", command=lambda: self.nb.select(4)).pack(side="right")

    def _on_conn_changed(self):
        pass  # sniffer step handles enabling/disabling itself

    # ── Step 7: Sniffer mode ─────────────────────────────────────────────
    def _build_step7(self, nb):
        f = ttk.Frame(nb, padding=20); nb.add(f, text="7. Sniffer")
        ttk.Label(f, text="Sniffer mode (WiFi only)", font=("Segoe UI", 12, "bold")).pack(anchor="w")
        ttk.Label(f, text="Only relevant when WiFi is selected. Ignored for BLE-only mode.",
                  wraplength=560).pack(anchor="w", pady=(4,14))

        modes = [
            (SNIFF_REALTIME,   "Realtime (slow)",
             "STA + promiscuous on the AP's channel simultaneously. Low latency, "
             "sniffs only AP channel — good for home monitoring."),
            (SNIFF_CYCLE,      "Cycle (balanced) — recommended",
             "Sniff for 10 s, pause 2 s for WiFi/MQTT flush. "
             "Captures ITS-G5 on the configured channel with minimal gaps."),
            (SNIFF_INDIVIDUAL, "Individual (custom timing)",
             "Set your own sniff and WiFi window durations below."),
        ]
        for val, label, hint in modes:
            ttk.Radiobutton(f, text=label, variable=self.sniff_mode, value=val,
                            command=self._on_sniff_changed).pack(anchor="w", pady=(0,2))
            ttk.Label(f, text="   " + hint, foreground="gray",
                      wraplength=540, justify="left").pack(anchor="w", pady=(0,8))

        self.timing_frame = ttk.LabelFrame(f, text="Custom timing (Individual mode)", padding=10)
        self.timing_frame.pack(fill="x", pady=(8,0))
        ttk.Label(self.timing_frame, text="Sniff window (ms):").grid(row=0, column=0, sticky="w")
        ttk.Entry(self.timing_frame, textvariable=self.sniff_ms, width=10).grid(row=0, column=1, padx=(8,0))
        ttk.Label(self.timing_frame, text="WiFi window (ms):").grid(row=1, column=0, sticky="w", pady=(6,0))
        ttk.Entry(self.timing_frame, textvariable=self.wifi_ms_var, width=10).grid(row=1, column=1, padx=(8,0), pady=(6,0))
        self._on_sniff_changed()

        bar = ttk.Frame(f); bar.pack(side="bottom", fill="x", pady=(12,0))
        ttk.Button(bar, text="Next >", command=lambda: self.nb.select(7)).pack(side="right", padx=(0,8))
        ttk.Button(bar, text="< Back", command=lambda: self.nb.select(5)).pack(side="right")

    def _on_sniff_changed(self):
        state = "normal" if self.sniff_mode.get() == SNIFF_INDIVIDUAL else "disabled"
        for child in self.timing_frame.winfo_children():
            try: child.configure(state=state)
            except tk.TclError: pass

    # ── Step 8: Apply ────────────────────────────────────────────────────
    def _build_step8(self, nb):
        f = ttk.Frame(nb, padding=20); nb.add(f, text="8. Apply")
        ttk.Label(f, text="Write config to device", font=("Segoe UI", 12, "bold")).pack(anchor="w")
        ttk.Label(f, text="Click 'Apply' to send all settings to the device via the USB config channel. "
                          "Then click 'Finish' to start the bridge.",
                  wraplength=560, justify="left").pack(anchor="w", pady=(4,12))

        self.apply_log = scrolledtext.ScrolledText(f, height=14, font=("Consolas",9), wrap="none")
        self.apply_log.pack(fill="both", expand=True)

        bar = ttk.Frame(f); bar.pack(side="bottom", fill="x", pady=(12,0))
        self.finish_btn = ttk.Button(bar, text="Finish — start bridge",
                                     command=self._finish, state="disabled")
        self.finish_btn.pack(side="right", padx=(0,8))
        self.apply_btn  = ttk.Button(bar, text="Apply config", command=self._apply_config)
        self.apply_btn.pack(side="right", padx=(0,8))
        ttk.Button(bar, text="< Back", command=lambda: self.nb.select(6)).pack(side="right")

    # ── COM port ─────────────────────────────────────────────────────────
    def _refresh_ports(self):
        ports = serial.tools.list_ports.comports()
        labels: list[str] = []
        self._port_label_to_device.clear()
        for p in ports:
            label = f"{p.device}  -  {p.description}"
            labels.append(label)
            self._port_label_to_device[label] = p.device
        self.port_combo["values"] = labels
        if labels:
            if not self.port_var.get() or self.port_var.get() not in self._port_label_to_device:
                self.port_var.set(labels[0])
        else:
            self.port_var.set("")

    def _selected_port(self) -> str:
        return self._port_label_to_device.get(self.port_var.get(), "")

    # ── Navigation ───────────────────────────────────────────────────────
    def _goto_step2(self):
        if not self._selected_port():
            messagebox.showerror("Error", "Please select a COM port first.")
            return
        self.nb.select(2 if self.skip_flash.get() else 1)

    def _goto_step3(self):
        self.nb.select(2)

    def _cancel(self):
        if self._busy: return
        self.result = None
        self.root.destroy()

    # ── Flash ─────────────────────────────────────────────────────────────
    def _start_flash(self):
        if self._busy: return
        port = self._selected_port()
        if not port:
            messagebox.showerror("Error", "No COM port selected."); return
        for _, name in FIRMWARE_FILES:
            path = _resource_path("firmware", name)
            if not os.path.isfile(path):
                messagebox.showerror("Error", f"Firmware file missing: {name}"); return
        self._busy = True
        self.flash_btn.configure(state="disabled")
        self.flash_next_btn.configure(state="disabled")
        self.flash_status.configure(text=f"Flashing to {port} …")
        self.flash_log.delete("1.0", "end")
        self.flash_progress.configure(mode="indeterminate")
        self.flash_progress.start(60)
        threading.Thread(target=self._flash_worker, args=(port,), daemon=True).start()

    def _flash_worker(self, port):
        args = ["--chip", CHIP, "--port", port, "--baud", FLASH_BAUD,
                "--before", "default-reset", "--after", "hard-reset",
                "write_flash", "--flash-mode", "dio", "--flash-size", "4MB", "--flash-freq", "80m"]
        for off, name in FIRMWARE_FILES:
            args += [off, _resource_path("firmware", name)]
        ok, msg = self._run_esptool(args, self.flash_log)
        self.root.after(0, self._on_flash_done, ok, msg)

    def _on_flash_done(self, ok, msg):
        self._busy = False
        self.flash_progress.stop()
        self.flash_progress.configure(mode="determinate", value=100 if ok else 0)
        self.flash_btn.configure(state="normal")
        if ok:
            self.flash_status.configure(text="Flash succeeded.")
            self.flash_next_btn.configure(state="normal")
            self.nb.select(2)
        else:
            self.flash_status.configure(text=f"Flash failed: {msg}")

    # ── Read MAC ─────────────────────────────────────────────────────────
    def _read_mac(self):
        if self._busy: return
        port = self._selected_port()
        if not port:
            messagebox.showerror("Error", "No COM port selected."); return
        self._busy = True
        self.mac_status.configure(text=f"Reading MAC from {port} …")
        self.mac_log.delete("1.0", "end")
        threading.Thread(target=self._mac_worker, args=(port,), daemon=True).start()

    def _mac_worker(self, port):
        buf = io.StringIO()
        class _Tee:
            def __init__(self, w):
                self.w = w
            def write(self, s):
                buf.write(s); return self.w.write(s)
            def flush(self): self.w.flush()
        ok, msg = self._run_esptool(["--chip", CHIP, "--port", port, "read_mac"],
                                    self.mac_log, tee=_Tee)
        text = buf.getvalue()
        m = re.search(r"\b([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})\b", text)
        mac = m.group(1) if m else None
        self.root.after(0, self._on_mac_done, ok, msg, mac)

    def _on_mac_done(self, ok, msg, mac):
        self._busy = False
        if ok and mac:
            self.mac_status.configure(text=f"MAC: {mac}")
            clean = mac.replace(":", "").lower()
            # New default: V2X2MAP + last 4 hex chars (last 2 bytes of MAC)
            self.node_id_var.set("V2X2MAP" + clean[-4:])
        elif ok:
            self.mac_status.configure(text="MAC not found in esptool output.")
        else:
            self.mac_status.configure(text=f"MAC read failed: {msg}")

    # ── Apply config ─────────────────────────────────────────────────────
    def _apply_config(self):
        if self._busy: return
        port = self._selected_port()
        if not port:
            messagebox.showerror("Error", "No COM port selected."); return
        node = self.node_id_var.get().strip()
        if not re.fullmatch(r"V2X2MAP[0-9a-fA-F]{4}|[0-9a-fA-F]{12}", node, re.I):
            messagebox.showerror("Error",
                "Node-ID must be V2X2MAP + 4 hex chars (e.g. V2X2MAPa1b2) "
                "or a full 12-hex MAC."); return

        # Build config dict
        cfg: dict[str, str] = {"nodeid": node.lower()}
        if self.wifi1_ssid.get().strip():
            cfg["wifi1ssid"] = self.wifi1_ssid.get().strip()
            cfg["wifi1pass"] = self.wifi1_pass.get()
        if self.wifi2_ssid.get().strip():
            cfg["wifi2ssid"] = self.wifi2_ssid.get().strip()
            cfg["wifi2pass"] = self.wifi2_pass.get()
        cfg["wifiopen"] = "1" if self.wifi_open.get() else "0"
        cfg["connmode"] = str(self.conn_mode.get())
        if not self.use_dhcp.get() and self.static_ip.get().strip():
            cfg["wifiip"]  = self.static_ip.get().strip()
            cfg["wifinm"]  = self.static_nm.get().strip()
            cfg["wifigw"]  = self.static_gw.get().strip()
            cfg["wifidns"] = self.static_dns.get().strip()
        cfg["sniffmode"] = str(self.sniff_mode.get())
        cfg["sniffms"]   = self.sniff_ms.get().strip() or "10000"
        cfg["wifims"]    = self.wifi_ms_var.get().strip() or "2000"

        self._busy = True
        self.apply_btn.configure(state="disabled")
        self.apply_log.delete("1.0", "end")
        threading.Thread(target=self._apply_worker, args=(port, cfg), daemon=True).start()

    def _apply_worker(self, port: str, cfg: dict):
        def log(s):
            self.root.after(0, lambda: (
                self.apply_log.insert("end", s + "\n"),
                self.apply_log.see("end")
            ))

        log(f"Opening {port} …")
        try:
            ser = serial.Serial(port, 115200, timeout=2)
            ser.dtr = False; ser.rts = False
            time.sleep(0.5)   # let device boot if just flashed
            ser.reset_input_buffer()
        except Exception as e:
            log(f"ERROR: {e}")
            self.root.after(0, self._on_apply_done, False)
            return

        ok = True
        for key, val in cfg.items():
            cmd = f"CFG:{key}={val}\n"
            ser.write(cmd.encode())
            # Read response (up to 2 s)
            resp = b""
            deadline = time.time() + 2
            while time.time() < deadline:
                chunk = ser.read(64)
                if chunk: resp += chunk
                if b"CFG_OK" in resp or b"CFG_ERR" in resp:
                    break
            resp_str = resp.decode("utf-8", errors="replace").strip()
            if f"CFG_OK:{key}" in resp_str:
                log(f"  {key} = {val if 'pass' not in key else '***'}  ✓")
            else:
                log(f"  {key}: {resp_str or 'no response'}")
                if "pass" not in key:   # password errors are non-fatal
                    ok = False

        ser.close()
        log("\nDone." if ok else "\nSome settings failed — check log above.")
        self.root.after(0, self._on_apply_done, ok)

    def _on_apply_done(self, ok: bool):
        self._busy = False
        self.apply_btn.configure(state="normal")
        if ok:
            self.finish_btn.configure(state="normal")

    # ── Finish ────────────────────────────────────────────────────────────
    def _finish(self):
        port = self._selected_port()
        node = self.node_id_var.get().strip().lower()
        if not port:
            messagebox.showerror("Error", "No COM port selected."); return
        self.result = {"port": port, "node_id": node}
        self.root.destroy()

    # ── esptool runner ────────────────────────────────────────────────────
    def _run_esptool(self, args, log_widget, tee=None):
        writer = _UiWriter(log_widget, self.root)
        sink = tee(writer) if tee else writer
        old_out, old_err = sys.stdout, sys.stderr
        sys.stdout = sys.stderr = sink
        try:
            try:
                import esptool
            except ImportError as e:
                return False, f"esptool not in bundle: {e}"
            try:
                esptool.main(args)
            except SystemExit as exc:
                code = exc.code
                if code in (0, None): return True, ""
                return False, f"esptool exit {code}"
            except Exception as exc:
                return False, str(exc)
            return True, ""
        finally:
            try: sink.flush()
            except Exception: pass
            sys.stdout, sys.stderr = old_out, old_err

    # ── run ───────────────────────────────────────────────────────────────
    def run(self) -> dict | None:
        self.root.protocol("WM_DELETE_WINDOW", self._cancel)
        self.root.mainloop()
        return self.result


def run_wizard() -> dict | None:
    return SetupWizard().run()
