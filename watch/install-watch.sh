#!/bin/sh
# Install the X3Trainer Broadcaster on the Galaxy Watch Active2 over Wi-Fi.
#
# One-time watch prep (on the watch):
#   1. Settings > Connections > Wi-Fi > ON, join the same network as this Mac.
#   2. Settings > About watch > Software > tap "Software version" 5 times
#      -> "Debugging" toggle appears; turn it ON.
#   3. Note the watch IP: Settings > Connections > Wi-Fi > (your network) > IP address.
#
# Then:  ./install-watch.sh <watch-ip>
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

[ -n "$IP" ] || { echo "usage: $0 <watch-ip>"; exit 1; }

arch -x86_64 "$SDB" connect "$IP:26101"
sleep 1
arch -x86_64 "$SDB" devices

echo "--- packaging (signing with the active security profile) ---"
cd "$PROJ"
arch -x86_64 "$TZ" package -t tpk -s X3Watch -- Release

TPK=$(ls "$PROJ"/Release/*.tpk | head -1)
echo "--- installing $TPK ---"
arch -x86_64 "$TZ" install -n "$(basename "$TPK")" -- "$PROJ/Release"

echo "--- launching ---"
arch -x86_64 "$SDB" shell app_launcher -s org.example.x3trainerbroadcaster || true
echo "Done. On the watch: allow Health access when prompted."
echo "On the X3: X3Trainer > Settings > Data Source = Active2 Direct."
