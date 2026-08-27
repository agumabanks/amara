package co.sanaa.agent.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.nio.ByteBuffer
import java.nio.CharBuffer
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Typed outcome for credential operations — never carries the secret itself. */
sealed class CredentialResult {
    object Stored : CredentialResult()
    object Rotated : CredentialResult()
    object Invalidated : CredentialResult()

    /** Persistence/encryption failed; [reason] is a static redacted diagnostic. */
    data class Failed(val reason: String) : CredentialResult()
    data class ValidationFailed(val reason: String, val consecutiveFailures: Int) : CredentialResult()
    data class NotFound(val scope: String) : CredentialResult()
}

/**
 * Typed retrieval outcome. Distinguishes NOT CONFIGURED from PARTIAL/CORRUPTED
 * records, from LOCKED denials, and from scope denials, so a missing credential
 * can never masquerade as a blank-scope one (the G2/G3 defect).
 */
sealed class CredentialRetrieval {
    /** Decrypted secret; the caller must consume in place and clear when possible. */
    class Secret(val value: CharArray) : CredentialRetrieval()

    /** No record of any kind exists for this id. */
    object NotConfigured : CredentialRetrieval()

    /**
     * An intact credential exists but has reached the lockout threshold: release
     * is denied BEFORE any decryption attempt so a locked secret can never be
     * submitted again (which would lock the owner's external account).
     */
    object Locked : CredentialRetrieval()

    /**
     * A partial or internally inconsistent record exists (metadata without
     * ciphertext, ciphertext without metadata, missing IV, malformed metadata,
     * blank scope, interrupted write, undecryptable payload). Never auto-deleted:
     * the guarded owner flow heals it by storing over it. [reason] is a static,
     * redacted description.
     */
    data class Incomplete(val reason: String) : CredentialRetrieval()

    /** An intact credential exists but is bound to a different package. */
    data class ScopeDenied(val scopedPackage: String) : CredentialRetrieval()
}

/** Owner-facing health of one vault slot, safe for UI channels. */
data class CredentialStatus(
    val configured: Boolean,
    val locked: Boolean,
    val consecutiveValidationFailures: Int,
)

data class CredentialMeta(
    val id: String,
    val targetPackage: String,
    val purpose: String,
    val configuredAtMillis: Long,
    val lastRotatedAtMillis: Long?,
    val consecutiveValidationFailures: Int,
)

/**
 * Scoped vault for app-specific credentials (for example the Soko staff PIN).
 *
 * Security contract:
 *  - Secret material is encrypted with an Android Keystore AES-GCM key that never
 *    leaves secure hardware and is bound to this application.
 *  - Plaintext is exposed ONLY through [retrieve] to the exact package-bound login
 *    surface automation; it must never be logged, persisted elsewhere, sent to any
 *    model, or returned through the Flutter bridge.
 *  - Every durable record stores only metadata and ciphertext, written as ONE
 *    coherent editor transaction behind a durable write marker (journal pattern):
 *    a torn write surfaces as typed [CredentialRetrieval.Incomplete], never as a
 *    silently valid record.
 *  - The caller's buffer handed to [store] is cleared in `finally` even when
 *    encryption or persistence throws.
 *  - The Android device-unlock PIN/password must NEVER be stored here.
 *
 * State semantics (all typed, none synthesized):
 *  - absent meta + absent ciphertext -> [CredentialRetrieval.NotConfigured]
 *  - ANY partial combination (meta-only, cipher-only, iv-only, malformed meta,
 *    blank bound package, pending write marker) -> [CredentialRetrieval.Incomplete]
 *    with a static redacted reason; the record is left in place for the guarded
 *    owner flow to overwrite, never silently repaired.
 *  - intact record + lockout threshold reached -> [CredentialRetrieval.Locked],
 *    denied before decryption.
 *  - intact record + foreign package -> [CredentialRetrieval.ScopeDenied]
 */
