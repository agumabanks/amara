package co.sanaa.agent.core.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeTest {

    private fun claim(kind: ClaimKind, statement: String, capturedAtMs: Long, source: String = "soko://terminal/listings") =
        SourcedClaim(kind, statement, source, capturedAtMs, freshnessMs = 3_600_000)

    @Test fun factsRequireProvenance() {
        var rejected = false
        runCatching { SourcedClaim(ClaimKind.FACT, "Price is 450000", "", 0, 1) }.onFailure { rejected = true }
        assertTrue(rejected)
    }

    @Test fun unknownsAndAssumptionsMayLackSources() {
        SourcedClaim(ClaimKind.UNKNOWN, "Stock level not visible", "", 0, 1)
        SourcedClaim(ClaimKind.ASSUMPTION, "Owner will approve", "", 0, 1)
    }

    @Test fun staleClaimsAreSurfacedNotSilentlyUsed() {
        val base = KnowledgeBase()
        base.add(claim(ClaimKind.FACT, "Basket price 30000", capturedAtMs = 0))
        val (fresh, stale) = base.freshClaims(nowMs = 4_000_000)
        assertTrue(fresh.isEmpty() && stale.size == 1)
        assertTrue(base.digest(4_000_000).contains("STALE"))
    }

    @Test fun newerFactSupersedesOlderButConflictIsRecorded() {
        val base = KnowledgeBase()
        base.add(claim(ClaimKind.FACT, "Basket price", capturedAtMs = 100))
        base.add(claim(ClaimKind.FACT, "Basket price", capturedAtMs = 200))
        assertEquals(1, base.recordedConflicts().size)
        assertEquals(200, base.recordedConflicts().first().winner.capturedAtMs)
    }

    @Test fun secretShapedStatementsNeverEnterTheKnowledgeBase() {
        val base = KnowledgeBase()
        var rejected = false
        runCatching { base.add(claim(ClaimKind.FACT, "the pin is 123456", 0)) }.onFailure { rejected = true }
        assertTrue(rejected)
    }

    @Test fun scopedRetrievalRejectsOutOfContractSubjects() {
        val base = KnowledgeBase()
        base.add(claim(ClaimKind.FACT, "Soko bookings pending today", 0))
        val scoped = ScopedRetrieval(base, allowedSubjects = setOf("soko"))
        assertTrue(scoped.retrieve(setOf("soko"), nowMs = 10).isNotEmpty())
        assertTrue(scoped.retrieve(setOf("soko bookings"), nowMs = 10).isNotEmpty())
        var rejected = false
        runCatching { scoped.retrieve(setOf("whatsapp history"), nowMs = 10) }.onFailure { rejected = true }
        assertTrue(rejected)
    }

    @Test fun digestDistinguishesKindsAndCarriesSourceRefs() {
        val base = KnowledgeBase()
        base.add(claim(ClaimKind.FACT, "Sales rose last week", 0, source = "soko://kpi"))
        base.add(SourcedClaim(ClaimKind.RECOMMENDATION, "Restock baskets", "inference-engine", 0, 60_000))
        val digest = base.digest(nowMs = 10)
        assertTrue(digest.contains("[FACT]") && digest.contains("soko://kpi") && digest.contains("[RECOMMENDATION]"))
        assertFalse(digest.contains("STALE"))
    }
}
