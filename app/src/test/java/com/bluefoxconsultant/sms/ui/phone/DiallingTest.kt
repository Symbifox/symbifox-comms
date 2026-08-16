package com.bluefoxconsultant.sms.ui.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiallingTest {

    @Test
    fun `a short number cannot be called`() {
        assertFalse(isCallable(""))
        assertFalse(isCallable("514"))
        assertFalse(isCallable("514513253"))
        // 911 and other short codes are refused server-side on purpose; the
        // button must not invite the attempt either.
        assertFalse(isCallable("911"))
    }

    @Test
    fun `ten digits is enough`() {
        assertTrue(isCallable("5145132535"))
        assertTrue(isCallable("15145132535"))
    }

    @Test
    fun `punctuation typed or pasted does not count as digits`() {
        assertFalse(isCallable("(514) 513-25"))
        assertTrue(isCallable("(514) 513-2535"))
        assertTrue(isCallable("+1 514 513 2535"))
    }

    @Test
    fun `digits keep the last ten, which is what identifies the line`() {
        assertEquals("5145132535", dialledDigits("+1 (514) 513-2535"))
        assertEquals("5145132535", dialledDigits("15145132535"))
        assertEquals("513", dialledDigits("513"))
    }
}
