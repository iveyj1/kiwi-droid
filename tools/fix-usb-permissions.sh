#!/usr/bin/env bash
# Grant normal-user ADB access to Google/Pixel USB devices on this Linux host.
# Only installs our rule; does not run ADB as root or alter other USB rules.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ $(uname -s) != Linux ]]; then
    echo 'This script is for Linux udev hosts.' >&2
    exit 1
fi
command -v udevadm >/dev/null || { echo 'udevadm is required.' >&2; exit 1; }
getent group plugdev >/dev/null || { echo 'The plugdev group does not exist on this host.' >&2; exit 1; }

user_name=${SUDO_USER:-$(id -un)}
if [[ $user_name == root ]]; then
    echo 'Run this from your normal user account, not a root login.' >&2
    exit 1
fi
if ! id -nG "$user_name" | tr ' ' '\n' | grep -qx plugdev; then
    echo "User $user_name must belong to plugdev. Add that membership and log in again first." >&2
    exit 1
fi

rule_dir=/etc/udev/rules.d
rule_file=$rule_dir/70-kiwi-android.rules
if [[ $EUID != 0 ]]; then
    echo 'Installing a Google/Pixel USB udev rule. sudo will prompt for your password.'
    sudo -- bash "$PWD/tools/fix-usb-permissions.sh"
    source tools/env.sh
    adb devices -l
    exit 0
fi

install -d -m 755 "$rule_dir"
# Do not silently replace a different administrator-owned rule.
if [[ -e $rule_file ]] && ! cmp -s tools/70-kiwi-android.rules "$rule_file"; then
    echo "A different rule already exists at $rule_file; review it before replacing it." >&2
    exit 1
fi
install -m 644 tools/70-kiwi-android.rules "$rule_file"
udevadm control --reload-rules
udevadm trigger --action=change --subsystem-match=usb --attr-match=idVendor=18d1
udevadm settle --timeout=10
printf '\nUSB rule installed. Unlock the phone and accept the debugging prompt.\n'
printf 'If access is still blocked, unplug/reconnect the USB cable and run:\n'
printf '  source tools/env.sh; adb devices -l\n'
