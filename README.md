# Earshot

[![CI](https://github.com/arda-ulas/earshot/actions/workflows/ci.yml/badge.svg)](https://github.com/arda-ulas/earshot/actions/workflows/ci.yml)

Offline push-to-talk voice control for a car's cabin climate, with a safety policy between the
models and the vehicle.

**Status: in progress.** Phase 1 (the on-device assistant on an Android phone emulator with a
simulated vehicle) is being built. This README is expanded when the phase lands.

## Quick start

Needs macOS or Linux, JDK 17+, the Android SDK with NDK 28.2.13676358 and CMake 3.31.6, and an arm64
Android emulator (API 36). The models (about 500 MB) are downloaded and checked against pinned
SHA-256 hashes; nothing is downloaded by the app.

```bash
scripts/fetch-models.sh
./gradlew :app:installDebug
scripts/push-models.sh
```

Then hold the button and speak. On an emulator without a microphone, `scripts/make-clips.sh` and
`scripts/push-models.sh --clips` add synthetic clips for the debug build's clip player.

Unit tests (no emulator needed): `./gradlew :core:check`.
