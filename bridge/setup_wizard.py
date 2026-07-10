"""
Tkinter setup wizard for the V2X2MAP ITS-G5 Receiver bridge.

Steps:
  1. Device — pick the serial port
  2. Flash  — write the bundled firmware
  3. Done   — the device derives its node-id from its own MAC automatically

Returns dict {port} to the caller.
"""

from __future__ import annotations

import io
import os
import queue
import re
import sys
import threading
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

# ── platform styling ────────────────────────────────────────────────────────
IS_WINDOWS = sys.platform.startswith("win")
IS_MAC     = sys.platform == "darwin"
PORT_WORD  = "COM port" if IS_WINDOWS else "Serial port"
UI_FONT    = "Segoe UI" if IS_WINDOWS else ("Helvetica Neue" if IS_MAC else "DejaVu Sans")
MONO_FONT  = "Consolas" if IS_WINDOWS else ("Menlo" if IS_MAC else "DejaVu Sans Mono")

# USB vendor IDs that indicate an ESP32 devboard (native USB-JTAG or common UART bridge).
_ESP_VIDS   = {0x303A}                     # Espressif USB-JTAG/serial
_UART_VIDS  = {0x10C4, 0x1A86, 0x0403}     # CP210x, CH34x, FTDI
# Ports that are never a devboard (macOS internals).
_PORT_BLACKLIST = ("bluetooth-incoming", "debug-console", "wlan-debug")

def _resource_path(*parts: str) -> str:
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, *parts)


_ANSI_RE = re.compile(r"\x1b\[[0-9;?]*[A-Za-z]|\x1b\][^\x07]*\x07")


class _UiWriter(io.TextIOBase):
    """File-like sink that forwards text to a queue drained by the Tk MAIN thread.

    Two hard-won rules live here (E2E test on macOS/Tk 9 found both):
    - NEVER touch tkinter from a worker thread — even `root.after` silently drops
      events on some builds. Workers only enqueue; `SetupWizard._pump` renders.
    - esptool ≥5 probes its stdout like a real file (isatty & friends) and emits
      ANSI cursor codes; TextIOBase supplies the full API and we strip the codes.
    """

    def __init__(self, q: "queue.Queue", widget: tk.Text):
        super().__init__()
        self._q = q; self._widget = widget

    def writable(self) -> bool:
        return True

    def isatty(self) -> bool:
        return False

    @property
    def encoding(self) -> str:          # some libs read .encoding directly
        return "utf-8"

    def write(self, s: str) -> int:
        clean = _ANSI_RE.sub("", s)
        if clean:
            self._q.put(("log", self._widget, clean))
        return len(s)

    def flush(self) -> None:
        pass


class _MarkerArt:
    """Draws the app's map-marker artwork (top-down car, bus, 3-light signal) onto a
    tk.Canvas — the same visual language as the V2X2MAP app, as pure vector calls so
    the bundle needs no image assets and stays crisp at any scale."""

    VEHICLE_BLUE = "#2f7fd0"
    BODY_DARK    = "#242a30"

    @staticmethod
    def _rounded(c: tk.Canvas, x0, y0, x1, y1, r, **kw):
        """Rounded rectangle as a smoothed polygon."""
        pts = [x0+r,y0, x1-r,y0, x1,y0, x1,y0+r, x1,y1-r, x1,y1, x1-r,y1,
               x0+r,y1, x0,y1, x0,y1-r, x0,y0+r, x0,y0]
        return c.create_polygon(pts, smooth=True, **kw)

    @classmethod
    def car(cls, c: tk.Canvas, cx, cy, s=1.0):
        w, h = 22*s, 38*s
        x0, y0 = cx-w/2, cy-h/2
        # mirrors
        for mx in (x0-2.5*s, x0+w-0.5*s):
            cls._rounded(c, mx, y0+h*0.30, mx+3*s, y0+h*0.30+6*s, 1.2*s,
                         fill=cls.VEHICLE_BLUE, outline="white", width=1)
        cls._rounded(c, x0, y0, x0+w, y0+h, w*0.32, fill=cls.VEHICLE_BLUE,
                     outline="white", width=1.5*s)
        cls._rounded(c, x0+3*s, y0+h*0.17, x0+w-3*s, y0+h*0.32, 2*s,
                     fill="#1b4d80", outline="")
        cls._rounded(c, x0+3*s, y0+h*0.66, x0+w-3*s, y0+h*0.79, 2*s,
                     fill="#245e9b", outline="")

    @classmethod
    def bus(cls, c: tk.Canvas, cx, cy, s=1.0):
        w, h = 23*s, 48*s
        x0, y0 = cx-w/2, cy-h/2
        cls._rounded(c, x0, y0, x0+w, y0+h, 6*s, fill=cls.VEHICLE_BLUE,
                     outline="white", width=1.5*s)
        cls._rounded(c, x0+3*s, y0+h*0.10, x0+w-3*s, y0+h*0.22, 1.6*s,
                     fill="#1b4d80", outline="")
        for f in (0.34, 0.50, 0.66):
            cls._rounded(c, x0+4*s, y0+h*f, x0+w-4*s, y0+h*f+2.6*s, 1.2*s,
                         fill="#7db4e8", outline="")

    @classmethod
    def signal(cls, c: tk.Canvas, cx, cy, s=1.0, active="green"):
        r = 17*s
        c.create_oval(cx-r, cy-r, cx+r, cy+r, fill="#2b3138", outline="white", width=1.5*s)
        cls._rounded(c, cx-6.5*s, cy-13*s, cx+6.5*s, cy+13*s, 5*s,
                     fill="#161a1e", outline="#8a949e", width=1)
        lamps = {"red": "#ff5555", "amber": "#ffc837", "green": "#5bdd6e"}
        for i, (name, col) in enumerate(lamps.items()):
            ly = cy + (i-1)*8*s
            fill = col if name == active else "#3c444d"
            c.create_oval(cx-3.6*s, ly-3.6*s, cx+3.6*s, ly+3.6*s, fill=fill, outline="")


