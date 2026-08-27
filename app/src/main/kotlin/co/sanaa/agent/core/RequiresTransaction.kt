package co.sanaa.agent.core

/**
 * Marks an externally visible phone operation. The mechanical boundary checker derives
 * its primitive list from these declarations, so a new external primitive is enforced
 * the moment it is annotated — call sites outside the universal transaction's act
 * boundary fail the check with file:line.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class RequiresTransaction(val reason: String)
