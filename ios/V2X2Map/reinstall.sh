#!/bin/bash
# Re-signs and reinstalls V2X2Map onto the connected iPhone.
# Needed every ~7 days because the free Apple-ID provisioning profile expires.
# Just connect the iPhone via cable and run:  ./reinstall.sh
set -e
cd "$(dirname "$0")"

TEAM=NLU2H7YNRC                               # Apple Development team
DEST_ID=00008140-000E28523CD2801C            # xcodebuild device UDID
BUNDLE=com.v2x2map

# devicectl device id (auto-detect the first connected iPhone)
DEV=$(xcrun devicectl list devices 2>/dev/null | awk '/iPhone/{print $3; exit}')
DEV=${DEV:-3E97F5EF-D485-5014-8B35-81BA644F31B6}

echo "▶︎ Build + Signierung…"
xcodebuild -project V2X2Map.xcodeproj -scheme V2X2Map -configuration Debug \
  -destination "id=$DEST_ID" -derivedDataPath build \
  -allowProvisioningUpdates DEVELOPMENT_TEAM="$TEAM" build >/dev/null

echo "▶︎ Installieren…"
xcrun devicectl device install app --device "$DEV" \
  build/Build/Products/Debug-iphoneos/V2X2Map.app

echo "▶︎ Starten…"
xcrun devicectl device process launch --device "$DEV" "$BUNDLE"
echo "✓ Fertig – die App ist wieder für 7 Tage gültig."
