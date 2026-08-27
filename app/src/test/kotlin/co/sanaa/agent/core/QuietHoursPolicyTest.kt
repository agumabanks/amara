package co.sanaa.agent.core

import java.time.LocalTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursPolicyTest {
    @Test fun overnightWindowIncludesLateNightAndEarlyMorning() {
        val policy = QuietHoursPolicy.parse("22:00", "06:30")!!
        assertTrue(policy.contains(LocalTime.of(23, 0)))
        assertTrue(policy.contains(LocalTime.of(6, 0)))
        assertFalse(policy.contains(LocalTime.of(12, 0)))
    }

    @Test fun equalTimesDisableQuietHours() {
        val policy = QuietHoursPolicy.parse("07:00", "07:00")!!
        assertFalse(policy.contains(LocalTime.of(7, 0)))
        assertFalse(policy.contains(LocalTime.of(20, 0)))
    }
}
