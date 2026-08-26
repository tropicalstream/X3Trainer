#!/usr/bin/env python3
"""
Pre-generate X3Trainer's coaching voice clips with fish.audio S2.1 Pro
(free developer tier: https://fish.audio/blog/s2-1-pro-free-api/).

Two voices: the enthusiastic coach for timer/strength/HIIT, and a soothing
voice for the yoga & general-stretching cues (see SOOTHING_IDS below).
  enthusiastic: https://fish.audio/app/m/b32a85fcc90249b99cb555c0c3e50675/
  soothing:     https://fish.audio/app/m/b35b628d1b1146529f36f44bc11d7f09/

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
VOICE_MODEL_ID = "b32a85fcc90249b99cb555c0c3e50675"  # enthusiastic coach (default)
SOOTHING_MODEL_ID = "b35b628d1b1146529f36f44bc11d7f09"  # calm voice for yoga/stretch
API_URL = "https://api.fish.audio/v1/tts"
# Override with FISH_MODEL=s2.1-pro-free on a key without credit.
TTS_ENGINE = os.environ.get("FISH_MODEL", "s2.1-pro")

# The yoga and general-stretching cues get the soothing voice; everything else
# (timer, coaching, HIIT/strength cues) keeps the enthusiastic coach. Delete a
# clip and re-run to regenerate it with whatever model maps here now.
SOOTHING_IDS = {
    # yoga & stretching exercise cues
    "cue_mountain_reach", "cue_forward_fold", "cue_chair", "cue_warrior",
    "cue_triangle", "cue_tree", "cue_neck_rolls", "cue_shoulder_cross",
    "cue_tricep_overhead", "cue_quad_stretch", "cue_seated_fold", "cue_butterfly",
    "cue_figure_four", "cue_hip_flexor_lunge", "cue_cat_cow", "cue_cobra",
    "cue_downward_dog", "cue_childs_pose",
    # calm-program flow lines (rest/done/etc.) so the whole track is one voice
    "wk_ready_calm", "wk_rest_calm", "wk_last_calm", "wk_done_calm",
    "wk_skip_calm", "wk_resume_calm", "wk_end_early_calm", "timer_paused_calm",
    "halfway_calm",
}


def model_for(pid: str) -> str:
    return SOOTHING_MODEL_ID if pid in SOOTHING_IDS else VOICE_MODEL_ID


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
                # The ENGINE, as distinct from the voice above. s2.1-pro is
                # fish.audio's current production model; s1 is the legacy one,
                # and the comment that used to sit here claiming the server
                # silently upgraded s1 to S2.1 Pro was wrong — every clip in
                # the repo before this change was generated on the old engine.
                # s2.1-pro-free is the same model under free-tier fair use, so
                # a key without credit only needs the environment variable.
                "model": TTS_ENGINE,
            },
            json={
                "text": text,
                "reference_id": model_for(pid),
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
