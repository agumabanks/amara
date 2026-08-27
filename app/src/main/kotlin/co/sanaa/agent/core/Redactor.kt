package co.sanaa.agent.core

import java.security.MessageDigest

/**
 * Central redaction applied before any text enters logs, backend exports, receipts,
 * or model prompts. Secrets are detected by pattern and replaced with stable,
 * non-reversible markers so correlation is possible without disclosure.
 */
object Redactor {

    private val patterns = listOf(
        "terminal_pin" to Regex("(?i)\\b(?:pin|staff pin|terminal pin)\\s*(?:is|:|=)?\\s*\\d{4,8}\\b"),
        "otp_code" to Regex("(?i)\\b(?:otp|one[- ]time (?:code|password)|verification code)\\s*(?:is|:|=)?\\s*\\d{4,8}\\b"),
        "api_key" to Regex("(?i)\\b(?:api[_ ]?key|token|bearer)\\s*(?:is|:|=)?\\s*[A-Za-z0-9_\\-]{16,}\\b"),
        "bearer_header" to Regex("(?i)bearer\\s+[A-Za-z0-9._\\-]{10,}"),
        "long_digit_secret" to Regex("(?<!\\d)\\d{9,}(?!\\d)"),
        "password_assignment" to Regex("(?i)(?:password|passwd)\\s*[:=]\\s*\\S+"),
    )

    /** Redacts known secret shapes. */
    fun redact(text: String): String {
        var output = text
        patterns.forEach { (name, regex) ->
            output = regex.replace(output) { match ->
                "${match.value.takeWhile { it.isLetter() || it.isWhitespace() }}${REDACTION_PREFIX}${fingerprint(match.value)}$REDACTION_SUFFIX"
            }
        }
        return output
    }

    /**
     * Redacts credential-shaped material (PINs, OTPs, keys, passwords) while leaving
     * long digit runs intact — for identity columns that legitimately carry phone
     * numbers and other structured numeric identifiers.
     */
    fun redactCredentialShapes(text: String): String {
        var output = text
        patterns.forEach { (name, regex) ->
            if (name == "long_digit_secret") return@forEach
            output = regex.replace(output) { match ->
                "${match.value.takeWhile { it.isLetter() || it.isWhitespace() }}${REDACTION_PREFIX}${fingerprint(match.value)}$REDACTION_SUFFIX"
            }
        }
        return output
    }

    /**
     * Redacts and additionally truncates for storage/export.
     * Long unstructured digit runs are treated as sensitive by default because phone
     * money flows use 9–12 digit identifiers.
     */
    fun redactForExport(text: String, maxChars: Int = 2_000): String = redact(text).take(maxChars)

    /** True when the text still contains a recognizable secret shape after redaction. */
    fun containsSecretShape(text: String): Boolean = patterns.any { it.second.containsMatchIn(text) }

    /**
     * Typed, redacted diagnostic for logs/backend/state sinks: exception CLASS NAME
     * plus an optionally redacted short message. Never a stack trace; the message is
     * dropped entirely when it still matches a secret shape. Safe for every sink.
     */
    fun safeDiagnostic(throwable: Throwable?): String = when (throwable) {
        null -> ""
        else -> {
            val message = throwable.message?.trim().orEmpty()
            val safeMessage = redact(message)
            buildString {
                append(throwable.javaClass.simpleName)
                if (safeMessage.isNotEmpty() && !containsSecretShape(safeMessage)) {
                    append(": ").append(safeMessage.take(160))
                }
            }
        }
    }

    fun fingerprint(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(8)

    const val REDACTION_PREFIX = "[REDACTED:"
    const val REDACTION_SUFFIX = "]"

    /** Wraps a redaction marker around a whole value with fingerprint. */
    fun mark(value: String): String = "$REDACTION_PREFIX${fingerprint(value)}$REDACTION_SUFFIX"
}
