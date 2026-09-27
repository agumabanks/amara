package co.sanaa.agent.core.work

import co.sanaa.agent.core.QuietHoursPolicy
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class NightlyDoctorSessionTest {
    @Test fun midnightDoesNotCreateAnotherMaintenanceSession() {
        val quiet = QuietHoursPolicy.parse("22:00", "06:00")!!
        val zone = ZoneId.of("Africa/Kampala")
        fun day(at: String) = NightlyDoctorCleanup.sessionDay(Instant.parse(at).toEpochMilli(), zone, quiet)
        assertEquals("2026-09-24", day("2026-09-24T20:00:00Z"))
        assertEquals("2026-09-24", day("2026-09-25T02:00:00Z"))
        assertNull(day("2026-09-25T03:00:00Z"))
        assertEquals("2026-09-25", day("2026-09-25T19:00:00Z"))
    }
}
