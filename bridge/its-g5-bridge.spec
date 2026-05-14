# -*- mode: python ; coding: utf-8 -*-
# Bridge only — no wizard, no esptool, no tkinter → smaller executable.

datas = [('dashboard.html', '.')]
binaries = []
hiddenimports = ['dashboard_server']

a = Analysis(
    ['its_g5_bridge.py'],
    pathex=[],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=['tkinter', '_tkinter', 'esptool', 'setup_wizard', 'setup_main'],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name='its-g5-bridge',
    icon='icons/its-g5-bridge.ico',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
