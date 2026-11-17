package com.example

import com.example.sip.CallEvent
import com.example.sip.CallResult
import com.example.sip.isEmergencyNumber
import com.example.sip.result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallResultTest {
    private fun ended(
        incoming: Boolean = false,
        answered: Boolean = false,
        code: Int = 0,
        local: Boolean = false
    ) = CallEvent.CallEnded(
        "sip:01711000001@pbx", "", if (answered) 30 else 0, wasMissed = incoming && !answered,
        isIncoming = incoming, answered = answered, sipCode = code, endedLocally = local
    )

    @Test
    fun outgoingResults() {
        assertEquals(CallResult.ANSWERED, ended(answered = true).result)
        assertEquals(CallResult.ANSWERED, ended(answered = true, local = true).result)
        assertEquals(CallResult.BUSY, ended(code = 486).result)
        assertEquals(CallResult.NO_ANSWER, ended(code = 480).result)
        assertEquals(CallResult.NO_ANSWER, ended(code = 408).result)
        assertEquals(CallResult.DECLINED, ended(code = 603).result)
        assertEquals(CallResult.CANCELLED, ended(local = true).result)
        assertEquals(CallResult.FAILED, ended(code = 404).result)
        assertEquals(CallResult.FAILED, ended(code = 0).result)
    }

    @Test
    fun incomingResults() {
        assertEquals(CallResult.ANSWERED, ended(incoming = true, answered = true).result)
        assertEquals(CallResult.MISSED, ended(incoming = true).result)
        assertEquals(CallResult.DECLINED, ended(incoming = true, local = true).result)
    }

    @Test
    fun emergencyNumbersAreNeverHeldBack() {
        assertTrue(isEmergencyNumber("999"))
        assertTrue(isEmergencyNumber("sip:999@cloudpbx.example.com"))
        assertTrue(isEmergencyNumber("112"))
        assertFalse(isEmergencyNumber("9990"))
        assertFalse(isEmergencyNumber("01711000999"))
        assertFalse(isEmergencyNumber("sip:support@pbx"))
    }
}
