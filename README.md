# KiwiDroid

Minimal native Android KiwiSDR client. Design and scope: [PROPOSAL.md](PROPOSAL.md).

## Listening

Select a receiver (edit the list under **Servers**), enter a frequency in **kHz**, and press **Tune**. Use **−/+** with a configurable whole-Hz step. Select AM, USB, LSB, CW, SAM, SAU, SAL, SAS, IQ, or CWN, then **Play**. **Passband** edits signed lower/upper Hz cuts, remembered independently per mode. Media volume is controlled by the phone. **Stop** disconnects and releases the receiver slot.

RSSI comes from incoming SND frames; there is no waterfall connection. Settings persist, but playback never starts automatically. Switching receivers keeps the old audio until the new stream is buffered, then crossfades using one AudioTrack. **Keep previous receiver** optionally retains the silent stream for instant switch-back (Off by default); retuning drops it. Two streams consume two receiver slots and extra bandwidth. See [docs/RECEIVER_SWITCHING.md](docs/RECEIVER_SWITCHING.md). CW/CWN display the wanted RF passband center and automatically offset the receiver frequency by the midpoint of the selected cuts. SAS preserves stereo audio; IQ plays I left / Q right (resampled playback, not raw-IQ export). See [docs/MODES_AND_FILTERS.md](docs/MODES_AND_FILTERS.md).

Playback continues when you switch apps, dismiss the activity, or turn the screen off. A media notification/lock-screen control shows the receiver and frequency; **Stop** releases the connection. Temporary audio-focus interruptions (such as notifications) silence both radio and beeps, then automatically restore them when focus returns, without reconnecting. Permanent focus loss or headphone disconnection still stops playback. A media playback foreground service owns audio/network resources; CPU and Wi-Fi locks are held only while listening. Background streaming consumes battery and data. Playback never automatically resumes after a process kill or reboot.

Supports no-password receivers, 0–30 MHz tuning, and uncompressed audio (~86 MB/hour mono, ~173 MB/hour SAS/IQ, before overhead). LAN entries require the appropriate Wi-Fi/VPN. HTTP/WS is explicitly permitted and unencrypted; HTTPS/WSS retains normal certificate validation and is never downgraded. No redirects or automatic reconnects. MediaSession notifications are exempt from Android 13's notification runtime permission; no permission prompt is needed for these playback controls.

## MW stations

Between 520 and 1710 kHz, the screen lists the North American AM stations on the channel(s) inside the current passband, from a bundled FCC table (US, Canada, Mexico, Cuba). **MW stations** searches by call, place, or kHz; tap a station to tune it. US construction permits are hidden unless enabled. Other bands keep the plain screen.

Stations are checked against FCC LMS, ISED (Canada), and the Mexican regulator's list; silent, permit, and unverified entries are hidden unless enabled, and ⊘ hides any entry you find wrong. Rows show the programming format where Wikipedia has one (CC BY-SA 4.0).

Set each receiver's antenna location under **Servers → Edit** (or fetch it from the receiver's published GPS) to rank stations by a coarse day/night ground- and sky-wave estimate, with distance and bearing. Starred stations appear as favorite chips on every band. See [docs/MW_STATIONS.md](docs/MW_STATIONS.md).

## Logbook

**Log…** records the time (UTC), receiver, frequency, mode, passband, RSSI with its 10-second range, the station (chosen from the in-passband MW candidates or typed), identification confidence, and notes. The MW dialog's **+ log / ✓ log** action creates a station-selected draft or edits its latest entry without retuning. **Logbook** lists, searches, edits, and tunes back to entries, and exports CSV through the system save dialog. See [docs/LOGBOOK.md](docs/LOGBOOK.md).

## Audible RSSI

Enable **RSSI beeps** and disable **Receiver audio** for tone-only cable troubleshooting. Default pitch rises one octave per 10 dB. **Sound settings** offers editable dBm-to-note points, musical scales/root/custom intervals, beep and receiver gains, timing/fades, smoothing, hysteresis, and sine/triangle waveforms. **RSSI → C5** centers the map on the current reading. Settings persist and changes apply without reconnecting.

