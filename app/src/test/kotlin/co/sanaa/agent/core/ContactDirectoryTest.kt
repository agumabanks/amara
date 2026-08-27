package co.sanaa.agent.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContactDirectoryTest {

    private lateinit var context: Context
    private lateinit var config: SecureConfig
    private lateinit var store: ContactDirectoryStore
    private lateinit var directory: ContactDirectory

    private fun entry(
        name: String,
        phone: String? = null,
        isGroup: Boolean = false,
        level: ContactPermission = ContactPermission.NONE,
        classification: Classification = Classification.UNKNOWN,
        consent: CommercialConsent = CommercialConsent.UNKNOWN,
        source: EntrySource = EntrySource.OWNER_CREATED,
        id: String = "",
        aliases: Set<String> = emptySet(),
    ) = DirectoryEntry(
        id = id, displayName = name, normalizedPhone = phone, aliases = aliases, isGroup = isGroup,
        source = source, lastVerifiedAt = System.currentTimeMillis(), ambiguity = Ambiguity.UNIQUE,
        classification = classification, commercialConsent = consent,
        permissions = ContactDirectoryStore.operationsForLevel(level),
        whatsappSurfaceEvidence = null, revocationEvidence = null,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(ContactDirectoryStore.DATABASE_NAME)
        context.deleteDatabase(AmaraMemory.DATABASE_NAME)
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

    @Test
    fun `every dial form resolves to the one durable identity`() {
        val saved = store.upsert(entry("Nakato", "+256772123456", level = ContactPermission.SEND))
        listOf("0772123456", "256772123456", "772123456").forEach { form ->
            val resolution = directory.resolve(ContactQuery(phone = form))
            assertTrue(resolution is Resolution.Unique)
            assertEquals(saved.id, (resolution as Resolution.Unique).entry.id)
            assertEquals("+256772123456", resolution.entry.normalizedPhone)
        }
    }

    @Test
    fun `duplicate display names with different numbers stay distinct and name lookup fails closed`() {
        store.upsert(entry("Sarah", "0772000001", level = ContactPermission.SEND))
        store.upsert(entry("Sarah", "0772000002"))
        val byPhone1 = directory.resolve(ContactQuery(phone = "0772000001"))
        val byPhone2 = directory.resolve(ContactQuery(phone = "0772000002"))
        assertTrue(byPhone1 is Resolution.Unique)
        assertTrue(byPhone2 is Resolution.Unique)
        assertNotEquals((byPhone1 as Resolution.Unique).entry.id, (byPhone2 as Resolution.Unique).entry.id)
        val byName = directory.resolve(ContactQuery(name = "Sarah"))
        assertTrue(byName is Resolution.Ambiguous)
        assertEquals(2, (byName as Resolution.Ambiguous).entries.size)
        // Permission lookups fail closed on the ambiguous name even though legacy prefs might grant.
        config.contactPermissionsJson = """[{"name":"Sarah","number":"","isGroup":false,"permission":"FULL"}]"""
        assertEquals(ContactPermission.NONE, ContactPermissions.permissionFor(config, "Sarah", null, false))
    }

    @Test
    fun `short queries never match by name`() {
        store.upsert(entry("Joan", "0772000003"))
        assertEquals(Resolution.NotFound, directory.resolve(ContactQuery(name = "Jo")))
        assertTrue(directory.resolve(ContactQuery(name = "Joan")) is Resolution.Unique)
    }

    @Test
    fun `group and contact surfaces stay separate`() {
        store.upsert(entry("Family", isGroup = true, level = ContactPermission.SEND))
        store.upsert(entry("Family", "0772000004"))
        assertTrue(directory.resolve(ContactQuery(name = "Family", isGroup = true)) is Resolution.Unique)
        assertTrue(directory.resolve(ContactQuery(name = "Family", isGroup = false)) is Resolution.Unique)
        assertTrue(directory.resolve(ContactQuery(name = "Family")) is Resolution.Ambiguous)
        val groupResolution = directory.resolve(ContactQuery(name = "Family", isGroup = true))
        assertTrue(groupResolution is Resolution.Unique)
        assertTrue((groupResolution as Resolution.Unique).entry.isGroup)
    }

    @Test
    fun `second claimant of the same number flags both entries and fails closed on that number`() {
        store.upsert(entry("Auntie Mary", "0772000005"))
        store.upsert(entry("Mukwano Shop", "0772000005"))
        val ambiguous = store.listAmbiguous()
        assertEquals(2, ambiguous.size)
        assertTrue(ambiguous.all { it.ambiguity == Ambiguity.AMBIGUOUS_NUMBER })
        val byPhone = directory.resolve(ContactQuery(phone = "0772000005"))
        assertTrue(byPhone is Resolution.Ambiguous)
    }

    @Test
    fun `revocation flips every path immediately and survives reopen`() {
        val saved = store.upsert(entry("Trusted Hand", "0772000006", level = ContactPermission.FULL))
        assertTrue(ContactPermissions.canMonitor(config, "Trusted Hand", "0772000006", false))
        assertTrue(ContactPermissions.canSend(config, "Trusted Hand", "0772000006", false))
        assertTrue(ContactPermissions.canWrite(config, "Trusted Hand", "0772000006", false))

        store.revokeAll(saved.id, "owner said stop contacting this person")

        assertFalse(ContactPermissions.canMonitor(config, "Trusted Hand", "0772000006", false))
        assertFalse(ContactPermissions.canReply(config, "Trusted Hand", "0772000006", false))
        assertFalse(ContactPermissions.canSend(config, "Trusted Hand", "0772000006", false))
        assertFalse(ContactPermissions.canWrite(config, "Trusted Hand", "0772000006", false))

        store.close()
        store = ContactDirectoryStore(context)
        directory = ContactDirectory(store)
        ContactDirectoryProvider.instance = directory

        val reopened = requireNotNull(store.byId(saved.id)) { "revoked entry must survive reopen" }
        assertEquals("owner said stop contacting this person", reopened.revocationEvidence)
        assertEquals(ContactPermission.NONE, ContactPermissions.permissionFor(config, "Trusted Hand", "0772000006", false))
        assertFalse(ContactPermissions.canSend(config, "Trusted Hand", "0772000006", false))
        assertFalse(reopened.isRevenueEligible())
    }

    @Test
    fun `explicit owner reauthorization clears contact revocation and replaces rather than widens the level`() {
        val saved = store.upsert(entry("Reauthorized Customer", "0772000016", level = ContactPermission.FULL))
        store.revokeAll(saved.id, "owner revoked")
        assertFalse(requireNotNull(store.byId(saved.id)).canSend)

        assertTrue(store.authorizeLevel(saved.id, ContactPermission.REPLY))
        val replyOnly = requireNotNull(store.byId(saved.id))
        assertEquals(null, replyOnly.revocationEvidence)
        assertTrue(replyOnly.canMonitor)
        assertTrue(replyOnly.canReply)
        assertFalse(replyOnly.canSend)
        assertFalse(replyOnly.canWrite)

        assertTrue(store.authorizeLevel(saved.id, ContactPermission.FULL))
        val full = requireNotNull(store.byId(saved.id))
        assertTrue(full.canMonitor && full.canReply && full.canSend && full.canWrite)
    }

    @Test
    fun `legacy migration imports monitor and send levels idempotently`() {
        config.ownerPhone = "+256700000001"
        config.contactPermissionsJson =
            """[{"name":"Buyer Seven","number":"+256700000007","isGroup":false,"permission":"SEND"},
               {"name":"Quiet Watch","number":"0781000002","isGroup":false,"permission":"MONITOR"}]"""
        config.monitoredWhatsAppJson = """["Buyer Seven"]"""
        config.whatsAppGroupsJson = """["Sanaa Family Group"]"""

        store.lazyMigrateFromLegacy(config)
        store.lazyMigrateFromLegacy(config)

        val buyer = directory.resolve(ContactQuery(phone = "+256700000007"))
        assertTrue(buyer is Resolution.Unique)
        val buyerEntry = (buyer as Resolution.Unique).entry
        assertEquals("Buyer Seven", buyerEntry.displayName)
        assertTrue(buyerEntry.canMonitor && buyerEntry.canReply && buyerEntry.canSend)
        assertFalse(buyerEntry.canWrite)
        assertEquals(Classification.CUSTOMER, buyerEntry.classification)

        val quiet = directory.resolve(ContactQuery(phone = "+256781000002"))
        assertTrue(quiet is Resolution.Unique)
        val quietEntry = (quiet as Resolution.Unique).entry
        assertTrue(quietEntry.canMonitor)
        assertFalse(quietEntry.canReply || quietEntry.canSend || quietEntry.canWrite)

        val group = directory.resolve(ContactQuery(name = "Sanaa Family Group", isGroup = true))
        assertTrue(group is Resolution.Unique)
        assertTrue((group as Resolution.Unique).entry.canSend)

        val owner = store.byId(ContactDirectoryStore.OWNER_ID)
        assertNotNull(owner)
        assertEquals(Classification.OWNER, owner?.classification)

        // Idempotent: a second run after mutating legacy JSON imports nothing new.
        config.contactPermissionsJson = """[{"name":"Late Addition","number":"0773000009","isGroup":false,"permission":"FULL"}]"""
        store.lazyMigrateFromLegacy(config)
        assertEquals(Resolution.NotFound, directory.resolve(ContactQuery(phone = "0773000009")))
    }

    @Test
    fun `revenue eligibility excludes owner test and suppressed contacts`() {
        val owner = entry("Owner", "07000000001", classification = Classification.OWNER)
        val tester = entry("QA Bot", "07000000002", classification = Classification.TEST)
        val customer = entry("Real Customer", "07000000003", classification = Classification.CUSTOMER)
        val suppressed = entry("Opted Out", "07000000004", classification = Classification.CUSTOMER, consent = CommercialConsent.SUPPRESSED)
        assertTrue(customer.isRevenueEligible())
        assertFalse(owner.isRevenueEligible())
        assertFalse(tester.isRevenueEligible())
        assertFalse(suppressed.isRevenueEligible())
    }

    @Test
    fun `legacy prefs still answer when the provider hook is unset`() {
        ContactDirectoryProvider.instance = null
        config.contactPermissionsJson = """[{"name":"Legacy Only","number":null,"isGroup":false,"permission":"REPLY"}]""".replace("\"number\":null", "\"number\":\"\"")
        assertEquals(ContactPermission.REPLY, ContactPermissions.permissionFor(config, "Legacy Only", null, false))
        assertTrue(ContactPermissions.canReply(config, "Legacy Only", null, false))
        assertFalse(ContactPermissions.canSend(config, "Legacy Only", null, false))
    }
}
