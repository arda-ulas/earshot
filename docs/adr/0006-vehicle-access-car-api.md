# ADR 0006: Vehicle access through the car API, with privileged climate writes on the emulator

Status: accepted (2026-09-30)

## Context

Phase 1 ran on the phone emulator with a simulated vehicle. Phase 3 moves the assistant to the Android
Automotive emulator and its public car API (`android.car`). The permission levels, read from the
Android Automotive 15 emulator image with `pm list permissions -f` on 2026-09-30:

| Permission | Protection level | Used for |
|---|---|---|
| `android.car.permission.CAR_SPEED` | dangerous (runtime prompt) | `PERF_VEHICLE_SPEED` |
| `android.car.permission.CAR_POWERTRAIN` | normal | `GEAR_SELECTION`, `CURRENT_GEAR` |
| `android.car.permission.CONTROL_CAR_CLIMATE` | signature\|privileged | `HVAC_TEMPERATURE_SET`, `HVAC_FAN_SPEED`, `HVAC_AC_ON`, `HVAC_DEFROSTER` |

An app installed normally can read speed and gear but cannot write climate properties.

## Decision

- **A separate module, `:vehicle:car`,** is the only code that touches `android.car`. It implements
  core's `VehicleGateway`; `:core` stays pure Kotlin. Everything is written against a thin wrapper
  interface (`CarProperties`) whose calls return null or false instead of throwing, so unavailable
  properties, error statuses, missing permissions and a disconnected car service look the same and
  are unit-tested with a fake.
- **Reads:** speed (`PERF_VEHICLE_SPEED`, m/s, converted to km/h) and gear (`GEAR_SELECTION`, falling
  back to `CURRENT_GEAR`) are polled on every driving-state tick (every 200 ms) and on every turn. A
  failed read is a missing value; with neither value readable there is no reading and the existing
  resolver's 1 s staleness rule makes the state unknown, handled as moving (SG-4). Speed above zero
  wins over a park gear.
- **Writes:** on the Android Automotive **emulator only**, the app is installed as a privileged app
  (`scripts/install-privileged.sh`: `/system/priv-app` plus a `privapp-permissions` allowlist that
  names only `CONTROL_CAR_CLIMATE`). The gateway writes climate to the car only when that permission
  is granted and the temperature property has areas; otherwise climate goes to the simulated
  vehicle, and the app's developer panel says "real driving-state signals; simulated climate
  writes". Temperature, fan and AC are written to every seat area (the assistant models one zone);
  defrost uses the front and rear windshield areas. Spoken replies come from reading the car back.
- **Runtime choice:** with the automotive system feature and a reachable car service the app uses
  the car API; otherwise (a phone) the simulated vehicle and scripted scenarios, unchanged from
  v0.2.x.

## Alternatives considered

- **Simulated writes only (the plan's fallback).** Kept as the automatic fallback, but not needed on
  the emulator: the privileged install worked within the 2 h timebox.
- **Subscribing to property events instead of polling.** Gear is an on-change property: with events
  only, a car parked for more than 1 s would look stale. Polling keeps the staleness rule meaningful
  and makes a failing read visible at once. The cost is a few binder calls per second.
- **Declaring only the read permissions.** `CONTROL_CAR_CLIMATE` is declared so that a privileged
  install can be granted it; a normal install is simply not granted it.

## Consequences

- Climate writes reach the car only on a privileged install, which needs a writable system image and
  root: the emulator, not a production device. A normal install falls back to simulated writes.
- The fan level passes through unchanged; the emulator's fan range was not mapped to the 0-5 model.
- Scenarios are scripted through the emulator's vehicle HAL test hooks (`scripts/aaos-scenario.sh`).
  Those hooks cannot make a polled property unavailable, so "signal lost" on the car API is covered by
  unit tests only.
- The debug APK is what gets installed as privileged; that is a test setup, stated as such.

## Evidence

- Unit tests: `CarSignalMapperTest`, `CarPropertyGatewayTest` (SR-22, SR-13, SR-14, SR-17).
- Manual test plan rows A-1 to A-8 ([manual-test-plan.md](../manual-test-plan.md)): on the Android
  Automotive emulator, "Set the temperature to 21" changed `HVAC_TEMPERATURE_SET` from 17.0 to 21.0 °C
  in all five seat areas, read back by the car service.
