package io.github.ardaulas.earshot.car

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The car service connection: property access and the platform's UX restrictions. Every platform
 * failure (missing permission, unavailable property, disconnected service) becomes null / false.
 */
class PlatformCar private constructor(
    private val car: Car,
) : CarProperties {
    private val properties: CarPropertyManager? =
        runCatching { car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager }.getOrNull()

    private val _requiresDistractionOptimization = MutableStateFlow(true)

    /**
     * Whether the platform currently requires distraction-optimized UI. Starts true and stays true
     * when the UX restrictions service cannot be reached (fail-safe).
     */
    val requiresDistractionOptimization: StateFlow<Boolean> = _requiresDistractionOptimization

    private val ux: CarUxRestrictionsManager? =
        runCatching { car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager }.getOrNull()

    private val uxLock = Any()
    private var uxCallbackSeen = false

    /** True if the restrictions service could not be registered; restrictions then stay assumed. */
    var uxFailed = false
        private set

    init {
        // Register first, then read; any failure leaves restrictions assumed (audit re-check N3).
        try {
            val m = ux ?: throw IllegalStateException("no UX restrictions service")
            // A callback is always newer than the initial read: the read is applied only if no
            // callback arrived first, under one lock (re-audit 6, N26).
            m.registerListener { r ->
                synchronized(uxLock) {
                    uxCallbackSeen = true
                    _requiresDistractionOptimization.value = r.isRequiresDistractionOptimization
                }
            }
            val initial = m.currentCarUxRestrictions.isRequiresDistractionOptimization
            synchronized(uxLock) { if (!uxCallbackSeen) _requiresDistractionOptimization.value = initial }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            _requiresDistractionOptimization.value = true
            uxFailed = true
        }
    }

    override val connected: Boolean get() = properties != null && runCatching { car.isConnected }.getOrDefault(false)

    override fun readFloat(
        propertyId: Int,
        areaId: Int,
    ): Float? = read(Float::class.javaObjectType, propertyId, areaId)

    override fun readFloatTimed(
        propertyId: Int,
        areaId: Int,
    ): Pair<Float, Long>? =
        runCatching {
            val v: CarPropertyValue<Float>? = properties?.getProperty(Float::class.javaObjectType, propertyId, areaId)
            if (v != null && v.status == CarPropertyValue.STATUS_AVAILABLE) v.value to v.timestamp else null
        }.getOrNull()

    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()

    override fun readInt(
        propertyId: Int,
        areaId: Int,
    ): Int? = read(Int::class.javaObjectType, propertyId, areaId)

    override fun readBoolean(
        propertyId: Int,
        areaId: Int,
    ): Boolean? = read(Boolean::class.javaObjectType, propertyId, areaId)

    private fun <T> read(
        type: Class<T>,
        propertyId: Int,
        areaId: Int,
    ): T? =
        runCatching {
            val v: CarPropertyValue<T>? = properties?.getProperty(type, propertyId, areaId)
            if (v != null && v.status == CarPropertyValue.STATUS_AVAILABLE) v.value else null
        }.getOrNull()

    override fun writeFloat(
        propertyId: Int,
        areaId: Int,
        value: Float,
    ) = write { properties?.setFloatProperty(propertyId, areaId, value) }

    override fun writeInt(
        propertyId: Int,
        areaId: Int,
        value: Int,
    ) = write { properties?.setIntProperty(propertyId, areaId, value) }

    override fun writeBoolean(
        propertyId: Int,
        areaId: Int,
        value: Boolean,
    ) = write { properties?.setBooleanProperty(propertyId, areaId, value) }

    private inline fun write(block: () -> Unit?): Boolean = runCatching { block() != null }.getOrDefault(false)

    override fun areaIds(propertyId: Int): IntArray =
        runCatching { properties?.getCarPropertyConfig(propertyId)?.areaIds }.getOrNull() ?: IntArray(0)

    fun disconnect() {
        runCatching { ux?.unregisterListener() }
        runCatching { car.disconnect() }
    }

    companion object {
        /** Connects to the car service; null when there is none (for example on a phone). */
        fun connect(context: Context): PlatformCar? =
            runCatching { Car.createCar(context.applicationContext) }.getOrNull()?.let(::PlatformCar)
    }
}
