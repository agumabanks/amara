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
        if(intent.action=="co.sanaa.agent.action.TEST_WHATSAPP_REVIEW_RESTORE") {
            val key=intent.getStringExtra("work_key").orEmpty()
            if(!key.startsWith("wa-inbound:")) return
            val runtime=AgentRuntime.get(context)
            val db=runtime.workQueue.writableDatabase
            val payload=db.rawQuery("SELECT payload FROM work_items WHERE dedupe_key=? AND kind='WA_REPLY_INBOUND' AND status='COMPLETED'",arrayOf(key)).use {
                if(it.moveToFirst()) org.json.JSONObject(it.getString(0)) else null
            }
            if(payload!=null) {
                payload.put("review_reason","Historical reply delivery was not proven; read-only reconciliation required before any further action")
                    .put("review_at",System.currentTimeMillis())
                db.execSQL("UPDATE work_items SET status='NEEDS_REVIEW',payload=?,in_flight_until=NULL WHERE dedupe_key=? AND status='COMPLETED'",arrayOf(payload.toString(),key))
            }
            runtime.evaluation.record("needs_review",key,org.json.JSONObject().put("kind","WA_REPLY_INBOUND").put("restored_historical",true))
            Log.i(TAG,"Historical inbound retained for review; no send queued")
            return
        }
        if (intent.action == "co.sanaa.agent.action.TEST_OWNER_CADENCE_30") {
            val runtime=AgentRuntime.get(context)
            runtime.config.tikTokPostIntervalMinutes=30
            runtime.config.tikTokDailyCap=48
            runtime.evaluation.record("owner_cadence_update",fields=org.json.JSONObject().put("interval_minutes",30).put("daily_cap",48)
                .put("build","cadence-return-v2"))
            runtime.workLoop.wake(co.sanaa.agent.core.work.WakeReason.ExternalEvent("owner_cadence_update",""))
            Log.i(TAG,"Owner cadence applied: interval=30 minutes; daily cap=48")
            return
        }
        if (intent.action == "co.sanaa.agent.action.TEST_EVALUATION_START") {
            val window = co.sanaa.agent.core.EvaluationJournal(context).start()
            Log.i(TAG, "Evaluation window=$window")
            return
        }
        if (intent.action == "co.sanaa.agent.action.TEST_EVALUATION_HEALTH") {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val runtime = AgentRuntime.get(context).awaitReady()
                    val commercial = runtime.revenueOps.dashboard.build().filterValues { it is Number || it is Boolean }
                    runtime.evaluation.record("health", fields = org.json.JSONObject(runtime.workLoop.diagnostics()
                        .filterKeys { it != "lastSummary" }).put("pending", runtime.workQueue.pendingCount())
                        .put("commercial", org.json.JSONObject(commercial))
                        .put("social_states", runtime.tikTokSocialStore.summary().optJSONObject("states"))
                        .put("queue", runtime.workQueue.evaluationSnapshot()))
                } finally { pending.finish() }
            }
            return
        }
        if (intent.action == "co.sanaa.agent.action.TEST_TIKTOK_SOCIAL") {
            Log.i(TAG, "TikTok social owner hook received")
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val runtime = AgentRuntime.get(context).awaitReady()
                    if (intent.getBooleanExtra("enable_owner_policy", false)) {
                        runtime.config.tikTokSocialEnabled = true
                        runtime.config.tikTokCommentsEnabled = true
                        runtime.config.memoryBackupEnabled = true
                    }
                    if (intent.getBooleanExtra("verify_existing", false)) {
                        runtime.workLoop.pauseForInspection(180000)
                        runtime.queue.withExclusiveDeviceAction {
                            val row=runtime.tikTokSocialStore.readableDatabase.rawQuery(
                                "SELECT creator,payload,response FROM observations WHERE reserved_at>0 ORDER BY reserved_at DESC LIMIT 1",null
                            ).use { c -> if(c.moveToFirst()) Triple(c.getString(0),org.json.JSONObject(c.getString(1)),c.getString(2)) else null }
                            if(row!=null) {
                                val surface=co.sanaa.agent.actions.TikTokSocialSurface(runtime.actions)
                                val profile=surface.profile()
                                val query=row.first
                                var verified=false
                                if(profile!=null && surface.discover(query)) for(i in 0 until 4) {
                                    val post=surface.readPost()
                                    if(post!=null && post.creator==row.first && post.caption==row.second.optString("caption")) {
                                        val evidence=surface.verify(post,row.third,profile.optString("display_name"))
                                        verified=evidence.verified
                                        Log.i(TAG,"Existing TikTok comment read-only verification=$evidence")
                                        break
                                    }
                                    if(!surface.next()) break
                                }
                                runtime.actions.captureScreenshot("tiktok_existing_comment_read")
                                Log.i(TAG,"Existing TikTok comment checked; verified=$verified; no dispatch attempted")
                            }
                        }
                    } else if (intent.hasExtra("inspect_query")) {
                        runtime.workLoop.pauseForInspection(180000)
                        runtime.queue.withExclusiveDeviceAction {
                            val surface=co.sanaa.agent.actions.TikTokSocialSurface(runtime.actions)
                            val searched=surface.search(intent.getStringExtra("inspect_query").orEmpty())
                            val evidence=runtime.actions.captureScreenshot("tiktok_social_search")
                            val screen=runtime.actions.snapshot()
                            Log.i(TAG, "TikTok search inspected=$searched; screenshot=${evidence.path}; labels=${screen.visibleText.take(30)}")
                        }
                    } else if (intent.getBooleanExtra("backup_only", false)) {
                        val result = runtime.memoryBackup.backupNow()
                        Log.i(TAG, "TikTok learning backup: success=${result.success}; social=${runtime.tikTokSocialStore.summary()}")
                    } else {
                        val offered = runtime.workQueue.offer(co.sanaa.agent.core.work.WorkItem(
                            dedupeKey = "owner-social-discovery-check:${java.time.LocalDate.now()}",
                            domain = co.sanaa.agent.core.work.Domain.TIKTOK,
                            kind = co.sanaa.agent.core.work.WorkKind.TIKTOK_COMMENT_REPLY,
                            payload = org.json.JSONObject().put("owner_always_on", true),
                            baseValueKes = 1000.0, urgencyHalfLifeHours = 1.0, estimatedScreenSeconds = 90,
                            requires = setOf(co.sanaa.agent.core.work.Capability.SCREEN, co.sanaa.agent.core.work.Capability.NETWORK),
                        ))
                        Log.i(TAG, "TikTok social policy enabled=${runtime.config.tikTokSocialEnabled}; queue=$offered")
                        runtime.workLoop.wake(co.sanaa.agent.core.work.WakeReason.ExternalEvent("owner_social_check", ""))
                    }
                } catch (error: Exception) { Log.w(TAG, "TikTok social check: ${error.javaClass.simpleName}") }
                finally { pending.finish() }
            }
            return
        }
        if (intent.action == "co.sanaa.agent.action.TEST_GROUP_PROMOTION") {
            val target = intent.getStringExtra(EXTRA_TARGET)?.trim().orEmpty()
            if (target.isBlank()) return
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val runtime = AgentRuntime.get(context).awaitReady()
                    if (co.sanaa.agent.core.ContactDirectoryProvider.instance?.broadcastGroups()?.contains(target) != true) return@launch
                    runtime.workQueue.offer(co.sanaa.agent.core.work.WorkItem(
                        dedupeKey = "owner-photo-group-check-v5:${java.time.LocalDate.now()}:$target",
                        domain = co.sanaa.agent.core.work.Domain.WHATSAPP,
                        kind = co.sanaa.agent.core.work.WorkKind.WA_BROADCAST,
                        payload = org.json.JSONObject().put("group_target", target).put("owner_always_on", true),
                        baseValueKes = 10_000.0, urgencyHalfLifeHours = 1.0, estimatedScreenSeconds = 60,
                        requires = setOf(co.sanaa.agent.core.work.Capability.SCREEN, co.sanaa.agent.core.work.Capability.NETWORK, co.sanaa.agent.core.work.Capability.CONSENT_TIER_2),
                        riskTier = co.sanaa.agent.core.work.RiskTier.MEDIUM,
                    ))
                    runtime.workLoop.wake(co.sanaa.agent.core.work.WakeReason.ExternalEvent("owner_group_check", ""))
                } finally { pending.finish() }
            }
            return
        }
        if (intent.action == "co.sanaa.agent.action.TEST_TIKTOK_VERIFY_READ") {
            val caption = intent.getStringExtra("caption").orEmpty()
            if (caption.isBlank()) return
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val evidence = co.sanaa.agent.actions.TargetBoundVerifiers(AccessibilityActions(context)).verifyTikTokPost(caption)
                    Log.i(TAG, "Read-only TikTok verification=$evidence")
                } finally { pending.finish() }
            }
            return
        }
        if (intent.action == "co.sanaa.agent.action.TEST_CHAT_NAVIGATION") {
            val target = intent.getStringExtra(EXTRA_TARGET)?.trim().orEmpty()
            if (target.isBlank()) return
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val actions = AccessibilityActions(context)
                    val verified = actions.openWhatsAppTarget(target)
                    Log.i(TAG, "Read-only exact chat navigation verified=$verified stage=${actions.lastWhatsAppNavigationFailure}")
                    val content = intent.getStringExtra("content").orEmpty()
                    if (verified && content.isNotBlank()) {
                        val evidence = if (intent.getBooleanExtra("photo", false)) actions.verifyCaptionedPhoto(target, content)
                            else co.sanaa.agent.actions.TargetBoundVerifiers(actions).verifyWhatsAppSend(target, content)
                        Log.i(TAG, "Read-only WhatsApp delivery verification=$evidence")
                    }
                } finally { pending.finish() }
            }
            return
        }
        if (intent.action == "co.sanaa.agent.action.TEST_INSPECTION_PAUSE") {
            AgentRuntime.get(context).workLoop.pauseForInspection(intent.getLongExtra("duration_ms", 180_000))
            return
        }
        if (intent.action == "co.sanaa.agent.action.TEST_JUMIA_REPAIR") {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val runtime = AgentRuntime.get(context).awaitReady()
                    if (!runtime.config.visionConsent) {
                        Log.i(TAG, "Jumia repair blocked: screenshot consent is not enabled")
                        return@launch
                    }
                    val model = intent.getStringExtra("vision_model").orEmpty()
                    if (runtime.config.groqVisionModel.isBlank() && model.matches(Regex("[A-Za-z0-9._/-]{1,160}"))) runtime.config.groqVisionModel = model
                    runtime.workQueue.offer(co.sanaa.agent.core.work.WorkItem(
                        dedupeKey = "jumia-repair:${System.currentTimeMillis()}", domain = co.sanaa.agent.core.work.Domain.INTERNAL,
                        kind = co.sanaa.agent.core.work.WorkKind.JUMIA_CAPTURE,
                        payload = org.json.JSONObject().put("owner_always_on", true),
                        baseValueKes = 1000.0, urgencyHalfLifeHours = 1.0, estimatedScreenSeconds = 180,
                        requires = setOf(co.sanaa.agent.core.work.Capability.SCREEN, co.sanaa.agent.core.work.Capability.NETWORK),
                    ))
                    runtime.workLoop.wake(co.sanaa.agent.core.work.WakeReason.ExternalEvent("jumia_repair", ""))
                } finally { pending.finish() }
            }
            return
        }
        if (intent.action == "co.sanaa.agent.action.TEST_SOKO_LOGIN_READ") {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val runtime = AgentRuntime.get(context).awaitReady()
                    val ok = runtime.queue.withExclusiveDeviceAction {
                        val actions = AccessibilityActions(context)
                        actions.recoverSokoHome(runtime.sokoPin())
                    }
                    if (ok) runtime.sokoCredentialGate.recordLoginAccepted()
                    Log.i(TAG, "Owner-authorized Terminal login read: homeVerified=$ok")
                } finally { pending.finish() }
            }
            return
        }
        if (intent.action == "co.sanaa.agent.action.TEST_READINESS") {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val runtime = AgentRuntime.get(context).awaitReady()
                    val status = runtime.vault.status(AgentRuntime.SOKO_PIN_ID)
                    Log.i(TAG, "Readiness: terminalCredential=$status; visionConsent=${runtime.config.visionConsent}; visionModelConfigured=${runtime.config.groqVisionModel.isNotBlank()}; visionBlocker=${runtime.groq.visionConfigurationBlocker()}; queueCounts=${runtime.workQueue.dashboard()["counts"]}; growth=${runtime.growthStore.dashboard().filterKeys { it in setOf("promotionOutcomes", "replyOutcomes") }}")
                } finally { pending.finish() }
            }
            return
        }
        if (intent.action == "co.sanaa.agent.action.TEST_GROWTH_REVIEW") {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val runtime = AgentRuntime.get(context).awaitReady()
                    val offerings = runtime.soko.promotableOfferings()
                    val report = co.sanaa.agent.core.growth.MarketGrowthReview(runtime.memory, runtime.marketAnalyzer, runtime.growthStore).review(offerings)
                    Log.i(TAG, "Read-only growth review: ${report.optString("summary")}")
                } finally { pending.finish() }
            }
            return
        }
        if (intent.action == "co.sanaa.agent.action.TEST_JIJI_READ") {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val runtime = AgentRuntime.get(context).awaitReady()
                    runtime.workQueue.offer(co.sanaa.agent.core.work.WorkItem(
                        dedupeKey = "jiji-repair-check:${System.currentTimeMillis()}",
                        domain = co.sanaa.agent.core.work.Domain.INTERNAL,
                        kind = co.sanaa.agent.core.work.WorkKind.JIJI_SCRAPE,
                        payload = org.json.JSONObject().put("category", "Printers & Scanners"),
                        baseValueKes = 10_000.0, urgencyHalfLifeHours = 1.0, estimatedScreenSeconds = 120,
                        requires = setOf(co.sanaa.agent.core.work.Capability.SCREEN, co.sanaa.agent.core.work.Capability.NETWORK),
                    ))
                    runtime.workLoop.wake(co.sanaa.agent.core.work.WakeReason.ExternalEvent("jiji_read_check", ""))
                } finally { pending.finish() }
            }
            return
        }
        if (intent.action == ACTION_WORK_LOOP_WAKE) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                val runtime = AgentRuntime.get(context).awaitReady()
                runtime.workLoop.wake(
                    co.sanaa.agent.core.work.WakeReason.ExternalEvent("supervised_device_test", ""),
                )
                Log.i(TAG, "Governed work loop wake requested for supervised device test")
                pending.finish()
            }
            return
        }
        if (intent.action == ACTION_TIKTOK_PREPARE) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                val runtime = AgentRuntime.get(context).awaitReady()
                val candidate = runCatching {
                    runtime.soko.activeListings()
                        .filter { !it.imageUrl.isNullOrBlank() }
                        .maxWithOrNull(compareBy<co.sanaa.agent.api.SokoListing> { it.viewCount }.thenBy { it.photoCount })
                }.getOrNull()
                if (candidate == null) {
                    Log.w(TAG, "TikTok canary preparation failed: no active Soko listing with media")
                } else {
                    val caption = runtime.tiktok.generateCaption(candidate.title, candidate.description)
                        .ifBlank { co.sanaa.agent.modules.TikTokSkill.fallbackCaption(candidate.title) }
                    run {
                        ModuleStateStore(context).apply {
                            putString(TIKTOK_LISTING_ID, candidate.id)
                            putString(TIKTOK_LISTING_TITLE, candidate.title)
                            putString(TIKTOK_CAPTION, caption)
                        }
                        Log.i(TAG, "TikTok canary ready listing=${candidate.id} title=${candidate.title} caption=$caption")
                    }
                }
                pending.finish()
            }
            return
        }
        if (intent.action == ACTION_TIKTOK_POST_CANARY) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                val runtime = AgentRuntime.get(context).awaitReady()
                val state = ModuleStateStore(context)
                val listingId = state.string(TIKTOK_LISTING_ID).trim()
                val caption = state.string(TIKTOK_CAPTION).trim()
                if (listingId.isBlank() || caption.isBlank()) {
                    Log.w(TAG, "TikTok canary refused: prepare an exact candidate first")
                } else {
                    val item = co.sanaa.agent.core.work.WorkItem(
                        dedupeKey = "owner-tiktok-canary:$listingId:${System.currentTimeMillis()}",
                        domain = co.sanaa.agent.core.work.Domain.TIKTOK,
                        kind = co.sanaa.agent.core.work.WorkKind.TIKTOK_POST_PUBLISH,
                        payload = org.json.JSONObject().put("listing_id", listingId).put("caption", caption).put("owner_canary", true),
                        baseValueKes = 10_000.0,
                        urgencyHalfLifeHours = 1.0,
                        estimatedScreenSeconds = 120,
                        requires = setOf(
                            co.sanaa.agent.core.work.Capability.SCREEN,
                            co.sanaa.agent.core.work.Capability.NETWORK,
                            co.sanaa.agent.core.work.Capability.GROQ,
                            co.sanaa.agent.core.work.Capability.CONSENT_TIER_2,
                        ),
                        riskTier = co.sanaa.agent.core.work.RiskTier.MEDIUM,
                    )
                    val existing = runtime.workQueue.allPending().firstOrNull {
                        it.kind == co.sanaa.agent.core.work.WorkKind.TIKTOK_POST_PUBLISH &&
                            it.payload.optBoolean("owner_canary", false) &&
                            it.payload.optString("listing_id") == listingId
                    }
                    val offered = if (existing != null) {
                        runtime.workQueue.requeue(existing.dedupeKey, System.currentTimeMillis(), existing.attempt)
                        co.sanaa.agent.core.work.WorkQueue.OfferResult.DEDUPED
                    } else {
                        runtime.workQueue.offer(item)
                    }
                    runtime.workLoop.wake(co.sanaa.agent.core.work.WakeReason.ExternalEvent("owner_tiktok_canary", listingId))
                    Log.i(TAG, "TikTok canary queued result=$offered listing=$listingId title=${state.string(TIKTOK_LISTING_TITLE)}")
                }
                pending.finish()
            }
            return
        }
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
        const val ACTION_WORK_LOOP_WAKE = "co.sanaa.agent.action.TEST_WORK_LOOP_WAKE"
        const val ACTION_TIKTOK_PREPARE = "co.sanaa.agent.action.TEST_TIKTOK_SOKO_PREPARE"
        const val ACTION_TIKTOK_POST_CANARY = "co.sanaa.agent.action.TEST_TIKTOK_SOKO_POST"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_MESSAGE_BASE64 = "message_base64"
        const val EXTRA_TARGET = "target"
        const val EXTRA_GROUPS_ONLY = "groups_only"
        const val CONTACT_NAME = "Sanaa Office Airtel"
        private const val TIKTOK_LISTING_ID = "poc_tiktok_listing_id"
        private const val TIKTOK_LISTING_TITLE = "poc_tiktok_listing_title"
        private const val TIKTOK_CAPTION = "poc_tiktok_caption"
        private const val TAG = "SanaaAgentPOC"
    }
}
