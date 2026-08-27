package co.sanaa.agent.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Campaign AMARA-REL-20260826 Task A proof: a scheduled owner command that carries
 * credential material must leave NO raw secret in ANY database table, journal,
 * owner-chat row, recurring-task row, or occurrence result — and no production call
 * site may bypass the sanitized-command path.
 *
 * Sentinels are deliberately fake and clearly marked; they never resemble a real
 * owner credential beyond shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecurringSecretPersistenceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // Synthetic sentinels (never real credentials).
    private val sentinelPin = "482913"
    private val sentinelPassword = "hunter2k"
    private val rawCommand = "Every hour check Soko bookings; my PIN is $sentinelPin and password: $sentinelPassword"

    private fun freshMemory(): AmaraMemory {
        context.deleteDatabase(AmaraMemory.DATABASE_NAME)
        return AmaraMemory(context)
    }

    /** Walks EVERY user table and TEXT cell of the live database. */
    private fun allTextCells(memory: AmaraMemory): List<Pair<String, String>> {
        val db = memory.readableDatabase
        val tables = mutableListOf<String>()
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_metadata'",
            null,
        ).use { cursor -> while (cursor.moveToNext()) tables.add(cursor.getString(0)) }
        val cells = mutableListOf<Pair<String, String>>()
        for (table in tables) {
            db.rawQuery("SELECT * FROM \"$table\"", null).use { cursor ->
                while (cursor.moveToNext()) {
                    for (index in 0 until cursor.columnCount) {
                        if (cursor.getType(index) == android.database.Cursor.FIELD_TYPE_STRING) {
                            cells += "$table.${cursor.getColumnName(index)}" to cursor.getString(index)
                        }
                    }
                }
            }
        }
        return cells
    }

    private fun assertNoSentinelAnywhere(memory: AmaraMemory) {
        val offenders = allTextCells(memory).filter { (_, value) ->
            value.contains(sentinelPin) || value.contains(sentinelPassword)
        }
        assertEquals("Raw sentinel secret persisted into: $offenders", emptyList<Pair<String, String>>(), offenders)
    }

    @Test
    fun scheduledCredentialBearingCommandLeavesNoRawSecretInAnyTable() {
        val memory = freshMemory()
        val schedule = CommandScheduleParser.parse(rawCommand)
        assertEquals("fixture must parse as the daily recurring instruction", true, schedule != null)

        // The exact persistence sequence AutonomyController.executeInternal performs,
        // with the sanitized command (as fixed in production).
        val guard = CredentialGuard.inspect(rawCommand)
        val safeCommand = guard.safeForPersistence
        assertFalse("guard must redact the sentinel PIN", safeCommand.contains(sentinelPin))

        val instructionId = memory.recordInstruction(safeCommand)
        val taskId = memory.createTaskJournal(instructionId, safeCommand)
        memory.recordOwnerChat(safeCommand)
        val recurringId = memory.createRecurringTask(safeCommand, schedule!!, nextRunAt = 1_000L)
        memory.updateTaskJournal(taskId, "completed", "report", "Scheduled “${schedule.taskText}”")
        memory.updateTaskContext(taskId, "observed state; owner said PIN is $sentinelPin again", "analysis noting password: $sentinelPassword")
        memory.recordAmaraChat("Scheduled. Your PIN stays private.")
        memory.completeRecurringOccurrence(recurringId, "ran with password: $sentinelPassword", 2_000L)

        assertNoSentinelAnywhere(memory)

        // Redaction markers must be present so correlation survives without disclosure.
        val stored = memory.recurringTask(recurringId)!!
        assertFalse(stored.instruction.contains(sentinelPin))
        assertFalse(stored.taskText.contains(sentinelPin))
        org.junit.Assert.assertTrue(stored.instruction.contains(Redactor.REDACTION_PREFIX))
    }

    @Test
    fun legacyRowsContainingSecretsAreHealedByScrubSweep() {
        val memory = freshMemory()
        val schedule = CommandScheduleParser.parse(rawCommand)!!
        val recurringId = memory.createRecurringTask("benign instruction", schedule, 1_000L)
        // Simulate a pre-fix row written before the storage-boundary guard existed.
        memory.writableDatabase.execSQL(
            "UPDATE recurring_tasks SET instruction = '$rawCommand' WHERE id = $recurringId",
        )
        assertAllCellsContain(memory, sentinelPin)
        val scrubbed = memory.scrubSecrets()
        org.junit.Assert.assertTrue("scrub sweep must heal at least one cell", scrubbed > 0)
        assertNoSentinelAnywhere(memory)
    }

    private fun assertAllCellsContain(memory: AmaraMemory, needle: String) {
        val hit = allTextCells(memory).any { it.second.contains(needle) }
        org.junit.Assert.assertTrue("fixture precondition: seeded row must contain the sentinel", hit)
    }

    /** Static negative fixture: no production sink may receive the RAW owner command. */
    @Test
    fun noProductionCallSitePassesRawOwnerCommandIntoPersistence() {
        val root = sequenceOf(File("..").absoluteFile, File(".").absoluteFile, File(System.getProperty("user.dir")))
            .map { it.resolve("app/src/main/kotlin/co/sanaa/agent") }
            .firstOrNull { it.isDirectory }
            ?: throw AssertionError("could not locate app/src/main/kotlin/co/sanaa/agent")

        val controller = File(root, "core/AutonomyController.kt").readText()
        // Raw `command` (NOT safeCommand) reaching any durable sink regresses Task A.
        val forbidden = listOf(
            Regex("createRecurringTask\\(\\s*command\\b"),
            Regex("recordInstruction\\(\\s*command\\b"),
            Regex("createTaskJournal\\([^,]+,\\s*command\\b"),
            Regex("recordOwnerChat\\(\\s*command\\b"),
            Regex("recordAction\\([^)]*\\bcommand\\b"),
        )
        for (pattern in forbidden) {
            assertFalse(
                "AutonomyController passes the RAW owner command into persistence via ${pattern.pattern}; use safeCommand",
                pattern.containsMatchIn(controller),
            )
        }

        // Exception-leakage rule across every production source file. Comment
        // prose is stripped so documentation mentions never false-positive.
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { !it.nameWithoutExtension.endsWith("Test") }
            .mapNotNull { file ->
                val codeOnly = file.readLines().joinToString("\n") { line ->
                    line.substringBefore("//")
                }
                if (codeOnly.contains("stackTraceToString")) file.name else null
            }.toList()
        assertEquals("stackTraceToString() reached production sinks in: $offenders", emptyList<String>(), offenders)
    }
}
