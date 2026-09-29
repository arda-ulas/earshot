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
