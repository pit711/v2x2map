# -*- mode: python ; coding: utf-8 -*-
# Setup wizard + flash tool — includes esptool and firmware binaries.

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
    a.binaries,
    a.datas,
    [],
    name='its-g5-setup',
    icon='icons/its-g5-setup.ico',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,          # GUI-only, no console window
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
