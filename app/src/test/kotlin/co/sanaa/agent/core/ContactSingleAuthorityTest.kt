package co.sanaa.agent.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Single-authority sentinel for contact identity: every monitor/reply/send/follow-up/
 * broadcast path resolves through the durable ContactDirectory, and NO production
 * source outside the migration bridge consults the legacy preference lists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContactSingleAuthorityTest {

    private lateinit var context: Context
    private lateinit var config: SecureConfig
    private lateinit var store: ContactDirectoryStore
    private lateinit var directory: ContactDirectory

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(ContactDirectoryStore.DATABASE_NAME)
        config = SecureConfig(context, useEncryptedPrefs = false)
        store = ContactDirectoryStore(context)
        directory = ContactDirectory(store)
        ContactDirectoryProvider.instance = directory
    }

    @After
    fun tearDown() {
        ContactDirectoryProvider.instance = null
        store.close()
    }

    private fun entry(
        name: String,
        phone: String? = null,
        isGroup: Boolean = false,
        level: ContactPermission = ContactPermission.NONE,
    ) = DirectoryEntry(
        id = "", displayName = name, normalizedPhone = phone, aliases = emptySet(), isGroup = isGroup,
        source = EntrySource.OWNER_CREATED, lastVerifiedAt = System.currentTimeMillis(),
        ambiguity = Ambiguity.UNIQUE, classification = Classification.UNKNOWN,
        commercialConsent = CommercialConsent.UNKNOWN,
        permissions = ContactDirectoryStore.operationsForLevel(level),
        whatsappSurfaceEvidence = null, revocationEvidence = null,
    )

    // ---------- mechanical single-authority proof ----------

    private val legacyTokens = listOf(
        "monitoredWhatsAppTargets(",
        "monitorWhatsAppTarget(",
        "stopMonitoringWhatsAppTarget(",
        "whatsAppGroupsJson",
        "monitoredWhatsAppJson",
        "contactPermissionsJson",
        "ContactPermissions.",
    )

    /** The ONLY production files allowed to mention the legacy lists (storage + migration input + bridge). */
    private val legacyAllowlist = setOf(
        "SecureConfig.kt",
        "ContactDirectoryStore.kt",
        "ContactPermissions.kt",
    )

    @Test
    fun noProductionPathOutsideTheMigrationBridgeTouchesTheLegacyContactLists() {
        val main = repoRoot().resolve("app/src/main/kotlin")
        val violations = mutableListOf<String>()
        Files.walk(main).use { stream ->
            stream.filter { it.toString().endsWith(".kt") }.forEach { path ->
                if (path.fileName.toString() in legacyAllowlist) return@forEach
                val text = path.toFile().readText()
                legacyTokens.forEach { token ->
                    if (token in text) violations += "${main.relativize(path)} references legacy authority '$token'"
                }
            }
        }
        assertTrue("legacy contact authorities still referenced:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    @Test
    fun conversationFollowUpAndBroadcastEnginesResolveThroughTheDirectory() {
        val engineSources = listOf(
            "app/src/main/kotlin/co/sanaa/agent/modules/ConversationEngine.kt",
            "app/src/main/kotlin/co/sanaa/agent/modules/FollowUpEngine.kt",
            "app/src/main/kotlin/co/sanaa/agent/modules/MorningBroadcastModule.kt",
            "app/src/main/kotlin/co/sanaa/agent/core/AutonomyController.kt",
            "app/src/main/kotlin/co/sanaa/agent/receivers/ProofOfConceptReceiver.kt",
            "app/src/main/kotlin/co/sanaa/agent/MainActivity.kt",
        ).map { repoRoot().resolve(it) }
        engineSources.forEach { path ->
            val text = path.toFile().readText()
            assertTrue(
                "${path.fileName} must consult ContactDirectoryProvider",
                "ContactDirectoryProvider" in text || "runtime.contacts" in text || "runtime.contacts." in text,
            )
        }
    }

    // ---------- directory authority behavior ----------

    @Test
    fun monitoredLabelsAndBroadcastGroupsComeFromTheDurableDirectoryOnly() {
        store.upsert(entry("Nakato", "0772000001", level = ContactPermission.MONITOR))
        store.upsert(entry("Moses", "0772000002", level = ContactPermission.REPLY))
        store.upsert(entry("Sanaa Family", isGroup = true, level = ContactPermission.SEND))
        store.upsert(entry("Quiet Group", isGroup = true, level = ContactPermission.MONITOR))
        store.upsert(entry("Stranger", "0772000009", level = ContactPermission.NONE))

        fun monitoredLabels() = directory.listAll()
            .filter { it.canMonitor }
            .flatMap { e -> listOf(e.displayName) + e.aliases }
            .toSet()

        val monitored = monitoredLabels()
        assertTrue("Nakato" in monitored)
        assertTrue("Moses" in monitored) // REPLY carries monitoring (cumulative grants)
        assertFalse("Stranger" in monitored) // NONE grants nothing
        assertEquals(listOf("Sanaa Family"), directory.broadcastGroups())

        // Revocation flips the authority immediately for every path.
        val nakato = (directory.resolve(ContactQuery(name = "Nakato")) as Resolution.Unique).entry
        store.revokeAll(nakato.id, "owner revoked")
        assertFalse("Nakato" in monitoredLabels())

        // A group without SEND never broadcasts.
        assertTrue(directory.can(Operation.SEND, "Sanaa Family", null, isGroup = true))
        assertFalse(directory.can(Operation.SEND, "Quiet Group", null, isGroup = true))
    }

    @Test
    fun authorityChecksFailClosedForUnknownAndAmbiguousIdentities() {
        store.upsert(entry("Sarah", "0772000003", level = ContactPermission.SEND))
        store.upsert(entry("Sarah", "0772000004", level = ContactPermission.SEND))
        assertFalse(directory.can(Operation.SEND, "Sarah", null, isGroup = false))
        assertFalse(directory.can(Operation.MONITOR, "Total Stranger", null, isGroup = false))
        assertFalse(directory.can(Operation.REPLY, null, "0772999999", isGroup = false))
        assertTrue(directory.can(Operation.SEND, "Sarah", "0772000003", isGroup = false))
    }

    @Test
    fun legacyListsAreMigrationInputAndNeverReWidenedAfterImport() {
        config.ownerPhone = "+256700000001"
        config.contactPermissionsJson =
            """[{"name":"Legacy Buyer","number":"0773000005","isGroup":false,"permission":"SEND"}]"""
        config.monitoredWhatsAppJson = """["Legacy Buyer"]"""
        config.whatsAppGroupsJson = """["Legacy Family"]"""

        store.lazyMigrateFromLegacy(config)
        assertTrue(directory.can(Operation.MONITOR, "Legacy Buyer", null, isGroup = false))
        assertTrue(directory.can(Operation.SEND, "Legacy Buyer", null, isGroup = false))
        assertEquals(listOf("Legacy Family"), directory.broadcastGroups())

        // Mutating the legacy JSON afterwards changes nothing: the directory is the authority.
        config.whatsAppGroupsJson = """["New Group"]"""
        assertEquals(listOf("Legacy Family"), directory.broadcastGroups())
        assertFalse(directory.can(Operation.SEND, "New Group", null, isGroup = true))
    }

    @Test
    fun directorySecretScrubKeepsPhoneIdentityAndRemovesCredentialShapes() {
        store.upsert(entry("Evidence Keeper", "0774000006", level = ContactPermission.MONITOR))
        val keeper = (directory.resolve(ContactQuery(name = "Evidence Keeper")) as Resolution.Unique).entry
        store.setPermission(keeper.id, Operation.MONITOR, true)
        store.upsert(keeper.copy(whatsappSurfaceEvidence = "surface note: pin is $PIN_MATERIAL"))
        directory.scrubSecrets()
        val after = requireNotNull(store.byId(keeper.id))
        assertFalse(after.whatsappSurfaceEvidence.orEmpty().contains(PIN_MATERIAL))
        assertEquals("+256774000006", after.normalizedPhone)
    }

    private companion object {
        const val PIN_MATERIAL = "774411"
    }

    private fun repoRoot(): Path {
        var dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null && !dir.resolve("settings.gradle").toFile().exists()) {
            dir = dir.parent
        }
        return requireNotNull(dir) { "repo root not found" }
    }
}
