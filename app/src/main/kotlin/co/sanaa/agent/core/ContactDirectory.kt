package co.sanaa.agent.core

/**
 * Canonical contact identity. One durable row per real contact or group; every
 * monitor/reply/send/follow-up/broadcast path resolves recipients through this.
 */
data class DirectoryEntry(
    val id: String,
    val displayName: String,
    val normalizedPhone: String?,
    val aliases: Set<String>,
    val isGroup: Boolean,
    val source: EntrySource,
    val lastVerifiedAt: Long?,
    val ambiguity: Ambiguity,
    val classification: Classification,
    val commercialConsent: CommercialConsent,
    val permissions: Map<Operation, Permission>,
    val whatsappSurfaceEvidence: String?,
    val revocationEvidence: String?,
) {
    val canMonitor: Boolean get() = revocationEvidence == null && permissions[Operation.MONITOR] == Permission.ALLOW
    val canReply: Boolean get() = revocationEvidence == null && permissions[Operation.REPLY] == Permission.ALLOW
    val canSend: Boolean get() = revocationEvidence == null && permissions[Operation.SEND] == Permission.ALLOW
    val canWrite: Boolean get() = revocationEvidence == null && permissions[Operation.WRITE] == Permission.ALLOW

    /** Legacy cumulative level (MONITOR < REPLY < SEND < FULL) for callers still keyed on it. */
    fun legacyLevel(): ContactPermission = when {
        canWrite -> ContactPermission.FULL
        canSend -> ContactPermission.SEND
        canReply -> ContactPermission.REPLY
        canMonitor -> ContactPermission.MONITOR
        else -> ContactPermission.NONE
    }

    /** Commercial outreach eligibility: owner/test contacts and suppressed consent never qualify. */
    fun isRevenueEligible(): Boolean = revocationEvidence == null &&
        commercialConsent != CommercialConsent.SUPPRESSED &&
        classification !in setOf(Classification.OWNER, Classification.TEST)
}

enum class Operation { MONITOR, REPLY, SEND, WRITE }
enum class Permission { NONE, ALLOW, DENY }
enum class EntrySource { ANDROID_CONTACTS, WHATSAPP, OWNER_CREATED, OBSERVED }
enum class Ambiguity { UNIQUE, AMBIGUOUS_NAME, AMBIGUOUS_NUMBER }
enum class Classification { OWNER, TEST, EMPLOYEE, CUSTOMER, UNKNOWN }
enum class CommercialConsent { UNKNOWN, GRANTED, SUPPRESSED }

/** Uganda/E.164 phone normalization. Two numbers are the same identity iff equal E.164. */
object Normalizer {
    private val keep = Regex("[^0-9+]")

    fun normalizeUganda(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.replace(keep, "")
        val digits = cleaned.filter(Char::isDigit)
        if (digits.isEmpty()) return null
        return when {
            cleaned.startsWith("+") -> "+$digits".takeIf { digits.startsWith("256") && digits.length == 12 }
            digits.startsWith("256") && digits.length == 12 -> "+$digits"
            digits.startsWith("0") && digits.length == 10 -> "+256${digits.substring(1)}"
            digits.startsWith("7") && digits.length == 9 -> "+256$digits"
            else -> null
        }
    }

    /** Last-three mask safe for logs and summaries; never a full number. */
    fun masked(phone: String?): String = phone?.takeLast(3)?.let { "•••$it" } ?: "unknown"
}

data class ContactQuery(
    val id: String? = null,
    val name: String? = null,
    val phone: String? = null,
    /** When set, candidates are restricted to that surface kind; null searches both. */
    val isGroup: Boolean? = null,
)

sealed class Resolution {
    data class Unique(val entry: DirectoryEntry) : Resolution()
    data class Ambiguous(val entries: List<DirectoryEntry>, val reason: String) : Resolution()
    object NotFound : Resolution()
}

/** Typed pre-send verdict from [ContactDirectory.authorizeOutgoingSend]. */
sealed class DispatchDecision {
    data class Allowed(val entry: DirectoryEntry) : DispatchDecision()
    data class Refused(val reasonCode: String, val detail: String) : DispatchDecision()

    companion object {
        const val UNKNOWN_RECIPIENT = "UNKNOWN_RECIPIENT"
        const val AMBIGUOUS_IDENTITY = "AMBIGUOUS_IDENTITY"
        const val NO_SEND_GRANT = "NO_SEND_GRANT"
        const val AUTHORIZATION_REVOKED = "AUTHORIZATION_REVOKED"
        const val NUMBER_NOT_BOUND_TO_IDENTITY = "NUMBER_NOT_BOUND_TO_IDENTITY"
        const val VISIBLE_THREAD_MISMATCH = "VISIBLE_THREAD_MISMATCH"
    }
}

/**
 * Fail-closed resolution facade over [ContactDirectoryStore]. Matching order: exact
 * durable id, then normalized-phone equality, then EXACT case-insensitive name/alias.
 * Short queries (<4 chars) never match by name; consequential sends never fuzzy-match.
 */
