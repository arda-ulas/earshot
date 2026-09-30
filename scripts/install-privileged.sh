#!/usr/bin/env bash
# Installs the debug APK as a privileged app on the Android Automotive EMULATOR, so the platform can
# grant CONTROL_CAR_CLIMATE (signature|privileged) and climate writes go to the vehicle HAL instead of
# the simulated vehicle. Emulator only: needs an emulator started with -writable-system and adb root.
#
#   scripts/install-privileged.sh          install (reboots the emulator)
#   scripts/install-privileged.sh --undo   remove the privileged copy (reboots)
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
adb="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
pkg="io.github.ardaulas.earshot"
apk="$root/app/build/outputs/apk/debug/app-debug.apk"
dir="/system/priv-app/Earshot"
perm="/system/etc/permissions/privapp-permissions-earshot.xml"

# Emulator only (re-audit N5): refuse anything that is not an Android emulator, and require an explicit
# target when more than one device is connected.
if [[ -z "${ANDROID_SERIAL:-}" && "$("$adb" devices | grep -c -w device)" != "1" ]]; then
  echo "more than one device: set ANDROID_SERIAL to the emulator" >&2; exit 1
fi
serial="$("$adb" get-serialno | tr -d '\r')"
qemu="$("$adb" shell getprop ro.kernel.qemu | tr -d '\r')"
bootqemu="$("$adb" shell getprop ro.boot.qemu | tr -d '\r')"
auto="$("$adb" shell pm has-feature android.hardware.type.automotive | tr -d '\r')"
if [[ "$serial" != emulator-* || ( "$qemu" != "1" && "$bootqemu" != "1" ) ]]; then
  echo "refusing: $serial is not an Android emulator" >&2; exit 1
fi
[[ "$auto" == "true" ]] || { echo "refusing: $serial is not an Android Automotive image" >&2; exit 1; }

"$adb" root >/dev/null && sleep 1
"$adb" remount
if [[ "${1:-}" == "--undo" ]]; then
  "$adb" shell rm -rf "$dir" "$perm"
else
  [[ -f "$apk" ]] || { echo "build first: ./gradlew :app:assembleDebug" >&2; exit 1; }
  "$adb" uninstall "$pkg" >/dev/null 2>&1 || true
  "$adb" shell mkdir -p "$dir"
  "$adb" push "$apk" "$dir/Earshot.apk"
  "$adb" push "$root/automotive/privapp-permissions-earshot.xml" "$perm"
  "$adb" shell chmod 644 "$dir/Earshot.apk" "$perm"
fi
"$adb" reboot
"$adb" wait-for-device
until [[ "$("$adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do sleep 3; done
"$adb" shell dumpsys package "$pkg" | grep -E "codePath|CONTROL_CAR_CLIMATE: granted" || true
