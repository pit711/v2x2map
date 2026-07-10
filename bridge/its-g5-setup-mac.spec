# -*- mode: python ; coding: utf-8 -*-
# macOS build of the V2X2MAP setup/flash tool → dist/V2X2MAP Setup.app
# Build:  ~/.local/py312/bin/pyinstaller --noconfirm its-g5-setup-mac.spec
# (needs a python with a working Tk ≥8.6 — the Xcode CLT python's Tk 8.5 renders blank)

from PyInstaller.utils.hooks import collect_all

datas = [
    ('firmware/bootloader.bin',       'firmware'),
    ('firmware/partition-table.bin',  'firmware'),
    ('firmware/ota_data_initial.bin', 'firmware'),
    ('firmware/firmware.bin',         'firmware'),
]
binaries = []
hiddenimports = ['setup_wizard']
tmp_ret = collect_all('esptool')
datas += tmp_ret[0]; binaries += tmp_ret[1]; hiddenimports += tmp_ret[2]

a = Analysis(
    ['setup_main.py'],
    pathex=[],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name='its-g5-setup',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    name='its-g5-setup',
)
app = BUNDLE(
    coll,
    name='V2X2MAP Setup.app',
    icon='icons/its-g5-setup.icns',
    bundle_identifier='com.v2x2map.setup',
    info_plist={
        'CFBundleDisplayName': 'V2X2MAP Setup',
        'CFBundleShortVersionString': '1.0.0',
        'NSHighResolutionCapable': True,
    },
)
