package co.sanaa.agent.core.artifacts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.AmaraMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Production delivery path for generated artifacts: latest revision renders into the
 * shareable cache exposed via FileProvider (agent-creatives/) and the written bytes hash
 * back to the stored content digest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArtifactDeliveryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(AmaraMemory.DATABASE_NAME)
        androidx.core.content.FileProvider().attachInfo(context,
            context.packageManager.resolveContentProvider("${context.packageName}.files",
                android.content.pm.PackageManager.GET_META_DATA)!!)
    }

    @Test fun latestRevisionDeliversThroughTheShareableCacheWithMatchingDigest() {
        val store = SqliteArtifactStore(AmaraMemory(context))
        val spec = ArtifactSpec(
            title = "Brief",
            format = ArtifactFormat.MARKDOWN_REPORT,
            sections = listOf(ArtifactSection("Summary", "Clean body.")),
            requiredHeadings = listOf("Summary"),
        )
        store.commit("brief", spec, nowMs = 1)

        val delivered = ArtifactDelivery.deliverLatest(context, store, "brief")
        assertNotNull(delivered)
        val (uri, revision) = delivered!!
        assertTrue("delivered through the FileProvider shareable cache", uri.path!!.contains("creativ"))
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertEquals(revision.renderedBytesHash, ContentDigest.of(bytes))
    }
}
