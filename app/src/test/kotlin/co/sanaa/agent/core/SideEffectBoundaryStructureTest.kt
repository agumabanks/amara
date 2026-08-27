package co.sanaa.agent.core

import co.sanaa.agent.actions.AccessibilityActions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * STRUCTURAL side-effect boundary enforcement (defense layer 2, after the private-scope
 * refactor). The transaction primitives are private members of AccessibilityActions and
 * reachable ONLY through `transacted { … }`, so wrapper/alias/callable-reference/indirect
 * attempts from arbitrary production code no longer compile. This test proves that
 * mechanically: none of the device-writing primitives may exist on the public API
 * surface, and the canonical violation fixtures must reference names that are absent
 * from it.
 */
class SideEffectBoundaryStructureTest {

    private val primitives = listOf(
        "sendToWhatsAppPhone", "sendToWhatsAppContact", "sendToWhatsAppGroup",
        "sendWhatsAppAttachment", "sendInCurrentChat", "postWhatsAppTextStatus",
        "postWhatsAppMediaStatus", "postTikTok", "saveEditForm", "updateSokoListing",
        "setFirstEditable",
    )

    @Test fun transactionPrimitivesAreAbsentFromThePublicApiSurface() {
        val publicMethods = AccessibilityActions::class.java.methods.map { it.name }.toSet() +
            AccessibilityActions::class.java.fields.map { it.name }.toSet()
        val leaked = primitives.filter { it in publicMethods }
        assertTrue(
            "primitives leaked into the public surface (would compile outside transactions): $leaked",
            leaked.isEmpty(),
        )
        // The scoped funnel exists and is the only public route.
        assertTrue(AccessibilityActions::class.java.declaredMethods.any {
            it.name == "transacted" || it.name.contains("transacted")
        })
        // And a nested scope type carries the restricted operations.
        assertTrue(AccessibilityActions::class.java.declaredClasses.any { it.simpleName.contains("TransactionScope") })
    }

    private fun repoRoot(): File =
        generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "settings.gradle").isFile }

    @Test fun canonicalViolationFixturesReferenceNamesThatNoLongerResolvePublicly() {
        // Each fixture file demonstrates one bypass family; all of them depend on public
        // access to primitive names. Structural privacy makes every one uncompilable.
        val fixtures = File(repoRoot(), "mission/amara-complete-employee/fixtures/boundary-violations")
            .walkTopDown().filter { it.extension == "kt" }.toList()
        assertFalse("violation fixtures must be present", fixtures.isEmpty())
        val publicNames = AccessibilityActions::class.java.methods.map { it.name }.toSet()
        fixtures.forEach { fixture ->
            val attempted = primitives.filter { fixture.readText().contains(it) }
            assertTrue("fixture ${fixture.name} must attempt a known primitive", attempted.isNotEmpty())
            val resolvable = attempted.filter { it in publicNames }
            assertTrue(
                "fixture ${fixture.name} attempts $resolvable which still resolves publicly — boundary regression",
                resolvable.isEmpty(),
            )
        }
    }
}
