package com.assistant.adi.ui.buddy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuddyTimePolicyTest {
    @Test
    fun nightRangeStartsAtTwentyTwoAndEndsAtFive() {
        assertFalse(BuddyTimePolicy.isNight(21))
        assertTrue(BuddyTimePolicy.isNight(22))
        assertTrue(BuddyTimePolicy.isNight(0))
        assertTrue(BuddyTimePolicy.isNight(4))
        assertFalse(BuddyTimePolicy.isNight(5))
    }
}
