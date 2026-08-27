package co.sanaa.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Failure-mode coverage for the enforceable typed schema layer. */
class TypedSchemaValidationTest {

    @Test fun parseAcceptsCanonicalCatalogShapes() {
        val schema = TypedSchema.parse("{target:nonblank!, message:string!}")
        assertEquals(2, schema.fields.size)
        assertEquals(SchemaFieldType.NONBLANK_STRING, schema.fields[0].type)
        assertTrue(schema.fields[0].required)
    }

    @Test fun parseRejectsUnknownTypesAndMalformedTokens() {
        assertTrue(runCatching { TypedSchema.parse("{x:wibble}") }.isFailure)
        assertTrue(runCatching { TypedSchema.parse("not-a-schema") }.isFailure)
        assertTrue(runCatching { TypedSchema.parse("{a:}") }.isFailure)
    }

    @Test fun validatePassesCanonicalRunnerInputs() {
        val schema = TypedSchema.parse("{target:nonblank!, content:string!}")
        assertTrue(schema.validate(mapOf("target" to "Sanaa Office", "content" to "hi")).isEmpty())
    }

    @Test fun validateRejectsMissingBlankAndUnexpectedFields() {
        val schema = TypedSchema.parse("{target:nonblank!, caption:string?}")
        val failures = schema.validate(mapOf("target" to "", "extra" to 1))
        assertTrue(failures.any { it.contains("'target'") })
        assertTrue(failures.any { it.contains("Unexpected field 'extra'") })
        // Optional absent field is fine.
        assertTrue(schema.validate(mapOf("target" to "x")).isEmpty())
    }

    @Test fun numericAndBooleanTypesEnforced() {
        val schema = TypedSchema.parse("{amount:number, flag:bool}")
        assertTrue(schema.validate(mapOf("amount" to "12.5", "flag" to true)).isEmpty())
        assertTrue(schema.validate(mapOf("amount" to "abc", "flag" to "yes")).size == 2)
    }

    @Test fun parseSupportsListsNestedObjectsAndMaps() {
        val schema = TypedSchema.parse("{name:nonblank!, evidence:string[], meta:map, receipt:{state:string, at:long}}")
        assertEquals(4, schema.fields.size)
        assertEquals(SchemaFieldType.LIST, schema.fields[1].type)
        assertEquals(SchemaFieldType.MAP, schema.fields[2].type)
        assertEquals(SchemaFieldType.OBJECT, schema.fields[3].type)
        // Brace-aware splitting kept the nested object intact as one field.
        assertTrue(schema.validate(mapOf("name" to "x", "evidence" to listOf("a"), "meta" to emptyMap<String, String>(), "receipt" to mapOf("state" to "ok"))).isEmpty())
        val failures = schema.validate(mapOf("name" to "x", "evidence" to "not-a-list", "meta" to 3, "receipt" to "nope"))
        assertTrue(failures.any { it.contains("'evidence' must be a list") })
        assertTrue(failures.any { it.contains("'meta' must be an object") })
        assertTrue(failures.any { it.contains("'receipt' must be an object") })
    }

    @Test fun everyCatalogInputSchemaParses() {
        CapabilityCatalog.specs.values.forEach { spec ->
            val parsed = runCatching { TypedSchema.parse(spec.inputSchema) }
            assertTrue("${spec.id}.inputSchema must parse: ${spec.inputSchema}", parsed.isSuccess)
        }
    }

    @Test fun everyCatalogOutputSchemaParses() {
        CapabilityCatalog.specs.values.forEach { spec ->
            val parsed = runCatching { spec.parsedOutputSchema }
            assertTrue("${spec.id}.outputSchema must parse: ${spec.outputSchema}", parsed.isSuccess)
        }
    }
}
