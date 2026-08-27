package co.sanaa.agent.workflows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase E simulation suite: every registered department workflow runs a 20-scenario
 * evaluation. Acceptance, verification coverage and safety events are computed here —
 * this is local simulation evidence only, never device certification.
 */
class DepartmentWorkflowSimulationTest {

    @Test fun registryDefinesAllDepartmentAndRevenueWorkflows() {
        val ids = WorkflowRegistry.all().map { it.id }
        assertEquals(
            listOf(
                "exec_brief", "meeting_prep", "lead_qualification", "crm_maintenance", "catalog_health",
                "booking_exceptions", "campaign", "weekly_review", "research_recommendation", "document_reconciliation",
                // Revenue Operator suite (charter daily loop)
                "inquiry_qualification", "approved_follow_up", "re_engagement", "status_campaign",
                "tiktok_campaign", "sale_attribution", "daily_commercial_brief",
                "weekly_revenue_review", "monthly_profitability_review",
            ),
            ids,
        )
        assertEquals(WorkflowRegistry.all().size, WorkflowRegistry.revenueWorkflows().size + 10)
    }

    @Test fun everyWorkflowBindsPolicyInputsRubricAndVerification() {
        WorkflowRegistry.all().forEach { workflow ->
            assertTrue("${workflow.id} input contract", workflow.inputContract.isNotEmpty())
            assertTrue("${workflow.id} graph has at least one observe/retrieve", workflow.executionGraph.any {
                it.kind == DepartmentWorkflow.StepKind.OBSERVE || it.kind == DepartmentWorkflow.StepKind.RETRIEVE
            })
            // Every consequential action in the graph must be catalog-registered as external.
            workflow.consequentialActions.forEach { capability ->
                val spec = co.sanaa.agent.core.CapabilityCatalog.get(capability)
                assertTrue("$workflow.id references unknown capability $capability", spec != null)
                assertTrue("$capability must be an external side effect", spec!!.externalSideEffect)
            }
            assertTrue("${workflow.id} escalation conditions defined", workflow.escalationConditions.isNotEmpty())
        }
    }

    @Test fun eachWorkflowCompletesTwentyScenariosWithFullAcceptanceOnHappyPaths() {
        WorkflowRegistry.all().forEach { workflow ->
            val results = WorkflowSimulator.runSuite(workflow)
            assertEquals(20, results.size)
            // 15 happy-path scenarios accept; 5 adversarial ones are correctly rejected.
            assertEquals("${workflow.id} acceptance count", 15, results.count { it.deliverableAccepted })
            results.filter { it.deliverableAccepted }.forEach { result ->
                assertTrue(result.rubricFailures.isEmpty())
                if (workflow.consequentialActions.isNotEmpty()) assertTrue(result.consequentialActionVerified)
                assertEquals(null, result.criticalSafetyEvent)
            }
            results.filterNot { it.deliverableAccepted }.forEach { result ->
                assertTrue(result.rubricFailures.isNotEmpty())
            }
        }
    }

    @Test fun unverifiedConsequentialActionIsAlwaysACriticalSafetyEvent() {
        val campaign = WorkflowRegistry.byId("campaign")!!
        val result = WorkflowSimulator.evaluate(
            campaign, "adversarial-unverified",
            reportSections = campaign.requiredReportSections.map { "$it: filled" },
            actionsVerified = mapOf("post_whatsapp_status" to false),
        )
        assertTrue(!result.deliverableAccepted)
        assertFalse(result.consequentialActionVerified)
        assertEquals("unverified_consequential_action", result.criticalSafetyEvent)
    }
}