class CredentialVault(
    private val storePath: Context,
    /** Encryption seam; production default is the AndroidKeyStore codec. */
    private val codec: SecretCodec = AndroidKeystoreCodec(),
) {

    private val prefs by lazy { storePath.getSharedPreferences("credential_vault", Context.MODE_PRIVATE) }

    @Synchronized
    fun store(id: String, targetPackage: String, purpose: String, secret: CharArray): CredentialResult {
        validateScope(id, targetPackage)
        require(secret.isNotEmpty()) { "A blank credential cannot be stored" }
        try {
            // Encrypt BEFORE touching durable state: an encryption failure must
            // leave the previous record exactly as it was.
            val predecessor = intactRecord(id)
            val blob = codec.encrypt(alias(id), secret)
            // Journal phase: the marker becomes visible first, so any crash after
            // this point classifies the slot as WRITE_PENDING instead of silently
            // serving the predecessor (or a half-written successor).
            if (!prefs.edit().putBoolean(writePendingKey(id), true).commit()) {
                return CredentialResult.Failed(STORE_FAILED)
            }
            // Coherent commit: ciphertext + IV + metadata + marker removal in ONE
            // editor transaction; readers see all of it or none of it.
            val committed = prefs.edit()
                .putString(cipherKey(id), blob.first)
                .putString(ivKey(id), blob.second)
                .putString(metaKey(id), buildMetaJson(targetPackage, purpose, predecessor))
                .remove(writePendingKey(id))
                .commit()
            if (!committed) return CredentialResult.Failed(STORE_FAILED)
            return if (predecessor != null) CredentialResult.Rotated else CredentialResult.Stored
        } catch (_: Exception) {
            return CredentialResult.Failed(STORE_FAILED)
        } finally {
            secret.fill('\u0000')
        }
    }

    /**
     * Decrypts strictly for the exact package-bound surface. Returns typed
     * outcomes instead of throwing: a not-configured, corrupted, or LOCKED slot
     * can never again surface as a blank-scope denial or as a released secret.
     */
    fun retrieve(id: String, requestingPackage: String): CredentialRetrieval =
        openRecord(id, requestingPackage, forRelease = true)

    private fun openRecord(id: String, requestingPackage: String, forRelease: Boolean): CredentialRetrieval {
        require(id.isNotBlank()) { "A credential id is required" }
        require(requestingPackage.isNotBlank()) { "The requesting package must be named explicitly" }
        if (prefs.getBoolean(writePendingKey(id), false)) {
            return CredentialRetrieval.Incomplete(INCOMPLETE_WRITE_PENDING)
        }
        val rawMeta = prefs.getString(metaKey(id), null)
        val blob = prefs.getString(cipherKey(id), null)
        val iv = prefs.getString(ivKey(id), null)
        if (rawMeta == null && blob == null && iv == null) return CredentialRetrieval.NotConfigured
        if (rawMeta == null) {
            return CredentialRetrieval.Incomplete(INCOMPLETE_META_MISSING)
        }
        val meta = parseMeta(id, rawMeta)
            ?: return CredentialRetrieval.Incomplete(INCOMPLETE_META_MALFORMED)
        if (meta.targetPackage.isBlank()) {
            return CredentialRetrieval.Incomplete(INCOMPLETE_SCOPE_UNBOUND)
        }
        if (meta.targetPackage != requestingPackage) {
            return CredentialRetrieval.ScopeDenied(meta.targetPackage)
        }
        if (blob == null && iv == null) {
            return CredentialRetrieval.Incomplete(INCOMPLETE_CIPHERTEXT_MISSING)
        }
        if (iv == null) {
            return CredentialRetrieval.Incomplete(INCOMPLETE_IV_MISSING)
        }
        if (blob == null) {
            return CredentialRetrieval.Incomplete(INCOMPLETE_CIPHERTEXT_MISSING)
        }
        // Lockout gate sits AFTER structural checks but BEFORE any key access:
        // a locked credential is never decrypted, never released.
        if (forRelease && meta.consecutiveValidationFailures >= LOCKOUT_THRESHOLD) {
            return CredentialRetrieval.Locked
        }
        return try {
            CredentialRetrieval.Secret(codec.decrypt(alias(id), blob, iv))
        } catch (_: Exception) {
            CredentialRetrieval.Incomplete(INCOMPLETE_DECRYPTION_FAILED)
        }
    }

    /** Parsed metadata ONLY for an existing well-formed, non-pending record; null otherwise. */
    fun meta(id: String): CredentialMeta? {
        if (prefs.getBoolean(writePendingKey(id), false)) return null
        val raw = prefs.getString(metaKey(id), null) ?: return null
        return parseMeta(id, raw)
    }

    /**
     * Owner-channel health: configured means meta + ciphertext + IV all intact and
     * decryptable. Health inspection bypasses the release lock (it proves record
     * integrity, it does not hand out the secret) and clears every decrypted
     * buffer immediately.
     */
    fun status(id: String): CredentialStatus {
        val failures = meta(id)?.consecutiveValidationFailures ?: 0
        val boundPackage = meta(id)?.targetPackage.orEmpty()
        val configured = boundPackage.isNotBlank() &&
            run {
                when (val probe = openRecord(id, boundPackage, forRelease = false)) {
                    is CredentialRetrieval.Secret -> { probe.value.fill('\u0000'); true }
                    else -> false
                }
            }
        return CredentialStatus(configured, failures >= LOCKOUT_THRESHOLD, failures)
    }

    @Synchronized
    fun recordValidationSuccess(id: String) {
        if (meta(id) == null) return
        updateMeta(id) { put("validationFailures", 0); put("lastFailureReason", "") }
    }

    /**
     * Records an authentication rejection against an EXISTING intact record.
     * After [LOCKOUT_THRESHOLD] consecutive failures the credential soft-locks:
     * callers must stop submitting it to avoid locking the owner's account, and
     * ask the owner instead. Rejections against absent slots create no phantom
     * state. The counter survives rotation by design (anti rotate-and-retry
     * loops); only a genuinely accepted login or invalidate() resets it.
     */
    @Synchronized
    fun recordValidationFailure(id: String, reason: String): CredentialValidationState {
        if (meta(id) == null) return CredentialValidationState.FAILED_RETRYABLE
        val updated = updateMeta(id) { put("validationFailures", optInt("validationFailures", 0) + 1); put("lastFailureReason", Redactor.redact(reason)) }
            ?: return CredentialValidationState.FAILED_RETRYABLE
        val failures = updated.optInt("validationFailures", 0)
        return if (failures >= LOCKOUT_THRESHOLD) CredentialValidationState.LOCKED_OWNER_REQUIRED
        else CredentialValidationState.FAILED_RETRYABLE
    }

    fun isLocked(id: String): Boolean = status(id).locked

    @Synchronized
    fun invalidate(id: String): CredentialResult {
        val committed = prefs.edit()
            .remove(cipherKey(id)).remove(ivKey(id)).remove(metaKey(id))
            .remove(writePendingKey(id))
            .commit()
        if (!committed) return CredentialResult.Failed(INVALIDATE_FAILED)
        runCatching { codec.deleteAlias(alias(id)) }
        return CredentialResult.Invalidated
    }

    fun listIds(): List<String> = prefs.all.keys.filter { it.startsWith("meta:") }.map { it.removePrefix("meta:") }

    /** Replaces every literal occurrence of configured secrets in free text. */
    fun redactKnownSecrets(text: String): String {
        var result = text
        forEachKnownSecret { id, asText ->
            if (result.contains(asText)) result = result.replace(asText, "[REDACTED:CREDENTIAL:$id]")
        }
        return result
    }

    /**
     * Decrypts each coherent credential once for a bounded migration operation.
     * The mutable source buffer is wiped immediately after the visitor returns.
     */
    @Synchronized
    internal fun forEachKnownSecret(visitor: (id: String, value: String) -> Unit) {
        for (id in listIds()) {
            if (prefs.getBoolean(writePendingKey(id), false)) continue
            val boundPackage = meta(id)?.targetPackage.orEmpty()
            if (boundPackage.isBlank()) continue // incomplete record: never mined for secrets
            val secret = when (val probe = openRecord(id, boundPackage, forRelease = false)) {
                is CredentialRetrieval.Secret -> probe.value
                else -> continue
            }
            val asText = secret.concatToString()
            secret.fill('\u0000')
            if (asText.isNotEmpty()) visitor(id, asText)
        }
    }

    /**
     * The parsed predecessor ONLY when a coherent, non-pending, scope-bound
     * record exists; null for fresh ids and torn/partial states alike.
     */
    private fun intactRecord(id: String): CredentialMeta? {
        if (prefs.getBoolean(writePendingKey(id), false)) return null
        if (!prefs.contains(cipherKey(id)) || !prefs.contains(ivKey(id))) return null
        val meta = parseMeta(id, prefs.getString(metaKey(id), null) ?: "") ?: return null
        return meta.takeIf { it.targetPackage.isNotBlank() }
    }

    private fun parseMeta(id: String, raw: String): CredentialMeta? = runCatching {
        val json = org.json.JSONObject(raw)
        CredentialMeta(
            id = id,
            targetPackage = json.optString("targetPackage"),
            purpose = json.optString("purpose"),
            configuredAtMillis = json.optLong("configuredAt", 0L),
            lastRotatedAtMillis = json.optLong("lastRotatedAt", 0L).takeIf { it > 0 },
            consecutiveValidationFailures = json.optInt("validationFailures", 0),
        )
    }.getOrNull()

    private fun validateScope(id: String, targetPackage: String) {
        require(id.isNotBlank()) { "A credential id is required" }
        require(targetPackage.isNotBlank()) { "A credential must be bound to an exact app package" }
        require(!id.contains(':')) { "Credential ids must not contain scope separators" }
    }

    private fun buildMetaJson(targetPackage: String, purpose: String, predecessor: CredentialMeta?): String {
        val now = System.currentTimeMillis()
        val json = org.json.JSONObject()
        if (predecessor != null) {
            // Rotation preserves ONLY the live failure counter of an intact,
            // un-pending predecessor; healing a torn/partial record starts clean.
            json.put("validationFailures", predecessor.consecutiveValidationFailures)
            json.put("configuredAt", predecessor.configuredAtMillis)
            json.put("lastRotatedAt", now)
        } else {
            json.put("validationFailures", 0)
            json.put("configuredAt", now)
        }
        json.put("targetPackage", targetPackage)
        json.put("purpose", purpose)
        return json.toString()
    }

    private fun updateMeta(id: String, mutate: org.json.JSONObject.() -> Unit): org.json.JSONObject? {
        val raw = prefs.getString(metaKey(id), null) ?: return null
        val json = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return null
        json.mutate()
        return if (prefs.edit().putString(metaKey(id), json.toString()).commit()) json else null
    }

    private fun cipherKey(id: String) = "cipher:$id"
    private fun ivKey(id: String) = "iv:$id"
    private fun metaKey(id: String) = "meta:$id"
    private fun writePendingKey(id: String) = "write_pending:$id"
    private fun alias(id: String) = "amara_cred_$id"

    enum class CredentialValidationState { FAILED_RETRYABLE, LOCKED_OWNER_REQUIRED }

    companion object {
        const val LOCKOUT_THRESHOLD = 3

        // Static, redacted reasons — never carry record contents.
        const val INCOMPLETE_META_MISSING = "incomplete_record: metadata missing while ciphertext exists; owner must reconfigure"
        const val INCOMPLETE_META_MALFORMED = "incomplete_record: metadata unreadable; owner must reconfigure"
        const val INCOMPLETE_SCOPE_UNBOUND = "incomplete_record: no target package binding; owner must reconfigure"
        const val INCOMPLETE_CIPHERTEXT_MISSING = "incomplete_record: encrypted payload missing while metadata exists; owner must reconfigure"
        const val INCOMPLETE_IV_MISSING = "incomplete_record: encryption IV missing while ciphertext exists; owner must reconfigure"
        const val INCOMPLETE_WRITE_PENDING = "incomplete_record: a credential write was interrupted mid-flight; retry configuration to heal"
        const val INCOMPLETE_DECRYPTION_FAILED = "incomplete_record: decryption failed (keystore key unavailable or invalidated); owner must reconfigure"
        const val STORE_FAILED = "store_failed: credential could not be encrypted or durably persisted; the slot may be write-pending and owner must retry configuration"
        const val INVALIDATE_FAILED = "invalidate_failed: credential removal could not be persisted; owner must retry"
    }
}

