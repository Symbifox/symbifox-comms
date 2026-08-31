package com.bluefoxconsultant.sms.ui.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiallingTest {

    @Test
    fun `a short number cannot be called`() {
        assertFalse(isCallable(""))
        assertFalse(isCallable("555"))
        assertFalse(isCallable("555555014"))
        // 911 and other short codes are refused server-side on purpose; the
        // button must not invite the attempt either.
        assertFalse(isCallable("911"))
    }

    @Test
    fun `ten digits is enough`() {
        assertTrue(isCallable("5555550142"))
        assertTrue(isCallable("15555550142"))
    }

    @Test
    fun `punctuation typed or pasted does not count as digits`() {
        assertFalse(isCallable("(555) 555-01"))
        assertTrue(isCallable("(555) 555-0142"))
        assertTrue(isCallable("+1 555 555 0142"))
    }

    @Test
    fun `digits keep the last ten, which is what identifies the line`() {
        assertEquals("5555550142", dialledDigits("+1 (555) 555-0142"))
        assertEquals("5555550142", dialledDigits("15555550142"))
        assertEquals("555", dialledDigits("555"))
    }
}
