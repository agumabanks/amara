package co.sanaa.agent.workers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AutonomousBypassStructureTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "app/src/main/AndroidManifest.xml").isFile }

    @Test fun releaseManifestDoesNotExportTikTokTestEntrypoints() {
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        val receiver = Regex("<receiver[^>]+TikTokTestReceiver[\\s\\S]*?</receiver>").find(manifest)?.value.orEmpty()
        val service = Regex("<service[^>]+TikTokTestService[\\s\\S]*?/>").find(manifest)?.value.orEmpty()
        assertTrue(receiver.contains("android:exported=\"false\""))
        assertTrue(service.contains("android:exported=\"false\""))
    }

    @Test fun autonomousAlarmWorkersOnlyWakeTheLoop() {
        val proactive = File(root, "app/src/main/kotlin/co/sanaa/agent/workers/ProactiveWorkers.kt").readText()
        val tiktok = File(root, "app/src/main/kotlin/co/sanaa/agent/workers/TikTokGrowthWorker.kt").readText()
        assertTrue(proactive.contains("workLoop.wake"))
        assertTrue(tiktok.contains("workLoop.wake"))
        assertFalse(proactive.contains("sendToWhatsApp"))
        assertFalse(tiktok.contains("createPost("))
        val commercial = File(root, "app/src/main/kotlin/co/sanaa/agent/workers/CommercialCycleWorker.kt").readText()
        val workerBody = commercial.substringBefore("class CommercialCycleRunner")
        assertTrue(workerBody.contains("workLoop.wake"))
        assertFalse(workerBody.contains("planMorning("))
        val agentWorkers = File(root, "app/src/main/kotlin/co/sanaa/agent/workers/AgentWorkers.kt").readText()
        val scheduledBody = agentWorkers.substringAfter("class ScheduledCommandWorker").substringBefore("class FollowUpWorker")
        assertTrue(scheduledBody.contains("workQueue.offer"))
        assertTrue(scheduledBody.contains("workLoop.wake"))
        assertFalse(scheduledBody.contains("CommandExecutor("))
        val healthBody = agentWorkers.substringAfter("class HealthWorker").substringBefore("class ReadOnlyShopAuditWorker")
        assertTrue(healthBody.contains("workLoop.wake"))
        assertFalse(healthBody.contains("health.run()"))
    }

    @Test fun jijiIsVisibleAndHasAnExplicitPackageMapping() {
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        val actions = File(root, "app/src/main/kotlin/co/sanaa/agent/actions/AccessibilityActions.kt").readText()
        assertTrue(manifest.contains("<package android:name=\"com.olx.ssa.ug\" />"))
        assertTrue(actions.contains("\"jiji\", \"jiji ug\", \"jiji.ug\" -> \"com.olx.ssa.ug\""))
    }

    @Test fun jumiaIsAddedAlongsideJijiRatherThanReplacingIt() {
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        val actions = File(root, "app/src/main/kotlin/co/sanaa/agent/actions/AccessibilityActions.kt").readText()
        assertTrue(manifest.contains("<package android:name=\"com.olx.ssa.ug\" />"))
        assertTrue(manifest.contains("<package android:name=\"com.jumia.android\" />"))
        assertTrue(actions.contains("\"jumia\", \"jumia ug\", \"jumia.ug\" -> \"com.jumia.android\""))
    }

    @Test fun jumiaExecutionIsOwnedByTheGovernedWorkLoop() {
        val source = File(root, "app/src/main/kotlin/co/sanaa/agent/core/work/sources/JijiWorkSource.kt").readText()
        val executor = File(root, "app/src/main/kotlin/co/sanaa/agent/core/work/WorkExecutor.kt").readText()
        val workers = File(root, "app/src/main/kotlin/co/sanaa/agent/workers").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertTrue(source.contains("WorkKind.JUMIA_CAPTURE"))
        assertTrue(source.contains("jumiaVisionAllowed()"))
        assertTrue(executor.contains("WorkKind.JUMIA_CAPTURE -> executeJumiaCapture"))
        assertFalse(workers.contains("captureFeaturedOffers()"))
    }

    @Test fun jijiColdStartScoreCanCrossTheExecutionFloor() {
        val source = File(root, "app/src/main/kotlin/co/sanaa/agent/core/work/sources/JijiWorkSource.kt").readText()
        val scorer = File(root, "app/src/main/kotlin/co/sanaa/agent/core/work/WorkScorer.kt").readText()
        assertTrue(source.contains("baseValueKes = 200.0"))
        assertTrue(scorer.contains("WorkKind.JIJI_SCRAPE -> 200.0"))
    }

    @Test fun inboundNotificationsProposeWorkWithoutRunningConversationDirectly() {
        listOf("AgentNotificationListenerService.kt", "AccessibilityAgentService.kt").forEach { name ->
            val service = File(root, "app/src/main/kotlin/co/sanaa/agent/services/$name").readText()
            assertTrue(service.contains("InboundWorkProposer.offerWhatsApp"))
            assertFalse(service.contains("conversation.observeWhatsApp"))
        }
    }

    @Test fun governedInboundUsesTheDurableHumanConversationEngine() {
        val proposer = File(root, "app/src/main/kotlin/co/sanaa/agent/core/work/InboundWorkProposer.kt").readText()
        val executor = File(root, "app/src/main/kotlin/co/sanaa/agent/core/work/WorkExecutor.kt").readText()
        assertTrue(proposer.contains("whatsAppAutomationEnabled"))
        assertTrue(proposer.contains("whatsAppInboundEnabled"))
        assertTrue(executor.contains(".processMessage("))
        assertTrue(executor.contains("inbound.target, inbound.sender, inbound.message, inbound.isGroup"))
        assertTrue(executor.contains("trustedWhatsAppNotification ="))
        assertFalse(executor.substringAfter("private suspend fun executeWhatsAppReply").substringBefore("private suspend fun executeWhatsAppBroadcast").contains(".observeWhatsApp(inbound)"))
    }

    @Test fun configSyncWorkerOnlyWakesTheGovernedLoop() {
        val workers = File(root, "app/src/main/kotlin/co/sanaa/agent/workers/AgentWorkers.kt").readText()
        val body = workers.substringAfter("class ConfigSyncWorker").substringBefore("object AgentWorkScheduler")
        assertTrue(body.contains("workLoop.wake"))
        assertFalse(body.contains("registerAndSync"))
        assertFalse(body.contains("backupNow"))
        assertFalse(body.contains("restoreLatest"))
    }

    @Test fun settingsExposeOwnerKillSwitchesMemoryAndCadence() {
        val settings = File(root, "app/src/main/kotlin/co/sanaa/agent/core/AmaraSettings.kt").readText()
        val ui = File(root, "flutter_ui/lib/screens/settings/settings_screen.dart").readText()
        listOf("whatsAppEnabled", "whatsAppInboundEnabled", "whatsAppFollowUpsEnabled", "whatsAppGroupsEnabled", "whatsAppAlwaysOn", "memoryBackupEnabled", "tikTokAlwaysOn")
            .forEach { assertTrue("missing $it", settings.contains("\"$it\"")) }
        assertTrue(ui.contains("Master autopilot"))
        assertTrue(ui.contains("Contact control"))
        assertTrue(ui.contains("Back up now"))
    }
}