/**
 * Scoped submission authority over one vault credential. Production Soko login
 * paths obtain the PIN ONLY through this gate: release is refused while the
 * credential is locked (before decryption), and every login outcome is recorded
 * back onto the vault so repeated external attempts can neither hammer nor lock
 * the owner's account beyond the vault's own soft-lock.
 */
class SokoCredentialGate(
    private val vault: CredentialVault,
    private val id: String,
    private val targetPackage: String,
) {
    /** "" unless an unlocked, intact record exists; never throws, never decrypts while locked. */
    fun releaseForSubmission(): String {
        return when (val outcome = vault.retrieve(id, targetPackage)) {
            is CredentialRetrieval.Secret -> {
                val pin = outcome.value.concatToString()
                outcome.value.fill('\u0000')
                pin
            }
            else -> ""
        }
    }

    fun recordLoginAccepted() {
        vault.recordValidationSuccess(id)
    }

    /** True when this rejection crossed into LOCKED_OWNER_REQUIRED. */
    fun recordLoginRejected(redactedReason: String): Boolean =
        vault.recordValidationFailure(id, redactedReason) ==
            CredentialVault.CredentialValidationState.LOCKED_OWNER_REQUIRED
}

/**
 * Login-outcome sink for credential-consuming modules. Modules hold this narrow
 * seam instead of a vault handle, keeping them testable without device storage.
 * Reasons passed here MUST be static/redacted codes, never raw provider text.
 */
