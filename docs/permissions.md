# Permissions

Least privilege (TH-3): every permission the app holds, and why. The merged manifest is checked at
build time (`:app:verify<Variant>MergedManifest`): no `INTERNET`, and nothing exported except the
launcher activity (SR-20).

| Permission | Why | Notes |
|---|---|---|
| `RECORD_AUDIO` | Push-to-talk speech capture | Requested at the first press. Capture runs only while the button (or the push-to-talk key) is held. Audio is kept in memory for one turn and never written to storage |

Not requested, on purpose:

- `INTERNET`: the assistant runs offline; models are pushed by `adb`, not downloaded by the app (TH-6).
- Storage permissions: models and debug clips live in the app's own external files directory, which
  needs none.
- Car permissions: the vehicle is simulated in this phase. Reading speed and gear and writing climate
  properties on the automotive emulator come in a later phase, each with its justification here.

Components: only `MainActivity` (launcher) is exported. AndroidX's `ProfileInstallReceiver` is
removed from the merged manifest.
