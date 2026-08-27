package co.sanaa.agent.core

/**
 * Enforceable typed schemas (Phase A gap fix): capability input/output descriptions are
 * parsed into [TypedSchema] instances and validated at the transaction boundary — no
 * longer free-form documentation strings.
 */
enum class SchemaFieldType { STRING, NONBLANK_STRING, NUMBER, BOOLEAN, LIST, MAP, OBJECT }

data class SchemaField(val name: String, val type: SchemaFieldType, val required: Boolean)

data class TypedSchema(val fields: List<SchemaField>) {

    /** Returns every violation of this schema against [inputs]; empty list means valid. */
    fun validate(inputs: Map<String, Any?>): List<String> {
        val failures = mutableListOf<String>()
        val provided = inputs.keys.toSet()
        fields.forEach { field ->
            if (!inputs.containsKey(field.name)) {
                if (field.required) failures += "Missing required field '${field.name}'"
                return@forEach
            }
            val value = inputs[field.name]
            when (field.type) {
                SchemaFieldType.STRING -> if (value != null && value !is String) failures += "'${field.name}' must be a string"
                SchemaFieldType.NONBLANK_STRING ->
                    if (value !is String || value.isBlank()) failures += "'${field.name}' must be a non-blank string"
                SchemaFieldType.NUMBER -> if ((value as? Number) == null && (value as? String)?.toDoubleOrNull() == null)
                    failures += "'${field.name}' must be numeric"
                SchemaFieldType.BOOLEAN -> if (value !is Boolean) failures += "'${field.name}' must be boolean"
                SchemaFieldType.LIST -> if (value !is List<*>) failures += "'${field.name}' must be a list"
                SchemaFieldType.MAP, SchemaFieldType.OBJECT -> if (value !is Map<*, *>) failures += "'${field.name}' must be an object"
            }
        }
        provided.filterNot { name -> fields.any { it.name == name } }.forEach {
            failures += "Unexpected field '$it'"
        }
        return failures
    }

    companion object {
        /**
         * Parses catalog schema strings of the shape "{target:string, message:string}".
         * Unknown type tokens fail loudly so schema drift cannot slip through silently.
         */
        /**
         * Parses catalog schema strings of the shape "{target:nonblank!, message:string}".
         * Supports list tokens ("evidence:string[]"), nested object values
         * ("evidence:{deliveryState:string}"), and map. Splitting is brace-aware so
         * nested commas cannot corrupt top-level fields. Unknown tokens fail loudly.
         */
        fun parse(description: String): TypedSchema {
            val inner = description.trim().removePrefix("{").removeSuffix("}").trim()
            if (inner.isEmpty()) return TypedSchema(emptyList())
            val fields = splitTopLevel(inner).mapNotNull { token ->
                val trimmed = token.trim()
                require(trimmed.isNotEmpty()) { "Empty schema field in '$description'" }
                val colon = trimmed.indexOf(':')
                require(colon > 0) { "Malformed schema token '$token' in '$description'" }
                val name = trimmed.substring(0, colon).trim()
                val typeToken = trimmed.substring(colon + 1).trim()
                var base = typeToken
                var isList = false
                if (base.endsWith("[]")) { isList = true; base = base.dropLast(2) }
                val required = when {
                    base.endsWith("!") -> { base = base.dropLast(1); true }
                    base.endsWith("?") -> { base = base.dropLast(1); false }
                    else -> true
                }
                val fieldType = when {
                    base.startsWith("{") && base.endsWith("}") -> SchemaFieldType.OBJECT
                    isList -> SchemaFieldType.LIST
                    else -> when (base) {
                        "string", "str" -> SchemaFieldType.STRING
                        "nonblank" -> SchemaFieldType.NONBLANK_STRING
                        "number", "int", "long", "float", "double" -> SchemaFieldType.NUMBER
                        "bool", "boolean" -> SchemaFieldType.BOOLEAN
                        "map" -> SchemaFieldType.MAP
                        else -> throw IllegalArgumentException("Unknown schema type '$base' in '$description'")
                    }
                }
                SchemaField(name, fieldType, required)
            }
            return TypedSchema(fields)
        }

        /** Splits on commas that are not nested inside braces. */
        private fun splitTopLevel(inner: String): List<String> {
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 0
            inner.forEach { ch ->
                when {
                    ch == '{' -> { depth++; current.append(ch) }
                    ch == '}' -> { depth--; current.append(ch) }
                    ch == ',' && depth == 0 -> { parts += current.toString(); current.clear() }
                    else -> current.append(ch)
                }
            }
            parts += current.toString()
            return parts
        }
    }
}
