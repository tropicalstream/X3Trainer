# X3Trainer

> ## ⚠️ SAMPLE SOFTWARE — NOT FOR ACTUAL EXERCISE
>
> **This is a demonstration and reference project, not a consumer fitness
> product and not a medical device.** It is **not intended to be used to
> conduct actual exercise.**
>
> **See a doctor first.** Consult a qualified physician before beginning any
> exercise program, and before using this software in connection with any
> physical activity. If a physician has not cleared you to exercise, do not
> use it.
>
> Nothing this software displays or says is medical advice. The heart-rate,
> calorie, cadence and pace figures are **estimates** from consumer sensors —
> they are not clinical measurements and must not be used to judge whether
> exertion is safe for you. It cannot detect a medical emergency.
>
> Stop at once and seek medical attention for pain, chest pressure, dizziness,
> faintness, irregular heartbeat or unusual breathlessness. In an emergency,
> call your local emergency number.
>
> Exercise carries an inherent risk of serious injury, disability and death.
> **Use of this software is entirely at your own risk.** The authors accept no
> liability — see **[DISCLAIMER.md](DISCLAIMER.md)** and [LICENSE](LICENSE),
> which you should read in full before use.


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

## The mat coach (3D vector skeleton)

**Swipe up** from the HUD to open the **MAT COACH** — a full-body OpenGL ES 3
vector-skeleton coach who *performs every exercise with you* on a glowing
neon mat: a 20-joint forward-kinematics rig, keyframe-animated, auto-grounded
so squats sink, push-ups lower, and glute bridges lift like the real
movement. Dumbbell moves render the dumbbells in the coach's hands.

Eight programs built from the most popular single-mat circuits, each at
**beginner / intermediate / advanced** (reps, seconds, rest, and classic
substitutions scale — knee push-ups become push-ups, squats become squat
jumps). Every pose in the library is verified against rendered contact
sheets of the actual FK output (`tools`-independent offline check):

- **FULL BODY START** — jacks, squats, push-ups, bridge, bird dog, dead bug, plank
- **CORE CRUSHER** — crunches, bicycles, leg raises, russian twists, superman, side plank, plank
- **HIIT SWEAT** — jacks, high knees, squat jumps, mountain climbers, plank jacks, burpees
- **DUMBBELL POWER** — goblet squats, rows, floor press, RDLs, presses, curls, raises, weighted twists
- **LOWER BODY BURN** — squats, lunges, bridges, RDLs, squat jumps, plank
- **YOGA FLOW** — mountain reach, forward fold, chair, warrior, triangle, downward dog, tree, child's pose (one-sided poses switch sides automatically mid-hold)
- **FULL STRETCH** — neck rolls, shoulder & tricep stretches, quad stretch, hip flexor lunge, seated fold, butterfly, figure four, cobra
- **COOL-DOWN FLOW** — cat cow, cobra, downward dog, child's pose

Flow: a 5-second **GET READY** (the coach demos the first move at slow
tempo) → **WORK** (rep ticks count with the coach's cycles; timed holds count
down with 3-2-1 beeps) → **REST** (the coach previews the next move slowly)
→ … → a victory pose and session summary. The voice coach announces each
exercise with a form cue, always at phase boundaries — never over a rep. Live
HR stays on screen in its zone color throughout.

In-workout controls: **tap** pause/resume · **double tap** skip exercise
(while paused: end workout) · **triple tap** settings. The workout is
stationary mat training, so this mode intentionally uses the center of view —
the empty-sightline contract applies to outdoor HUD training.

### Coaching best practices built in

- **Live vitals callouts** — on alternating rests the coach *speaks your
  actual numbers* ("Heart rate 142, zone 3 — right in the working zone").
  These lines embed live data, so they use dynamic TTS; everything scripted
  stays pre-generated. Spoken only at phase boundaries, never over a rep.
- **Every workout is saved** — date, program, level, duration, reps,
  average/peak HR, and a MET-based calorie estimate (set **Body Weight** in
  settings for accuracy). History drives **streaks**, **weekly counts**, and
  lifetime totals shown in the picker and on the summary screen.
- **Progressive overload** — complete a program three times at a level and
  the coach suggests moving up; the picker tracks your progress toward it.
- **Warm-up nudge** before intense programs, halfway encouragement on long
  holds, and a personalized spoken summary (minutes, calories, HR, streak)
  when you finish.

## Telemetry sources

Set in Settings → Data Source:

- **Active2 Direct** (default) — receives live heart rate and cadence straight
  from the X3Trainer Broadcaster installed on the Galaxy Watch Active2. The
  watch exposes standard Bluetooth Heart Rate and RSC services; no phone relay,
  Samsung Health polling, account, cloud, or Internet connection is involved.
- **Demo (simulated)** — a simulated workout that sweeps through the HR zones;
  lets you exercise the HUD, coach, and timers with no sensor.
- The live source also listens for the standard Bluetooth **Heart Rate
  service** (0x180D) and, when present, **Running Speed & Cadence** (0x1814).
  Works with chest straps and sport watches that broadcast natively
  (Garmin/Polar style). **Apple Watch:** the Watch never exposes HR over
  standard BLE natively, and RayNeo's Apple Watch gesture link carries no
  health data — run a broadcaster app on the Watch (e.g. HeartCast) and it
  becomes a standard HR peripheral this app can read.

> **Hardware honesty:** the direct Active2 broadcaster requires Tizen's BLE
> GATT-server feature on the installed watch firmware. X3Trainer reports scan,
> connection, and stale-data failures explicitly and never substitutes demo
> numbers while the live source is selected.

If the telemetry device raises a problem (signal lost, Bluetooth off, stale
data), the **screen frame flashes red** and a **CHECK DEVICE** message
appears, with a voice callout.

## First run

A combined **international health disclaimer** (not medical advice; consult a
physician; stop on pain/dizziness) and **moisture warning** (the glasses are
not waterproof — heavy sweat can damage the waveguides; see the RayNeo
glasses guide) is shown once. Tap to accept and continue.

## Controls summary

| Gesture | HUD | Workout picker / workout | Settings |
|---|---|---|---|
| Single tap | Start/pause timer (or confirm pending mode switch) | Start workout / pause-resume | Activate item |
| Double tap | Reset timer (or cancel pending switch) | Back / skip exercise (paused: end) | Back |
| Triple tap | Open settings | Open settings | Close settings |
| Swipe left/right | Propose previous/next timer type (tap to confirm) | Change level | Adjust value |
| Swipe up/down | Open the mat coach | Change program | Move selection |

## Build & install

```bash
cd ~/Projects/X3Trainer
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

JDK 17, AGP 8.7.3, Kotlin 2.0.21, compileSdk 35 / minSdk 29. Binocular SBS
auto-enables on RayNeo hardware (detected by manufacturer identity, never
`Build.MODEL`).
