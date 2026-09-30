# Permissions

Least privilege (TH-3): every permission the app holds, and why. The merged manifest is checked at
build time (`:app:verify<Variant>MergedManifest`): no `INTERNET`, and nothing exported except the
launcher activity (SR-20).

| Permission | Why | Notes |
|---|---|---|
| `io.github.ardaulas.earshot.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Added by AndroidX `core` for receivers registered as not exported | A signature permission defined by the app for itself; no other app can hold it; grants nothing to the app |
| `RECORD_AUDIO` | Push-to-talk speech capture | Requested at the first press. Capture runs only while the button (or the push-to-talk key) is held. Audio is kept in memory for one turn and never written to storage |
| `android.car.permission.CAR_SPEED` | Android Automotive only: read `PERF_VEHICLE_SPEED` for the driving state | Dangerous: requested at start-up on an automotive device. Without it only the gear is read, and the driving state falls back to the gear rules. Unused on a phone |
| `android.car.permission.CAR_POWERTRAIN` | Android Automotive only: read `GEAR_SELECTION` / `CURRENT_GEAR` | Normal: granted at install. Unused on a phone |
| `android.car.permission.CONTROL_CAR_CLIMATE` | Android Automotive only: write the four climate properties the assistant controls | Signature\|privileged: granted only to a privileged install on the emulator with the allowlist in `automotive/privapp-permissions-earshot.xml`, which names this permission and nothing else. A normal install is not granted it and climate writes stay simulated. See [ADR 0006](adr/0006-vehicle-access-car-api.md) |

Protection levels as read from the Android Automotive 15 emulator image (`pm list permissions -f`,
2026-09-30).

Not requested, on purpose:

- `INTERNET`: the assistant runs offline; models are pushed by `adb`, not downloaded by the app (TH-6).
- Storage permissions: models and debug clips live in the app's own external files directory, which
  needs none.
- Other car permissions (energy, doors, windows, seats, lights, info): the assistant does not touch
  them, and the gateway can only express the five allowlisted climate properties (SR-17).

Components: only `MainActivity` (launcher) is exported. AndroidX's `ProfileInstallReceiver` is
removed from the merged manifest.
