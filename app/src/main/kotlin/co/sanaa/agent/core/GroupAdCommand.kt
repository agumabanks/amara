package co.sanaa.agent.core

/** Narrow owner syntax for a governed group job, independent of model availability. */
internal object GroupAdCommand {
    private val pattern = Regex("^(?:please\\s+)?(?:post|share|send)\\s+(?:one|an?|a single)\\s+(?:soko\\s+)?(?:studio\\s+)?ad\\s+to\\s+(?:the\\s+)?(?:whatsapp\\s+)?(?:group\\s+)?(.+?)$", RegexOption.IGNORE_CASE)
    fun target(command: String): String? = pattern.matchEntire(command.trim())?.groupValues?.get(1)
        ?.trim()?.takeIf { it.isNotBlank() && !it.contains('\n') }
}
