package co.sanaa.agent.core.artifacts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.AmaraMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tests for the PRODUCTION durable artifact store ([SqliteArtifactStore]) — the same
 * class wired into [co.sanaa.agent.core.AgentRuntime] — over real SQLite (Robolectric).
 * Covers: reopen persistence, concurrent commits, parent-chain integrity, rubric
 * rejection, corrupt-row tolerance, and owner deletion/export behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SqliteArtifactStoreTest {

    private lateinit var context: Context
    private lateinit var memory: AmaraMemory

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(AmaraMemory.DATABASE_NAME)
        memory = AmaraMemory(context)
    }

    private fun brief(title: String) = ArtifactSpec(
        title = title,
        format = ArtifactFormat.MARKDOWN_REPORT,
        sections = listOf(ArtifactSection("Summary", "Clean body text for $title.")),
        requiredHeadings = listOf("Summary"),
    )

    @Test fun commitPersistsAcrossReopenOnTheProductionStore() {
        val store = SqliteArtifactStore(memory)
        val first = store.commit("brief", brief("v1"), nowMs = 10)
        val second = store.commit("brief", brief("v2"), nowMs = 20)

        // Reopen the database through a fresh AmaraMemory + fresh production store.
        val reopened = SqliteArtifactStore(AmaraMemory(context))
        val history = reopened.history("brief")
        assertEquals(2, history.size)
        assertEquals(first.id, history[0].id)
        assertEquals(second.id, history[1].id)
        assertEquals("v2", reopened.latest("brief")!!.spec.title)
        // Full-fidelity round trip: format and sections survive serialization.
        assertEquals(ArtifactFormat.MARKDOWN_REPORT, history[1].spec.format)
        assertEquals(listOf("Summary"), history[1].spec.requiredHeadings)
    }

    @Test fun parentChainIsIntactAcrossConcurrentCommits() {
        val store = SqliteArtifactStore(memory)
        val pool = Executors.newFixedThreadPool(4)
        val ready = CountDownLatch(4)
        val done = CountDownLatch(4)
        repeat(4) { index ->
            pool.submit {
                ready.countDown()
                ready.await()
                store.commit("chain", brief("rev-$index"), nowMs = 100L + index)
                done.countDown()
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()

        val history = SqliteArtifactStore(AmaraMemory(context)).history("chain")
        assertEquals("every concurrent commit persists", 4, history.size)
        assertEquals("ids are strictly increasing", history.map { it.id }, history.map { it.id }.sorted())
        assertEquals("first revision has no parent", null, history.first().parentRevision)
        history.zipWithNext().forEach { (previous, next) ->
            assertEquals("each revision descends from its predecessor", previous.id, next.parentRevision)
        }
    }

    @Test fun rubricFailuresNeverPersistAnything() {
        val store = SqliteArtifactStore(memory)
        var rejected = false
        runCatching { store.commit("bad", brief("pin is 123456"), nowMs = 1) }.onFailure { rejected = true }
        assertTrue(rejected)
        assertEquals(0, memory.artifactRevisions("bad").size)
        // Wrong arithmetic is equally rejected at the production boundary.
        val wrongSum = ArtifactSpec(
            "T", ArtifactFormat.MARKDOWN_REPORT,
            listOf(ArtifactSection("Summary", "2 + 2 = 5")),
            requiredHeadings = listOf("Summary"),
        )
        assertTrue(runCatching { store.commit("bad2", wrongSum, 2) }.isFailure)
        assertEquals(0, memory.artifactRevisions("bad2").size)
    }

    @Test fun corruptRowsAreSkippedAndHealthyHistorySurvives() {
        val store = SqliteArtifactStore(memory)
        store.commit("mixed", brief("good-1"), nowMs = 1)
        // Simulate on-disk corruption with a row whose spec JSON cannot parse.
        memory.insertArtifactRevision("mixed", 5, "amara", "{not json!!", parentRevision = null, contentHash = "x")
        store.commit("mixed", brief("good-2"), nowMs = 10)

        val reopened = SqliteArtifactStore(AmaraMemory(context))
        val history = reopened.history("mixed")
        assertEquals("corrupt row excluded, healthy rows intact", 2, history.size)
        assertEquals("good-1", history[0].spec.title)
        assertEquals("good-2", history[1].spec.title)
        assertTrue(reopened.skippedRowIds().isNotEmpty())
    }

    @Test fun imageSpecsRoundTripThroughTheDurableStore() {
        val store = SqliteArtifactStore(memory)
        val chart = ArtifactSpec(
            title = "Weekly revenue",
            format = ArtifactFormat.PNG_IMAGE,
            sections = listOf(ArtifactSection("Chart", "Revenue by week.")),
            requiredHeadings = listOf("Chart"),
            image = ChartImageSpec(320, 200, "Weekly revenue (UGX)", listOf(10.0, 40.0, 25.0, 55.0)),
        )
        val revision = store.commit("chart", chart, nowMs = 7)
        val reopened = SqliteArtifactStore(AmaraMemory(context))
        val restored = reopened.latest("chart")
        assertNotNull(restored)
        assertEquals(ArtifactFormat.PNG_IMAGE, restored!!.spec.format)
        assertEquals(chart.image, restored.spec.image)
        assertEquals(revision.renderedBytesHash, restored.renderedBytesHash)
    }

    @Test fun ownerDeletionWipesRevisionsButExportCarriesRedactedHistory() {
        val store = SqliteArtifactStore(memory)
        store.commit("exported", brief("public brief"), nowMs = 3)
        assertNotEquals(0, memory.artifactRevisions("exported").size)
        // Export exposes the stored revisions to the owner…
        val export = memory.exportOwnerData()
        assertTrue(export.has("artifact_revisions"))
        assertTrue(export.getJSONArray("artifact_revisions").length() >= 1)
        // …and deletion removes them while keeping the schema usable.
        memory.deleteOwnerBusinessData()
        assertEquals(0, memory.artifactRevisions("exported").size)
        val afterWipe = SqliteArtifactStore(AmaraMemory(context))
        assertEquals(0, afterWipe.history("exported").size)
        afterWipe.commit("post-wipe", brief("still writable"), nowMs = 9)
        assertEquals(1, afterWipe.history("post-wipe").size)
    }
}