class SetupWizard:
    def __init__(self) -> None:
        self.result: dict | None = None

        self.root = tk.Tk()
        self.root.title("V2X2MAP — Setup")
        self.root.geometry("640x560")
        self.root.minsize(620, 500)
        self._center_window()

        self.port_var   = tk.StringVar()
        self.skip_flash = tk.BooleanVar(value=False)
        self._port_label_to_device: dict[str, str] = {}


        self._busy = False
        # Single UI queue: worker threads enqueue log text and callables; only the
        # main thread (via _pump) touches tkinter.
        self._uiq: queue.Queue = queue.Queue()
        self._build()
        self._pump()

    def _pump(self) -> None:
        try:
            while True:
                item = self._uiq.get_nowait()
                if item[0] == "log":
                    _, widget, chunk = item
                    widget.insert("end", chunk)
                    widget.see("end")
                else:
                    _, fn, args = item
                    fn(*args)
        except queue.Empty:
            pass
        self.root.after(80, self._pump)

    def _post(self, fn, *args) -> None:
        """Thread-safe: schedule `fn(*args)` on the main thread."""
        self._uiq.put(("call", fn, args))

    # ── UI ──────────────────────────────────────────────────────────────────

    def _center_window(self) -> None:
        self.root.update_idletasks()
        w, h = 640, 560
        x = (self.root.winfo_screenwidth() - w) // 2
        y = max(40, (self.root.winfo_screenheight() - h) // 3)
        self.root.geometry(f"{w}x{h}+{x}+{y}")

    def _build_header(self) -> None:
        """Brand banner: dark map-night ground, app title, and the app's own map
        markers (car, bus, live traffic signal) drawn as vector art."""
        c = tk.Canvas(self.root, height=76, highlightthickness=0, bg="#1d2530")
        c.pack(fill="x")
        c.create_text(20, 28, anchor="w", text="V2X2MAP",
                      font=(UI_FONT, 20, "bold"), fill="white")
        c.create_text(20, 52, anchor="w", text="ITS-G5 Receiver · Setup & Firmware-Flasher",
                      font=(UI_FONT, 11), fill="#93a1af")

        def place_markers(_evt=None):
            c.delete("art")
            before = set(c.find_all())
            w = c.winfo_width() or 640
            # subtle "road" line behind the vehicles
            c.create_line(w-215, 66, w-15, 10, fill="#39424c", width=10, capstyle="round")
            c.create_line(w-215, 66, w-15, 10, fill="#4a5560", width=1, dash=(4, 6))
            _MarkerArt.car(c, w-180, 46, 0.82)
            _MarkerArt.bus(c, w-120, 38, 0.82)
            _MarkerArt.signal(c, w-52, 34, 0.95, active="green")
            for item in set(c.find_all()) - before:
                c.addtag_withtag("art", item)
        c.bind("<Configure>", place_markers)
        place_markers()

    def _build(self) -> None:
        self._build_header()
        nb = ttk.Notebook(self.root)
        nb.pack(fill="both", expand=True, padx=10, pady=10)
        self.nb = nb
        self._build_step1(nb)
        self._build_step2(nb)
        self._build_done(nb)

    # ── Step 1: Device ───────────────────────────────────────────────────────
    def _build_step1(self, nb):
        f = ttk.Frame(nb, padding=20); nb.add(f, text="1. Device")
        ttk.Label(f, text="V2X2MAP Setup", font=(UI_FONT, 15, "bold")).pack(anchor="w")
        ttk.Label(f, text=f"Connect the ESP32-C5 via USB and select the {PORT_WORD}.\n"
                          "Detected ESP32 boards are preselected automatically.",
                  wraplength=540, justify="left").pack(anchor="w", pady=(8, 16))
        bar = ttk.Frame(f); bar.pack(side="bottom", fill="x", pady=(20, 0))
        ttk.Button(bar, text="Cancel", command=self._cancel).pack(side="right")
        ttk.Button(bar, text="Next >", command=self._goto_step2).pack(side="right", padx=(0, 8))
        row = ttk.Frame(f); row.pack(fill="x")
        ttk.Label(row, text=f"{PORT_WORD}:").pack(side="left")
        self.port_combo = ttk.Combobox(row, textvariable=self.port_var, state="readonly")
        self.port_combo.pack(side="left", padx=(8, 8), fill="x", expand=True)
        ttk.Button(row, text="Refresh", command=self._refresh_ports).pack(side="left")
        ttk.Checkbutton(f, text="Skip firmware flash (already flashed)",
                        variable=self.skip_flash).pack(anchor="w", pady=(20, 0))
        self._refresh_ports()

    # ── Step 2: Flash ────────────────────────────────────────────────────────
    def _build_step2(self, nb):
        f = ttk.Frame(nb, padding=20); nb.add(f, text="2. Flash")
        # Button bar packed FIRST: pack() starves last-packed widgets when space runs
        # out, and the bar must always win over the expanding log.
        bar = ttk.Frame(f); bar.pack(side="bottom", fill="x", pady=(12, 0))
        self.flash_btn      = ttk.Button(bar, text="Start flash", command=self._start_flash)
        self.flash_btn.pack(side="right", padx=(0, 8))
        self.flash_next_btn = ttk.Button(bar, text="Next >",
                                         command=lambda: self.nb.select(2), state="disabled")
        self.flash_next_btn.pack(side="right", padx=(0, 8))
        ttk.Button(bar, text="< Back", command=lambda: self.nb.select(0)).pack(side="right")
        ttk.Label(f, text="Flash firmware", font=(UI_FONT, 13, "bold")).pack(anchor="w")
        ttk.Label(f, text="Writes bootloader, partition table and application. Takes 30-60 s.",
                  wraplength=540, justify="left").pack(anchor="w", pady=(4, 12))
        self.flash_status   = ttk.Label(f, text="Ready.")
        self.flash_status.pack(anchor="w")
        self.flash_progress = ttk.Progressbar(f, mode="determinate")
        self.flash_progress.pack(fill="x", pady=(0, 8))

        # Boot-mode help: quietly present from the start, highlighted when a flash
        # attempt fails to connect (the one situation where it's actually needed).
        self.bootmode_box = tk.Frame(f, bd=1, relief="solid",
                                     highlightthickness=0, bg="#f4f6f8")
        self.bootmode_label = tk.Label(
            self.bootmode_box, justify="left", anchor="w", bg="#f4f6f8", fg="#3a4552",
            font=(UI_FONT, 11),
            text=("If the device is not detected: put the ESP32-C5 into boot mode —\n"
                  "  1.  hold the BOOT button\n"
                  "  2.  tap RESET once (or re-plug USB) while still holding BOOT\n"
                  "  3.  release BOOT, then press “Start flash” again"))
        self.bootmode_label.pack(fill="x", padx=10, pady=8)
        self.bootmode_box.pack(fill="x", pady=(0, 8))
        self.flash_log = scrolledtext.ScrolledText(f, height=12, font=(MONO_FONT, 10), wrap="none")
        self.flash_log.pack(fill="both", expand=True)

    # ── Step 3: Done ─────────────────────────────────────────────────────────
    def _build_done(self, nb):
        f = ttk.Frame(nb, padding=20); nb.add(f, text="3. Done")
        bar = ttk.Frame(f); bar.pack(side="bottom", fill="x", pady=(12, 0))
        ttk.Button(bar, text="Finish", command=self._finish).pack(side="right", padx=(0, 8))
        ttk.Button(bar, text="< Back", command=lambda: self.nb.select(1)).pack(side="right")

        box = tk.Canvas(f, height=88, highlightthickness=0, bg="#eef6ee")
        box.pack(fill="x", pady=(4, 12))
        box.create_oval(18, 20, 66, 68, fill="#2e7d32", outline="")
        box.create_line(30, 45, 40, 55, 56, 32, fill="white", width=5,
                        capstyle="round", joinstyle="round")
        box.create_text(84, 34, anchor="w", text="Receiver ready!",
                        font=(UI_FONT, 15, "bold"), fill="#20301f")
        box.create_text(84, 58, anchor="w",
                        text="Firmware flashed — the device restarts by itself.",
                        font=(UI_FONT, 11), fill="#4a5548")

        ttk.Label(f, justify="left", wraplength=560, text=(
            "No further configuration is needed:\n\n"
            "  •  The receiver derives its node-id automatically from its own\n"
            "      hardware MAC address (V2X2MAP…).\n"
            "  •  Open the V2X2MAP app on your phone — the receiver appears\n"
            "      via Bluetooth within a few seconds.")).pack(anchor="w")

    # ── serial port ──────────────────────────────────────────────────────────
    @staticmethod
    def _port_score(p) -> int:
        """Higher = more likely the ESP32 devboard. Blacklisted internals sink."""
        dev = p.device.lower()
        if any(b in dev for b in _PORT_BLACKLIST):
            return -1
        vid = getattr(p, "vid", None)
        if vid in _ESP_VIDS:
            return 100                              # Espressif USB-JTAG: that's the board
        if vid in _UART_VIDS:
            return 60                               # common devboard UART bridge
        if "usbmodem" in dev or "usbserial" in dev or "ttyusb" in dev or "ttyacm" in dev:
            return 30                               # generic USB serial
        return 0

    def _refresh_ports(self):
        ports = sorted(serial.tools.list_ports.comports(),
                       key=self._port_score, reverse=True)
        labels: list[str] = []
        self._port_label_to_device.clear()
        for p in ports:
            if self._port_score(p) < 0:
                continue                            # hide macOS-internal pseudo ports
            desc = (p.description or "").strip()
            label = p.device if desc in ("", "n/a") else f"{p.device}  -  {desc}"
            if getattr(p, "vid", None) in _ESP_VIDS:
                label += "   ← ESP32"
            labels.append(label)
            self._port_label_to_device[label] = p.device
        self.port_combo["values"] = labels
        if labels:
            if not self.port_var.get() or self.port_var.get() not in self._port_label_to_device:
                self.port_var.set(labels[0])        # best-scored port preselected
        else:
            self.port_var.set("")

    def _selected_port(self) -> str:
        return self._port_label_to_device.get(self.port_var.get(), "")

    # ── Navigation ───────────────────────────────────────────────────────────
    def _goto_step2(self):
        if not self._selected_port():
            messagebox.showerror("Error", f"Please select a {PORT_WORD} first.")
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
            messagebox.showerror("Error", f"No {PORT_WORD} selected."); return
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
        # esptool v4 spells options with underscores, v5 with dashes — pick per version.
        try:
            import esptool
            v5 = int(str(getattr(esptool, "__version__", "5")).split(".")[0]) >= 5
        except Exception:
            v5 = True
        wf, fm, fs, ff = (("write-flash", "--flash-mode", "--flash-size", "--flash-freq") if v5
                          else ("write_flash", "--flash_mode", "--flash_size", "--flash_freq"))
        args = ["--chip", CHIP, "--port", port, "--baud", FLASH_BAUD,
                "--before", "default-reset", "--after", "hard-reset",
                wf, fm, "dio", fs, "4MB", ff, "80m"]
        for off, name in FIRMWARE_FILES:
            args += [off, _resource_path("firmware", name)]
        ok, msg = self._run_esptool(args, self.flash_log)
        self._post(self._on_flash_done, ok, msg)

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
            # Connection trouble is the boot-mode case — make the help impossible to miss.
            log_tail = self.flash_log.get("1.0", "end").lower()
            if any(k in (msg or "").lower() + log_tail
                   for k in ("failed to connect", "no serial data", "timed out", "could not open")):
                self.bootmode_box.configure(bg="#fff3cd")
                self.bootmode_label.configure(bg="#fff3cd", fg="#7a5c00",
                                              font=(UI_FONT, 11, "bold"))

    # ── Finish ────────────────────────────────────────────────────────────────
    def _finish(self):
        self.result = {"port": self._selected_port()}
        self.root.destroy()

    # ── esptool runner ─────────────────────────────────────────────────────────
    def _run_esptool(self, args, log_widget, tee=None):
        writer = _UiWriter(self._uiq, log_widget)
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