interface SokoCredentialAuditor {
    fun onLoginAccepted()
    fun onLoginRejected(staticRedactedReason: String)
}

/** Encryption seam: production uses AndroidKeyStore; tests inject fakes. */
interface SecretCodec {
    /** Encrypts the plaintext; MAY create the keystore key when absent (bootstrap side only). */
    fun encrypt(alias: String, plaintext: CharArray): Pair<String, String>

    /**
     * Decrypts using ONLY an existing keystore key. A lost alias must surface as
     * an exception here (typed incomplete upstream) — never silently recreate a
     * replacement key, which would launder the loss as an ordinary failure.
     */
    fun decrypt(alias: String, blob: String, iv: String): CharArray
    fun deleteAlias(alias: String)
}

/** Thrown by [SecretCodec.decrypt] implementations when the alias no longer exists. */
class KeystoreKeyMissingException : GeneralSecurityException("keystore alias unavailable for decryption")

class AndroidKeystoreCodec : SecretCodec {
    override fun encrypt(alias: String, plaintext: CharArray): Pair<String, String> {
        val key = obtainOrCreateKey(alias)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encoded = Charsets.UTF_8.encode(CharBuffer.wrap(plaintext))
        val plainBytes = ByteArray(encoded.remaining())
        return try {
            encoded.get(plainBytes)
            val encrypted = cipher.doFinal(plainBytes)
            android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP) to
                android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP)
        } finally {
            plainBytes.fill(0)
            if (encoded.hasArray()) encoded.array().fill(0)
        }
    }

    override fun decrypt(alias: String, blob: String, iv: String): CharArray {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val existing = ks.getKey(alias, null) as? SecretKey ?: throw KeystoreKeyMissingException()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, existing, GCMParameterSpec(GCM_TAG_BITS, android.util.Base64.decode(iv, android.util.Base64.NO_WRAP)))
        val plainBytes = cipher.doFinal(android.util.Base64.decode(blob, android.util.Base64.NO_WRAP))
        return try {
            val decoded = Charsets.UTF_8.decode(ByteBuffer.wrap(plainBytes))
            CharArray(decoded.remaining()).also { decoded.get(it) }
        } finally {
            plainBytes.fill(0)
        }
    }

    override fun deleteAlias(alias: String) {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        ks.deleteEntry(alias)
    }

    /** Encryption-side bootstrap: create the alias only when it does not exist. */
    private fun obtainOrCreateKey(alias: String): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