class ContactDirectory(private val store: ContactDirectoryStore) {

    fun resolve(query: ContactQuery): Resolution {
        if (!query.id.isNullOrBlank()) {
            store.byId(query.id)?.let { return checkFlagged(it, "durable id") }
            return Resolution.NotFound
        }
        val phone = Normalizer.normalizeUganda(query.phone)
        if (phone != null) {
            store.byPhone(phone)?.let { hit ->
                if (query.isGroup == null || hit.isGroup == query.isGroup) {
                    return when (hit.ambiguity) {
                        Ambiguity.AMBIGUOUS_NUMBER -> contestedByPhone(hit, phone)
                        else -> Resolution.Unique(hit)
                    }
                }
            }
        }
        val name = query.name?.trim().orEmpty()
        if (name.isNotEmpty() && name.length >= MIN_NAME_MATCH) {
            val matches = store.exactNameMatches(name, query.isGroup)
            return when {
                matches.isEmpty() -> Resolution.NotFound
                matches.size == 1 -> Resolution.Unique(matches.first())
                else -> Resolution.Ambiguous(matches, "multiple saved contacts share the name \"$name\"")
            }
        }
        return Resolution.NotFound
    }

    private fun checkFlagged(entry: DirectoryEntry, matchedOn: String): Resolution = when (entry.ambiguity) {
        Ambiguity.UNIQUE -> Resolution.Unique(entry)
        else -> Resolution.Ambiguous(store.listAmbiguous(), "$matchedOn is flagged ${entry.ambiguity.name}; refusing to guess")
    }

    private fun contestedByPhone(hit: DirectoryEntry, phone: String): Resolution {
        val others = store.listAmbiguous().filter { it.id != hit.id && it.aliases.any { a -> Normalizer.normalizeUganda(a) == phone } }
        return Resolution.Ambiguous(listOf(hit) + others, "the number ${Normalizer.masked(phone)} is claimed by more than one saved contact")
    }

    /**
     * Legacy-permission bridge: returns the derived [ContactPermission] for a raw
     * label/number pair, or null when the directory holds no such identity so callers
     * may fall back to legacy prefs. Ambiguous resolves fail closed to NONE.
     */
    fun permissionLevelFor(name: String?, number: String?, isGroup: Boolean): ContactPermission? =
        when (val resolution = resolve(ContactQuery(name = name?.trim()?.takeIf(String::isNotBlank), phone = number, isGroup = isGroup))) {
            is Resolution.Unique -> resolution.entry.legacyLevel()
            is Resolution.Ambiguous -> ContactPermission.NONE
            Resolution.NotFound -> null
        }

    fun setPermission(id: String, operation: Operation, allow: Boolean) = store.setPermission(id, operation, allow)

    fun authorizeLevel(id: String, level: ContactPermission): Boolean = store.authorizeLevel(id, level)

    fun upsert(entry: DirectoryEntry): DirectoryEntry = store.upsert(entry)

    fun byId(id: String): DirectoryEntry? = store.byId(id)

    fun revokeAll(id: String, evidence: String) = store.revokeAll(id, evidence)

    fun listAmbiguous(): List<DirectoryEntry> = store.listAmbiguous()

    /** Every durable entry — the single listing authority (owner UI, engines). */
    fun listAll(): List<DirectoryEntry> = store.listAll()

    /** Single-authority broadcast groups: non-revoked group entries holding SEND. */
    fun broadcastGroups(): List<String> = store.listAll()
        .filter { it.isGroup && it.canSend }
        .map { it.displayName.trim() }
        .filter(String::isNotEmpty)
        .distinct()

    /**
     * Fail-closed authority check for any raw surface label/number pair.
     * When BOTH a name and a number are presented, the number must belong to the
     * resolved identity — a unique display name alone can never bless a thread
     * whose number the directory does not know (wrong-recipient defense).
     */
    fun can(operation: Operation, name: String?, number: String?, isGroup: Boolean): Boolean =
        when (val resolution = resolve(ContactQuery(name = name?.trim()?.takeIf(String::isNotBlank), phone = number, isGroup = isGroup))) {
            is Resolution.Unique -> numberBelongsToEntry(resolution.entry, number) && when (operation) {
                Operation.MONITOR -> resolution.entry.canMonitor
                Operation.REPLY -> resolution.entry.canReply
                Operation.SEND -> resolution.entry.canSend
                Operation.WRITE -> resolution.entry.canWrite
            }
            is Resolution.Ambiguous, Resolution.NotFound -> false
        }

