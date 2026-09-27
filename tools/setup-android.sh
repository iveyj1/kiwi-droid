#!/usr/bin/env bash
# User-local, pinned Linux x86_64 setup. Does not change shell profiles or require root.
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/env.sh
[[ $(uname -sm) == 'Linux x86_64' ]] || { echo 'This bootstrap supports Linux x86_64 only.' >&2; exit 1; }
for tool in curl unzip tar sha256sum; do command -v "$tool" >/dev/null; done
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
fetch() {
    curl -fLsS --retry 3 "$1" -o "$work/$2"
    echo "$3  $work/$2" | sha256sum -c -
}
mkdir -p "$KIWI_TOOLS_HOME/toolchains" "$ANDROID_HOME/cmdline-tools"
if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
    fetch 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz' jdk.tar.gz ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94
    tar -xzf "$work/jdk.tar.gz" -C "$KIWI_TOOLS_HOME/toolchains"
fi
if [[ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]]; then
    fetch https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip tools.zip 7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee
    unzip -q "$work/tools.zip" -d "$work/android"
    mv "$work/android/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi
# Interactive license acceptance; no silent agreement on subsequent machines.
sdkmanager --licenses
sdkmanager 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0'
java -version
adb version
./gradlew --version
