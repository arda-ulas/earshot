#!/usr/bin/env bash
# Drives the Android Automotive emulator's vehicle HAL into a scripted driving state, through the
# emulator's public test hooks (no app involvement):
#
#   scripts/aaos-scenario.sh parked   gear park, speed 0
#   scripts/aaos-scenario.sh city     gear drive, about 50 km/h (13.9 m/s)
#   scripts/aaos-scenario.sh stopped  gear drive, speed 0 (handled as moving: ADR 0003)
#   scripts/aaos-scenario.sh status   print the current speed and gear as the car service sees them
#
# Speed is fed by the vehicle HAL's linear fake-data generator (a one-off injected event is overwritten
# by the emulator), gear by the vehicle HAL's debug --set command. Needs `adb root` (the
# emulator's automotive image allows it). There is no hook here for "signal lost" on polled reads;
# that case is covered by unit tests (see docs/manual-test-plan.md).
set -euo pipefail
adb="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
vhal="android.hardware.automotive.vehicle.IVehicle/default"
SPEED=0x11600207
GEAR=0x11400400
"$adb" root >/dev/null && sleep 1

speed() { # m/s
  "$adb" shell dumpsys "$vhal" --genfakedata --stoplinear "$SPEED" >/dev/null 2>&1 || true
  "$adb" shell dumpsys "$vhal" --genfakedata --startlinear "$SPEED" "$1" "$1" 0 0 100000000 >/dev/null
}
gear() { "$adb" shell dumpsys "$vhal" --set "$GEAR" -i "$1" >/dev/null; }

case "${1:-}" in
  parked) gear 4; speed 0 ;;
  city) gear 8; speed 13.9 ;;
  stopped) gear 8; speed 0 ;;
  status) ;;
  *) echo "usage: $0 parked|city|stopped|status" >&2; exit 2 ;;
esac
sleep 1
"$adb" shell cmd car_service get-property-value PERF_VEHICLE_SPEED | tail -1 | grep -o "Value: .*"
"$adb" shell cmd car_service get-property-value GEAR_SELECTION | tail -1 | grep -o "Value: .*"