A **dip/peak latch** captures one-sample received RSSI excursions before smoothing. Defaults: both directions, 1 dB threshold, 1-second hold starting at the first beep. Both extremes alternate; mode/threshold/hold are configurable in Sound settings. Pitch quantization and mapping limits still apply.

Muting receiver audio keeps the SND connection for RSSI; data use is unchanged. Tones stop on stale RSSI, Stop, or disconnect. See [docs/RSSI_SOUND.md](docs/RSSI_SOUND.md) for mapping details and tuning for cable faults.

## Audio gaps and buffering

0.5.2 fixes a separate background starvation issue by disabling runtime debugging in normal APKs; the strengthened two-minute background/screen-off test passed without underruns. See [docs/AUDIO_DIAGNOSTICS.md](docs/AUDIO_DIAGNOSTICS.md) for the reproduced failure and build/runtime comparison.

Playback sizes its radio prebuffer from the actual device/route buffer and adapts after local starvation. This handles larger Bluetooth output bursts at the cost of additional radio latency. The main screen reports **local underruns**, **device underruns**, discarded PCM, and buffer sizes separately from missing network frames. See [docs/AUDIO_DIAGNOSTICS.md](docs/AUDIO_DIAGNOSTICS.md).

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

APK: `app/build/outputs/apk/debug/app-debug.apk`. It retains Android's debug signing key for compatible in-place updates, but **runtime debugging is disabled by default** as of 0.5.2 to prevent background audio starvation observed on Android 17. For debugger development only, build with `./gradlew :app:assembleDebug -Pdebuggable=true`; rebuild normally before installing/publishing for listening.

Run `./copy-apk.sh` to copy the existing APK to `/mnt/main/public/kiwi-droid/kiwi-droid.apk`. It does not rebuild; run `./tools/check.sh` first when sources have changed.

If Linux reports `no permissions` for a Google/Pixel phone, run `./tools/fix-usb-permissions.sh` from your normal account. It prompts for sudo, installs the included vendor-specific udev rule, and reapplies USB permissions. The account must already belong to `plugdev`. Reconnect the cable if necessary. For other phone vendors, use your distribution's Android udev rules. Do not run ADB as root. Android 11+ wireless debugging is an alternative (`adb pair host:pairing-port`, then `adb connect host:debug-port`).

## Tests and current status

```sh
./tools/check.sh
./tools/test-phone.sh [serial]  # unlock phone first; takes about a minute
```

The phone script installs in place to preserve settings, then runs control/filter UI checks, a 20-second silent mono-to-stereo AudioTrack test on the current route, and a silent loopback receiver test including SAS/IQ mode changes, 60 seconds behind Home, and 60 seconds screen-off with the full audio path enabled. It also exercises two simultaneous loopback receivers and retained switch-back. It interrupts playback, restores the original controls/receiver list, removes test log entries, and ends with the phone locked. Leave the phone untouched during testing; unlock it afterward. No real receiver is contacted. Running the whole Gradle connected-test suite on a PIN-locked device can block UI checks after the screen-off test; the script deliberately runs UI checks first.

Edit boxes select their existing text when first focused; subsequent taps let you place the cursor normally. CWN appears directly below CW.

Validated: 113 core JVM tests, 16 app JVM tests, Android lint (no errors), debug APK build, and ten phone tests, including two offline MW station tests, station log actions, two-stream handover/switch-back, and background playback on Pixel 8 Pro / Android 17 (0.7.0). Fetching a location from a live receiver's `/status` is covered only by a mock-server test. Device coverage includes Pixel 3 XL / Android 12 (earlier builds) and Pixel 8 Pro / Android 17 over Bluetooth (0.5.1). The latter passed a 20-second silent mono/stereo test with zero local/device underruns or dropped audio. The user confirmed the previous Bluetooth fix works. Live listening checks of the newly added modes and longer/background/Doze testing remain; see [docs/VALIDATION.md](docs/VALIDATION.md).

Source layout: `core/` is Android-independent protocol/transport/PCM code; `app/` contains Compose controls, process-scoped settings/state, and a media playback service owning the session, audio focus, notification, power locks, and AudioTrack output. The implementation follows `~/kiwi` protocol documentation; test vectors are independently generated, not copied receiver captures.

Do not commit local SDKs, credentials, signing keys, device identifiers, or raw private receiver captures.
