package co.sanaa.agent.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.util.Base64
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.FileProvider
import java.io.File
import co.sanaa.agent.actions.AccessibilityActions
import co.sanaa.agent.core.ModuleStateStore
import co.sanaa.agent.core.AgentRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Shell-only calibration hook. Android's DUMP permission prevents third-party apps from invoking it. */
class ProofOfConceptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Shell-only calibration hook. Compile-isolated from release builds so no POC
        // path can bypass the universal side-effect boundary in production.
        if (!co.sanaa.agent.BuildConfig.DEBUG) return
        if (intent.action == ACTION_READ_CHATS) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                val groupsOnly = intent.getBooleanExtra(EXTRA_GROUPS_ONLY, false)
                val chats = runCatching { AccessibilityActions(context).discoverWhatsAppChats(groupsOnly = groupsOnly) }.getOrDefault(emptyList())
                Log.i(TAG, "WhatsApp read-only discovery groupsOnly=$groupsOnly count=${chats.size} labels=${chats.joinToString(" | ")}")
                pending.finish()
            }
            return
        }
        if (intent.action == ACTION_MONITOR) {
            val target = intent.getStringExtra(EXTRA_TARGET)?.trim().orEmpty()
            if (target.isNotBlank()) {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    val runtime = AgentRuntime.get(context)
                    // Single authority: the durable ContactDirectory identity receives the
                    // MONITOR grant (legacy preference lists are never written).
                    val resolution = runtime.contacts.resolve(co.sanaa.agent.core.ContactQuery(name = target))
                    val entryId = when (resolution) {
                        is co.sanaa.agent.core.Resolution.Unique -> resolution.entry.id
                        else -> runtime.contacts.upsert(
                            co.sanaa.agent.core.DirectoryEntry(
                                id = "", displayName = target,
                                normalizedPhone = co.sanaa.agent.core.Normalizer.normalizeUganda(target),
                                aliases = emptySet(), isGroup = false,
                                source = co.sanaa.agent.core.EntrySource.OWNER_CREATED,
                                lastVerifiedAt = System.currentTimeMillis(),
                                ambiguity = co.sanaa.agent.core.Ambiguity.UNIQUE,
                                classification = co.sanaa.agent.core.Classification.UNKNOWN,
                                commercialConsent = co.sanaa.agent.core.CommercialConsent.UNKNOWN,
                                permissions = co.sanaa.agent.core.ContactDirectoryStore.operationsForLevel(co.sanaa.agent.core.ContactPermission.NONE),
                                whatsappSurfaceEvidence = null, revocationEvidence = null,
                            ),
                        ).id
                    }
                    val outcome = runtime.sideEffects.execute(
                        capabilityId = co.sanaa.agent.core.CapabilityIds.MONITOR_WHATSAPP,
                        idempotencyKey = "poc-monitor:$target",
                        target = target,
                        content = target,
                        initiator = co.sanaa.agent.core.Initiator.INTERNAL_RUNTIME,
                        act = { runtime.contacts.setPermission(entryId, co.sanaa.agent.core.Operation.MONITOR, true); true },
                        verify = {
                            val granted = runtime.contacts.byId(entryId)?.canMonitor == true
                            co.sanaa.agent.core.VerificationEvidence(granted, if (granted) 1.0 else 0.0, "co.sanaa.agent", "monitoring_enabled", System.currentTimeMillis())
                        },
                    )
                    Log.i(TAG, "WhatsApp monitoring verified=${outcome.verified} for $target")
                    pending.finish()
                }
            }
            return
        }
        if (intent.action == ACTION_READ_GROUP) {
            val target = intent.getStringExtra(EXTRA_TARGET)?.trim().orEmpty()
            if (target.isBlank()) return
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                val labels = runCatching { AccessibilityActions(context).readWhatsAppGroupParticipants(target) }.getOrDefault(emptyList())
                Log.i(TAG, "WhatsApp group read target=$target count=${labels.size} labels=${labels.joinToString(" | ")}")
                pending.finish()
            }
            return
        }
        if (intent.action == ACTION_ATTACHMENT) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                val directory = File(context.cacheDir, "agent-creatives").apply { mkdirs() }
                val file = File(directory, "amara-whatsapp-test.png")
                val bitmap = Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).apply {
                    drawColor(Color.rgb(8, 10, 10))
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(80, 227, 194); textAlign = Paint.Align.CENTER }
                    paint.textSize = 80f
                    drawText("AMARA", 360f, 320f, paint)
                    paint.textSize = 34f
                    drawText("WhatsApp attachment test", 360f, 390f, paint)
                }
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val runtime = AgentRuntime.get(context)
                val outcome = runtime.queue.withExclusiveDeviceAction {
                    runtime.sideEffects.execute(
                        capabilityId = co.sanaa.agent.core.CapabilityIds.SEND_WHATSAPP_ATTACHMENT,
                        idempotencyKey = "poc-attachment:${System.currentTimeMillis()}",
                        target = CONTACT_NAME,
                        content = "attachment:amara-whatsapp-test.png",
                        initiator = co.sanaa.agent.core.Initiator.INTERNAL_RUNTIME,
                        inputs = mapOf("target" to CONTACT_NAME, "content" to "Amara attachment test"),
                        act = { AccessibilityActions(context).transacted { sendWhatsAppAttachment(CONTACT_NAME, uri, "image/png", "Amara attachment test") } },
                        verify = { co.sanaa.agent.actions.TargetBoundVerifiers(AccessibilityActions(context)).verifyAttachment(CONTACT_NAME, "Amara attachment test") },
                    )
                }
                Log.i(TAG, "WhatsApp attachment test verified=${outcome.verified} state=${outcome.javaClass.simpleName} target=$CONTACT_NAME")
                pending.finish()
            }
            return
        }
        if (intent.action == ACTION_STUDIO_SHARE) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                val result = AgentRuntime.get(context).queue.withExclusiveDeviceAction {
                    AgentRuntime.get(context).studioSharing.shareOne(CONTACT_NAME)
                }
                Log.i(TAG, "Soko Studio share success=${result.success} product=${result.productName} summary=${result.summary}")
                pending.finish()
            }
            return
        }
        if (intent.action == ACTION_CONFIRM_STUDIO_SHARE) {
            val result = AgentRuntime.get(context).studioSharing.confirmVisibleShare(CONTACT_NAME, "Thermal Mini Printer")
            Log.i(TAG, "Soko Studio visible confirmation success=${result.success} summary=${result.summary}")
            return
        }
        if (intent.action != ACTION_SEND) return
        val encoded = intent.getStringExtra(EXTRA_MESSAGE_BASE64)
        val message = if (encoded.isNullOrBlank()) {
            intent.getStringExtra(EXTRA_MESSAGE)?.trim().orEmpty()
        } else {
            runCatching { String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault("").trim()
        }
        if (message.isBlank() || message.length > 1_000) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val runtime = AgentRuntime.get(context)
            val actions = AccessibilityActions(context)
            val target = intent.getStringExtra(EXTRA_TARGET)?.trim().orEmpty().ifBlank { CONTACT_NAME }
            val outcome = runtime.queue.withExclusiveDeviceAction {
                runtime.sideEffects.execute(
                    capabilityId = co.sanaa.agent.core.CapabilityIds.REPLY_WHATSAPP,
                    idempotencyKey = "poc-send:${target}:${message.hashCode()}",
                    target = target,
                    content = message,
                    initiator = co.sanaa.agent.core.Initiator.INTERNAL_RUNTIME,
                    act = { actions.transacted { sendToWhatsAppContact(target, message) } },
                    verify = { co.sanaa.agent.actions.TargetBoundVerifiers(actions).verifyWhatsAppSend(target, message) },
                )
            }
            val state = ModuleStateStore(context)
            val verified = outcome is co.sanaa.agent.core.SideEffectOutcome.Verified
            val sent = verified || outcome is co.sanaa.agent.core.SideEffectOutcome.Uncertain
            state.putString("poc_status", if (verified) "verified" else if (sent) "sent_unverified" else "failed")
            state.putString("poc_message", message)
            Log.i(TAG, "WhatsApp proof-of-concept target=$target status=${state.string("poc_status")}")
            pending.finish()
        }
    }

    companion object {
        const val ACTION_SEND = "co.sanaa.agent.action.POC_WHATSAPP_SEND"
        const val ACTION_READ_CHATS = "co.sanaa.agent.action.TEST_WHATSAPP_READ_CHATS"
        const val ACTION_MONITOR = "co.sanaa.agent.action.TEST_WHATSAPP_MONITOR"
        const val ACTION_READ_GROUP = "co.sanaa.agent.action.TEST_WHATSAPP_READ_GROUP"
        const val ACTION_ATTACHMENT = "co.sanaa.agent.action.TEST_WHATSAPP_ATTACHMENT"
        const val ACTION_STUDIO_SHARE = "co.sanaa.agent.action.TEST_SOKO_STUDIO_SHARE"
        const val ACTION_CONFIRM_STUDIO_SHARE = "co.sanaa.agent.action.TEST_CONFIRM_STUDIO_SHARE"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_MESSAGE_BASE64 = "message_base64"
        const val EXTRA_TARGET = "target"
        const val EXTRA_GROUPS_ONLY = "groups_only"
        const val CONTACT_NAME = "Sanaa Office Airtel"
        private const val TAG = "SanaaAgentPOC"
    }
}
