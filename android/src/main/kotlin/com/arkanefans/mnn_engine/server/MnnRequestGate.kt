package com.arkanefans.mnn_engine.server

/** Request admission remains held until native work has actually returned. */
internal class MnnRequestGate {
    enum class Admission { ACCEPTED, BUSY, STOPPING, CANCELLED }
    @Volatile var isOpen = true
        private set
    private class Request(val id: String) { @Volatile var cancelled = false }
    @Volatile private var current: Request? = null
    private val cancelledBeforeAdmission = LinkedHashSet<String>()
    @Synchronized fun acquire(requestId: String = ""): Admission = when {
        !isOpen -> Admission.STOPPING
        current != null -> Admission.BUSY
        cancelledBeforeAdmission.remove(requestId) -> Admission.CANCELLED
        else -> Admission.ACCEPTED.also { current = Request(requestId) }
    }
    // This read must not take the gate lock: reset holds the native lock.
    fun allows(requestId: String): Boolean {
        val r = current
        return isOpen && r != null && r.id == requestId && !r.cancelled
    }
    fun isActive(requestId: String): Boolean = current?.id == requestId
    @Synchronized fun cancel(requestId: String, cancelNative: () -> Unit): Boolean {
        val r = current
        if (r == null || r.id != requestId) {
            if (cancelledBeforeAdmission.size >= 128) {
                cancelledBeforeAdmission.remove(cancelledBeforeAdmission.first())
            }
            cancelledBeforeAdmission.add(requestId)
            return false
        }
        r.cancelled = true
        cancelNative()
        return true
    }
    @Synchronized fun release(requestId: String? = null) {
        if (requestId == null || current?.id == requestId) current = null
    }
    @Synchronized fun close() { isOpen = false }
}
