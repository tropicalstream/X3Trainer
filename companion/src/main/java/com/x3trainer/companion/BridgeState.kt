package com.x3trainer.companion

data class BridgeSnapshot(
    val watchStatus: String = "Starting watch link…",
    val glassesStatus: String = "Starting X3 Pro link…",
    val heartRate: Int = 0,
    val cadence: Int = 0,
    val speedMps: Float = -1f,
    val lastSampleAt: Long = 0L
)

object BridgeState {
    @Volatile
    var snapshot = BridgeSnapshot()
        private set

    @Synchronized
    fun update(transform: (BridgeSnapshot) -> BridgeSnapshot) {
        snapshot = transform(snapshot)
    }
}
