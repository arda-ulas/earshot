# ADR 0007: Platform UX restrictions and the policy: the stricter wins

Status: accepted (2026-09-30)

## Context

Android Automotive has its own driver-distraction model: `CarUxRestrictionsManager` reports whether the
platform currently requires distraction-optimized UI. Earshot's policy has its own rule from the
driving signals (voice only while moving or unknown, SG-3). The two can disagree, for example if the
platform's configuration restricts UI while the car is idling but the signals say parked.

## Decision

The driving state the policy sees is `resolved.withUxRestrictions(requiresDistractionOptimization)`
(`core/.../policy/DrivingState.kt`):

- restrictions required and signals say parked -> handled as moving;
- restrictions never relax a moving or unknown state;
- no restrictions service at all (a phone) -> no change;
- on a car, if the restrictions service cannot be reached, restrictions are assumed (the watcher
  starts as "required" and stays so without a service).

Because the combined state feeds the policy, restrictions also make replies voice-only and
visibility-reducing commands need a spoken yes, not only hide the screen.

## Alternatives considered

- **Only hiding the screen when restricted.** Rejected: the policy's other moving rules
  (confirmation for defrost off, 12-word replies) would then not apply in exactly the situation the
  platform considers driving.
- **Letting the platform decide alone.** Rejected: the policy's own fail-safe (unknown signals ->
  moving) must hold even when the platform's configuration is permissive.

## Consequences

- The assistant can be stricter than the rest of the car UI, never looser.
- Tested at the level of the combining function (SR-23, `UxRestrictionsTest`). On the emulator the
  restrictions switched to "required" when the vehicle HAL reported 50 km/h in drive (row A-2).
