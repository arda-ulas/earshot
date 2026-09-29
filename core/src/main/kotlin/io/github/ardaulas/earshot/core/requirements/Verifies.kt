package io.github.ardaulas.earshot.core.requirements

/**
 * Marks a test as verifying one or more requirements (`SR-n` safety/security, `F-n` functional) listed
 * in `docs/safety.md`. Used to build the requirement-to-test traceability table.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Verifies(
    vararg val ids: String,
)
