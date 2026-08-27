package co.sanaa.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Executes the mechanical boundary script against (a) the real tree — must stay clean —
 * and (b) a synthetic tree containing a violation — must fail with a file:line report.
 * Scope: JVM process execution; the script itself performs the static analysis.
 */
class BoundaryCheckEnforcementTest {

    private fun script(): String {
        val candidates = listOf(
            "mission/amara-complete-employee/scripts/check_side_effect_boundary.sh",
            "../mission/amara-complete-employee/scripts/check_side_effect_boundary.sh",
        )
        return candidates.firstOrNull { File(it).isFile }
            ?: error("boundary script not found from ${File(".").absolutePath}")
    }

    @Test fun realTreeIsClean() {
        val process = ProcessBuilder("bash", script()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("boundary script must pass on the real tree:\n$output", 0, process.waitFor())
        assertTrue(output.contains("clean"))
    }

    @Test fun syntheticViolationIsRejectedWithFileAndLine() {
        val repoRoot = File(script()).parentFile.parentFile.parentFile // mission/amara-complete-employee/scripts -> repo
        val tempRoot = File(repoRoot, "build/tmp/boundary-fixture").apply { deleteRecursively(); mkdirs() }
        File(tempRoot, "app/src/main/kotlin/co/sanaa/agent/core").mkdirs()
        File(tempRoot, "app/src/main/kotlin/co/sanaa/agent/core/Violation.kt").writeText(
            """
            package co.sanaa.agent.core
            class Violation { fun boom(a: AccessibilityActions) { a.sendInCurrentChat("x") } }
            """.trimIndent(),
        )
        val process = ProcessBuilder("bash", script(), tempRoot.absolutePath).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        assertTrue("script must fail on violation:\n$output", exit != 0)
        assertTrue(output.contains("Violation.kt"))
        assertTrue(output.contains("outside the transaction boundary"))
        tempRoot.deleteRecursively()
    }
}
