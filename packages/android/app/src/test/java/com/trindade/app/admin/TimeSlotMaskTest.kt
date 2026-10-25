package com.trindade.app.admin

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The hour mask, including the case that decides whether it is usable: deleting.
 */
class TimeSlotMaskTest {

    @Test
    fun `the colon lands between hour and minute`() {
        assertEquals("1", formatTimeSlotMask("1"))
        assertEquals("14", formatTimeSlotMask("14"))
        assertEquals("14:3", formatTimeSlotMask("143"))
        assertEquals("14:30", formatTimeSlotMask("1430"))
    }

    @Test
    fun `a pasted time and a typed one agree`() {
        assertEquals("14:30", formatTimeSlotMask("14:30"))
        assertEquals("14:30", formatTimeSlotMask("14.30"))
        assertEquals("14:30", formatTimeSlotMask("14h30"))
    }

    @Test
    fun `a fifth digit is refused instead of shifting the hour`() {
        assertEquals("14:30", formatTimeSlotMask("143099"))
    }

    @Test
    fun `deleting walks back out of the colon`() {
        assertEquals("14:3", formatTimeSlotMask("14:30".dropLast(1)))
        assertEquals("14", formatTimeSlotMask("14:".dropLast(1)))
        assertEquals("1", formatTimeSlotMask("14".dropLast(1)))
        assertEquals("", formatTimeSlotMask(""))
    }

    @Test
    fun `letters never reach the field`() {
        assertEquals("12", formatTimeSlotMask("ab12cd"))
    }
}
