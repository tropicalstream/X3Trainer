# X3Trainer

An AR fitness coach for the **RayNeo X3 Pro** smart glasses: a sports timer
and live watch telemetry pinned to the edges of your vision, an enthusiastic
voice coach in your ears, and — by design — **nothing at all in the middle of
your sight**.

Native Android 12 app, Kotlin, zero dependencies, zero vendor AARs. All UI
sounds are synthesized at first launch; the coach's voice ships as
pre-generated fish.audio clips.

## The layout contract

Black is transparent on the waveguide, so the sightline stays real:

| Zone | Content | Controls |
|---|---|---|
| **Top 10%** | Active sports timer (mode, clock, phase/round, state) | Single tap start/pause · double tap reset · swipe + tap-to-confirm switches timer type |
| **Center 80%** | **Completely empty.** Environmental awareness during high-velocity outdoor training. Only brief celebrations, device warnings, and the mode-switch confirm prompt may appear here. | — |
| **Bottom 10%** | Normalized telemetry string — HR + zone, cadence vs target, delta velocity vs target pace — **tinted with the current cardiac-zone color** | — |

Triple tap opens settings (discrete one-swipe-per-step navigation, Reset
Settings at the bottom, suite conventions throughout).

## Sports timer

Six popular timer types, cycled by horizontal swipe — **every switch asks for
a tap-to-confirm** so an accidental brush of the temple pad can't dump your
workout (ignoring the prompt for 4 s cancels it):

- **STOPWATCH** — count up
- **COUNTDOWN** — configurable minutes
- **INTERVAL** — work/rest × rounds (all three configurable)
- **TABATA** — the classic 20 s / 10 s × 8
- **EMOM** — every minute on the minute, configurable minutes
- **AMRAP** — configurable time cap

Phase endings get 3-2-1 beeps and a go tone; round completions ding; workout
completion gets a fanfare and a celebration flash.

## The coach (voice only — never text)

Coaching advice blends the habits of the popular training apps and is
delivered **exclusively as audio** through an enthusiastic voice:

- **Zone guidance** toward your target HR zone (ease off / push / dialed in)
- **Cadence nudges** when your turnover drifts from target
- **Pace nudges** from delta velocity vs your target pace
- **Interval calls** — work/rest transitions, final-round push
- **Milestones** — five/ten-minute marks, round celebrations
- **Hydration reminders** every ~15 minutes
- **Safety**: sustained Zone-5 warning (active even with coaching off)

Chattiness is settable (Off/Low/Normal/High); every rule has its own cooldown
so the coach encourages rather than nags.

### Generating the voice

Clips are pre-generated with **fish.audio S2.1 Pro**
([free developer API](https://fish.audio/blog/s2-1-pro-free-api/)) using the
enthusiastic coach voice model
[`b32a85fcc90249b99cb555c0c3e50675`](https://fish.audio/app/m/b32a85fcc90249b99cb555c0c3e50675/):

```bash
export FISH_API_KEY=...   # free at fish.audio
python3 tools/generate_tts.py
./gradlew assembleDebug   # clips ship inside the APK; no network at run time
```

Until clips are generated the app falls back to Android TTS (pitched up), so
it works out of the box — but generate the real voice before release.

## Telemetry sources

Set in Settings → Data Source:

- **Demo** (default) — a simulated workout that sweeps through the HR zones;
  lets you exercise the HUD, coach, and timers with no sensor.
- **HR Broadcast (BLE)** — listens for the standard Bluetooth **Heart Rate
  service** (0x180D) and, when present, **Running Speed & Cadence** (0x1814).
  Works with chest straps and sport watches that broadcast natively
  (Garmin/Polar style). **Apple Watch:** the Watch never exposes HR over
  standard BLE natively, and RayNeo's Apple Watch gesture link carries no
  health data — run a broadcaster app on the Watch (e.g. HeartCast) and it
  becomes a standard HR peripheral this app can read.

> **Hardware honesty:** the X3 Pro's consumer pairing is locked to its
> companion phone, and whether third-party apps may use BLE central scanning
> on-device is unverified. Standard HR broadcast requires **no pairing**, so
> it may well work — this app is itself the test. If scanning is blocked the
> app degrades to a "CHECK DEVICE" warning, and a phone-relay telemetry
> source is the planned fallback.

If the telemetry device raises a problem (signal lost, Bluetooth off, stale
data), the **screen frame flashes red** and a **CHECK DEVICE** message
appears, with a voice callout.

## First run

A combined **international health disclaimer** (not medical advice; consult a
physician; stop on pain/dizziness) and **moisture warning** (the glasses are
not waterproof — heavy sweat can damage the waveguides; see the RayNeo
glasses guide) is shown once. Tap to accept and continue.

## Controls summary

| Gesture | HUD | Settings |
|---|---|---|
| Single tap | Start/pause timer (or confirm pending mode switch) | Activate item |
| Double tap | Reset timer (or cancel pending switch) | Back to HUD |
| Triple tap | Open settings | Close settings |
| Swipe left/right | Propose previous/next timer type (tap to confirm) | Adjust value |
| Swipe up/down | — | Move selection |

## Build & install

```bash
cd ~/Projects/X3Trainer
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

JDK 17, AGP 8.7.3, Kotlin 2.0.21, compileSdk 35 / minSdk 29. Binocular SBS
auto-enables on RayNeo hardware (detected by manufacturer identity, never
`Build.MODEL`).
