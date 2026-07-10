#!/usr/bin/env python3
"""
Pre-generate X3Trainer's coaching voice clips with fish.audio S2.1 Pro
(free developer tier: https://fish.audio/blog/s2-1-pro-free-api/).

The enthusiastic coach voice model:
  https://fish.audio/app/m/b32a85fcc90249b99cb555c0c3e50675/

Usage:
  export FISH_API_KEY=...          # from https://fish.audio developer console
  python3 tools/generate_tts.py    # writes app/src/main/assets/tts/<id>.mp3

The clips ship inside the APK (assets/tts/) so the glasses never need the
network: CoachVoice plays them from storage. Re-run only when phrases.json
changes. Phrases already generated are skipped; delete a file to force it.

Requires: pip install requests  (or use ormsgpack variant per fish docs)
"""

import json
import os
import sys
import time
from pathlib import Path

try:
    import requests
except ImportError:
    sys.exit("pip install requests")

ROOT = Path(__file__).resolve().parent.parent
PHRASES = ROOT / "app/src/main/assets/phrases.json"
OUT_DIR = ROOT / "app/src/main/assets/tts"
VOICE_MODEL_ID = "b32a85fcc90249b99cb555c0c3e50675"  # enthusiastic coach
API_URL = "https://api.fish.audio/v1/tts"


def main() -> None:
    api_key = os.environ.get("FISH_API_KEY")
    if not api_key:
        sys.exit("Set FISH_API_KEY first (free tier: fish.audio developer console)")

    phrases = json.loads(PHRASES.read_text())
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    done = skipped = failed = 0
    for pid, text in phrases.items():
        out = OUT_DIR / f"{pid}.mp3"
        if out.exists() and out.stat().st_size > 0:
            skipped += 1
            continue
        resp = requests.post(
            API_URL,
            headers={
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json",
                "Model": "s1",  # server maps to the current S2.1 Pro engine
            },
            json={
                "text": text,
                "reference_id": VOICE_MODEL_ID,
                "format": "mp3",
                "mp3_bitrate": 64,
                "normalize": True,
                "latency": "normal",
            },
            timeout=60,
        )
        if resp.status_code == 200 and resp.content:
            out.write_bytes(resp.content)
            print(f"  ok  {pid}: {text[:50]}")
            done += 1
        else:
            print(f" FAIL {pid}: HTTP {resp.status_code} {resp.text[:120]}")
            failed += 1
        time.sleep(0.4)  # be polite to the free tier

    print(f"\ngenerated={done} skipped={skipped} failed={failed}")
    print(f"clips in {OUT_DIR}")
    if failed:
        sys.exit(1)


if __name__ == "__main__":
    main()
