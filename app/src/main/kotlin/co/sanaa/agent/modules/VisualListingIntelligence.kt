package co.sanaa.agent.modules

import co.sanaa.agent.api.BrainFailureFinalizer
import co.sanaa.agent.api.GroqClient
import co.sanaa.agent.api.ModelFailureKind
import co.sanaa.agent.api.ModelResponseException
import co.sanaa.agent.api.ModelSchemas
import co.sanaa.agent.core.AmaraMemory
import co.sanaa.agent.core.ContentHashing
import java.io.File

data class VisualListingAssessment(
    val listingName: String,
    val imageDescription: String,
    val mismatch: Boolean,
    val issue: String,
    val confidence: Double,
    val screenshotPath: String,
)

object VisualAssessmentGuard {
    fun accepted(value: VisualListingAssessment, expectedListing: String): Boolean =
        value.listingName.equals(expectedListing, true) &&
            value.screenshotPath.isNotBlank() &&
            value.imageDescription.length >= 8 &&
            value.confidence in 0.70..1.0 &&
            (!value.mismatch || value.issue.length >= 8)
}

/** Typed outcome of one visual audit attempt — never a silent null on model failure. */
sealed class InspectionResult {
    data class Verified(val assessment: VisualListingAssessment) : InspectionResult()
    data class Blocked(val reason: String) : InspectionResult()
    data class Failed(val error: ModelResponseException) : InspectionResult()
}

class VisualListingIntelligence(private val groq: GroqClient, private val memory: AmaraMemory) {

    /**
     * Full typed audit path: schema-validated vision call, guard evaluation, and
     * durable failure records (task id "visual_audit", stage "vision"). The model
     * correlation id (contract §3) is deterministic per listing, so every attempt,
     * repair row, and terminal outcome for one audit chains under one id.
     */
    suspend fun inspectListing(listingName: String, screenshotPath: String): InspectionResult {
        val screenshot = File(screenshotPath)
        if (!screenshot.isFile) {
            return InspectionResult.Blocked("Screenshot file is missing: $screenshotPath")
        }
        val correlationId = "$MODULE_TAG${ContentHashing.hash(listingName).take(24)}"
        val assessment = try {
            assess(listingName, screenshot, correlationId)
        } catch (error: ModelResponseException) {
            recordModelFailure(error)
            BrainFailureFinalizer.finalizeFailed(
                memory, correlationId, ModelSchemas.VISUAL_AUDIT.name, error.kind, "visual listing audit",
            )
            return InspectionResult.Failed(error)
        } catch (error: IllegalStateException) {
            // Consent/config preconditions surface as blocked, never as model failures.
            return InspectionResult.Blocked(error.message ?: "Vision audit precondition unmet")
        }
        if (!VisualAssessmentGuard.accepted(assessment, listingName)) {
            return InspectionResult.Blocked(
                "Guard rejected the audit: listing match=${assessment.listingName.equals(listingName, true)}, " +
                    "confidence=${assessment.confidence}, descriptionLength=${assessment.imageDescription.length}",
            )
        }
        if (assessment.mismatch) {
            memory.recordBusinessFinding(
                "Soko visual audit", listingName, assessment.issue, "high", assessment.confidence,
                "Screenshot ${assessment.screenshotPath}; visible image: ${assessment.imageDescription}",
                "Replace or recrop the listing image after reviewing the screenshot and approving the exact asset.",
            )
        }
        return InspectionResult.Verified(assessment)
    }

    /** Legacy wrapper kept compiling for existing callers; null now means Blocked or Failed. */
    suspend fun inspect(listingName: String, screenshotPath: String): VisualListingAssessment? =
        when (val result = inspectListing(listingName, screenshotPath)) {
            is InspectionResult.Verified -> result.assessment
            is InspectionResult.Blocked, is InspectionResult.Failed -> null
        }

    private suspend fun assess(listingName: String, screenshot: File, correlationId: String): VisualListingAssessment {
        val json = groq.completeVisionJson(
            """Inspect this Soko listing screenshot. The exact listing being reviewed is: $listingName.
                |Describe only the visible cover/product image. Decide whether that image clearly mismatches the listing name, is missing, duplicated, badly cropped, or contains unreadable promotional text. Do not infer invisible details. If uncertain, mismatch must be false and confidence low.
                |Return ONLY JSON: {"listing_name":"$listingName","image_description":"","mismatch":false,"issue":"","confidence":0.0}""".trimMargin(),
            screenshot,
            ModelSchemas.VISUAL_AUDIT,
            correlationId,
        )
        BrainFailureFinalizer.markRecovered(memory, correlationId, ModelSchemas.VISUAL_AUDIT.name)
        return VisualListingAssessment(
            json.optString("listing_name").trim(), json.optString("image_description").trim(), json.optBoolean("mismatch"),
            json.optString("issue").trim(), json.optDouble("confidence", 0.0), screenshot.absolutePath,
        )
    }

    private suspend fun recordModelFailure(error: ModelResponseException) {
        memory.recordFailure(
            taskId = TASK_ID,
            runId = "",
            stepId = STEP_ID,
            capability = CAPABILITY,
            targetPackage = "co.sanaa.agent",
            stage = STAGE,
            cause = "${error.kind.name}: ${error.message ?: "model vision call failed"}",
            retryable = error.kind.retryable || error.kind == ModelFailureKind.CIRCUIT_OPEN,
            attemptCount = error.attemptCount,
            screenEvidenceJson = "{}",
            correctiveAction = "REVIEW_MODEL_OUTPUT_AND_RETRY_LATER:${error.kind.name}",
            disposition = when {
                error.kind == ModelFailureKind.CIRCUIT_OPEN -> "CIRCUIT_OPEN"
                error.kind.retryable -> "RETRY_EXHAUSTED"
                else -> "FAILED_PERMANENT"
            },
            nextSafeAction = "Retry the visual audit after the model service recovers, or review the screenshot manually.",
        )
    }

    private companion object {
        const val TASK_ID = "visual_audit"
        const val STEP_ID = "inspect_listing"
        const val CAPABILITY = "vision_listing_audit"
        const val STAGE = "vision"
        const val MODULE_TAG = "visual-audit-"
    }
}
