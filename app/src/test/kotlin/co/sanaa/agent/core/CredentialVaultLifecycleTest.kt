package co.sanaa.agent.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Full credential-lifecycle semantics over the production SharedPreferences
 * store with a deterministic reversible test codec. Proves that missing,
 * partial, corrupted, and foreign-scoped records are typed distinctly and that
 * no failure path ever synthesizes a blank-scope credential (the G2/G3 defect).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CredentialVaultLifecycleTest {

    private lateinit var context: Context
    private lateinit var codec: ReversibleTestCodec
    private lateinit var vault: CredentialVault

    companion object {
        const val ID = AgentRuntime.SOKO_PIN_ID
        const val PKG = AgentRuntime.SOKO_TERMINAL_PACKAGE
        const val OTHER_PKG = "com.other.app"
        const val SECRET = "483920"
        const val AUTH_CODE = "soko_auth: stored Terminal PIN rejected by Staff Login"
    }

    /**
     * Deterministic alias-bound reversible codec; can be forced to fail
     * decryption, tracks encrypt-side key creations (to prove decrypt never
     * recreates a lost alias) and retains decrypted buffers (to prove the vault
     * clears them).
     */
    class ReversibleTestCodec : SecretCodec {
        val deletedAliases = mutableListOf<String>()
        val failingAliases = mutableSetOf<String>()
        val createdAliases = linkedSetOf<String>()
        val lastDecryptedBuffers = mutableListOf<CharArray>()

        override fun encrypt(alias: String, plaintext: CharArray): Pair<String, String> {
            createdAliases.add(alias)
            val masked = plaintext.concatToString().toByteArray(Charsets.UTF_8)
                .map { (it.toInt() xor mask(alias)).toByte() }.toByteArray()
            return Base64.getEncoder().encodeToString(masked) to "iv:${alias.hashCode()}"
        }

        override fun decrypt(alias: String, blob: String, iv: String): CharArray {
            if (alias in failingAliases) throw IllegalStateException("keystore key unavailable")
            val unmasked = Base64.getDecoder().decode(blob).map { (it.toInt() xor mask(alias)).toByte() }.toByteArray()
            return String(unmasked, Charsets.UTF_8).toCharArray().also { lastDecryptedBuffers.add(it) }
        }

        override fun deleteAlias(alias: String) {
            deletedAliases.add(alias)
            failingAliases.add(alias) // deletion invalidates future decryptions, like the real keystore
        }

        private fun mask(alias: String): Int = (alias.hashCode() ushr 1) or 0x20
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("credential_vault", Context.MODE_PRIVATE).edit().clear().commit()
        codec = ReversibleTestCodec()
        vault = CredentialVault(context, codec)
    }

    // ---- raw-record manipulation helpers (simulating partial/corrupted states) ----

    private fun prefs() = context.getSharedPreferences("credential_vault", Context.MODE_PRIVATE)

    private fun seedMetaOnly(json: String) {
        prefs().edit().putString("meta:$ID", json).remove("cipher:$ID").remove("iv:$ID").commit()
    }

    // ------------------------------------------------------------------ 1. empty

    @Test
    fun completelyEmptyVaultIsNotConfiguredNotABlankScopeDenial() {
        assertEquals(CredentialRetrieval.NotConfigured, vault.retrieve(ID, PKG))
        assertFalse(vault.status(ID).configured)
        assertNull(vault.meta(ID))
        assertFalse(vault.isLocked(ID))
        assertEquals(listOf<String>(), vault.listIds())
    }

    @Test
    fun blankIdsAndPackagesAreRejectedEverywhere() {
        for (blank in listOf("", "   ")) {
            try {
                vault.retrieve(blank, PKG)
                fail("blank id must be rejected")
            } catch (_: IllegalArgumentException) { }
            try {
                vault.retrieve(ID, blank)
                fail("blank requesting package must be rejected")
            } catch (_: IllegalArgumentException) { }
            try {
                vault.store(blank, PKG, "p", SECRET.toCharArray())
                fail("blank id store must be rejected")
            } catch (_: IllegalArgumentException) { }
            try {
                vault.store(ID, blank, "p", SECRET.toCharArray())
                fail("blank package store must be rejected")
            } catch (_: IllegalArgumentException) { }
        }
        try {
            vault.store(ID, PKG, "p", CharArray(0))
            fail("blank secret must be rejected")
        } catch (_: IllegalArgumentException) { }
        // Nothing persisted by any rejected call.
        assertTrue(prefs().all.isEmpty())
    }

    // ------------------------------------------------------------------ 2/3/4. partial records

    @Test
    fun ciphertextWithoutMetadataIsTypedIncompleteAndNeverReleasedOrDeleted() {
        vault.store(ID, PKG, "terminal_login", SECRET.toCharArray())
        prefs().edit().remove("meta:$ID").commit()

        val outcome = vault.retrieve(ID, PKG)
        assertTrue(outcome is CredentialRetrieval.Incomplete)
        assertEquals(CredentialVault.INCOMPLETE_META_MISSING, (outcome as CredentialRetrieval.Incomplete).reason)
        // Non-destructive: record left for owner-flow healing.
        assertTrue(prefs().contains("cipher:$ID"))
        assertFalse(vault.status(ID).configured)
    }

    @Test
    fun metadataWithoutCiphertextIsTypedIncomplete() {
        seedMetaOnly("""{"targetPackage":"$PKG","purpose":"terminal_login","configuredAt":123,"validationFailures":0}""")
        val outcome = vault.retrieve(ID, PKG)
        assertTrue(outcome is CredentialRetrieval.Incomplete)
        assertEquals(CredentialVault.INCOMPLETE_CIPHERTEXT_MISSING, (outcome as CredentialRetrieval.Incomplete).reason)
    }

    @Test
    fun metadataWithCipherButMissingIvIsTypedIncomplete() {
        vault.store(ID, PKG, "terminal_login", SECRET.toCharArray())
        prefs().edit().remove("iv:$ID").commit()
        val outcome = vault.retrieve(ID, PKG)
        assertTrue(outcome is CredentialRetrieval.Incomplete)
        assertEquals(CredentialVault.INCOMPLETE_IV_MISSING, (outcome as CredentialRetrieval.Incomplete).reason)
    }

    @Test
    fun metadataWithoutCipherButWithIvIsTypedIncompleteWithDistinctCipherReason() {
        seedMetaOnly("""{"targetPackage":"$PKG","purpose":"terminal_login","configuredAt":123,"validationFailures":0}""")
        prefs().edit().putString("iv:$ID", "orphan-iv").commit()
        val outcome = vault.retrieve(ID, PKG)
        assertTrue(outcome is CredentialRetrieval.Incomplete)
        assertEquals(CredentialVault.INCOMPLETE_CIPHERTEXT_MISSING, (outcome as CredentialRetrieval.Incomplete).reason)
    }

    // ------------------------------------------------------------------ 5. malformed metadata

    @Test
    fun malformedMetadataJsonIsTypedIncompleteWithoutThrowingOrLeakingContents() {
        vault.store(ID, PKG, "terminal_login", SECRET.toCharArray())
        prefs().edit().putString("meta:$ID", "{not valid json!! targetPackage=$PKG").commit()
        val outcome = vault.retrieve(ID, PKG)
        assertTrue(outcome is CredentialRetrieval.Incomplete)
        val reason = (outcome as CredentialRetrieval.Incomplete).reason
        assertEquals(CredentialVault.INCOMPLETE_META_MALFORMED, reason)
        assertFalse(reason.contains(PKG)) // static reason only; no record contents echoed
        assertNull(vault.meta(ID))
    }

    // ------------------------------------------------------------------ 6. blank scope

    @Test
    fun storedBlankTargetPackageBecomesTypedIncompleteNotAScopeGrant() {
        // Simulates exactly the legacy corrupted row from G2/G3 evidence.
        prefs().edit()
            .putString("meta:$ID", """{"targetPackage":"","purpose":"terminal_login","configuredAt":9,"validationFailures":0}""")
            .putString("cipher:$ID", "AAAA").putString("iv:$ID", "BBBB")
            .commit()
        val outcome = vault.retrieve(ID, PKG)
        assertTrue(outcome is CredentialRetrieval.Incomplete)
        assertEquals(CredentialVault.INCOMPLETE_SCOPE_UNBOUND, (outcome as CredentialRetrieval.Incomplete).reason)
        assertFalse(vault.status(ID).configured)
    }

    // ------------------------------------------------------------------ 7/8. exact vs wrong package

    @Test
    fun exactPackageRetrievalReturnsTheStoredSecretAndClearsItsBuffer() {
        val buffer = SECRET.toCharArray()
        vault.store(ID, PKG, "terminal_login", buffer)
        // store() consumed/cleared the caller's buffer.
        assertTrue(buffer.all { it == '\u0000' })

        when (val outcome = vault.retrieve(ID, PKG)) {
            is CredentialRetrieval.Secret -> assertEquals(SECRET, outcome.value.concatToString())
            else -> fail("expected Secret, got $outcome")
        }
        assertTrue(vault.status(ID).configured)
        assertFalse(vault.status(ID).locked)
    }

    @Test
    fun wrongPackageAccessIsDeniedAsATypedOutcomeWithoutDecryption() {
        vault.store(ID, PKG, "terminal_login", SECRET.toCharArray())
        codec.failingAliases.add(codecAlias()) // even if decryption were attempted it would fail

        when (val outcome = vault.retrieve(ID, OTHER_PKG)) {
            is CredentialRetrieval.ScopeDenied ->
                assertEquals(PKG, outcome.scopedPackage)
            else -> fail("expected ScopeDenied, got $outcome")
        }
    }

    private fun codecAlias() = "amara_cred_$ID"

    // ------------------------------------------------------------------ 9. rotation

    @Test
    fun rotationReplacesSecretPreservesConfiguredAtAndStampsRotatedAt() {
        vault.store(ID, PKG, "terminal_login", SECRET.toCharArray())
        val first = vault.meta(ID)!!
        Thread.sleep(5)
        val result = vault.store(ID, PKG, "terminal_login", "112233".toCharArray())
        assertTrue(result is CredentialResult.Rotated)
        val second = vault.meta(ID)!!
        assertEquals(first.configuredAtMillis, second.configuredAtMillis)
        assertTrue(second.lastRotatedAtMillis != null && second.lastRotatedAtMillis >= first.configuredAtMillis)
        when (val outcome = vault.retrieve(ID, PKG)) {
            is CredentialRetrieval.Secret -> assertEquals("112233", outcome.value.concatToString())
            else -> fail("expected rotated Secret")
        }
    }

    // ------------------------------------------------------------------ 10. legacy migration

    @Test
    fun legacyPlaintextPinMigratesIntoTheVaultAndIsScrubbedFromPlainPrefs() {
        val config = SecureConfig(context, useEncryptedPrefs = false)
        config.sokoTerminalPin = SECRET
        assertTrue(prefs().all.isEmpty())

        val pin = AgentRuntime.sokoPinFrom(vault, config)

        assertEquals(SECRET, pin)
        assertEquals("", config.sokoTerminalPin)
        assertTrue(vault.status(ID).configured)
        // Second read serves from the vault alone.
        assertEquals(SECRET, AgentRuntime.sokoPinFrom(vault, config))
        assertEquals("", config.sokoTerminalPin)
    }

    @Test
    fun migrationHealsAPartialRecordNonDestructivelyByOverwritingIt() {
        // Corrupted pre-existing row (the on-device failure shape).
        prefs().edit()
            .putString("meta:$ID", """{"targetPackage":"","purpose":"x","configuredAt":1,"validationFailures":2}""")
            .putString("cipher:$ID", "junk").putString("iv:$ID", "junkiv")
            .commit()
        val config = SecureConfig(context, useEncryptedPrefs = false)
        config.sokoTerminalPin = SECRET

        assertEquals(SECRET, AgentRuntime.sokoPinFrom(vault, config))
        assertTrue(vault.status(ID).configured)
        // The corrupted predecessor carried no trustworthy counter; healing starts clean.
        assertEquals(0, vault.meta(ID)!!.consecutiveValidationFailures)
    }

    @Test
    fun incompleteRecordWithNoLegacyCopyYieldsEmptyPinInsteadOfCrashOrBlankScope() {
        seedMetaOnly("""{"targetPackage":"$PKG","purpose":"terminal_login","configuredAt":5,"validationFailures":0}""")
        val config = SecureConfig(context, useEncryptedPrefs = false)
        assertEquals("", AgentRuntime.sokoPinFrom(vault, config))
    }

    @Test
    fun failedProtectedStorageKeepsLegacyPlaintextForRetryInsteadOfDestroyingIt() {
        val config = SecureConfig(context, useEncryptedPrefs = false)
        config.sokoTerminalPin = SECRET
        val throwingCodec = object : SecretCodec {
            override fun encrypt(alias: String, plaintext: CharArray): Pair<String, String> =
                throw IllegalStateException("keystore unavailable")
            override fun decrypt(alias: String, blob: String, iv: String): CharArray = throw IllegalStateException()
            override fun deleteAlias(alias: String) { }
        }
        val brokenVault = CredentialVault(context, throwingCodec)

        // Migration attempt fails at protected storage...
        assertEquals("", AgentRuntime.sokoPinFrom(brokenVault, config))
        // ...so the ONLY surviving copy of the credential must survive too.
        assertEquals(SECRET, config.sokoTerminalPin)
        assertTrue(prefs().all.isEmpty())

        // The next attempt with a healthy vault migrates and only THEN clears.
        assertEquals(SECRET, AgentRuntime.sokoPinFrom(vault, config))
        assertEquals("", config.sokoTerminalPin)
        assertTrue(vault.status(ID).configured)
    }

    // ------------------------------------------------------------------ 11. lockout

    @Test
    fun validationFailuresCountLockAtThresholdAndSuccessResets() {
        vault.store(ID, PKG, "terminal_login", SECRET.toCharArray())
        repeat(2) { index ->
            assertEquals(
                CredentialVault.CredentialValidationState.FAILED_RETRYABLE,
                vault.recordValidationFailure(ID, "rejected attempt $index"),
            )
        }
        assertFalse(vault.isLocked(ID))
        assertEquals(
            CredentialVault.CredentialValidationState.LOCKED_OWNER_REQUIRED,
            vault.recordValidationFailure(ID, "third rejection"),
        )
        assertTrue(vault.isLocked(ID))
        assertFalse("locked slot must not advertise itself usable", vault.status(ID).let { it.configured && !it.locked })

        vault.recordValidationSuccess(ID)
        assertFalse(vault.isLocked(ID))
        assertEquals(0, vault.meta(ID)!!.consecutiveValidationFailures)
    }

    @Test
    fun concurrentValidationFailuresCannotLoseCounterUpdates() {
        vault.store(ID, PKG, "terminal_login", SECRET.toCharArray())
        val workers = 8
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workers)
        try {
            val futures = (0 until workers).map { index ->
                executor.submit<CredentialVault.CredentialValidationState> {
                    ready.countDown()
                    start.await()
                    vault.recordValidationFailure(ID, "concurrent rejection $index")
                }
            }
            ready.await()
            start.countDown()
            futures.forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }
        assertEquals(workers, vault.meta(ID)!!.consecutiveValidationFailures)
        assertTrue(vault.isLocked(ID))
    }

    @Test
    fun failuresAgainstAbsentSlotsCreateNoPhantomState() {
        assertEquals(
            CredentialVault.CredentialValidationState.FAILED_RETRYABLE,
            vault.recordValidationFailure(ID, "no such credential"),
        )
        assertNull(vault.meta(ID))
        assertTrue(prefs().all.isEmpty())
        assertFalse(vault.isLocked(ID))
    }

    // ------------------------------------------------------------------ 12. redaction sentinels

    @Test
    fun noFailurePathEverEmbedsSecretMaterialInReasonsOrExceptions() {
        vault.store(ID, PKG, "terminal_login", SECRET.toCharArray())

        // redactKnownSecrets masks the live secret wherever it appears in free text.
        val text = "the terminal said code ${SECRET}was wrong"
        assertTrue(vault.redactKnownSecrets(text).contains("[REDACTED:CREDENTIAL:$ID]"))
        assertFalse(vault.redactKnownSecrets(text).contains(SECRET))

        val probes = listOf(
            { vault.retrieve(ID, OTHER_PKG) },
            {
                prefs().edit().remove("iv:$ID").commit(); vault.retrieve(ID, PKG)
            },
            {
                prefs().edit().putString("meta:$ID", "garbage{").commit(); vault.retrieve(ID, PKG)
            },
        )
        probes.forEachIndexed { index, probe ->
            val outcome = probe()
            if (outcome is CredentialRetrieval.Incomplete) {
                assertFalse("probe $index leaked secret", outcome.reason.contains(SECRET))
                assertFalse(outcome.reason.contains("483920"))
            }
        }
        // Decryption-failure reason is static too.
        codec.failingAliases.add(codecAlias())
        prefs().edit().putString("iv:$ID", "restored").commit()
        val failed = vault.retrieve(ID, PKG)
        assertTrue(failed is CredentialRetrieval.Incomplete)
        assertFalse((failed as CredentialRetrieval.Incomplete).reason.contains(SECRET))
    }

    @Test
    fun databaseMigrationDecryptsEachVaultSecretOnceInsteadOfOncePerCell() {
        vault.store(ID, PKG, "terminal_login", SECRET.toCharArray())
        codec.lastDecryptedBuffers.clear()
        context.deleteDatabase(AmaraMemory.DATABASE_NAME)
        val memory = AmaraMemory(context)
        memory.recordOwnerChat("historical credential $SECRET must disappear")

        assertTrue(memory.scrubSecrets(vault) > 0)
        val stored = memory.ownerChatHistory().joinToString(" ") { it.text }
        assertFalse(stored.contains(SECRET))
        assertTrue(stored.contains("[REDACTED:CREDENTIAL:$ID]"))
        assertEquals("one configured secret must be opened once for the whole DB", 1, codec.lastDecryptedBuffers.size)
        assertTrue(codec.lastDecryptedBuffers.single().all { it == '\u0000' })
        memory.close()
    }

    @Test
    fun keystoreKeyLossIsTypedIncompleteAndOwnerActionable() {
        vault.store(ID, PKG, "terminal_login", SECRET.toCharArray())
        codec.failingAliases.add(codecAlias())
        val outcome = vault.retrieve(ID, PKG)
        assertTrue(outcome is CredentialRetrieval.Incomplete)
        assertEquals(
            CredentialVault.INCOMPLETE_DECRYPTION_FAILED,
            (outcome as CredentialRetrieval.Incomplete).reason,
        )
    }

    // ------------------------------------------------------------------ 13. buffer hygiene under failure

    @Test
    fun encryptionFailureClearsCallerBufferLeavesPreviousRecordAndTypesTheOutcome() {
        vault.store(ID, PKG, "terminal_login", SECRET.toCharArray())
        val snapshotAll = prefs().all.toMap()
        val throwingCodec = object : SecretCodec {
            var seenPlaintextSnapshot: String? = null
            override fun encrypt(alias: String, plaintext: CharArray): Pair<String, String> {
                seenPlaintextSnapshot = plaintext.concatToString() // snapshot BEFORE the vault wipes the buffer
                throw IllegalStateException("keystore unavailable")
            }
            override fun decrypt(alias: String, blob: String, iv: String): CharArray = throw IllegalStateException()
            override fun deleteAlias(alias: String) { }
        }
        val brokenVault = CredentialVault(context, throwingCodec)
        val buffer = "776611".toCharArray()

        val result = brokenVault.store(ID, PKG, "terminal_login", buffer)

        assertTrue(result is CredentialResult.Failed)
        assertEquals(CredentialVault.STORE_FAILED, (result as CredentialResult.Failed).reason)
        // The codec received the caller's rotation payload verbatim (proven via the snapshot)...
        assertEquals("776611", throwingCodec.seenPlaintextSnapshot)
        // ...and the caller's own buffer is wiped even though encryption threw.
        assertTrue(buffer.all { it == '\u0000' })
        // Previous intact record untouched; no marker or partial state leaked.
        assertEquals(snapshotAll, prefs().all)
        assertTrue(vault.status(ID).configured)
    }

    @Test
    fun statusClearsEveryDecryptedBufferItTouches() {
        vault.store(ID, PKG, "terminal_login", SECRET.toCharArray())
        codec.lastDecryptedBuffers.clear()
        assertTrue(vault.status(ID).configured)
        assertFalse(vault.status(ID).locked)
        assertTrue(codec.lastDecryptedBuffers.isNotEmpty())
        for (buffer in codec.lastDecryptedBuffers) {
            assertTrue("status() must clear decrypted buffers", buffer.all { it == '\u0000' })
        }
    }

    // ------------------------------------------------------------------ 14. lockout enforced on release

    /** Two rejections stay retryable; the third crosses LOCKOUT_THRESHOLD and soft-locks the slot. */
    private fun lockSlotViaProductionRejections(gate: SokoCredentialGate) {
        repeat(2) { index ->
            assertFalse("rejection ${index + 1} must not yet lock", gate.recordLoginRejected(AUTH_CODE))
        }
        assertTrue("third rejection crosses LOCKOUT_THRESHOLD", gate.recordLoginRejected(AUTH_CODE))
    }

    @Test
    fun lockedCredentialIsNeverDecryptedOrReleased() {
        val gate = SokoCredentialGate(vault, ID, PKG)
        vault.store(ID, PKG, "terminal_login", SECRET.toCharArray())
        lockSlotViaProductionRejections(gate)

        // Even a decryption that WOULD fail is never attempted while locked:
        // failingAliases would produce Incomplete(DECRYPTION_FAILED); Locked proves
        // the denial happened before any key access.
        codec.failingAliases.add(codecAlias())
        assertEquals(CredentialRetrieval.Locked, vault.retrieve(ID, PKG))
        assertEquals("", gate.releaseForSubmission())
        assertEquals("", AgentRuntime.sokoPinFrom(vault, SecureConfig(context, useEncryptedPrefs = false)))
        assertTrue(vault.isLocked(ID))
    }

    @Test fun onlyOwnerConfirmedSaveResetsLocalCredentialLockout() {
        vault.store(ID, PKG, "terminal_login", SECRET.toCharArray())
        lockSlotViaProductionRejections(SokoCredentialGate(vault, ID, PKG))
        vault.store(ID, PKG, "terminal_login", SECRET.toCharArray())
        assertTrue(vault.status(ID).locked)
        vault.store(ID, PKG, "terminal_login", SECRET.toCharArray(), ownerConfirmedReset = true)
        assertFalse(vault.status(ID).locked)
        assertTrue(vault.status(ID).configured)
    }

    @Test
    fun productionLoginFailuresIncrementCounterAndFourthSubmissionIsBlocked() {
        val gate = SokoCredentialGate(vault, ID, PKG)
        vault.store(ID, PKG, "terminal_login", SECRET.toCharArray())

        // First and second failures recorded but retryable; release continues.
        assertFalse(gate.recordLoginRejected(AUTH_CODE))
        assertFalse(gate.recordLoginRejected(AUTH_CODE))
        assertEquals(2, vault.meta(ID)!!.consecutiveValidationFailures)
        assertTrue(gate.releaseForSubmission().isNotEmpty())

        // Third failure crosses LOCKOUT_THRESHOLD.
        assertTrue(gate.recordLoginRejected(AUTH_CODE))
        assertEquals(3, vault.meta(ID)!!.consecutiveValidationFailures)
        assertTrue(vault.isLocked(ID))

        // The FOURTH submission is blocked outright: nothing is handed out to type.
        assertEquals("", gate.releaseForSubmission())
    }

    @Test
    fun successfulLoginResetsCounterAndRestoresRelease() {
        val gate = SokoCredentialGate(vault, ID, PKG)
        vault.store(ID, PKG, "terminal_login", SECRET.toCharArray())
        lockSlotViaProductionRejections(gate)
        gate.recordLoginAccepted()
        assertEquals(0, vault.meta(ID)!!.consecutiveValidationFailures)
        assertFalse(vault.isLocked(ID))
        assertEquals(SECRET, gate.releaseForSubmission())
    }

    @Test
    fun noRejectionLoopCanKeepSubmittingWhileLockedUntilAnAcceptedLoginHeals() {
        val gate = SokoCredentialGate(vault, ID, PKG)
        val config = SecureConfig(context, useEncryptedPrefs = false)
        vault.store(ID, PKG, "terminal_login", SECRET.toCharArray())
        lockSlotViaProductionRejections(gate)
        assertTrue(gate.recordLoginRejected(AUTH_CODE))

        // A scheduler loop hammering the path can obtain no material and therefore
        // cannot generate further external attempts (which could lock the owner's
        // real account): every release stays denied across arbitrary repetitions,
        // and repeated rejections keep the slot locked instead of cycling it.
        repeat(25) {
            assertEquals("", AgentRuntime.sokoPinFrom(vault, config))
            assertEquals("", gate.releaseForSubmission())
            gate.recordLoginRejected(AUTH_CODE)
            assertTrue(vault.isLocked(ID))
        }

        // Only a genuinely accepted login heals the slot.
        gate.recordLoginAccepted()
        assertFalse(vault.isLocked(ID))
        assertEquals(SECRET, gate.releaseForSubmission())
    }

    // ------------------------------------------------------------------ 15. key-access separation

    @Test
    fun decryptNeverRecreatesLostAliasWhileEncryptStillBootstraps() {
        vault.store(ID, PKG, "terminal_login", SECRET.toCharArray())
        assertEquals(setOf(codecAlias()), codec.createdAliases)

        codec.deleteAlias(codecAlias())
        val outcome = vault.retrieve(ID, PKG)

        assertTrue(outcome is CredentialRetrieval.Incomplete)
        assertEquals(
            CredentialVault.INCOMPLETE_DECRYPTION_FAILED,
            (outcome as CredentialRetrieval.Incomplete).reason,
        )
        // Decrypt did NOT silently mint a replacement key.
        assertEquals(setOf(codecAlias()), codec.createdAliases)
        // Encryption side may bootstrap when the owner reconfigures; the fresh
        // key becomes usable for the NEW ciphertext (old material is gone).
        vault.store(ID, PKG, "terminal_login", "445599".toCharArray())
        assertEquals(setOf(codecAlias()), codec.createdAliases) // same alias recreated via encrypt only
        codec.failingAliases.remove(codecAlias()) // recreated alias is a working key again
        assertEquals("445599", (vault.retrieve(ID, PKG) as CredentialRetrieval.Secret).value.concatToString())
    }

    // ------------------------------------------------------------------ 16. coherent storage / torn writes

    @Test
    fun tornWriteMarkerYieldsTypedIncompleteEvenWhenTripleLooksValid() {
        vault.store(ID, PKG, "terminal_login", SECRET.toCharArray())
        prefs().edit().putBoolean("write_pending:$ID", true).commit()

        val outcome = vault.retrieve(ID, PKG)
        assertTrue(outcome is CredentialRetrieval.Incomplete)
        assertEquals(CredentialVault.INCOMPLETE_WRITE_PENDING, (outcome as CredentialRetrieval.Incomplete).reason)
        assertNull(vault.meta(ID))
        assertFalse(vault.status(ID).configured)
        // Healing: a fresh store commits coherently and clears the journal.
        vault.store(ID, PKG, "terminal_login", SECRET.toCharArray())
        assertTrue(vault.status(ID).configured)
    }

    @Test
    fun interruptedWriteLeavesPartialPayloadTypedIncompleteNotSilentlyValid() {
        // Simulated crash after the journal marker, mid-way through the commit edit.
        prefs().edit()
            .putBoolean("write_pending:$ID", true)
            .putString("cipher:$ID", "AAAA")
            .commit()
        val outcome = vault.retrieve(ID, PKG)
        assertTrue(outcome is CredentialRetrieval.Incomplete)
        assertEquals(CredentialVault.INCOMPLETE_WRITE_PENDING, (outcome as CredentialRetrieval.Incomplete).reason)
        // A rotation interrupted between marker and commit also refuses to serve
        // the stale predecessor as if it were current truth.
        assertEquals("", SokoCredentialGate(vault, ID, PKG).releaseForSubmission())
    }
}
