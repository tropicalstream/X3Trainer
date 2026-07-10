# Coaching voice clips

This folder holds the pre-generated fish.audio S2.1-Pro MP3 clips for the
enthusiastic coach voice (one `<phrase-id>.mp3` per entry in
`../phrases.json`). They are produced ONCE on a dev machine by
`tools/generate_tts.py` (needs a free `FISH_API_KEY`), then ship inside the
APK so the glasses never touch the network.

Until the clips are generated, CoachVoice falls back to Android TTS pitched
up — functional, but generate the real voice before release.
