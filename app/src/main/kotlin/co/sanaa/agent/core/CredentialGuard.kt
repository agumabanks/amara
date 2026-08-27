package co.sanaa.agent.core

/**
 * Ingress guard for owner commands that carry credential material (Soko Terminal
 * PINs, OTPs, passwords, API keys/tokens). It runs BEFORE any persistence or model
 * call: durable records receive only the redacted placeholder form, while the raw
 * credential stays in process memory just long enough to reach the scoped vault.
 */
object CredentialGuard {

    data class Inspection(
        /** Command with every detected secret replaced by a stable redaction marker. */
        val redactedCommand: String,
        /** True when at least one credential shape was detected and redacted. */
        val credentialDetected: Boolean,
        /** Detected credential kinds (diagnostics only — never the secret itself). */
        val kinds: Set<String>,
    ) {
        /** Safe to persist, log, echo, or send to a model. */
        val safeForPersistence: String get() = redactedCommand
    }

    /** PIN stated after the credential word: "use 1234 as the Soko PIN". */
    private val trailingPin = Regex(
        "(?i)\\b(\\d{4,8})\\b(\\s+(?:is|as|for)\\s+(?:the\\s+|my\\s+|new\\s+)*" +
            "(?:soko\\s+|terminal\\s+|staff\\s+)*pin\\b)",
    )

    /** PIN stated before the credential word: "pin is 1234" / "PIN: 1234". */
    private val leadingPin = Regex(
        "(?i)\\b((?:soko\\s+|terminal\\s+|staff\\s+)*pin)\\s*(?:is|:|=)?\\s*\\d{4,8}\\b",
    )

    fun inspect(text: String): Inspection {
        val kinds = mutableSetOf<String>()
        var output = text
        if (trailingPin.containsMatchIn(output)) {
            kinds += "terminal_pin"
            output = trailingPin.replace(output) { match ->
                val marker = "${Redactor.REDACTION_PREFIX}${Redactor.fingerprint(match.groupValues[1])}${Redactor.REDACTION_SUFFIX}"
                marker + match.groupValues[2]
            }
        }
        if (leadingPin.containsMatchIn(output)) {
            kinds += "terminal_pin"
            output = leadingPin.replace(output) { match ->
                val marker = "${Redactor.REDACTION_PREFIX}${Redactor.fingerprint(match.value)}${Redactor.REDACTION_SUFFIX}"
                match.groupValues[1] + " " + marker
            }
        }
        val canonical = Redactor.redact(output)
        if (canonical != output) {
            if (Regex("(?i)\\b(?:otp|one[- ]time (?:code|password)|verification code)\\b").containsMatchIn(text)) kinds += "otp"
            if (Regex("(?i)\\b(?:api[_ ]?key|token|bearer)\\b").containsMatchIn(text)) kinds += "api_key"
            if (Regex("(?i)\\b(?:password|passwd)\\b").containsMatchIn(text)) kinds += "password"
            if (Regex("(?i)\\b(?:pin|staff pin|terminal pin)\\b").containsMatchIn(text)) kinds += "terminal_pin"
            if (Regex("(?<!\\d)\\d{9,}(?!\\d)").containsMatchIn(text)) kinds += "long_digit_secret"
        }
        return Inspection(
            redactedCommand = canonical,
            credentialDetected = kinds.isNotEmpty(),
            kinds = kinds,
        )
    }
}
