package com.x3trainer.telemetry

/** A non-simulated source used while live telemetry needs user action. */
class StatusSource(
    private val status: String,
    private val warning: String? = null,
) : TelemetrySource {
    override fun start(listener: TelemetrySource.Listener) {
        listener.onStatus(status)
        warning?.let(listener::onDeviceWarning)
    }

    override fun stop() = Unit
}
