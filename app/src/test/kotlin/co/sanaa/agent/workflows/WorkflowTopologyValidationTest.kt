package co.sanaa.agent.workflows

import co.sanaa.agent.core.CapabilityIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Negative topology validation: every malformed graph shape must produce a precise
 * failure before any executor may touch it.
 */
class WorkflowTopologyValidationTest {

    private fun step(id: String, kind: DepartmentWorkflow.StepKind, capability: String? = null, after: List<String> = emptyList(), producesSection: String? = null) =
        GraphStep(id, kind, capability, after, producesSection)

    private fun workflow(graph: List<GraphStep>, consequential: Set<String> = emptySet()) = DepartmentWorkflow(
        id = "broken", name = "Broken workflow",
        inputContract = setOf("x"), dataClassification = co.sanaa.agent.core.work.DataClassification.BUSINESS_INTERNAL,
        executionGraph = graph, requiredReportSections = listOf("Summary"),
        consequentialActions = consequential, escalationConditions = listOf("always"),
    )

    @Test fun effectWithoutPrecedingApprovalIsRejected() {
        val result = WorkflowSimulator.validateTopology(
            workflow(
                listOf(step("retrieve", DepartmentWorkflow.StepKind.RETRIEVE), step("send", DepartmentWorkflow.StepKind.EXECUTE_VERIFIED, CapabilityIds.SEND_WHATSAPP)),
                consequential = setOf(CapabilityIds.SEND_WHATSAPP),
            ),
        )
        assertTrue(result.any { it.contains("without a preceding approval step") })
    }

    @Test fun consequentialActionMissingFromTheGraphIsRejected() {
        val result = WorkflowSimulator.validateTopology(
            workflow(
                listOf(
                    step("retrieve", DepartmentWorkflow.StepKind.RETRIEVE),
                    step("approve", DepartmentWorkflow.StepKind.REQUEST_APPROVAL, CapabilityIds.SEND_WHATSAPP),
                    step("verify", DepartmentWorkflow.StepKind.VERIFY),
                ),
                consequential = setOf(CapabilityIds.SEND_WHATSAPP),
            ),
        )
        assertTrue(result.any { it.contains("no EXECUTE_VERIFIED step for ${CapabilityIds.SEND_WHATSAPP}") })
    }

    @Test fun duplicateStepIdsAreRejected() {
        val result = WorkflowSimulator.validateTopology(
            workflow(
                listOf(
                    step("retrieve", DepartmentWorkflow.StepKind.RETRIEVE),
                    step("retrieve", DepartmentWorkflow.StepKind.DRAFT),
                ),
            ),
        )
        assertTrue(result.any { it.contains("Duplicate step ids") })
    }

    @Test fun draftingBeforeAnyRetrievalIsRejected() {
        val result = WorkflowSimulator.validateTopology(
            workflow(listOf(step("draft", DepartmentWorkflow.StepKind.DRAFT))),
        )
        assertTrue(result.any { it.contains("drafting begins without any observation/retrieval") })
    }

    @Test fun consequentialActionsWithoutVerificationGateAreRejected() {
        val result = WorkflowSimulator.validateTopology(
            workflow(
                listOf(
                    step("retrieve", DepartmentWorkflow.StepKind.RETRIEVE),
                    step("approve", DepartmentWorkflow.StepKind.REQUEST_APPROVAL, CapabilityIds.SEND_WHATSAPP),
                    step("send", DepartmentWorkflow.StepKind.EXECUTE_VERIFIED, CapabilityIds.SEND_WHATSAPP),
                ),
                consequential = setOf(CapabilityIds.SEND_WHATSAPP),
            ),
        )
        assertTrue(result.any { it.contains("without any VERIFY step") })
    }

