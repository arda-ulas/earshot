package io.github.ardaulas.earshot.car

class FakeCarProperties : CarProperties {
    override var connected = true
    val floats = mutableMapOf<Pair<Int, Int>, Float?>()
    val ints = mutableMapOf<Pair<Int, Int>, Int?>()
    val booleans = mutableMapOf<Pair<Int, Int>, Boolean?>()
    val areas = mutableMapOf<Int, IntArray>()
    var rejectWrites = false

    /** A blocking platform call: real sleep, as a binder call would block its thread. */
    var writeBlockMs = 0L
    val writes = mutableListOf<Triple<Int, Int, Any>>()

    override fun readFloat(
        propertyId: Int,
        areaId: Int,
    ) = floats[propertyId to areaId]

    /** Vehicle timestamp per float property (nanoseconds); defaults to "now". */
    val floatTimestamps = mutableMapOf<Pair<Int, Int>, Long>()
    var nowNanos = 0L

    override fun readFloatTimed(
        propertyId: Int,
        areaId: Int,
    ) = floats[propertyId to areaId]?.let { it to (floatTimestamps[propertyId to areaId] ?: nowNanos) }

    override fun elapsedRealtimeNanos() = nowNanos

    /** Runs on every int read (for example to let time pass during a slow read). */
    var onReadInt: (() -> Unit)? = null

    override fun readInt(
        propertyId: Int,
        areaId: Int,
    ): Int? {
        onReadInt?.invoke()
        return ints[propertyId to areaId]
    }

    override fun readBoolean(
        propertyId: Int,
        areaId: Int,
    ) = booleans[propertyId to areaId]

    override fun writeFloat(
        propertyId: Int,
        areaId: Int,
        value: Float,
    ) = record(propertyId, areaId, value) { floats[propertyId to areaId] = value }

    override fun writeInt(
        propertyId: Int,
        areaId: Int,
        value: Int,
    ) = record(propertyId, areaId, value) { ints[propertyId to areaId] = value }

    override fun writeBoolean(
        propertyId: Int,
        areaId: Int,
        value: Boolean,
    ) = record(propertyId, areaId, value) { booleans[propertyId to areaId] = value }

    private fun record(
        propertyId: Int,
        areaId: Int,
        value: Any,
        apply: () -> Unit,
    ): Boolean {
        if (writeBlockMs > 0) Thread.sleep(writeBlockMs)
        if (rejectWrites) return false
        writes += Triple(propertyId, areaId, value)
        apply()
        return true
    }

    override fun areaIds(propertyId: Int) = areas[propertyId] ?: IntArray(0)
}
