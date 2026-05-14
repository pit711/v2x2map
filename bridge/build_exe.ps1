# Builds two Windows EXEs for V2X2MAP:
#   dist\its-g5-bridge.exe  -- bridge only, starts directly (reads config\v2x2map.cfg)
#   dist\its-g5-setup.exe   -- setup wizard + firmware flash
#
# Run from V2X2MAP\bridge:
#   .\build_exe.ps1

$ErrorActionPreference = 'Continue'

# Refresh bundled firmware bins from the latest build/ if it exists.
$buildDir = Resolve-Path "..\firmware\build" -ErrorAction SilentlyContinue
if ($buildDir) {
    Copy-Item "$buildDir\bootloader\bootloader.bin"             ".\firmware\bootloader.bin"       -Force
    Copy-Item "$buildDir\partition_table\partition-table.bin"   ".\firmware\partition-table.bin"  -Force
    Copy-Item "$buildDir\ota_data_initial.bin"                  ".\firmware\ota_data_initial.bin" -Force
    Copy-Item "$buildDir\its-g5-receiver-firmware.bin"          ".\firmware\firmware.bin"         -Force
    Write-Host "Firmware-Bins aktualisiert aus $buildDir"
} else {
    Write-Host "Kein ..\firmware\build\ gefunden -- bestehende firmware\*.bin werden verwendet."
}

# Ensure required packages are available.
python -m pip install --quiet pyinstaller esptool paho-mqtt pyserial pillow

# Generate icons
Write-Host ""
Write-Host "=== Generiere Icons ==="
python make_icons.py
if ($LASTEXITCODE -ne 0) { Write-Warning "Icon-Generierung fehlgeschlagen -- baue ohne Icons weiter." }

Write-Host ""
Write-Host "=== Baue its-g5-bridge.exe (Bridge ohne Wizard) ==="
python -m PyInstaller --noconfirm its-g5-bridge.spec
if ($LASTEXITCODE -ne 0) { Write-Error "Bridge-Build fehlgeschlagen."; exit 1 }

Write-Host ""
Write-Host "=== Baue its-g5-setup.exe (Setup-Wizard und Flash) ==="
python -m PyInstaller --noconfirm its-g5-setup.spec
if ($LASTEXITCODE -ne 0) { Write-Error "Setup-Build fehlgeschlagen."; exit 1 }

$bridge = (Get-Item "dist\its-g5-bridge.exe").Length / 1MB
$setup  = (Get-Item "dist\its-g5-setup.exe").Length  / 1MB
Write-Host ""
Write-Host "Fertig:"
Write-Host ("  dist\its-g5-bridge.exe  {0:N1} MB  -- Doppelklick zum Starten" -f $bridge)
Write-Host ("  dist\its-g5-setup.exe   {0:N1} MB  -- Einrichten und Flashen" -f $setup)
Write-Host ""
Write-Host "Ordnerstruktur nach dem ersten Start:"
Write-Host "  dist\"
Write-Host "    its-g5-bridge.exe"
Write-Host "    its-g5-setup.exe"
Write-Host "    config\"
Write-Host "      v2x2map.cfg      (von Setup geschrieben)"
Write-Host "    recordings\"
Write-Host "      its5_*.pcap      (PCAP-Aufnahmen)"
