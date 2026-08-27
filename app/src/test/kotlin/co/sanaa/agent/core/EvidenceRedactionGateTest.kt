package co.sanaa.agent.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Mechanical evidence-leakage sentinel (companion to
 * mission/amara-complete-employee/scripts/check_evidence_redaction.sh): every text
 * file under the mission evidence trees must be free of contact-name announcements
 * and secret material. Binary files are skipped; the scan mirrors the shell gate so
 * a leak cannot pass one and silently fail the other.
 */
class EvidenceRedactionGateTest {

    private val secretPatterns = listOf(
        "groq_key" to Regex("gsk_[A-Za-z0-9]{10,}"),
        "sk_key" to Regex("\\bsk-[A-Za-z0-9]{16,}"),
        "bearer" to Regex("(?i)bearer\\s+[A-Za-z0-9._\\-]{16,}"),
        "auth_header" to Regex("(?i)authorization:\\s*\\S+"),
        "pin_statement" to Regex("(?i)\\b(?:pin|otp|password)\\s*(?:is|:|=)\\s*\\d{4,8}\\b"),
        "api_key_statement" to Regex("(?i)\\bapi[_ ]?key\\s*(?:is|:|=)\\s*[A-Za-z0-9_\\-]{12,}"),
    )

    private val namePatterns = listOf(
        "person_after_from" to Regex("\\b(?:from|with)\\s+[A-Z][a-z]+\\s+[A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*\\b"),
    )

    private val textSuffixes = setOf("log", "txt", "xml", "json", "csv", "md")

    @Test
    fun evidenceTreesCarryNoContactNamesOrSecrets() {
        val roots = listOf(
            "mission/amara-10-10/evidence-device-20260825",
            "mission/amara-10-10/evidence-device-20260825-reliability",
            "mission/amara-complete-employee/evidence",
            "mission/run-20260825",
        ).mapNotNull { relative ->
            repoRoot()?.resolve(relative)?.takeIf { it.exists() }
        }
        assertTrue("evidence roots must exist for the sentinel scan", roots.isNotEmpty())
        val leaks = mutableListOf<String>()
        for (root in roots) {
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                if (file.extension.lowercase() !in textSuffixes) return@forEach
                file.readLines().forEachIndexed { index, line ->
                    secretPatterns.forEach { (label, regex) ->
                        if (regex.containsMatchIn(line)) leaks += "${file.relativeTo(repoRoot()!!)}:${index + 1}: SECRET [$label]"
                    }
                    namePatterns.forEach { (label, regex) ->
                        if (regex.containsMatchIn(line)) leaks += "${file.relativeTo(repoRoot()!!)}:${index + 1}: CONTACT NAME [$label] :: ${line.take(90)}"
                    }
                }
            }
        }
        assertTrue("evidence leakage detected:\n" + leaks.joinToString("\n"), leaks.isEmpty())
    }

    @Test
    fun theDetectorItselfCatchesPlantedLeaks() {
        val nameRegex = namePatterns.first().second
        val pinRegex = secretPatterns.first { it.first == "pin_statement" }.second
        assertTrue(nameRegex.containsMatchIn("Recorded unmonitored WhatsApp message from Annah Cashier Bweyale"))
        assertTrue(pinRegex.containsMatchIn("owner said pin is 483920 twice"))
        assertFalse(nameRegex.containsMatchIn("Recorded unmonitored WhatsApp notification"))
        assertFalse(pinRegex.containsMatchIn("Ready chip over Point of Sale"))
    }

    private fun assertFalse(condition: Boolean) = org.junit.Assert.assertTrue(!condition)

    private fun repoRoot(): File? {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle").exists()) {
            dir = dir.parentFile
        }
        return dir
    }
}
