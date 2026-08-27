package co.sanaa.agent.modules

import co.sanaa.agent.actions.AccessibilityActions
import co.sanaa.agent.core.ActionRisk
import co.sanaa.agent.core.CapabilityIds
import co.sanaa.agent.core.Initiator
import co.sanaa.agent.core.AmaraMemory
import co.sanaa.agent.core.HumanPacing
import co.sanaa.agent.core.InteractionKind
import co.sanaa.agent.core.SideEffectLedger
import co.sanaa.agent.core.SideEffectOutcome
import co.sanaa.agent.core.SideEffectRunner
import co.sanaa.agent.core.SecureConfig
import co.sanaa.agent.core.VerificationEvidence
import co.sanaa.agent.actions.SokoSaveVerification
import kotlinx.coroutines.delay

data class SokoEditResult(
    val success: Boolean,
    val productName: String,
    val summary: String,
    val before: Map<String, String> = emptyMap(),
    val after: Map<String, String> = emptyMap(),
)

class SokoEditModule(
    private val config: SecureConfig,
    private val actions: AccessibilityActions,
    private val memory: AmaraMemory,
    private val sideEffects: SideEffectRunner = SideEffectRunner(SideEffectLedger.from(memory)),
) {
    suspend fun proposeEdit(
        productName: String,
        fieldName: String,
        currentValue: String,
        proposedValue: String,
        risk: ActionRisk = ActionRisk.LOW_IMPACT_CHANGE,
    ): SokoEditResult {
        if (!actions.openSokoEditForm(productName)) {
            return SokoEditResult(false, productName, "Could not open the edit form for $productName.")
        }
        val before = readRelevantFields(fieldName)
        val observedCurrent = fieldValue(before, fieldName).ifBlank { currentValue }
        val beforeJson = buildJson(mapOf(fieldName to observedCurrent))
        val afterJson = buildJson(mapOf(fieldName to proposedValue))
        val description = "Edit $productName: change $fieldName from '$observedCurrent' to '$proposedValue'."
        memory.createApprovalRequest(
            "edit_soko_listing", productName, description,
            beforeJson, afterJson, risk,
        )
        return SokoEditResult(true, productName, "Prepared an edit proposal for $productName. The change to $fieldName is waiting for your approval.", before)
    }

    suspend fun applyApprovedEdit(productName: String, fieldName: String, newValue: String): SokoEditResult {
        val afterJson = buildJson(mapOf(fieldName to newValue))
        val approval = memory.matchingApprovedApproval(EDIT_CAPABILITY, productName, afterJson)
            ?: return SokoEditResult(false, productName, "No unexpired approval exactly matches $productName, $fieldName, and the proposed value. Nothing was changed.")
        val idempotencyKey = "soko-edit-approval:${approval.id}"
        val outcome = sideEffects.execute(
            capabilityId = CapabilityIds.APPLY_SOKO_EDIT,
            idempotencyKey = idempotencyKey,
            target = productName,
            content = newValue,
            initiator = Initiator.OWNER_CHAT,
            inputs = mapOf("product" to productName, "field" to fieldName, "value" to newValue),
            approvalId = approval.id,
            approvalValidator = { id -> memory.approvalStillValid(id, EDIT_CAPABILITY, productName, afterJson) },
            act = { performApprovedSave(productName, fieldName, newValue, approval.id) },
            verify = { verifySavedField(productName, fieldName, newValue) },
        )
        val before = emptyMap<String, String>()
        return when (outcome) {
            is SideEffectOutcome.Verified -> {
                val after = readRelevantFields(fieldName)
                memory.recordAction(
                    "soko_edit", productName, "Soko Terminal", "Edit $productName $fieldName",
                    "Edited $productName: $fieldName is now '$newValue'. Saved and verified.",
                    "Field value verified after save.", null, true,
                )
                SokoEditResult(true, productName, "Edited $productName: $fieldName is now '$newValue'. Saved and verified.", before, after)
            }
            is SideEffectOutcome.DuplicateBlocked ->
                SokoEditResult(false, productName, "This approved edit was already applied once, so I will not apply it twice.")
            is SideEffectOutcome.Rejected -> {
                memory.recordAction("soko_edit", productName, "Soko Terminal", "Edit $productName $fieldName", outcome.reason, "Refused before any change.", null, false)
                SokoEditResult(false, productName, outcome.reason)
            }
            is SideEffectOutcome.Failed -> {
                memory.recordAction("soko_edit", productName, "Soko Terminal", "Edit $productName $fieldName", outcome.reason, "Nothing was saved.", null, false)
                SokoEditResult(false, productName, outcome.reason)
            }
            is SideEffectOutcome.Uncertain -> {
                memory.recordAction("soko_edit", productName, "Soko Terminal", "Edit $productName $fieldName", outcome.reason, "Verification inconclusive; no automatic retry.", null, false)
                SokoEditResult(false, productName, "${outcome.reason} Check the listing on the phone and tell me exactly what to do next.")
            }
        }
    }

    /**
     * One attempt of the approved change. Returns false only when it is provable that
     * nothing was saved (form unreachable, field unset, or approval invalidated first).
     */
    // TRANSACTION-ACT: this helper runs exclusively inside the apply_soko_edit act lambda.
    private suspend fun performApprovedSave(productName: String, fieldName: String, newValue: String, approvalId: Long): Boolean {
        if (!actions.openSokoEditForm(productName)) return false
        val fieldNameLow = fieldName.lowercase()
        val set = when {
            fieldNameLow.contains("name") || fieldNameLow == "product name" -> actions.setEditFieldByOrder(0, newValue)
            fieldNameLow.contains("price") || fieldNameLow == "selling price" -> {
                actions.scrollToEditField("Selling Price") && actions.setEditFieldByOrder(0, newValue)
            }
            fieldNameLow.contains("stock") || fieldNameLow == "stock qty" -> {
                actions.scrollToEditField("Stock Qty") && actions.setEditFieldByOrder(0, newValue)
            }
            fieldNameLow.contains("description") || fieldNameLow == "product description" -> {
                actions.scrollToEditField("Product Description") && actions.setEditFieldByOrder(0, newValue)
            }
            else -> false
        }
        if (!set) return false
        // Consume the exact approval immediately before the save click so a crash can
        // never leave an unconsumed approval paired with a possibly completed save.
        if (!memory.consumeApproval(approvalId)) return false
        return actions.transacted { saveEditForm() }
    }

    private suspend fun verifySavedField(productName: String, fieldName: String, newValue: String): VerificationEvidence {
        delay(HumanPacing.delayMillis(InteractionKind.NETWORK_CONTENT))
        // Reopen the saved form and compare the exact field against the approved value.
        val reopened = actions.openSokoEditForm(productName)
        val evidence = SokoSaveVerification.evaluate(
            fieldName, newValue,
            SokoSaveVerification.ReopenObservation(
                reopened = reopened,
                observedPackage = if (reopened) SokoSaveVerification.SOKO_PACKAGE else "",
                fieldMatchesApprovedValue = reopened && actions.verifyEditFormFields(mapOf(fieldName to newValue)),
                observedAtMs = System.currentTimeMillis(),
            ),
        )
        return evidence
    }

    private suspend fun readRelevantFields(fieldName: String): Map<String, String> {
        val form = actions.readSokoEditForm()
        return mapOf(
            "Product Name" to form.productName,
            "Selling Price" to form.sellingPrice,
            "Stock Qty" to form.stockQty,
            "Product Description" to form.description,
        ) + form.rawFields
    }

    private fun buildJson(fields: Map<String, String>): String =
        org.json.JSONObject().apply { fields.forEach { (k, v) -> put(k, v) } }.toString()

    private fun fieldValue(fields: Map<String, String>, requested: String): String {
        val normalized = requested.lowercase().filter(Char::isLetterOrDigit)
        return fields.entries.firstOrNull {
            val candidate = it.key.lowercase().filter(Char::isLetterOrDigit)
            candidate == normalized || candidate.contains(normalized) || normalized.contains(candidate)
        }?.value.orEmpty()
    }

    companion object { private const val EDIT_CAPABILITY = CapabilityIds.APPLY_SOKO_EDIT }
}
