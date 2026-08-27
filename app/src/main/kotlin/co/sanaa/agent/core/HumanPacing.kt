package co.sanaa.agent.core

import kotlin.random.Random

enum class InteractionKind { TAP_SETTLE, TYPE_SETTLE, SCROLL_SETTLE, APP_LOAD, NETWORK_CONTENT }

object HumanPacing {
    private val ranges = mapOf(
        InteractionKind.TAP_SETTLE to 220L..480L,
        InteractionKind.TYPE_SETTLE to 280L..620L,
        InteractionKind.SCROLL_SETTLE to 500L..900L,
        InteractionKind.APP_LOAD to 800L..1_500L,
        InteractionKind.NETWORK_CONTENT to 1_200L..2_200L,
    )

    fun delayMillis(kind: InteractionKind, randomLong: (Long, Long) -> Long = { from, until -> Random.nextLong(from, until) }): Long {
        val range = ranges.getValue(kind)
        return randomLong(range.first, range.last + 1).coerceIn(range)
    }
}
