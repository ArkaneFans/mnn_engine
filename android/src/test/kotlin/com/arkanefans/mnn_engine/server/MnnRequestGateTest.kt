package com.arkanefans.mnn_engine.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MnnRequestGateTest {
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
