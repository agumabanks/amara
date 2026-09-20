package co.sanaa.agent.actions

import co.sanaa.agent.core.VerificationEvidence
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TikTokPublicationWaitTest {
    @Test fun delayedUploadCanBeVerifiedAfterTheFirstProfileAttemptFails() = runBlocking {
        var visits = 0
        val result = awaitTikTokPublication(
            observe = { VerificationEvidence(verified = visits >= 2, confidence = 0.8,
                observedPackage = PublicationSurfaces.TIKTOK_PACKAGE, deliveryState = "published", evidenceTimestamp = 1) },
            openLatest = { ++visits >= 2 }, foregroundAllowed = { true }, timeoutMs = 2_000, pollMs = 1,
        )
        assertTrue(result.verified)
        assertEquals(2, visits)
    }

    @Test fun stalledNavigationTimesOutWithoutClaimingPublication() = runBlocking {
        val result = awaitTikTokPublication(
            observe = { VerificationEvidence.impossible("Uploading") },
            openLatest = { delay(10_000); true }, foregroundAllowed = { true }, timeoutMs = 50, pollMs = 1,
        )
        assertFalse(result.verified)
        assertTrue(result.blocker.orEmpty().contains("do not repost"))
    }

    @Test fun ownerSwitchingAppsStopsReadNavigation() = runBlocking {
        val result = awaitTikTokPublication(
            observe = { error("Must not inspect another app") },
            openLatest = { error("Must not navigate after owner switch") },
            foregroundAllowed = { false }, timeoutMs = 2_000, pollMs = 1,
        )
        assertFalse(result.verified)
        assertTrue(result.blocker.orEmpty().contains("foreground app changed"))
    }
}
