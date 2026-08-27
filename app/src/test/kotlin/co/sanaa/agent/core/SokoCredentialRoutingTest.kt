package co.sanaa.agent.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.actions.AccessibilityActions
import co.sanaa.agent.api.GroqClient
import co.sanaa.agent.modules.SokoFullIntelligence
import co.sanaa.agent.modules.SokoIntelligenceModule
import co.sanaa.agent.modules.SokoIntelligenceModule.Companion.PIN_WITHHELD_SUMMARY
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Paths

/**
 * Proves Soko credential routing has exactly ONE production authority — the
 * scoped vault behind AgentRuntime's lockout gate — through BOTH behavior
 * (injected provider reaches the terminal-login layer; blank PINs never touch
 * authentication; rejected logins land on the vault) and a mechanical repo-wide
 * source scan (the plaintext config copy is read nowhere outside the documented
 * one-time migration, and every production construction injects an explicit
 * pin authority).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SokoCredentialRoutingTest {

    companion object {
        const val ID = AgentRuntime.SOKO_PIN_ID
        const val PKG = AgentRuntime.SOKO_TERMINAL_PACKAGE
        const val SENTINEL_PIN = "913275"
        const val REJECTION_TEXT =
            "Soko Terminal is at Staff Login, but the saved PIN was not accepted. Check the stored Terminal PIN."
        const val INDETERMINATE_TEXT = "Soko Terminal could not be launched."

        /**
         * Dated waivers for files NOT owned by Agent 2 (see coordination/
         * INTERFACE_REQUESTS.md REQ-2.1): each may hold EXACTLY ONE latent
         * default-lambda read shaped `{ config.sokoTerminalPin }`. Both are
         * constructed with explicit vault-backed providers today; owners are
         * requested to switch defaults to fail-closed `{ "" }`, after which this
         * waiver list becomes empty.
         */
        val LATENT_DEFAULT_LAMBDA_WAIVERS: Map<String, Int> = mapOf(
            // 2026-08-26: Agent 1 removed both latent defaults (fail-closed now);
            // waiver allowance drops to zero — no parallel authority remains.
        )
    }

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("credential_vault", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("sanaa_agent_secrets_test", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun prefs() = context.getSharedPreferences("credential_vault", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ source-scan machinery

    private fun mainKotlinRoot(): java.nio.file.Path {
        var dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        repeat(8) {
            val candidate = dir.resolve("app/src/main/kotlin/co/sanaa/agent")
            if (candidate.toFile().isDirectory) return candidate
            dir = dir.parent ?: return@repeat
        }
        throw AssertionError("Could not locate app/src/main/kotlin/co/sanaa/agent from ${System.getProperty("user.dir")}")
    }

    private fun allMainSources(): List<Pair<String, String>> {
        val root = mainKotlinRoot()
        return root.toFile().walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { root.relativize(it.toPath()).toString().replace('\\', '/') to it.readText() }
            .toList()
    }

    /** Removes documented marker-bounded migration regions from the content. */
    private fun withoutMigrationRegions(content: String): String =
        content.replace(Regex("(?s)// \\[SOKO-PIN-AUTHORITY-BEGIN].*?// \\[SOKO-PIN-AUTHORITY-END]"), "// [migration region elided by scan]")

    private fun occurrencesOutsideClearingWrites(lines: List<String>): List<String> =
        lines.withIndex().filter { (_, line) ->
            line.contains("sokoTerminalPin") && !Regex("\\.sokoTerminalPin\\s*=\\s*\"\"").containsMatchIn(line)
        }.map { (index, line) -> "line ${index + 1}: ${line.trim()}" }

    // ------------------------------------------------------------------ static guarantees

    @Test
    fun plaintextSokoTerminalPinIsReadOnlyInsideDocumentedMigration() {
        val violations = mutableListOf<String>()
        for ((relative, content) in allMainSources()) {
            val totalOccurrences = content.split("sokoTerminalPin").size - 1
            when {
                relative == "core/SecureConfig.kt" -> {
                    val declared = Regex("""var\s+sokoTerminalPin\s*:""").containsMatchIn(content)
                    if (!declared || totalOccurrences != 1) {
                        violations.add("$relative must declare sokoTerminalPin exactly once (found $totalOccurrences)")
                    }
                }
                relative == "MainActivity.kt" -> {
                    if (totalOccurrences != 0) violations.add("$relative must not reference the plaintext PIN copy at all")
                }
                else -> {
                    val remaining = occurrencesOutsideClearingWrites(
                        withoutMigrationRegions(content).lines(),
                    )
                    val waiverAllowance = LATENT_DEFAULT_LAMBDA_WAIVERS[relative] ?: 0
                    val latentDefaults = remaining.count { it.contains("{ config.sokoTerminalPin }") }
                    val others = remaining.filterNot { it.contains("{ config.sokoTerminalPin }") }
                    if (others.isNotEmpty()) {
                        violations.add("$relative reads/clears improperly: $others")
                    }
                    if (latentDefaults > waiverAllowance) {
                        violations.add("$relative holds $latentDefaults latent default-lambda authorities (waiver allows $waiverAllowance)")
                    }
                }
            }
        }
        assertTrue("Parallel PIN authorities detected: $violations", violations.isEmpty())
    }

    @Test
    fun everyProductionSokoConstructionInjectsAnExplicitPinAuthority() {
        val typeNames = listOf(
            "SokoFullIntelligence",
            "SokoIntelligenceModule",
            "SokoInventoryModule",
            "SokoStudioSharingModule",
        )
        val failures = mutableListOf<String>()
        var checkedConstructions = 0
        outer@ for ((relative, content) in allMainSources()) {
            for (typeName in typeNames) {
                var index = content.indexOf("$typeName(")
                while (index >= 0) {
                    val lineStart = content.lastIndexOf('\n', index).let { if (it < 0) 0 else it + 1 }
                    val prefix = content.substring(lineStart, index).trim()
                    val commented = prefix.startsWith("//") || prefix.startsWith("*") || prefix.startsWith("/*")
                    val declaration = prefix.endsWith("class")
                    if (!commented && !declaration) {
                        checkedConstructions++
                        val span = balancedParenSpan(content, index + typeName.length)
                        if (!Regex("""\bpin\s*=""").containsMatchIn(span)) {
                            failures.add("$relative constructs $typeName without an explicit pin authority: $span")
                        }
                    }
                    index = content.indexOf("$typeName(", index + 1)
                }
            }
        }
        assertTrue("scan found no constructions to check", checkedConstructions >= 5)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun balancedParenSpan(content: String, openParenIndex: Int): String {
        var depth = 0
        var i = openParenIndex
        val sb = StringBuilder()
        while (i < content.length) {
            val c = content[i]
            sb.append(c)
            if (c == '(') depth++
            if (c == ')') { depth--; if (depth == 0) break }
            i++
        }
        return sb.toString()
    }

    @Test
    fun sokoIntelligenceDefaultPinFailsClosedAndFullIntelligenceRequiresInjection() {
        val sources = allMainSources().toMap()
        val module = sources["modules/SokoIntelligenceModule.kt"]
            ?: throw AssertionError("SokoIntelligenceModule.kt missing")
        assertTrue(module.contains("pin: () -> String = { \"\" }"))
        val full = sources["modules/SokoFullIntelligence.kt"] ?: throw AssertionError("SokoFullIntelligence.kt missing")
        assertTrue(full.contains("pin: () -> String = { \"\" }"))
        // And the alerts path forwards the injected authority, never builds its own.
        assertTrue(full.contains("pin = pin"))
    }

    // ------------------------------------------------------------------ behavioral routing

    private fun wiredVault(): Pair<CredentialVault, ReversibleRoutingCodec> {
        val codec = ReversibleRoutingCodec()
        return CredentialVault(context, codec) to codec
    }

    class ReversibleRoutingCodec : SecretCodec {
        override fun encrypt(alias: String, plaintext: CharArray): Pair<String, String> =
            java.util.Base64.getEncoder().encodeToString(
                plaintext.concatToString().toByteArray(Charsets.UTF_8).map { (it.toInt() xor 0x5A).toByte() }.toByteArray(),
            ) to "iv:$alias"

        override fun decrypt(alias: String, blob: String, iv: String): CharArray =
            String(
                java.util.Base64.getDecoder().decode(blob).map { (it.toInt() xor 0x5A).toByte() }.toByteArray(),
                Charsets.UTF_8,
            ).toCharArray()

        override fun deleteAlias(alias: String) { }
    }

    @Test
    fun fullShopReportAndAlertsRouteThroughTheInjectedVaultProvider() = runBlocking {
        val (vault, _) = wiredVault()
        vault.store(ID, PKG, "terminal_login", SENTINEL_PIN.toCharArray())
        val config = SecureConfig(context, useEncryptedPrefs = false)
        assertEquals("", config.sokoTerminalPin)
        val memory = AmaraMemory(context)
        val actions = AccessibilityActions(context, memory)
        val released = mutableListOf<String>()
        val full = SokoFullIntelligence(
            config, actions, memory,
            GroqClient(config, memory, allowInsecureTestEndpoint = true),
            pin = { gateRelease(vault).also { released.add(it) } },
        )

        full.readAlerts()
        full.fullShopReport()

        // The alerts section (inside the shop report too) drew its material from
        // the injected vault authority, twice, and nothing else ever produced a PIN.
        assertEquals(listOf(SENTINEL_PIN, SENTINEL_PIN), released)
        assertEquals("", config.sokoTerminalPin)
    }

    private fun gateRelease(vault: CredentialVault): String =
        SokoCredentialGate(vault, ID, PKG).releaseForSubmission()

    @Test
    fun blankPinAfterMigrationNeverReachesAuthentication() = runBlocking {
        val config = SecureConfig(context, useEncryptedPrefs = false)
        val memory = AmaraMemory(context)
        val actions = AccessibilityActions(context, memory)
        val (vault, _) = wiredVault()
        val module = SokoIntelligenceModule(
            config, actions, memory,
            GroqClient(config, memory, allowInsecureTestEndpoint = true),
            pin = { SokoCredentialGate(vault, ID, PKG).releaseForSubmission() },
        )

        val result = module.bookingsNeedingAction()

        assertFalse(result.success)
        assertEquals(PIN_WITHHELD_SUMMARY, result.summary)
        // Had authentication been touched anyway, the failure prose would differ;
        // equality with the withheld summary proves the guard fired first.
        assertFalse(result.summary.contains(INDETERMINATE_TEXT))
        assertNull(vault.meta(ID))
        assertTrue(prefs().all.isEmpty())
    }

    @Test
    fun rejectedProductionLoginsLandOnTheVaultAndLockTheFourthAttempt() = runBlocking {
        val config = SecureConfig(context, useEncryptedPrefs = false)
        val memory = AmaraMemory(context)
        val actions = AccessibilityActions(context, memory)
        val (vault, _) = wiredVault()
        vault.store(ID, PKG, "terminal_login", SENTINEL_PIN.toCharArray())
        val gate = SokoCredentialGate(vault, ID, PKG)
        val module = SokoIntelligenceModule(
            config, actions, memory,
            GroqClient(config, memory, allowInsecureTestEndpoint = true),
            pin = { gate.releaseForSubmission() },
            credentialAudit = object : SokoCredentialAuditor {
                override fun onLoginAccepted() { gate.recordLoginAccepted() }
                override fun onLoginRejected(staticRedactedReason: String) { gate.recordLoginRejected(staticRedactedReason) }
            },
        )

        // Classification contract: success and the rejection marker are conclusive;
        // unrelated infrastructure failures never touch the counter.
        module.auditLoginOutcome(null)
        assertEquals(0, vault.meta(ID)!!.consecutiveValidationFailures)
        module.auditLoginOutcome(INDETERMINATE_TEXT)
        assertEquals(0, vault.meta(ID)!!.consecutiveValidationFailures)
        module.auditLoginOutcome(REJECTION_TEXT)
        assertEquals(1, vault.meta(ID)!!.consecutiveValidationFailures)

        module.auditLoginOutcome(REJECTION_TEXT)
        module.auditLoginOutcome(REJECTION_TEXT)
        assertTrue(vault.isLocked(ID))

        // The next scheduled run obtains no material at all: blank in, refusal out,
        // counter untouched by the attempt itself.
        val blocked = module.alertsNeedingAction()
        assertEquals(PIN_WITHHELD_SUMMARY, blocked.summary)
        assertEquals(CredentialVault.LOCKOUT_THRESHOLD, vault.meta(ID)!!.consecutiveValidationFailures)

        // An accepted login through the same chain heals the slot.
        module.auditLoginOutcome(null)
        assertFalse(vault.isLocked(ID))
        assertEquals(SENTINEL_PIN, gate.releaseForSubmission())
    }

    @Test
    fun legacyMigrationHealsPartialStateOrReportsItWithoutReachingAuth() = runBlocking {
        val config = SecureConfig(context, useEncryptedPrefs = false)
        val (vault, _) = wiredVault()

        // Partial record + legacy plaintext: guarded migration heals non-destructively.
        prefs().edit()
            .putString("meta:$ID", """{"targetPackage":"","purpose":"x","configuredAt":1,"validationFailures":2}""")
            .putString("cipher:$ID", "junk").putString("iv:$ID", "junkiv")
            .commit()
        config.sokoTerminalPin = SENTINEL_PIN
        assertEquals(SENTINEL_PIN, AgentRuntime.sokoPinFrom(vault, config))
        assertEquals("", config.sokoTerminalPin)
        assertTrue(vault.status(ID).configured)

        // Partial record WITHOUT any legacy copy: typed refusal, empty handout,
        // plaintext config still untouched, nothing fabricated.
        context.getSharedPreferences("credential_vault", Context.MODE_PRIVATE).edit().clear().commit()
        prefs().edit()
            .putString("meta:$ID", """{"targetPackage":"$PKG","purpose":"terminal_login","configuredAt":7,"validationFailures":0}""")
            .commit()
        assertEquals("", AgentRuntime.sokoPinFrom(vault, config))
        assertEquals("", config.sokoTerminalPin)
    }

    @Test
    fun lockedSlotRefusesMigrationOfALegacyCopyUntilUnlocked() {
        val config = SecureConfig(context, useEncryptedPrefs = false)
        val (vault, _) = wiredVault()
        vault.store(ID, PKG, "terminal_login", SENTINEL_PIN.toCharArray())
        repeat(3) { vault.recordValidationFailure(ID, SokoIntelligenceModule.AUTH_REJECTED_CODE) }
        config.sokoTerminalPin = SENTINEL_PIN

        assertEquals("", AgentRuntime.sokoPinFrom(vault, config))
        assertTrue(vault.isLocked(ID))
        // The plaintext copy was neither migrated nor consumed while locked.
        assertEquals(SENTINEL_PIN, config.sokoTerminalPin)
    }

    @Test
    fun noSentinelSecretAppearsInAnyDurableTextOrFailureProse() = runBlocking {
        val config = SecureConfig(context, useEncryptedPrefs = false)
        val memory = AmaraMemory(context)
        val actions = AccessibilityActions(context, memory)
        val (vault, _) = wiredVault()
        vault.store(ID, PKG, "terminal_login", SENTINEL_PIN.toCharArray())
        val gate = SokoCredentialGate(vault, ID, PKG)
        val module = SokoIntelligenceModule(
            config, actions, memory,
            GroqClient(config, memory, allowInsecureTestEndpoint = true),
            pin = { gate.releaseForSubmission() },
            credentialAudit = object : SokoCredentialAuditor {
                override fun onLoginAccepted() { gate.recordLoginAccepted() }
                override fun onLoginRejected(staticRedactedReason: String) { gate.recordLoginRejected(staticRedactedReason) }
            },
        )

        val observed = mutableListOf<String>()
        observed.add(module.alertsNeedingAction().summary)
        observed.add(module.bookingsNeedingAction().summary)
        module.auditLoginOutcome(REJECTION_TEXT)
        module.auditLoginOutcome(null)
        observed.add(vault.redactKnownSecrets("code ${SENTINEL_PIN}wrong"))
        val full = SokoFullIntelligence(
            config, actions, memory,
            GroqClient(config, memory, allowInsecureTestEndpoint = true),
            pin = { gate.releaseForSubmission() },
        )
        observed.add(full.readAlerts().summary)

        // Durable vault/config bytes plus every produced sentence stay clean.
        val durableText = (prefs().all.values.filterIsInstance<String>() +
            context.getSharedPreferences("sanaa_agent_secrets_test", Context.MODE_PRIVATE)
                .all.values.filterIsInstance<String>())
        for (text in durableText + observed) {
            assertFalse("sentinel leaked: ${text.take(80)}", text.contains(SENTINEL_PIN))
        }
        // Redaction actively masks the live secret wherever it surfaces in free text.
        assertTrue(vault.redactKnownSecrets("code ${SENTINEL_PIN}wrong").contains("[REDACTED:CREDENTIAL:$ID]"))
    }
}
