#!/bin/sh
# Install X3Trainer Link on the Galaxy Watch Active2 through either SDB-over-
# Bluetooth (recommended with the paired S23) or direct Wi-Fi SDB.
#
# Bluetooth: enable Debugging under Settings > About watch, connect the watch
# in Samsung's SDB-over-BT phone helper, then run this script without an IP.
#
# Wi-Fi: enable Debugging, join the Mac's network, then run:
#   ./install-watch.sh <watch-ip>
#
# If install fails with a signature/certificate error (common on Samsung
# watches with the default Tizen certificate), create a Samsung certificate:
#   arch -x86_64 open /Users/me/tizen-studio/tools/certificate-manager/certificate-manager.app
#   -> new certificate profile > Samsung > Mobile/Wearable > sign in with a
#      Samsung account; the connected watch's DUID is picked up automatically.
# Re-run this script afterwards (it re-signs with the active profile).

set -e
TS=/Users/me/tizen-studio
SDB="$TS/tools/sdb"
TZ="$TS/tools/ide/bin/tizen"
PROJ="$(cd "$(dirname "$0")/X3TrainerBroadcaster" && pwd)"
IP="$1"

if [ -n "$IP" ]; then
	arch -x86_64 "$SDB" connect "$IP:26101"
fi

SERIAL=$(arch -x86_64 "$SDB" devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')
[ -n "$SERIAL" ] || {
	echo "No Active2 debug target found. Connect SDB-over-BT or pass the watch IP."
	exit 1
}

echo "Using watch target: $SERIAL"

echo "--- packaging (signing with the active security profile) ---"
cd "$PROJ"
arch -x86_64 "$TZ" package -t tpk -s X3Watch -- Release

TPK=$(ls "$PROJ"/Release/*.tpk | head -1)
echo "--- installing $TPK ---"
arch -x86_64 "$SDB" -s "$SERIAL" install "$TPK"

echo "--- launching ---"
arch -x86_64 "$SDB" -s "$SERIAL" shell app_launcher -s org.example.x3trainerbroadcaster || true
echo "Done. On the watch: allow Health access when prompted."
echo "The S23 bridge will connect automatically, then relay the live feed to X3Trainer."
