# KiwiDroid

Minimal native Android KiwiSDR client. Design and scope: [PROPOSAL.md](PROPOSAL.md).

## Listening

Select a receiver (edit the list under **Servers**), enter a frequency in **kHz**, and press **Tune**. Use **−/+** with a configurable whole-Hz step. Select AM, USB, LSB, or CW, then **Play**. Media volume is controlled by the phone. **Stop** disconnects and releases the receiver slot.

RSSI comes from incoming SND frames; there is no waterfall connection. Settings persist, but playback never starts automatically. Switching receivers replaces the session; tuning/mode changes reuse it. CW displays the wanted signal frequency and applies an 800 Hz heterodyne offset internally.

Playback continues when you switch apps, dismiss the activity, or turn the screen off. A media notification/lock-screen control shows the receiver and frequency; **Stop** releases the connection. Lost audio focus (including calls/other audio apps) or headphone disconnection still stops playback. A media playback foreground service owns audio/network resources; CPU and Wi-Fi locks are held only while listening. Background streaming consumes battery and data. Playback never automatically resumes after a process kill or reboot.

Supports no-password receivers, 0–30 MHz tuning, and uncompressed mono audio (~86 MB/hour before overhead). LAN entries require the appropriate Wi-Fi/VPN. HTTP/WS is explicitly permitted and unencrypted; HTTPS/WSS retains normal certificate validation and is never downgraded. No redirects or automatic reconnects. MediaSession notifications are exempt from Android 13's notification runtime permission; no permission prompt is needed for these playback controls.

## Audible RSSI

Enable **RSSI beeps** and disable **Receiver audio** for tone-only cable troubleshooting. Default pitch rises one octave per 10 dB. **Sound settings** offers editable dBm-to-note points, musical scales/root/custom intervals, beep and receiver gains, timing/fades, smoothing, hysteresis, and sine/triangle waveforms. **RSSI → C5** centers the map on the current reading. Settings persist and changes apply without reconnecting.

A **dip/peak latch** captures one-sample received RSSI excursions before smoothing. Defaults: both directions, 1 dB threshold, 1-second hold starting at the first beep. Both extremes alternate; mode/threshold/hold are configurable in Sound settings. Pitch quantization and mapping limits still apply.

Muting receiver audio keeps the SND connection for RSSI; data use is unchanged. Tones stop on stale RSSI, Stop, or disconnect. See [docs/RSSI_SOUND.md](docs/RSSI_SOUND.md) for mapping details and tuning for cable faults.

## Audio gaps and buffering

Playback now sizes its radio prebuffer from the actual device/route buffer and adapts after local starvation. This handles larger Bluetooth output bursts at the cost of additional radio latency. The main screen reports **local underruns**, **device underruns**, discarded PCM, and buffer sizes separately from missing network frames. See [docs/AUDIO_DIAGNOSTICS.md](docs/AUDIO_DIAGNOSTICS.md).

## Build tools

Requires Linux x86_64 for the supplied bootstrap, `curl`, `tar`, `unzip`, and `sha256sum`.

```sh
./tools/setup-android.sh    # installs user-local tools; prompts for Android SDK licenses
source tools/env.sh
./tools/check.sh           # JVM tests, Android lint, debug APK
```

Tools live under `~/.local/share/kiwi-droid/`, not in the repository. The bootstrap pins Temurin JDK 21 and Android command-line tools with SHA-256 checks; Gradle 8.13 is wrapper/checksum pinned. Android compile/target SDK is 36, minimum API is 26 (Android 8). Existing `JAVA_HOME` and `ANDROID_HOME` are respected. No system packages or shell profiles are changed.

## Phone setup

1. Enable Developer options and USB debugging on the phone.
2. Connect a data-capable USB cable, unlock the phone, and accept the computer's debugging key.
3. Run `source tools/env.sh; adb devices -l`; the phone should show `device`, not `unauthorized`.
4. Run `./tools/install-phone.sh [serial]` to build, install, and launch. Omit the serial when only one device is attached.

APK: `app/build/outputs/apk/debug/app-debug.apk`. Development builds use Android's debug signing key.

If Linux reports `no permissions` for a Google/Pixel phone, run `./tools/fix-usb-permissions.sh` from your normal account. It prompts for sudo, installs the included vendor-specific udev rule, and reapplies USB permissions. The account must already belong to `plugdev`. Reconnect the cable if necessary. For other phone vendors, use your distribution's Android udev rules. Do not run ADB as root. Android 11+ wireless debugging is an alternative (`adb pair host:pairing-port`, then `adb connect host:debug-port`).

## Tests and current status

```sh
./tools/check.sh
./tools/test-phone.sh [serial]  # unlock phone first; takes about a minute
```

The phone script installs in place to preserve settings, then runs UI checks, a 20-second silent AudioTrack test on the current route, and a screen-off silent loopback receiver test. It interrupts playback, restores the original controls/receiver list, and ends with the phone locked. Leave the phone untouched during testing; unlock it afterward. No real receiver is contacted. Running the whole Gradle connected-test suite on a PIN-locked device can block UI checks after the screen-off test; the script deliberately runs UI checks first.

Validated: 47 core JVM tests, 6 settings tests, Android lint (no errors), debug APK build, sound-controls/UI and screen-off playback tests. Device coverage includes Pixel 3 XL / Android 12 (earlier builds) and Pixel 8 Pro / Android 17 over Bluetooth (0.4.1). The latter passed a 20-second silent radio-PCM test with zero local/device underruns or dropped audio. Live listening confirmation of the Bluetooth fix and longer/background/Doze testing remain; see [docs/VALIDATION.md](docs/VALIDATION.md).

Source layout: `core/` is Android-independent protocol/transport/PCM code; `app/` contains Compose controls, process-scoped settings/state, and a media playback service owning the session, audio focus, notification, power locks, and AudioTrack output. The implementation follows `~/kiwi` protocol documentation; test vectors are independently generated, not copied receiver captures.

Do not commit local SDKs, credentials, signing keys, device identifiers, or raw private receiver captures.
