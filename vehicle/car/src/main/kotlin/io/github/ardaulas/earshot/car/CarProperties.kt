package io.github.ardaulas.earshot.car

/**
 * The thin wrapper over the platform's property manager that everything else is written against, so
 * the mapping and fail-safe logic are unit-tested on the JVM with a fake. Every call returns null or
 * false instead of throwing: unavailable, error status, missing permission and a disconnected car
 * service all look the same to the caller.
 */
interface CarProperties {
    /** False when the car service is not connected. */
    val connected: Boolean

    fun readFloat(
        propertyId: Int,
        areaId: Int,
    ): Float?

    /** The value with the time the vehicle reported it (elapsed-realtime nanoseconds), or null. */
    fun readFloatTimed(
        propertyId: Int,
        areaId: Int,
    ): Pair<Float, Long>?

    /** Elapsed-realtime clock (nanoseconds) on the same time base as [readFloatTimed]. */
    fun elapsedRealtimeNanos(): Long

    fun readInt(
        propertyId: Int,
        areaId: Int,
    ): Int?

    fun readBoolean(
        propertyId: Int,
        areaId: Int,
    ): Boolean?

    fun writeFloat(
        propertyId: Int,
        areaId: Int,
        value: Float,
    ): Boolean

    fun writeInt(
        propertyId: Int,
        areaId: Int,
        value: Int,
    ): Boolean

    fun writeBoolean(
        propertyId: Int,
        areaId: Int,
        value: Boolean,
    ): Boolean

    /** Area ids the property supports; empty when it is not supported or not readable. */
    fun areaIds(propertyId: Int): IntArray
}

/**
 * Property ids, gear values and areas from the public car API (android.car.VehiclePropertyIds,
 * VehicleGear, VehicleAreaWindow), copied as constants so this file and its tests need no platform
 * classes. Checked against the android.car stub in platforms/android-36/optional on 2026-09-30.
 */
object CarIds {
    const val PERF_VEHICLE_SPEED = 291504647
    const val GEAR_SELECTION = 289408000
    const val CURRENT_GEAR = 289408001
    const val HVAC_TEMPERATURE_SET = 358614275
    const val HVAC_FAN_SPEED = 356517120
    const val HVAC_AC_ON = 354419973
    const val HVAC_DEFROSTER = 320865540

    const val GEAR_UNKNOWN = 0
    const val GEAR_NEUTRAL = 1
    const val GEAR_REVERSE = 2
    const val GEAR_PARK = 4
    const val GEAR_DRIVE = 8

    /** VehicleAreaWindow.FRONT_WINDSHIELD and REAR_WINDSHIELD. */
    const val WINDOW_FRONT = 1
    const val WINDOW_REAR = 2
    const val AREA_GLOBAL = 0
}
