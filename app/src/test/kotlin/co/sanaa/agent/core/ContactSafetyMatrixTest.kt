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

/**
 * Contact/WhatsApp safety matrix over the production SQLite directory: the
 * pre-send authority gate must fail closed for every hostile listing and stay
 * open only for the exact authorized controlled recipient. Send paths consult
 * [ContactDirectory.can] at DISPATCH time, so a revocation after planning is
 * honored without any stale cached decision.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContactSafetyMatrixTest {

    private lateinit var context: Context
    private lateinit var store: ContactDirectoryStore
    private lateinit var directory: ContactDirectory

    private fun entry(
        name: String,
        phone: String?,
        level: ContactPermission,
        id: String = "",
        ambiguity: Ambiguity = Ambiguity.UNIQUE,
    ) = DirectoryEntry(
        id = id, displayName = name, normalizedPhone = phone, aliases = emptySet(), isGroup = false,
        source = EntrySource.OWNER_CREATED, lastVerifiedAt = System.currentTimeMillis(),
        ambiguity = ambiguity, classification = Classification.CUSTOMER,
        commercialConsent = CommercialConsent.UNKNOWN,
        permissions = ContactDirectoryStore.operationsForLevel(level),
        whatsappSurfaceEvidence = null, revocationEvidence = null,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(ContactDirectoryStore.DATABASE_NAME)
        store = ContactDirectoryStore(context)
        directory = ContactDirectory(store)
        ContactDirectoryProvider.instance = directory
    }

    @After
    fun tearDown() {
        ContactDirectoryProvider.instance = null
        store.close()
    }

    private fun granted(name: String, phone: String): DirectoryEntry {
        val saved = directory.upsert(entry(name, phone, ContactPermission.SEND))
        directory.setPermission(saved.id, Operation.SEND, true)
        return directory.byId(saved.id)!!
    }

    @Test
    fun uniqueExactMatchIsTheOnlySendableShape() {
        granted("Controlled Recipient", "+256770000001")
        assertTrue(directory.can(Operation.SEND, "Controlled Recipient", "+256770000001", false))
        // An unregistered number with a plausible name is still refused.
        assertFalse(directory.can(Operation.SEND, "Unregistered Person", "+256770000099", false))
    }

    @Test
    fun normalizedNumberMatchAuthorizesExactlyOneIdentity() {
        granted("Buyer A", "+256770000002")
        // Local format normalizes to the same MSISDN.
        assertTrue(directory.can(Operation.SEND, "Buyer A", "0770000002", false))
        // One digit off -> nobody -> fail closed.
        assertFalse(directory.can(Operation.SEND, "Buyer A", "0770000003", false))
    }

    @Test
    fun duplicateNamesFailClosedEvenWhenOneHoldsGrant() {
        val saved = directory.upsert(entry("Mukasa", "+256770000004", ContactPermission.NONE))
        directory.setPermission(saved.id, Operation.SEND, true)
        directory.upsert(entry("Mukasa", "+256770000005", ContactPermission.NONE))
        assertFalse(directory.can(Operation.SEND, "Mukasa", null, false))
        assertEquals(2, directory.resolve(ContactQuery(name = "Mukasa")).let {
            (it as Resolution.Ambiguous).entries.size
        })
    }

    @Test
    fun neverAuthorizedContactsAreRefusedWithoutAnyLegacyFallback() {
        directory.upsert(entry("Stranger", "+256770000006", ContactPermission.NONE))
        assertFalse(directory.can(Operation.SEND, "Stranger", "+256770000006", false))
        assertFalse(directory.can(Operation.REPLY, "Stranger", "+256770000006", false))
        assertFalse(directory.can(Operation.MONITOR, "Stranger", "+256770000006", false))
    }

    @Test
    fun explicitDenyBlocksEvenIfALevelWouldOtherwiseAllow() {
        val saved = granted("Blocked Person", "+256770000007")
        directory.setPermission(saved.id, Operation.SEND, false)
        val reloaded = directory.byId(saved.id)!!
        assertEquals(Permission.DENY, reloaded.permissions[Operation.SEND])
        assertFalse(directory.can(Operation.SEND, "Blocked Person", "+256770000007", false))
    }

    @Test
    fun revocationAfterPlanningIsHonoredAtDispatchTime() {
        val saved = granted("Planned Recipient", "+256770000008")
        // Planning phase consults the gate: open.
        val plannedOk = directory.can(Operation.SEND, "Planned Recipient", "+256770000008", false)
        assertTrue(plannedOk)
        // Owner revokes before the task executes.
        directory.revokeAll(saved.id, "owner-revoked-pre-send")
        // Dispatch-time consultation must now refuse — no stale grant survives.
        assertFalse(directory.can(Operation.SEND, "Planned Recipient", "+256770000008", false))
        val reloaded = directory.byId(saved.id)!!
        assertEquals("owner-revoked-pre-send", reloaded.revocationEvidence)
    }

    @Test
    fun changedPhoneNumberIsADifferentSurfaceUntilOwnerUpdatesIt() {
        granted("Number Mover", "+256770000009")
        assertTrue(directory.can(Operation.SEND, "Number Mover", "+256770000009", false))
        // The same name presenting a NEW number resolves to nothing: fail closed.
        assertFalse(directory.can(Operation.SEND, "Number Mover", "+256770000010", false))
    }

    @Test
    fun emptyDirectoryFailsClosedForEveryOperation() {
        for (operation in Operation.entries) {
            assertFalse(directory.can(operation, "Anyone", "+256770000000", false))
            assertFalse(directory.can(operation, null, "+256770000000", false))
            assertFalse(directory.can(operation, "Anyone", null, false))
        }
        assertTrue(directory.listAll().isEmpty())
        assertTrue(directory.broadcastGroups().isEmpty())
    }

    // ------------------------------------------------------------------ pre-send dispatch gate
    // The full production gate binds identity + grant + number + VISIBLE THREAD LABEL.
    // Every mismatch below must produce a typed refusal and ZERO dispatch.

    @Test
    fun correctVisibleThreadWithBoundNumberIsTheOnlyAllowedDispatchShape() {
        granted("Buyer Seven", "+256770000101")
        val decision = directory.authorizeOutgoingSend("Buyer Seven", "0770000101", "Buyer Seven", isGroup = false)
        assertTrue(decision is DispatchDecision.Allowed)
        assertEquals("Buyer Seven", (decision as DispatchDecision.Allowed).entry.displayName)
        // The visible thread may also present the contact's own normalized number.
        assertTrue(directory.authorizeOutgoingSend("Buyer Seven", "+256770000101", "+256770000101") is DispatchDecision.Allowed)
    }

    @Test
    fun wrongVisibleThreadRefusesDispatchEvenWhenIdentityAndGrantAreValid() {
        granted("Intended Recipient", "+256770000102")
        granted("Other Chat", "+256770000103")
        val decision = directory.authorizeOutgoingSend("Intended Recipient", "+256770000102", "Other Chat")
        val refused = decision as DispatchDecision.Refused
        assertEquals(DispatchDecision.VISIBLE_THREAD_MISMATCH, refused.reasonCode)
        // The identity itself is still fine; only the visible surface mismatches.
        assertTrue(directory.can(Operation.SEND, "Intended Recipient", "+256770000102", false))
    }

    @Test
    fun aliasVisibleThreadAuthorizesButUnknownThreadLabelNeverDoes() {
        val saved = directory.upsert(
            entry("Formal Name", "+256770000104", ContactPermission.SEND).copy(aliases = setOf("Shop Line")),
        )
        directory.setPermission(saved.id, Operation.SEND, true)
        assertTrue(directory.authorizeOutgoingSend("Formal Name", "+256770000104", "shop line") is DispatchDecision.Allowed)
        val refused = directory.authorizeOutgoingSend("Formal Name", "+256770000104", "Totally Unrelated Label") as DispatchDecision.Refused
        assertEquals(DispatchDecision.VISIBLE_THREAD_MISMATCH, refused.reasonCode)
        assertTrue(directory.authorizeOutgoingSend("Formal Name", "+256770000104", "") is DispatchDecision.Refused)
        assertTrue(directory.authorizeOutgoingSend("Formal Name", "+256770000104", null) is DispatchDecision.Refused)
    }

    @Test
    fun unknownRecipientRefusesTheDispatchBeforeAnySurfaceCheck() {
        granted("Known Person", "+256770000105")
        val ghost = directory.authorizeOutgoingSend("Ghost Contact", "+256770000105", "Ghost Contact") as DispatchDecision.Refused
        assertEquals(DispatchDecision.UNKNOWN_RECIPIENT, ghost.reasonCode)
        val numberOnlyUnknown = directory.authorizeOutgoingSend(null, "+256770999999", null) as DispatchDecision.Refused
        assertEquals(DispatchDecision.UNKNOWN_RECIPIENT, numberOnlyUnknown.reasonCode)
        val unboundNumber = directory.authorizeOutgoingSend("Known Person", "+256770999998", "Known Person") as DispatchDecision.Refused
        assertEquals(DispatchDecision.NUMBER_NOT_BOUND_TO_IDENTITY, unboundNumber.reasonCode)
    }

    @Test
    fun duplicateIdentityAmbiguityRefusesTheDispatch() {
        val saved = directory.upsert(entry("Twin Name", "+256770000106", ContactPermission.SEND))
        directory.setPermission(saved.id, Operation.SEND, true)
        directory.upsert(entry("Twin Name", "+256770000107", ContactPermission.NONE))
        val refused = directory.authorizeOutgoingSend("Twin Name", null, "Twin Name") as DispatchDecision.Refused
        assertEquals(DispatchDecision.AMBIGUOUS_IDENTITY, refused.reasonCode)
        assertFalse(directory.can(Operation.SEND, "Twin Name", null, false))
    }

    @Test
    fun revocationAfterPlanningRefusesTheDispatchAtSendTime() {
        val saved = granted("Planned Buyer", "+256770000108")
        assertTrue(directory.authorizeOutgoingSend("Planned Buyer", "+256770000108", "Planned Buyer") is DispatchDecision.Allowed)
        // Owner revokes AFTER planning but BEFORE dispatch.
        directory.revokeAll(saved.id, "owner-revoked-pre-dispatch")
        val refused = directory.authorizeOutgoingSend("Planned Buyer", "+256770000108", "Planned Buyer") as DispatchDecision.Refused
        assertEquals(DispatchDecision.AUTHORIZATION_REVOKED, refused.reasonCode)
    }

    @Test
    fun changedNumberRefusesTheDispatchAsUnboundToTheIdentity() {
        granted("Number Mover Two", "+256770000109")
        val refused = directory.authorizeOutgoingSend("Number Mover Two", "+256770000110", "Number Mover Two") as DispatchDecision.Refused
        assertEquals(DispatchDecision.NUMBER_NOT_BOUND_TO_IDENTITY, refused.reasonCode)
        // A presented but unparsable number value also refuses: never silently ignored.
        val garbage = directory.authorizeOutgoingSend("Number Mover Two", "not-a-number", "Number Mover Two") as DispatchDecision.Refused
        assertEquals(DispatchDecision.NUMBER_NOT_BOUND_TO_IDENTITY, garbage.reasonCode)
    }

    @Test
    fun missingGrantRefusesEvenForAUniqueWellFormedIdentity() {
        directory.upsert(entry("Grantless", "+256770000111", ContactPermission.NONE))
        val refused = directory.authorizeOutgoingSend("Grantless", "+256770000111", "Grantless") as DispatchDecision.Refused
        assertEquals(DispatchDecision.NO_SEND_GRANT, refused.reasonCode)
    }

    @Test
    fun everyMismatchInTheMatrixYieldsATypedRefusalAndZeroAllowance() {
        granted("Matrix Buyer", "+256770000112")
        granted("Matrix Other", "+256770000113")
        val cases = listOf(
            // (name, number, visible thread) — every row must refuse with zero dispatch.
            Triple("Matrix Buyer", "+256770000112", "Matrix Other"),   // wrong visible thread
            Triple("Matrix Buyer", "+256770000113", "Matrix Buyer"),   // changed number
            Triple("Matrix Other", "+256770000113", "Matrix Buyer"),   // swapped identities
            Triple(null, "+256770000112", "Matrix Other"),             // number-only probe, wrong thread
            Triple("Matrix Buyer", null, "Someone Else Entirely"),     // name-only wrong thread
            Triple("Stranger Danger", "+256770000114", "Stranger Danger"), // unknown recipient
            Triple("Matrix Buyer", "not-a-number", "Matrix Buyer"),    // malformed number surface
        )
        for ((name, number, thread) in cases) {
            val decision = directory.authorizeOutgoingSend(name, number, thread)
            assertTrue(
                "expected refusal for ($name, $number, $thread) but got $decision",
                decision is DispatchDecision.Refused,
            )
        }
        // And the one exact match still works after all hostile probes.
        assertTrue(directory.can(Operation.SEND, "Matrix Buyer", "+256770000112", false))
    }
}
