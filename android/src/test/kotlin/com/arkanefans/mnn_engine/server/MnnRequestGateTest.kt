package com.arkanefans.mnn_engine.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MnnRequestGateTest {
    @Test
    fun cancellationCannotAffectAnotherRequestAndDoesNotReleaseAdmission() {
        val gate = MnnRequestGate()
        var nativeCancels = 0
        assertEquals(MnnRequestGate.Admission.ACCEPTED, gate.acquire("first"))
        assertFalse(gate.cancel("external") { nativeCancels++ })
        assertEquals(0, nativeCancels)
        assertEquals(true, gate.cancel("first") { nativeCancels++ })
        assertFalse(gate.allows("first"))
        assertEquals(true, gate.isActive("first"))
        assertEquals(MnnRequestGate.Admission.BUSY, gate.acquire("next"))
        gate.release("first")
        assertEquals(MnnRequestGate.Admission.ACCEPTED, gate.acquire("next"))
        gate.release("first")
        assertEquals(true, gate.isActive("next"))
        assertEquals(1, nativeCancels)
    }

    @Test
    fun cancellationRacingHttpAdmissionPreventsGeneration() {
        val gate = MnnRequestGate()
        gate.cancel("early") { error("Native must not be touched") }
        assertEquals(MnnRequestGate.Admission.CANCELLED, gate.acquire("early"))
        assertEquals(MnnRequestGate.Admission.ACCEPTED, gate.acquire("normal"))
    }

    @Test
    fun onlyOneRequestIsAdmittedAndClosingTakesPrecedenceOverBusy() {
        val gate = MnnRequestGate()
        assertEquals(MnnRequestGate.Admission.ACCEPTED, gate.acquire())
        assertEquals(MnnRequestGate.Admission.BUSY, gate.acquire())
        gate.close()
        assertEquals(MnnRequestGate.Admission.STOPPING, gate.acquire())
        assertFalse(gate.isOpen)
    }

    @Test
    fun finishingAnOldRequestCannotReopenItOrReleaseTheNewServersRequest() {
        val old = MnnRequestGate()
        assertEquals(MnnRequestGate.Admission.ACCEPTED, old.acquire())
        old.close()
        val restarted = MnnRequestGate()
        assertEquals(MnnRequestGate.Admission.ACCEPTED, restarted.acquire())
        old.release()
        assertEquals(MnnRequestGate.Admission.STOPPING, old.acquire())
        assertEquals(MnnRequestGate.Admission.BUSY, restarted.acquire())
        restarted.release()
        assertEquals(MnnRequestGate.Admission.ACCEPTED, restarted.acquire())
    }
}
