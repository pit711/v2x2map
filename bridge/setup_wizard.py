"""
Tkinter setup wizard for the V2X2MAP ITS-G5 Receiver bridge.

Steps:
  1. Device   — pick the COM port
  2. Flash    — write bundled firmware (skippable)
  3. Node-ID  — read chip MAC → V2X2MAP:<mac>
  4. Apply    — write nodeid config to device, configure MQTT brokers

Returns dict {port, node_id, brokers} to the caller (bridge CLI args).
"""

from __future__ import annotations

import io
import json
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
CHIP       = "esp32c5"

DEFAULT_BROKER = "mqtts://cits1.opentrafficmap.org:8883"
CONFIG_FILE    = "v2x2map.cfg"


def _config_path() -> str:
    base = os.path.dirname(sys.executable if getattr(sys, "frozen", False)
                           else os.path.abspath(__file__))
    d = os.path.join(base, "config")
    os.makedirs(d, exist_ok=True)
    return os.path.join(d, CONFIG_FILE)


def _save_config(cfg: dict) -> None:
    try:
        with open(_config_path(), "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2, ensure_ascii=False)
    except Exception as exc:
        print(f"Warning: could not save config: {exc}")


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
        self.root.geometry("620x500")
        self.root.minsize(600, 440)

        self.port_var   = tk.StringVar()
        self.skip_flash = tk.BooleanVar(value=False)
        self._port_label_to_device: dict[str, str] = {}
        self.node_id_var  = tk.StringVar()
        self.auto_start   = tk.BooleanVar(value=True)

        self._busy = False
        self._build()

    # ── UI ──────────────────────────────────────────────────────────────────

    def _build(self) -> None:
        nb = ttk.Notebook(self.root)
        nb.pack(fill="both", expand=True, padx=10, pady=10)
        self.nb = nb
        self._build_step1(nb)
        self._build_step2(nb)
        self._build_step3(nb)
        self._build_step4(nb)

    # ── Step 1: Device ───────────────────────────────────────────────────────
    def _build_step1(self, nb):
        f = ttk.Frame(nb, padding=20); nb.add(f, text="1. Device")
        ttk.Label(f, text="V2X2MAP Setup", font=("Segoe UI", 14, "bold")).pack(anchor="w")
        ttk.Label(f, text="Connect the ESP32-C5 via USB and select the COM port.",
                  wraplength=540, justify="left").pack(anchor="w", pady=(8, 16))
        row = ttk.Frame(f); row.pack(fill="x")
        ttk.Label(row, text="COM port:").pack(side="left")
        self.port_combo = ttk.Combobox(row, textvariable=self.port_var, state="readonly")
        self.port_combo.pack(side="left", padx=(8, 8), fill="x", expand=True)
        ttk.Button(row, text="Refresh", command=self._refresh_ports).pack(side="left")
        ttk.Checkbutton(f, text="Skip firmware flash (already flashed)",
                        variable=self.skip_flash).pack(anchor="w", pady=(20, 0))
        bar = ttk.Frame(f); bar.pack(side="bottom", fill="x", pady=(20, 0))
        ttk.Button(bar, text="Cancel", command=self._cancel).pack(side="right")
        ttk.Button(bar, text="Next >", command=self._goto_step2).pack(side="right", padx=(0, 8))
        self._refresh_ports()

    # ── Step 2: Flash ────────────────────────────────────────────────────────
    def _build_step2(self, nb):
        f = ttk.Frame(nb, padding=20); nb.add(f, text="2. Flash")
        ttk.Label(f, text="Flash firmware", font=("Segoe UI", 12, "bold")).pack(anchor="w")
        ttk.Label(f, text="Writes bootloader, partition table and application. Takes 30-60 s.",
                  wraplength=540, justify="left").pack(anchor="w", pady=(4, 12))
        self.flash_status   = ttk.Label(f, text="Ready.")
        self.flash_status.pack(anchor="w")
        self.flash_progress = ttk.Progressbar(f, mode="determinate")
        self.flash_progress.pack(fill="x", pady=(0, 8))
        self.flash_log = scrolledtext.ScrolledText(f, height=12, font=("Consolas", 9), wrap="none")
        self.flash_log.pack(fill="both", expand=True)
        bar = ttk.Frame(f); bar.pack(side="bottom", fill="x", pady=(12, 0))
        self.flash_btn      = ttk.Button(bar, text="Start flash", command=self._start_flash)
        self.flash_btn.pack(side="right", padx=(0, 8))
        self.flash_next_btn = ttk.Button(bar, text="Next >",
                                         command=lambda: self.nb.select(2), state="disabled")
        self.flash_next_btn.pack(side="right", padx=(0, 8))
        ttk.Button(bar, text="< Back", command=lambda: self.nb.select(0)).pack(side="right")

    # ── Step 3: Node-ID ──────────────────────────────────────────────────────
    def _build_step3(self, nb):
        f = ttk.Frame(nb, padding=20); nb.add(f, text="3. Node-ID")
        ttk.Label(f, text="Node-ID", font=("Segoe UI", 12, "bold")).pack(anchor="w")
        ttk.Label(f, text="Identifies this device in the MQTT topic its/<node-id>/packet.\n"
                          "Format: V2X2MAP:<12-hex MAC>  (filled automatically after reading MAC).",
                  wraplength=540, justify="left").pack(anchor="w", pady=(4, 12))
        self.mac_status = ttk.Label(f, text="MAC not yet read.")
        self.mac_status.pack(anchor="w")
        ttk.Button(f, text="Read MAC from chip", command=self._read_mac).pack(anchor="w", pady=(8, 16))
        row = ttk.Frame(f); row.pack(fill="x")
        ttk.Label(row, text="Node-ID:").pack(side="left")
        ttk.Entry(row, textvariable=self.node_id_var, width=28).pack(side="left", padx=(8, 0))
        self.mac_log = scrolledtext.ScrolledText(f, height=7, font=("Consolas", 9), wrap="none")
        self.mac_log.pack(fill="both", expand=True, pady=(16, 0))
        bar = ttk.Frame(f); bar.pack(side="bottom", fill="x", pady=(12, 0))
        ttk.Button(bar, text="Next >", command=lambda: self.nb.select(3)).pack(side="right", padx=(0, 8))
        ttk.Button(bar, text="< Back", command=lambda: self.nb.select(1)).pack(side="right")

    # ── Step 4: Apply ────────────────────────────────────────────────────────
    def _build_step4(self, nb):
        f = ttk.Frame(nb, padding=20); nb.add(f, text="4. Apply")
        ttk.Label(f, text="Write configuration", font=("Segoe UI", 12, "bold")).pack(anchor="w")
        ttk.Label(f, text="'Apply' sends the node-id to the device and saves the configuration.\n"
                          "'Finish' optionally launches the bridge.",
                  wraplength=540, justify="left").pack(anchor="w", pady=(4, 8))

        # MQTT broker list
        ttk.Label(f, text="MQTT brokers (one URL per line):",
                  font=("Segoe UI", 9, "bold")).pack(anchor="w", pady=(4, 2))
        self.broker_text = scrolledtext.ScrolledText(f, height=4, font=("Consolas", 9), wrap="none")
        self.broker_text.insert("end", DEFAULT_BROKER)
        self.broker_text.pack(fill="x", pady=(0, 8))
        ttk.Label(f, text="Format: mqtts://host:port  or  mqtt://host:port",
                  foreground="gray").pack(anchor="w", pady=(0, 8))

        self.apply_log = scrolledtext.ScrolledText(f, height=8, font=("Consolas", 9), wrap="none")
        self.apply_log.pack(fill="both", expand=True)

        ttk.Checkbutton(f, text="Launch bridge automatically (its-g5-bridge.exe)",
                        variable=self.auto_start).pack(anchor="w", pady=(8, 0))

        bar = ttk.Frame(f); bar.pack(side="bottom", fill="x", pady=(12, 0))
        self.finish_btn = ttk.Button(bar, text="Finish",
                                     command=self._finish, state="disabled")
        self.finish_btn.pack(side="right", padx=(0, 8))
        self.apply_btn  = ttk.Button(bar, text="Apply", command=self._apply_config)
        self.apply_btn.pack(side="right", padx=(0, 8))
        ttk.Button(bar, text="< Back", command=lambda: self.nb.select(2)).pack(side="right")

    # ── COM port ─────────────────────────────────────────────────────────────
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

    # ── Navigation ───────────────────────────────────────────────────────────
    def _goto_step2(self):
        if not self._selected_port():
            messagebox.showerror("Error", "Please select a COM port first.")
            return
        self.nb.select(2 if self.skip_flash.get() else 1)

    def _cancel(self):
        if self._busy: return
        self.result = None
        self.root.destroy()

    # ── Flash ─────────────────────────────────────────────────────────────────
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
        self.flash_status.configure(text=f"Flashing {port} ...")
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
            self.flash_status.configure(text="Flash successful.")
            self.flash_next_btn.configure(state="normal")
            self.nb.select(2)
        else:
            self.flash_status.configure(text=f"Flash failed: {msg}")

    # ── Read MAC ──────────────────────────────────────────────────────────────
    def _read_mac(self):
        if self._busy: return
        port = self._selected_port()
        if not port:
            messagebox.showerror("Error", "No COM port selected."); return
        self._busy = True
        self.mac_status.configure(text=f"Reading MAC from {port} ...")
        self.mac_log.delete("1.0", "end")
        threading.Thread(target=self._mac_worker, args=(port,), daemon=True).start()

    def _mac_worker(self, port):
        buf = io.StringIO()
        class _Tee:
            def __init__(self, w): self.w = w
            def write(self, s): buf.write(s); return self.w.write(s)
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
            # Format: V2X2MAP:<full-12-hex-mac>
            self.node_id_var.set("V2X2MAP:" + clean)
        elif ok:
            self.mac_status.configure(text="MAC not found in esptool output.")
        else:
            self.mac_status.configure(text=f"MAC read failed: {msg}")

    # ── Apply config ──────────────────────────────────────────────────────────
    def _apply_config(self):
        if self._busy: return
        port = self._selected_port()
        if not port:
            messagebox.showerror("Error", "No COM port selected."); return
        node = self.node_id_var.get().strip()
        if not re.fullmatch(r"V2X2MAP:[0-9a-fA-F]{12}|[0-9a-fA-F]{12}", node, re.I):
            messagebox.showerror("Error",
                "Node-ID must follow the format V2X2MAP:<12 hex chars>\n"
                "Example: V2X2MAP:d2cf13ed6293"); return

        self._busy = True
        self.apply_btn.configure(state="disabled")
        self.apply_log.delete("1.0", "end")
        threading.Thread(target=self._apply_worker, args=(port, node), daemon=True).start()

    def _apply_worker(self, port: str, node: str):
        def log(s):
            self.root.after(0, lambda: (
                self.apply_log.insert("end", s + "\n"),
                self.apply_log.see("end")
            ))

        log(f"Opening {port} ...")
        try:
            ser = serial.Serial(port, 115200, timeout=2)
            ser.dtr = False; ser.rts = False
            time.sleep(0.5)
            ser.reset_input_buffer()
        except Exception as e:
            log(f"ERROR: {e}")
            self.root.after(0, self._on_apply_done, False)
            return

        ok = True
        cmd = f"CFG:nodeid={node.lower()}\n"
        ser.write(cmd.encode())
        resp = b""
        deadline = time.time() + 2
        while time.time() < deadline:
            chunk = ser.read(64)
            if chunk: resp += chunk
            if b"CFG_OK" in resp or b"CFG_ERR" in resp:
                break
        resp_str = resp.decode("utf-8", errors="replace").strip()
        if "CFG_OK:nodeid" in resp_str:
            log(f"  nodeid = {node.lower()}  OK")
        else:
            log(f"  nodeid: {resp_str or 'no response'}")
            ok = False

        if ok:
            log("\nConfiguration saved.")
        else:
            log("\nWrite error — check output above.")

        try: ser.close()
        except Exception: pass
        self.root.after(0, self._on_apply_done, ok)

    def _on_apply_done(self, ok: bool):
        self._busy = False
        self.apply_btn.configure(state="normal")
        if ok:
            self.finish_btn.configure(state="normal")

    # ── Finish ────────────────────────────────────────────────────────────────
    def _finish(self):
        port = self._selected_port()
        node = self.node_id_var.get().strip().lower()
        if not port:
            messagebox.showerror("Error", "No COM port selected."); return
        brokers = [
            line.strip() for line in self.broker_text.get("1.0", "end").splitlines()
            if line.strip()
        ]
        if not brokers:
            brokers = [DEFAULT_BROKER]

        # Save config file so the bridge can start without wizard next time
        _save_config({"node_id": node, "port": port, "brokers": brokers})

        self.result = {
            "port":       port,
            "node_id":    node,
            "brokers":    brokers,
            "auto_start": self.auto_start.get(),
        }
        self.root.destroy()

    # ── esptool runner ─────────────────────────────────────────────────────────
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

    # ── run ────────────────────────────────────────────────────────────────────
    def run(self) -> dict | None:
        self.root.protocol("WM_DELETE_WINDOW", self._cancel)
        self.root.mainloop()
        return self.result


def run_wizard() -> dict | None:
    return SetupWizard().run()