    /**
     * Full pre-send dispatch gate (fail closed): resolves the identity, re-checks the
     * SEND grant AT DISPATCH TIME (so a revocation after planning refuses here), binds
     * the presented number to the resolved identity, and finally requires that the
     * VISIBLE WhatsApp thread label is exactly this contact. Any mismatch yields a
     * typed [DispatchDecision.Refused] and ZERO dispatch; callers must treat Refused
     * as "do not touch the phone".
     */
    fun authorizeOutgoingSend(
        presentedName: String?,
        presentedNumber: String?,
        visibleThreadLabel: String?,
        isGroup: Boolean = false,
    ): DispatchDecision {
        val cleanName = presentedName?.trim()?.takeIf(String::isNotBlank)
        // A PRESENTED but unparsable number can never be bound to an identity:
        // fail closed instead of silently dropping the surface evidence.
        if (!presentedNumber.isNullOrBlank() && Normalizer.normalizeUganda(presentedNumber) == null) {
            return DispatchDecision.Refused(
                DispatchDecision.NUMBER_NOT_BOUND_TO_IDENTITY,
                "the presented value is not a parsable Uganda number",
            )
        }
        val resolution = resolve(ContactQuery(name = cleanName, phone = presentedNumber, isGroup = isGroup))
        val entry = when (resolution) {
            is Resolution.Unique -> resolution.entry
            is Resolution.Ambiguous -> return DispatchDecision.Refused(
                DispatchDecision.AMBIGUOUS_IDENTITY,
                "the presented identity matched more than one saved contact; refusing to guess",
            )
            Resolution.NotFound -> return DispatchDecision.Refused(
                DispatchDecision.UNKNOWN_RECIPIENT,
                "no unique saved contact matches the presented name/number",
            )
        }
        // A presented name that contradicts the phone-resolved identity is an
        // unknown recipient: refuse before ANY grant or surface check so a
        // wrong-number presentation can never ride a valid contact's grant.
        if (cleanName != null && !entry.presentsAs(cleanName)) {
            val byName = resolve(ContactQuery(name = cleanName, phone = null, isGroup = isGroup))
            if (byName !is Resolution.Unique || byName.entry.id != entry.id) {
                return DispatchDecision.Refused(
                    DispatchDecision.UNKNOWN_RECIPIENT,
                    "the presented name does not belong to the saved contact reached by the presented number",
                )
            }
        }
        if (entry.revocationEvidence != null) {
            return DispatchDecision.Refused(
                DispatchDecision.AUTHORIZATION_REVOKED,
                "authorization was revoked (${entry.revocationEvidence}); no stale grant survives",
            )
        }
        if (!entry.canSend) {
            return DispatchDecision.Refused(
                DispatchDecision.NO_SEND_GRANT,
                "the resolved contact does not hold an explicit SEND grant",
            )
        }
        if (!numberBelongsToEntry(entry, presentedNumber)) {
            return DispatchDecision.Refused(
                DispatchDecision.NUMBER_NOT_BOUND_TO_IDENTITY,
                "the presented number is not bound to the resolved contact",
            )
        }
        if (!visibleThreadMatches(entry, visibleThreadLabel)) {
            return DispatchDecision.Refused(
                DispatchDecision.VISIBLE_THREAD_MISMATCH,
                "the visible thread label does not match the authorized contact",
            )
        }
        return DispatchDecision.Allowed(entry)
    }

    /** The visible thread must be EXACTLY this contact's display name, an alias, or its own normalized number. */
    private fun visibleThreadMatches(entry: DirectoryEntry, visibleThreadLabel: String?): Boolean {
        val label = visibleThreadLabel?.trim().orEmpty()
        if (label.isEmpty()) return false
        if (label.equals(entry.displayName.trim(), ignoreCase = true)) return true
        if (entry.aliases.any { it.trim().equals(label, ignoreCase = true) && it.isNotBlank() }) return true
        val normalizedLabel = Normalizer.normalizeUganda(label)
        val entryNumber = Normalizer.normalizeUganda(entry.normalizedPhone)
        return normalizedLabel != null && normalizedLabel == entryNumber
    }

    /** True when [name] is exactly (case-insensitive) this entry's display name or one of its non-blank aliases. */
    private fun DirectoryEntry.presentsAs(name: String): Boolean {
        val candidate = name.trim()
        if (displayName.trim().equals(candidate, ignoreCase = true)) return true
        return aliases.any { it.isNotBlank() && it.trim().equals(candidate, ignoreCase = true) }
    }


    /** True when the presented number is unknown-for-entry, a group surface, or one of the entry's own numbers. */
    private fun numberBelongsToEntry(entry: DirectoryEntry, rawNumber: String?): Boolean {
        val presented = Normalizer.normalizeUganda(rawNumber) ?: return true
        if (entry.isGroup) return true
        val known = listOfNotNull(entry.normalizedPhone) +
            entry.aliases.mapNotNull { Normalizer.normalizeUganda(it) }
        return known.isEmpty() || presented in known
    }

    /** Migration/scrub for credential material in directory text columns. */
    fun scrubSecrets(): Int = store.scrubSecrets()

    /** One-time idempotent import of legacy permission JSON into the durable directory. */
    fun migrateLegacyIfConfigured(config: SecureConfig) = store.lazyMigrateFromLegacy(config)

    companion object {
        private const val MIN_NAME_MATCH = 4
    }
}

/**
 * Process-wide directory hook. AgentRuntime sets [instance] at startup;
 * ContactPermissions consults it and falls back to legacy prefs while unset.
 */
object ContactDirectoryProvider {
    @Volatile var instance: ContactDirectory? = null
}
