package com.example

import com.example.sip.toDialableNumber
import org.junit.Assert.assertEquals
import org.junit.Test

class DialNumberTest {
    @Test
    fun bangladeshInternationalFormsBecomeLocal() {
        assertEquals("01712345678", toDialableNumber("+8801712345678"))
        assertEquals("01712345678", toDialableNumber("008801712345678"))
        assertEquals("01712345678", toDialableNumber("8801712345678"))
        assertEquals("09678771660", toDialableNumber("+8809678771660"))
    }

    @Test
    fun everythingElseIsUnchanged() {
        assertEquals("01712345678", toDialableNumber("01712345678"))
        assertEquals("1002", toDialableNumber("1002"))
        assertEquals("alice", toDialableNumber("alice"))
        assertEquals("+14155550123", toDialableNumber("+14155550123"))
        assertEquals("+", toDialableNumber("+"))
        assertEquals("*121#", toDialableNumber("*121#"))
    }
}
