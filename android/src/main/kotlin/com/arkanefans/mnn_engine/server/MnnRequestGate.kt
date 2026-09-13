package com.arkanefans.mnn_engine.server

/** One gate per server run: closing it also invalidates requests still preparing their input. */
internal class MnnRequestGate {
    enum class Admission { ACCEPTED, BUSY, STOPPING }

    @Volatile
    var isOpen: Boolean = true
        private set
    private var busy = false

    @Synchronized
    fun acquire(): Admission = when {
        !isOpen -> Admission.STOPPING
        busy -> Admission.BUSY
        else -> Admission.ACCEPTED.also { busy = true }
    }

    @Synchronized
    fun release() {
        busy = false
    }

    @Synchronized
    fun close() {
        isOpen = false
    }
}
