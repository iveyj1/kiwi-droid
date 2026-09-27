# KiwiDroid

Minimal native Android KiwiSDR client. Design and scope: [PROPOSAL.md](PROPOSAL.md).

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

Do not commit local SDKs, credentials, signing keys, device identifiers, or raw private receiver captures.
