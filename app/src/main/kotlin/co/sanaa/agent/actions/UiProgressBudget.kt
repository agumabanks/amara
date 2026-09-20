package co.sanaa.agent.actions

/** Extend waiting only for new observable progress, never for a repeating spinner. */
internal class UiProgressBudget(
    private val idleMillis: Long,
    maxMillis: Long,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val started = now()
    private val hardDeadline = started + maxMillis
    private var lastProgress = started
    private val seen = mutableSetOf<String>()

    init { require(idleMillis > 0 && maxMillis >= idleMillis) }

    fun progress(token: String?) {
        if (!token.isNullOrBlank() && seen.size < 256 && seen.add(token)) lastProgress = now()
    }

    fun expired(): Boolean = now() >= hardDeadline || now() - lastProgress >= idleMillis
}
