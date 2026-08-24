# X3Trainer: Pixel Watch 5 → S23 Ultra → RayNeo X3 Pro Telemetry

## Final status

Working and verified on 2026-08-23.

The live telemetry path is:

```text
Pixel Watch 5
  Connected Fitness / standard BLE Heart Rate Service (0x180D)
        ↓
Samsung Galaxy S23 Ultra
  X3Trainer Bridge companion app
        ↓
Classic Bluetooth RFCOMM/SPP over the existing paired phone link
        ↓
RayNeo X3 Pro
  X3Trainer glasses app / PhoneRelaySource
```

The RayNeo glasses do **not** connect directly to the watch. Their OS is locked
down and only permits the supported phone connection. All watch BLE work belongs
on the S23 companion app.

Final hardware verification:

- Pixel Watch screen: `Sharing 69 bpm with S23`.
- S23 bridge screen: `Live sensor feed`, `X3 Pro connected`, `69 bpm`, sample age
  `0s`.
- RayNeo X3Trainer display: `69 bpm` in zone `Z1`.
- RayNeo logs received changing real samples: 72, 71, 69, 68, 67, 66, and back
  to 69 bpm.
- No diagnostic or simulated sample was used for the successful result.

## Device identifiers used during verification

Serials and addresses are redacted — they identify particular hardware.
Set `PHONE_SERIAL` and `GLASSES_SERIAL` from `adb devices` before running
the commands below.

```text
Galaxy S23 Ultra ADB serial: $PHONE_SERIAL
RayNeo X3 Pro ADB serial:    $GLASSES_SERIAL
Pixel Watch BLE address:     (the watch's BLE address)
RayNeo Bluetooth address:    (the glasses' Bluetooth address)
S23 Bluetooth name:          (the phone's Bluetooth name)
```

Bluetooth addresses and visible names can change or be redacted by Android, so
code should continue identifying the watch primarily by the standard Heart Rate
service UUID, not by its address.

## Required watch procedure

On Pixel Watch 5:

1. Open **Settings → Connectivity → Connected Fitness**.
2. Enable **Allow more devices / Extended Pairing** when needed.
3. Tap **Connect**.
4. Confirm the S23 receiver when prompted.
5. Wait for the watch to say **Sharing _NN_ bpm with S23**.

Important distinctions:

- **Allow more devices / Extended Pairing** only changes compatibility and
  discoverability. It does not start heart-rate sharing by itself.
- **Visible as Pixel Watch 5** is a discovery/countdown screen. It is not proof
  that heart-rate samples are being transmitted.
- **Connected** can refer to the watch's normal companion-phone connection. The
  definitive success message is **Sharing _NN_ bpm with S23**.
- The S23 bridge should already be running before tapping **Connect**, because
  the watch's advertising/acceptance window is short.

## What was actually broken

There were two separate behaviors that looked like the same failure.

### 1. A delayed receiver consumed the watch's acceptance window

An experimental six-second “settle” delay was added after detecting the watch.
Pixel Watch Connected Fitness only advertises during its visible countdown. The
delay consumed most of that window and could produce a GATT link that appeared
subscribed but never received heart-rate notifications.

The delay was removed. The bridge now accepts the first advertisement
immediately.

The known-good connection sequence completes in roughly 75 ms:

```text
watch advertisement seen
→ connectGatt
→ GATT connected
→ services discovered
→ subscribe to 0x2A37
```

### 2. Service restart orphaned an already-sharing watch session

This was the decisive final bug.

After the S23 Bluetooth radio was cycled, the existing X3Trainer companion
process automatically connected to the fresh Pixel Watch link at approximately
20:11:18. A later recovery command restarted the companion process at
approximately 20:11:27. Android unregistered that GATT client, but the watch had
already stopped advertising and continued to display that it was sharing with
the S23.

The replacement companion process then scanned forever. That could never work:
Connected Fitness stops advertising after a central accepts the session.

The fix is in:

```text
<repo>/companion/src/main/java/
  com/x3trainer/companion/WatchBleClient.kt
```

`WatchBleClient.start()` now checks the phone's persisted bonded devices for a
Pixel Watch and reattaches to it before starting a BLE scan. Reattaching joins
the existing encrypted Bluetooth ACL and restores the Heart Rate Measurement
subscription even though the watch is no longer advertising.

Conceptually:

