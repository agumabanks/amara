package co.sanaa.agent.core.artifacts

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * PRODUCTION artifact delivery: renders the latest stored revision of an artifact and
 * writes it into the app-private shareable cache directory exposed through FileProvider
 * (`agent-creatives/`, authority `co.sanaa.agent.files`). Every generated artifact —
 * including PNG images, PDFs, and PPTX packages — reaches the owner through this single
 * audited path instead of ad-hoc file writes.
 */
object ArtifactDelivery {

    fun writeToShareableCache(context: Context, revision: ArtifactStore.StoredRevision): Uri {
        val extension = when (revision.spec.format) {
            ArtifactFormat.MARKDOWN_REPORT -> "md"
            ArtifactFormat.CSV_SPREADSHEET -> "csv"
            ArtifactFormat.STRUCTURED_JSON -> "json"
            ArtifactFormat.PDF_DOCUMENT -> "pdf"
            ArtifactFormat.PPTX_PRESENTATION -> "pptx"
            ArtifactFormat.PNG_IMAGE -> "png"
        }
        val safeName = revision.artifactId.replace(Regex("[^a-zA-Z0-9_-]+"), "_").take(60).ifBlank { "artifact" }
        val directory = File(context.cacheDir, "agent-creatives").apply { mkdirs() }
        val file = File(directory, "${safeName}_rev${revision.id}_${revision.renderedBytesHash.take(12)}.$extension")
        file.outputStream().use { output -> output.write(ArtifactRenderer.renderBytes(revision.spec)) }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    /** Renders and delivers the newest revision of [artifactId], or null when none exists. */
    fun deliverLatest(context: Context, store: ArtifactStore, artifactId: String): Pair<Uri, ArtifactStore.StoredRevision>? {
        val latest = store.latest(artifactId) ?: return null
        return writeToShareableCache(context, latest) to latest
    }
}
