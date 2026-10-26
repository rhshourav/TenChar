package com.rhshourav.tenchar

import com.rhshourav.tenchar.ui.DemoData
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoDataTest {
    @Test
    fun sampleMessagesRespectTheLimits() {
        assertTrue(DemoData.sample.isNotEmpty())
        for ((text, _) in DemoData.sample) assertTrue(text, MessageLimits.fits(text))
    }

    @Test
    fun sampleNumberIsTwoGroupsOfFourDigits() {
        assertTrue(Regex("""\d{4} \d{4}""").matches(DemoData.SAMPLE_NUMBER))
    }
}
