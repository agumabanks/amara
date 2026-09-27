package co.sanaa.agent.actions

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WhatsAppPickerSearchTest {
    @Test fun waitsForFreshReadbackInsteadOfTrustingWriteAcceptance() = runBlocking {
        var visible: String? = null
        var pending: String? = null
        var opened = 0
        val result = WhatsAppPickerSearch.enter("Exact Group", { visible },
            { pending = it; true }, { opened++; visible = ""; true },
            { if (pending != null) visible = pending })
        assertTrue(result)
        assertEquals(1, opened)
        assertEquals("Exact Group", visible)
    }
    @Test fun acceptedWriteWithoutMatchingReadbackNeverSelectsRecipient() = runBlocking {
        var polls = 0
        assertFalse(WhatsAppPickerSearch.enter("Exact Group", { "Wrong Group" },
            { true }, { false }, { polls++ }))
        assertEquals(24, polls)
    }
    @Test fun absentSearchFieldNeverWritesToAnotherEditor() = runBlocking {
        var writes = 0
        assertFalse(WhatsAppPickerSearch.enter("Exact Group", { null },
            { writes++; true }, { false }, {}))
        assertEquals(0, writes)
    }
}