```kotlin
val sharingWatch = adapter.bondedDevices.firstOrNull {
    it.name.orEmpty().contains("Pixel Watch", ignoreCase = true)
}

if (sharingWatch != null) {
    connect(sharingWatch)
} else {
    startScan(useFallback = false)
}
```

This is process-restart recovery, not a name-based replacement for normal
discovery. Fresh watches and generic heart-rate straps are still discovered by
the standard `0x180D` service UUID.

## Successful wire-level evidence

Immediately after deploying the reattachment fix, the S23 logged:

```text
20:13:57.679 reattaching to bonded Pixel Watch 5
20:13:57.717 connection status=0 state=2
20:13:57.728 services status=0 ... 0000180d ...
20:13:57.769 notify 00002a37 00 49
20:13:57.861 subscription 00002a37... status=0
20:13:59.778 notify 00002a37 00 48
20:14:01.585 notify 00002a37 00 47
20:14:03.604 notify 00002a37 00 45
```

For the Bluetooth SIG Heart Rate Measurement characteristic:

- Byte 0 is the flags byte.
- When bit 0 is clear, byte 1 is an unsigned 8-bit BPM value.
- `0x49` = 73 bpm.
- `0x48` = 72 bpm.
- `0x47` = 71 bpm.
- `0x45` = 69 bpm.

The glasses then logged the decoded relay samples:

```text
sample hr=72 cadence=0 speed=0.0
sample hr=71 cadence=0 speed=0.0
sample hr=69 cadence=0 speed=0.0
sample hr=68 cadence=0 speed=0.0
sample hr=67 cadence=0 speed=0.0
sample hr=66 cadence=0 speed=0.0
sample hr=69 cadence=0 speed=0.0
```

This proves all three legs independently:

1. Pixel Watch emitted standard Heart Rate Measurement notifications.
2. S23 decoded the notifications and published them over RFCOMM.
3. RayNeo received and rendered the changing live BPM values.

## Application responsibilities

### Pixel Watch 5

- Uses native Connected Fitness.
- Advertises the Bluetooth SIG Heart Rate service `0x180D`.
- Sends Heart Rate Measurement notifications on characteristic `0x2A37`.
- Native broadcasting provides heart rate only; cadence remains zero.

### S23 companion app

Package:

```text
com.x3trainer.companion
```

Key components:

- `WatchBleClient.kt`: scans, reconnects, discovers services, subscribes to
  `0x2A37`, parses BPM, and reports live samples.
- `BridgeService.kt`: owns the watch client, keeps the bridge alive as a
  foreground service, merges optional phone cadence/speed, and publishes
  telemetry to the glasses.
- `PhoneRfcommServer.kt`: serves the already-paired RayNeo over classic
  RFCOMM/SPP.
- `BridgeState.kt`: provides the current bridge/UI snapshot.

The bridge must keep verbose `X3TrainerWatchLink` logging. It was essential for
distinguishing these states:

- watch not advertising;
- advertisement detected;
- GATT connected;
- CCCD write succeeded;
- subscription exists but no notification arrived;
- real notification bytes arrived.

### RayNeo X3 Pro app

Package:

```text
com.x3trainer
```

The glasses must use `PhoneRelaySource.kt`. It connects only to the bonded S23
RFCOMM server, reads the bridge protocol, and passes samples to the trainer
engine/UI.

Do not restore direct BLE watch scanning on the glasses. A temporary direct-BLE
experiment was fully removed after confirming the RayNeo OS restriction.

## Build and install

Project root:

```text
<repo>
```

Build the S23 companion:

```bash
cd <repo>
./gradlew :companion:assembleDebug
```

Install it:

```bash
adb -s $PHONE_SERIAL install -r \
  companion/build/outputs/apk/debug/companion-debug.apk
```

Build the glasses app:

```bash
cd <repo>
./gradlew :app:assembleDebug
```

Install it:

```bash
adb -s $GLASSES_SERIAL install -r \
  app/build/outputs/apk/debug/app-debug.apk
```

Launch the S23 companion:

```bash
adb -s $PHONE_SERIAL shell monkey \
  -p com.x3trainer.companion \
  -c android.intent.category.LAUNCHER 1
```

Launch X3Trainer on the glasses:

```bash
adb -s $GLASSES_SERIAL shell monkey \
  -p com.x3trainer \
  -c android.intent.category.LAUNCHER 1
```

