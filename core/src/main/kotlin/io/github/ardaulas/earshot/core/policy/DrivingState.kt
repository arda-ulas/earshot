package io.github.ardaulas.earshot.core.policy

/** The driving state as the assistant sees it. [UNKNOWN] is always handled as [MOVING] (SG-4). */
enum class DrivingState {
    PARKED,
    MOVING,
    UNKNOWN,
    ;

    /** The state the policy acts on: unknown is treated as moving. */
    val effective: DrivingState get() = if (this == UNKNOWN) MOVING else this
}

/**
 * Combines the signal-based driving state with the platform's own UX restrictions: the stricter
 * wins. If the platform requires distraction-optimized UI while the signals say parked, the state is
 * handled as moving. `null` means the platform has no UX restrictions service (a phone): no change.
 */
fun DrivingState.withUxRestrictions(requiresDistractionOptimization: Boolean?): DrivingState =
    if (requiresDistractionOptimization == true && this == DrivingState.PARKED) DrivingState.MOVING else this
