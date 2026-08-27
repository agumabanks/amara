package co.sanaa.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactNormalizationTest {

    @Test
    fun `all common Uganda dial forms collapse to one E164 identity`() {
        val forms = listOf("+256772123456", "256772123456", "0772123456", "772123456")
        val normalized = forms.map(Normalizer::normalizeUganda)
        normalized.forEach { assertEquals("+256772123456", it) }
        assertEquals(normalized.toSet().size, 1)
    }

    @Test
    fun `spacing dashes and parentheses are stripped`() {
        assertEquals("+256772123456", Normalizer.normalizeUganda("+256 (772) 123-456"))
        assertEquals("+256772123456", Normalizer.normalizeUganda("0772 123 456"))
    }

    @Test
    fun `blank input normalizes to null`() {
        assertNull(Normalizer.normalizeUganda(null))
        assertNull(Normalizer.normalizeUganda(""))
        assertNull(Normalizer.normalizeUganda("   "))
    }

    @Test
    fun `garbage input normalizes to null`() {
        assertNull(Normalizer.normalizeUganda("not a phone"))
        assertNull(Normalizer.normalizeUganda("12345"))
        assertNull(Normalizer.normalizeUganda("077212345"))
        assertNull(Normalizer.normalizeUganda("+441234567890"))
        assertNull(Normalizer.normalizeUganda("25677"))
    }

    @Test
    fun `different numbers never share an identity`() {
        assertNotEquals(
            Normalizer.normalizeUganda("0772123456"),
            Normalizer.normalizeUganda("0782123456"),
        )
    }
}
