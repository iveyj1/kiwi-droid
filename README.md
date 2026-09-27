# KiwiDroid

Minimal native Android KiwiSDR client. Design and scope: [PROPOSAL.md](PROPOSAL.md).

## Listening

Select a receiver (edit the list under **Servers**), enter a frequency in **kHz**, and press **Tune**. Use **−/+** with a configurable whole-Hz step. Select AM, USB, LSB, or CW, then **Play**. Media volume is controlled by the phone. **Stop** disconnects and releases the receiver slot.

RSSI comes from incoming SND frames; there is no waterfall connection. Settings persist, but playback never starts automatically. Switching receivers replaces the session; tuning/mode changes reuse it. CW displays the wanted signal frequency and applies an 800 Hz heterodyne offset internally.

This first build is foreground-only: backgrounding, screen lock, lost audio focus, or headphone disconnection stops playback. It supports no-password receivers, 0–30 MHz tuning, and uncompressed mono audio (~86 MB/hour before overhead). LAN entries require the appropriate Wi-Fi/VPN. HTTP/WS is explicitly permitted and unencrypted; HTTPS/WSS retains normal certificate validation and is never downgraded. No redirects or automatic reconnects.

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
source tools/env.sh
./gradlew :app:connectedDebugAndroidTest  # offline UI smoke test on attached phone
./tools/install-phone.sh                 # test runner may uninstall the app on completion
```

The device smoke test changes frequency/step/mode, checks input validation and activity recreation, and never presses Play. It leaves ordinary tuning defaults, so do not run it while listening.

Validated: 17 core JVM tests, 3 settings tests, Android lint (no errors), debug APK build, and one offline UI test on a Pixel 3 XL / Android 12. Live receiver/audio listening and extended lifecycle/network tests remain to be validated; see [docs/VALIDATION.md](docs/VALIDATION.md).

Source layout: `core/` is Android-independent protocol/transport/PCM code; `app/` contains Compose controls, settings, audio focus, and AudioTrack output. The implementation follows `~/kiwi` protocol documentation; test vectors are independently generated, not copied receiver captures.

Do not commit local SDKs, credentials, signing keys, device identifiers, or raw private receiver captures.
