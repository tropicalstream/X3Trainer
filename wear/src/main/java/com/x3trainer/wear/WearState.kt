package com.x3trainer.wear

/**
 * What the watch face shows, in one place.
 *
 * The service owns the sensors and the radio; the activity only draws. A tiny
 * shared snapshot keeps it that way, so closing the screen cannot disturb a
 * running workout and opening it cannot need a handle on the service.
 */
object WearState {
    @Volatile var hr: Int = 0; private set
    @Volatile var cadence: Int = 0; private set
    @Volatile var status: String = "Starting"; private set
    @Volatile var linked: Boolean = false; private set

    /** Called on any change; the activity subscribes while it is visible. */
    @Volatile var onChange: (() -> Unit)? = null

    fun set(
        hr: Int = this.hr,
        cadence: Int = this.cadence,
        status: String = this.status,
        linked: Boolean = this.linked
    ) {
        val same = hr == this.hr && cadence == this.cadence &&
            status == this.status && linked == this.linked
        if (same) return
        this.hr = hr; this.cadence = cadence; this.status = status; this.linked = linked
        onChange?.invoke()
    }
}
