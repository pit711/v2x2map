"""
Entry point for the V2X2MAP Setup executable (its-g5-setup.exe).

Runs the setup wizard; if the user checks "Bridge automatisch starten",
launches its-g5-bridge.exe from the same directory afterwards.
"""
import os
import subprocess
import sys


def main():
    from setup_wizard import run_wizard
    result = run_wizard()
    if result is None:
        sys.exit(0)

    if result.get("auto_start"):
        exe_dir = os.path.dirname(
            sys.executable if getattr(sys, "frozen", False)
            else os.path.abspath(__file__)
        )
        bridge = os.path.join(exe_dir, "its-g5-bridge.exe")
        if not os.path.isfile(bridge):
            # Dev mode: try without .exe extension
            bridge = os.path.join(exe_dir, "its-g5-bridge")
        try:
            subprocess.Popen([bridge, "--open-browser"])
        except FileNotFoundError:
            import tkinter.messagebox as mb
            mb.showinfo(
                "V2X2MAP Setup",
                f"Bridge executable not found:\n{bridge}\n\n"
                "Please start its-g5-bridge.exe manually.",
            )


if __name__ == "__main__":
    main()