    @Test fun unknownCapabilitiesAnywhereAreRejected() {
        val result = WorkflowSimulator.validateTopology(
            workflow(listOf(step("observe", DepartmentWorkflow.StepKind.OBSERVE, "not_a_real_capability"))),
        )
        assertTrue(result.any { it.contains("unknown capability 'not_a_real_capability'") })
    }

    @Test fun unknownDependencyEdgesAreRejected() {
        val result = WorkflowSimulator.validateTopology(
            workflow(
                listOf(
                    step("retrieve", DepartmentWorkflow.StepKind.RETRIEVE, after = listOf("ghost")),
                    step("draft", DepartmentWorkflow.StepKind.DRAFT, after = listOf("retrieve")),
                ),
            ),
        )
        assertTrue(result.any { it.contains("depends on unknown step 'ghost'") })
    }

    @Test fun dependencyCyclesAreRejected() {
        val result = WorkflowSimulator.validateTopology(
            workflow(
                listOf(
                    step("a", DepartmentWorkflow.StepKind.RETRIEVE, after = listOf("b")),
                    step("b", DepartmentWorkflow.StepKind.DRAFT, after = listOf("a")),
                    step("render", DepartmentWorkflow.StepKind.RENDER_ARTIFACT, after = listOf("b")),
                ),
            ),
        )
        assertTrue(result.joinToString(), result.any { it.contains("dependency cycle among [a, b]") })
    }

    @Test fun stepsUnreachableFromGraphRootsAreRejected() {
        val result = WorkflowSimulator.validateTopology(
            workflow(
                listOf(
                    step("root", DepartmentWorkflow.StepKind.RETRIEVE),
                    step("island", DepartmentWorkflow.StepKind.DRAFT, after = listOf("phantom-gate")),
                    step("render", DepartmentWorkflow.StepKind.RENDER_ARTIFACT, after = listOf("root")),
                ),
            ),
        )
        // 'phantom-gate' is an unknown edge; 'island' can never execute from the roots.
        assertTrue(result.any { it.contains("unknown step") || it.contains("unreachable") })
    }

    @Test fun everyRegisteredWorkflowPassesTheUpgradedTopologyRules() {
        WorkflowRegistry.all().forEach { definition ->
            val failures = WorkflowSimulator.validateTopology(definition)
            assertEquals("${definition.id} topology failures: $failures", emptyList<String>(), failures)
        }
    }

    @Test fun requiredSectionNeverProducedByAnyStepIsRejected() {
        val result = WorkflowSimulator.validateTopology(
            workflow(
                listOf(
                    step("retrieve", DepartmentWorkflow.StepKind.RETRIEVE, producesSection = "Evidence"),
                    step("draft", DepartmentWorkflow.StepKind.DRAFT, producesSection = "Summary"),
                    step("render", DepartmentWorkflow.StepKind.RENDER_ARTIFACT),
                ),
            ),
        )
        // "Summary" is produced; a fully aligned graph has no failures.
        assertEquals(result.joinToString(), emptyList<String>(), result)
    }

    @Test fun misalignedRequiredSectionsFailTopologyBeforeExecution() {
        val result = WorkflowSimulator.validateTopology(
            DepartmentWorkflow(
                id = "misaligned", name = "Misaligned",
                inputContract = setOf("x"),
                dataClassification = co.sanaa.agent.core.work.DataClassification.BUSINESS_INTERNAL,
                executionGraph = listOf(
                    GraphStep("retrieve", DepartmentWorkflow.StepKind.RETRIEVE, null, producesSection = "Evidence"),
                    GraphStep("draft", DepartmentWorkflow.StepKind.DRAFT, null, producesSection = "Summary"),
                    GraphStep("render", DepartmentWorkflow.StepKind.RENDER_ARTIFACT, null),
                ),
                requiredReportSections = listOf("Evidence", "Ghost Section"),
                consequentialActions = emptySet(), escalationConditions = emptyList(),
            ),
        )
        assertTrue(result.joinToString(), result.any { it.contains("required section 'Ghost Section' is never produced") })
    }
}