## Verification commands

Watch/S23 bridge logs:

```bash
adb -s $PHONE_SERIAL logcat -v time \
  -s X3TrainerWatchLink:I X3TrainerBridge:I '*:S'
```

The critical real-data line is:

```text
X3TrainerWatchLink: notify 00002a37 ...
```

Glasses relay logs:

```bash
adb -s $GLASSES_SERIAL logcat -v time | \
  rg -i 'X3TrainerPhoneLink|telemetry|sample'
```

The critical final-hop line is:

```text
X3TrainerPhoneLink: sample hr=<changing real value> ...
```

Capture the phone and glasses screens:

```bash
adb -s $PHONE_SERIAL exec-out screencap -p > /tmp/x3-phone.png
adb -s $GLASSES_SERIAL exec-out screencap -p > /tmp/x3-glasses.png
```

## Troubleshooting decision tree

### Glasses show `-- bpm`

First inspect the S23 bridge UI.

#### S23 has a live BPM, but glasses do not

The watch leg is healthy. Troubleshoot RFCOMM only:

1. Confirm the S23 bridge says `X3 Pro connected`.
2. Confirm the glasses are set to the live phone source, not Demo.
3. Restart the glasses app, not the watch connection.
4. Inspect `X3TrainerPhoneLink` for socket errors and subsequent reconnects.

#### S23 says scanning and has no BPM

The watch is not advertising to X3Trainer:

1. Ensure Connected Fitness is enabled.
2. Tap **Connect**, not merely **Allow more devices**.
3. Look for **Sharing _NN_ bpm with S23** on the watch.
4. Leave the S23 bridge running during the watch countdown.

#### Watch says sharing, but S23 still says scanning

This is the service-restart/orphaned-session case. The bonded-watch reattachment
fix should recover it automatically. Verify the installed companion contains the
latest `WatchBleClient.start()` logic and look for:

```text
reattaching to bonded Pixel Watch 5
```

Do not repeatedly restart the bridge after the watch begins sharing. A restart
was the action that originally orphaned the accepted session.

#### S23 says subscribed, but no `notify 00002a37` appears

The GATT CCCD write succeeded, but the watch has not authorized or started data:

1. Check the watch screen for an S23 confirmation.
2. Confirm the watch says **Sharing**, not merely **Connected** or **Visible**.
3. If Extended Pairing was recently toggled, restart the Bluetooth session once
   and then leave the bridge process running.
4. Do not add artificial delays before `connectGatt()`.

### Bluetooth radio recovery

Cycling S23 Bluetooth forced a fresh encrypted session after Extended Pairing
had been changed:

```bash
adb -s $PHONE_SERIAL shell svc bluetooth disable
sleep 3
adb -s $PHONE_SERIAL shell svc bluetooth enable
```

This disconnects both the watch and glasses temporarily. Use it only when the
watch security/session state is genuinely stale. After enabling Bluetooth:

1. Let the S23 bridge start and remain running.
2. Let the glasses reconnect over RFCOMM.
3. Start Connected Fitness sharing on the watch.
4. Do not kill the companion process after acceptance.

## Known limitations and follow-up work

- Pixel Watch native Connected Fitness supplies heart rate only. Cadence is
  expected to remain `0 spm` unless the separate watch app or phone motion
  fallback supplies it.
- The repository contains substantial uncommitted work, including the companion
  module and phone relay. Commit a known-good snapshot before further changes.
- Existing robustness concerns remain outside this successful fix: blocking
  RFCOMM writes can risk an ANR, half-open glasses sockets need stronger recovery,
  and Bluetooth-off startup handling deserves a dedicated test.
- The glasses UI may still contain legacy `ACTIVE2` wording. That is cosmetic;
  the verified source is Pixel Watch 5 through the S23 bridge.

## Do not regress these behaviors

1. Do not attempt Pixel Watch BLE directly from the RayNeo glasses.
2. Do not delay after seeing the `0x180D` advertisement.
3. Do not depend solely on scanning after the watch has started sharing.
4. Preserve bonded Pixel Watch reattachment on companion service startup.
5. Do not treat CCCD status `0` as proof of live telemetry; require at least one
   `0x2A37` notification.
6. Do not use a diagnostic sample to declare end-to-end success.
7. Verify the final hop by observing changing real BPM values on the glasses.

