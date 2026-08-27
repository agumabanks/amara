package co.sanaa.agent.core.commerce

import android.content.ContentValues
import co.sanaa.agent.core.AmaraMemory
import co.sanaa.agent.core.ContentHashing

/**
 * Revenue Operator domain model — durable typed records over AmaraMemory's v10 tables.
 *
 * Every funnel transition carries timestamp, source, evidence, confidence and reason;
 * every metric-bearing record carries stable unique ids and deduplication keys so no
 * inquiry, sale, or cost can ever be counted twice. Corrections (cancellation/refund)
 * mutate sale state instead of deleting history: the ledger stays auditable.
 */
object FunnelStage {
    const val OBSERVED = "OBSERVED"
    const val CONTACTABLE = "CONTACTABLE"
    const val ENGAGED = "ENGAGED"
    const val QUALIFIED_INQUIRY = "QUALIFIED_INQUIRY"
    const val ORDER_INTENT = "ORDER_INTENT"
    const val ORDER_CREATED = "ORDER_CREATED"
    const val SALE_VERIFIED = "SALE_VERIFIED"
    const val LOST = "LOST"
    const val DISQUALIFIED = "DISQUALIFIED"

    /** Ordered forward progression; LOST/DISQUALIFIED are terminal side-exits. */
    val PROGRESSION = listOf(
        OBSERVED, CONTACTABLE, ENGAGED, QUALIFIED_INQUIRY,
        ORDER_INTENT, ORDER_CREATED, SALE_VERIFIED,
    )
    val TERMINAL_NEGATIVE = listOf(LOST, DISQUALIFIED)
    val ALL = PROGRESSION + TERMINAL_NEGATIVE

    fun isForward(from: String, to: String): Boolean =
        PROGRESSION.indexOf(to) == PROGRESSION.indexOf(from) + 1

    fun known(stage: String): Boolean = stage in ALL
}

/** Owner goal record (objective only — never treated as evidence). */
data class RevenueGoal(
    val id: String,
    val kind: GoalKind,
    val metric: String,
    val targetValue: Double,
    val periodStartMs: Long?,
    val active: Boolean,
    val createdAtMs: Long,
) {
    enum class GoalKind { DAILY_QUALIFIED_INQUIRY, WEEKLY_VERIFIED_SALES, MONTHLY_PROFIT_TO_COST }
}

data class Opportunity(
    val id: String,
    val contactKey: String,
    val productRef: String,
    val stage: String,
    val dedupeKey: String,
    val confidence: Double,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

/** One auditable funnel move. Nothing advances a stage without one of these rows. */
data class FunnelTransition(
    val opportunityId: String,
    val fromStage: String,
    val toStage: String,
    val occurredAtMs: Long,
    val source: String,
    val evidenceKind: String,
    val evidenceRef: String,
    val confidence: Double,
    val reason: String,
)

data class QualifiedInquiry(
    val uniqueKey: String,
    val channel: String,
    val contactKey: String,
    val productRef: String,
    val interactionId: String,
    val evidenceKind: String,
    val evidenceRef: String,
    val confidence: Double,
    val occurredAtMs: Long,
)

data class SaleEvidenceRecord(
    val uniqueKey: String,
    val evidenceKind: SaleEvidenceKind,
    val saleRef: String,
    val contactKey: String,
    val productRef: String,
    val amountUgx: Long,
    val state: SaleState,
    val correctionReason: String,
    val occurredAtMs: Long,
) {
    enum class SaleEvidenceKind { SOKO_ORDER, BOOKING, POS_RECEIPT, PAYMENT_RECORD, OWNER_CONFIRMED }
    enum class SaleState { ACTIVE, CANCELLED, REFUNDED }
}

data class CostEntry(
    val uniqueKey: String,
    val component: CostComponent,
    val productRef: String,
    val ref: String,
    val amountUgx: Long,
    val occurredAtMs: Long,
) {
    enum class CostComponent {
        PRODUCT_COST, DISCOUNTS, CAMPAIGN_SPEND, TRANSACTION_FEES, REFUNDS,
        OPERATING_ALLOCATION, SUBSCRIPTION,
    }
}

enum class AttributionLabel { DIRECT, INFLUENCED, UNATTRIBUTED }

/** Per-sale attribution row; UNIQUE(sale) guarantees a sale is never counted twice. */
data class RevenueAttribution(
    val saleUniqueKey: String,
    val label: AttributionLabel,
    val ruleType: String,
    val windowMs: Long,
    val campaignRef: String,
    val touchRef: String,
    val confidence: Double,
    val recordedAtMs: Long,
)

/**
 * Durable repository for the revenue domain. All writes go through admission rules that
 * live in [RevenueMetricEngine]; this class only persists typed records mechanically
 * (unique keys enforced by the schema itself).
 */
class RevenueStore(private val memory: AmaraMemory) {

    private val db get() = memory.writableDatabase

    data class CampaignTouch(
        val uniqueKey: String,
        val campaignRef: String,
        val contactKey: String,
        val evidenceRef: String,
        val occurredAtMs: Long,
        val recordedAtMs: Long,
    )

    data class DeliveryObservation(
        val uniqueKey: String,
        val contactKey: String,
        val contentHash: String,
        val deliveryState: String,
        val observedAtMs: Long,
        val recordedAtMs: Long,
    )

    // ---------- goals ----------

    @Synchronized
    fun saveGoal(goal: RevenueGoal): Boolean {
        db.execSQL(
            """INSERT INTO revenue_goals(id, kind, metric, target_value, period_start_ms, active, created_at)
               VALUES(?,?,?,?,?,?,?)
               ON CONFLICT(id) DO UPDATE SET kind=excluded.kind, metric=excluded.metric,
                 target_value=excluded.target_value, period_start_ms=excluded.period_start_ms,
                 active=excluded.active""",
            arrayOf<Any>(
                goal.id, goal.kind.name, goal.metric, goal.targetValue,
                goal.periodStartMs ?: 0L, if (goal.active) 1 else 0, goal.createdAtMs,
            ),
        )
        return true
    }

    @Synchronized
    fun goals(activeOnly: Boolean = true): List<RevenueGoal> = db.query(
        "revenue_goals", null, if (activeOnly) "active = 1" else null, null, null, null, "created_at ASC",
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                RevenueGoal(
                    id = c.getString(0), kind = RevenueGoal.GoalKind.valueOf(c.getString(1)),
                    metric = c.getString(2), targetValue = c.getDouble(3),
                    periodStartMs = if (c.getLong(4) == 0L) null else c.getLong(4),
                    active = c.getInt(5) == 1, createdAtMs = c.getLong(6),
                ),
            )
        }
    }

    // ---------- opportunities + funnel ----------

    fun opportunityDedupeKey(contactKey: String, productRef: String): String =
        ContentHashing.hash("opportunity|$contactKey|$productRef")

    @Synchronized
    fun ensureOpportunity(contactKey: String, productRef: String, source: String, nowMs: Long): Opportunity? {
        require(FunnelStage.known(source)) { "Unknown source stage '$source'" }
        val id = "opp-" + opportunityDedupeKey(contactKey, productRef).take(20)
        val existing = findOpportunity(id)
        if (existing != null) return existing
        return runCatching {
            db.insertOrThrow(
                "revenue_opportunities", null,
                ContentValues().apply {
                    put("id", id); put("contact_key", contactKey.take(200)); put("product_ref", productRef.take(300))
                    put("stage", source); put("dedupe_key", opportunityDedupeKey(contactKey, productRef))
                    put("confidence", 0.5); put("created_at", nowMs); put("updated_at", nowMs)
                },
            )
            findOpportunity(id)
        }.getOrNull()
    }

    @Synchronized
    fun findOpportunity(id: String): Opportunity? = db.query(
        "revenue_opportunities", null, "id = ?", arrayOf(id), null, null, null,
    ).use { c ->
        if (!c.moveToFirst()) null else Opportunity(
            c.getString(0), c.getString(1), c.getString(2), c.getString(3),
            c.getString(4), c.getDouble(5), c.getLong(6), c.getLong(7),
        )
    }

    @Synchronized
    fun opportunities(stages: Set<String>? = null): List<Opportunity> = db.query(
        "revenue_opportunities", null,
        if (stages != null) stages.joinToString(" OR ") { "stage = ?" } else null,
        stages?.toTypedArray(), null, null, "updated_at DESC", "500",
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                Opportunity(c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                    c.getString(4), c.getDouble(5), c.getLong(6), c.getLong(7)),
            )
        }
    }

    /**
     * Records the transition row first, then moves the stage exactly one forward step.
     * Terminal negative exits (LOST/DISQUALIFIED) are legal from any non-terminal stage;
     * anything else off-progression is refused without recording.
     */
    @Synchronized
    /**
     * Funnel transition = evidence-row INSERT + stage compare-and-set in ONE SQLite
     * transaction. If the stage no longer matches `from` (a concurrent worker moved it)
     * or the insert collides, BOTH writes roll back and this returns false — the ledger
     * can never hold a transition row whose opportunity never advanced.
     */
    fun recordTransition(transition: FunnelTransition): Boolean {
        require(FunnelStage.known(transition.toStage)) { "Unknown target stage '${transition.toStage}'" }
        require(transition.source.isNotBlank() && transition.evidenceKind.isNotBlank() && transition.evidenceRef.isNotBlank()) {
            "A funnel transition needs source and evidence"
        }
        require(transition.confidence in 0.0..1.0) { "Transition confidence within [0,1]" }
        require(transition.reason.isNotBlank()) { "A funnel transition records its reason" }
        val current = findOpportunity(transition.opportunityId) ?: return false
        val from = current.stage
        val to = transition.toStage
        val legal = when {
            from == to -> false
            to in FunnelStage.TERMINAL_NEGATIVE -> from in FunnelStage.PROGRESSION
            else -> FunnelStage.isForward(from, to)
        }
        if (!legal) return false
        db.beginTransaction()
        try {
            db.insertWithOnConflict(
                "revenue_funnel_transitions", null,
                ContentValues().apply {
                    put("opportunity_id", transition.opportunityId); put("from_stage", from); put("to_stage", to)
                    put("occurred_at", transition.occurredAtMs); put("source", transition.source.take(120))
                    put("evidence_kind", transition.evidenceKind.take(80)); put("evidence_ref", transition.evidenceRef.take(500))
                    put("confidence", transition.confidence); put("reason", transition.reason.take(500))
                },
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
            )
            // Compare-and-set on the observed stage; a count != 1 means a concurrent
            // writer changed the stage and the whole transaction must roll back.
            val cas = db.compileStatement(
                "UPDATE revenue_opportunities SET stage = ?, updated_at = ? WHERE id = ? AND stage = ?",
            )
            cas.bindString(1, to)
            cas.bindLong(2, transition.occurredAtMs)
            cas.bindString(3, transition.opportunityId)
            cas.bindString(4, from)
            val moved = cas.executeUpdateDelete()
            if (moved != 1) throw IllegalStateException("stage compare-and-set lost the race")
            db.setTransactionSuccessful()
            return true
        } catch (t: Throwable) {
            return false
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun transitions(opportunityId: String): List<FunnelTransition> = db.query(
        "revenue_funnel_transitions", null, "opportunity_id = ?", arrayOf(opportunityId), null, null, "occurred_at ASC",
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                FunnelTransition(
                    c.getString(1), c.getString(2), c.getString(3), c.getLong(4),
                    c.getString(5), c.getString(6), c.getString(7), c.getDouble(8), c.getString(9),
                ),
            )
        }
    }

    // ---------- inquiries / sales / costs / attribution ----------

    /** Returns false only when the UNIQUE key collides (duplicate refused upstream). */
    @Synchronized
    fun insertInquiry(inquiry: QualifiedInquiry, nowMs: Long): Boolean = runCatching {
        db.insertOrThrow(
            "revenue_inquiries", null,
            ContentValues().apply {
                put("unique_key", inquiry.uniqueKey); put("channel", inquiry.channel.take(80))
                put("contact_key", inquiry.contactKey.take(200)); put("product_ref", inquiry.productRef.take(300))
                put("interaction_id", inquiry.interactionId.take(200)); put("evidence_kind", inquiry.evidenceKind.take(80))
                put("evidence_ref", inquiry.evidenceRef.take(500)); put("confidence", inquiry.confidence)
                put("occurred_at", inquiry.occurredAtMs); put("recorded_at", nowMs)
            },
        )
    }.isSuccess

    @Synchronized
    fun inquiries(fromMs: Long, toMs: Long): List<QualifiedInquiry> = db.query(
        "revenue_inquiries", null, "occurred_at >= ? AND occurred_at <= ?", arrayOf(fromMs.toString(), toMs.toString()),
        null, null, "occurred_at ASC",
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                QualifiedInquiry(
                    c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                    c.getString(5), c.getString(6), c.getDouble(7), c.getLong(8),
                ),
            )
        }
    }

    @Synchronized
    fun findInquiry(uniqueKey: String): QualifiedInquiry? = db.query(
        "revenue_inquiries", null, "unique_key = ?", arrayOf(uniqueKey), null, null, null,
    ).use { c ->
        if (!c.moveToFirst()) null else QualifiedInquiry(
            c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
            c.getString(5), c.getString(6), c.getDouble(7), c.getLong(8),
        )
    }

    @Synchronized
    fun insertSale(sale: SaleEvidenceRecord, nowMs: Long): Boolean = runCatching {
        db.insertOrThrow(
            "revenue_sales", null,
            ContentValues().apply {
                put("unique_key", sale.uniqueKey); put("evidence_kind", sale.evidenceKind.name)
                put("sale_ref", sale.saleRef.take(300)); put("contact_key", sale.contactKey.take(200))
                put("product_ref", sale.productRef.take(300)); put("amount_ugx", sale.amountUgx)
                put("state", sale.state.name); put("correction_reason", sale.correctionReason.take(500))
                put("occurred_at", sale.occurredAtMs); put("recorded_at", nowMs)
            },
        )
    }.isSuccess

    @Synchronized
    fun sales(fromMs: Long, toMs: Long, states: Set<SaleEvidenceRecord.SaleState>? = null): List<SaleEvidenceRecord> {
        val where = StringBuilder("occurred_at >= ? AND occurred_at <= ?")
        val args = mutableListOf(fromMs.toString(), toMs.toString())
        states?.takeIf { it.isNotEmpty() }?.let { st ->
            where.append(" AND state IN (").append(st.joinToString(",") { "?" }).append(")")
            args += st.map { it.name }
        }
        return db.query(
            "revenue_sales", null, where.toString(), args.toTypedArray(), null, null, "occurred_at ASC",
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    SaleEvidenceRecord(
                        c.getString(0), SaleEvidenceRecord.SaleEvidenceKind.valueOf(c.getString(1)),
                        c.getString(2), c.getString(3), c.getString(4), c.getLong(5),
                        SaleEvidenceRecord.SaleState.valueOf(c.getString(6)), c.getString(7), c.getLong(8),
                    ),
                )
            }
        }
    }

    @Synchronized
    fun findSaleByRef(saleRef: String): SaleEvidenceRecord? = db.query(
        "revenue_sales", null, "sale_ref = ?", arrayOf(saleRef), null, null, null,
    ).use { c ->
        if (!c.moveToFirst()) null else SaleEvidenceRecord(
            c.getString(0), SaleEvidenceRecord.SaleEvidenceKind.valueOf(c.getString(1)),
            c.getString(2), c.getString(3), c.getString(4), c.getLong(5),
            SaleEvidenceRecord.SaleState.valueOf(c.getString(6)), c.getString(7), c.getLong(8),
        )
    }

    /** Correction: cancellation/refund mutates state and keeps the original row auditable. */
    @Synchronized
    fun correctSaleState(saleUniqueKey: String, newState: SaleEvidenceRecord.SaleState, reason: String, nowMs: Long): Boolean {
        require(newState != SaleEvidenceRecord.SaleState.ACTIVE) { "Corrections move AWAY from ACTIVE" }
        require(reason.isNotBlank()) { "A correction names its reason" }
        return db.update(
            "revenue_sales",
            ContentValues().apply { put("state", newState.name); put("correction_reason", reason.take(500)) },
            "unique_key = ? AND state = ?",
            arrayOf(saleUniqueKey, SaleEvidenceRecord.SaleState.ACTIVE.name),
        ) == 1
    }

    /** Partial refund: reduces an ACTIVE sale's recognized amount by exactly [refundedUgx]. */
    @Synchronized
    fun reduceActiveSaleAmount(saleUniqueKey: String, refundedUgx: Long, reason: String, nowMs: Long): Boolean {
        require(refundedUgx > 0) { "Partial refunds are positive" }
        require(reason.isNotBlank()) { "A partial refund names its reason" }
        db.execSQL(
            """UPDATE revenue_sales
               SET amount_ugx = amount_ugx - ?, correction_reason = ?
               WHERE unique_key = ? AND state = 'ACTIVE' AND amount_ugx > ?""",
            arrayOf<Any>(refundedUgx, reason.take(500), saleUniqueKey, refundedUgx),
        )
        return db.compileStatement("SELECT changes()").simpleQueryForLong() == 1L
    }

    @Synchronized
    fun insertCost(cost: CostEntry, nowMs: Long): Boolean = runCatching {
        db.insertOrThrow(
            "revenue_costs", null,
            ContentValues().apply {
                put("unique_key", cost.uniqueKey); put("component", cost.component.name)
                put("product_ref", cost.productRef.take(300)); put("ref", cost.ref.take(300))
                put("amount_ugx", cost.amountUgx); put("occurred_at", cost.occurredAtMs); put("recorded_at", nowMs)
            },
        )
    }.isSuccess

    @Synchronized
    fun costs(fromMs: Long, toMs: Long): List<CostEntry> = db.query(
        "revenue_costs", null, "occurred_at >= ? AND occurred_at <= ?", arrayOf(fromMs.toString(), toMs.toString()),
        null, null, "occurred_at ASC",
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                CostEntry(
                    c.getString(0), CostEntry.CostComponent.valueOf(c.getString(1)),
                    c.getString(2), c.getString(3), c.getLong(4), c.getLong(5),
                ),
            )
        }
    }

    /** One attribution per sale forever: the second insert loses (UNIQUE). */
    @Synchronized
    fun insertAttribution(attribution: RevenueAttribution): Boolean = runCatching {
        db.insertOrThrow(
            "revenue_attributions", null,
            ContentValues().apply {
                put("sale_unique_key", attribution.saleUniqueKey); put("label", attribution.label.name)
                put("rule_type", attribution.ruleType.take(80)); put("window_ms", attribution.windowMs)
                put("campaign_ref", attribution.campaignRef.take(200)); put("touch_ref", attribution.touchRef.take(300))
                put("confidence", attribution.confidence); put("recorded_at", attribution.recordedAtMs)
            },
        )
    }.isSuccess

    @Synchronized
    fun attributions(fromMs: Long, toMs: Long): List<RevenueAttribution> = db.query(
        "revenue_attributions", null, "recorded_at >= ? AND recorded_at <= ?", arrayOf(fromMs.toString(), toMs.toString()),
        null, null, "recorded_at ASC",
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                RevenueAttribution(
                    c.getString(1), AttributionLabel.valueOf(c.getString(2)), c.getString(3),
                    c.getLong(4), c.getString(5), c.getString(6), c.getDouble(7), c.getLong(8),
                ),
            )
        }
    }

    @Synchronized
    fun attributionForSale(saleUniqueKey: String): RevenueAttribution? = db.query(
        "revenue_attributions", null, "sale_unique_key = ?", arrayOf(saleUniqueKey), null, null, null,
    ).use { c ->
        if (!c.moveToFirst()) null else RevenueAttribution(
            c.getString(1), AttributionLabel.valueOf(c.getString(2)), c.getString(3),
            c.getLong(4), c.getString(5), c.getString(6), c.getDouble(7), c.getLong(8),
        )
    }

    // ---------- suppression list ----------

    @Synchronized
    fun suppressContact(contactKey: String, reason: String, nowMs: Long): Boolean {
        db.execSQL(
            """INSERT INTO outreach_suppressions(contact_key, reason, suppressed_at) VALUES(?,?,?)
               ON CONFLICT(contact_key) DO UPDATE SET reason=excluded.reason, suppressed_at=excluded.suppressed_at""",
            arrayOf<Any>(contactKey.take(200), reason.take(300), nowMs),
        )
        return true
    }

    @Synchronized
    fun isSuppressed(contactKey: String): Boolean = db.rawQuery(
        "SELECT 1 FROM outreach_suppressions WHERE contact_key = ?", arrayOf(contactKey),
    ).use { it.moveToFirst() }

    @Synchronized
    fun suppressions(): List<Triple<String, String, Long>> = db.query(
        "outreach_suppressions", null, null, null, null, null, "suppressed_at DESC", "300",
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getLong(2)))
        }
    }

    /**
     * Attribution evidence lookup over the shared production database: the most recent
     * INBOUND customer reply from [contactKey] inside [afterMs, beforeMs]. Reads the
     * canonical conversations table — the same rows the ConversationEngine records.
     */
    @Synchronized
    fun lastInboundReplyBetween(contactKey: String, afterMs: Long, beforeMs: Long): Pair<Long, String>? =
        db.query(
            "conversations", arrayOf("timestamp", "message_text"),
            "contact_name = ? AND direction = 'received' AND timestamp BETWEEN ? AND ?",
            arrayOf(contactKey, afterMs.toString(), beforeMs.toString()),
            null, null, "timestamp DESC", "1",
        ).use { c ->
            if (!c.moveToFirst()) null else c.getLong(0) to c.getString(1).take(200)
        }

    // ---------- campaign-touch and delivery evidence ----------

    @Synchronized
    fun insertCampaignTouch(touch: CampaignTouch): Boolean = runCatching {
        db.insertOrThrow(
            "revenue_campaign_touches", null,
            ContentValues().apply {
                put("unique_key", touch.uniqueKey)
                put("campaign_ref", touch.campaignRef.take(200))
                put("contact_key", touch.contactKey.take(200))
                put("evidence_ref", touch.evidenceRef.take(500))
                put("occurred_at", touch.occurredAtMs)
                put("recorded_at", touch.recordedAtMs)
            },
        )
    }.isSuccess

    @Synchronized
    fun campaignTouches(
        campaignRef: String? = null,
        contactKey: String? = null,
        fromMs: Long = 0,
        toMs: Long = Long.MAX_VALUE,
    ): List<CampaignTouch> {
        val where = mutableListOf("occurred_at >= ?", "occurred_at <= ?")
        val args = mutableListOf(fromMs.toString(), toMs.toString())
        campaignRef?.let { where += "campaign_ref = ?"; args += it }
        contactKey?.let { where += "contact_key = ?"; args += it }
        return db.query(
            "revenue_campaign_touches", null, where.joinToString(" AND "), args.toTypedArray(),
            null, null, "occurred_at ASC", "1000",
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    CampaignTouch(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5)),
                )
            }
        }
    }

    @Synchronized
    fun insertDeliveryObservation(observation: DeliveryObservation): Boolean = runCatching {
        db.insertOrThrow(
            "revenue_delivery_observations", null,
            ContentValues().apply {
                put("unique_key", observation.uniqueKey)
                put("contact_key", observation.contactKey.take(200))
                put("content_hash", observation.contentHash.take(128))
                put("delivery_state", observation.deliveryState.take(80))
                put("observed_at", observation.observedAtMs)
                put("recorded_at", observation.recordedAtMs)
            },
        )
    }.isSuccess

    @Synchronized
    fun deliveryObservations(
        contactKey: String? = null,
        contentHash: String? = null,
        fromMs: Long = 0,
        toMs: Long = Long.MAX_VALUE,
    ): List<DeliveryObservation> {
        val where = mutableListOf("observed_at >= ?", "observed_at <= ?")
        val args = mutableListOf(fromMs.toString(), toMs.toString())
        contactKey?.let { where += "contact_key = ?"; args += it }
        contentHash?.let { where += "content_hash = ?"; args += it }
        return db.query(
            "revenue_delivery_observations", null, where.joinToString(" AND "), args.toTypedArray(),
            null, null, "observed_at ASC", "1000",
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    DeliveryObservation(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5)),
                )
            }
        }
    }

    // ---------- experiments ----------

    @Synchronized
    fun upsertExperiment(id: String, specJson: String, state: String, startedAt: Long?, endedAt: Long?, resultJson: String, nowMs: Long): Boolean {
        db.execSQL(
            """INSERT INTO revenue_experiments(id, spec_json, state, started_at, ended_at, result_json, created_at)
               VALUES(?,?,?,?,?,?,?)
               ON CONFLICT(id) DO UPDATE SET spec_json=excluded.spec_json, state=excluded.state,
                 started_at=COALESCE(excluded.started_at, started_at), ended_at=excluded.ended_at,
                 result_json=excluded.result_json""",
            arrayOf<Any?>(id, specJson, state, startedAt, endedAt, resultJson, nowMs),
        )
        return true
    }

    data class ExperimentRow(
        val id: String, val specJson: String, val state: String,
        val startedAtMs: Long?, val endedAtMs: Long?, val resultJson: String, val createdAtMs: Long,
    )

    @Synchronized
    fun experiments(states: Set<String>? = null): List<ExperimentRow> {
        val where = states?.takeIf { it.isNotEmpty() }?.joinToString(" OR ") { "state = ?" }
        return db.query(
            "revenue_experiments", null, where, states?.toTypedArray(), null, null, "created_at DESC", "100",
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    ExperimentRow(
                        c.getString(0), c.getString(1), c.getString(2),
                        if (c.isNull(3)) null else c.getLong(3),
                        if (c.isNull(4)) null else c.getLong(4),
                        c.getString(5), c.getLong(6),
                    ),
                )
            }
        }
    }

    // ---------- commercial actions / plans / briefs ----------

    /** Durable experiment evidence sample (v12): every measurement keeps its source. */
    data class ExperimentSample(
        val id: Long,
        val experimentId: String,
        val metric: String,
        val value: Double,
        val evidenceRef: String,
        val recordedAtMs: Long,
    )

    @Synchronized
    fun insertExperimentSample(experimentId: String, metric: String, value: Double, evidenceRef: String, nowMs: Long): Boolean = runCatching {
        db.insertOrThrow(
            "experiment_samples", null,
            ContentValues().apply {
                put("experiment_id", experimentId.take(200)); put("metric", metric.take(120))
                put("value", value); put("evidence_ref", evidenceRef.take(500)); put("recorded_at", nowMs)
            },
        )
    }.isSuccess

    @Synchronized
    fun experimentSamples(experimentId: String, metric: String? = null): List<ExperimentSample> {
        val where = if (metric == null) "experiment_id = ?" else "experiment_id = ? AND metric = ?"
        val args = if (metric == null) arrayOf(experimentId) else arrayOf(experimentId, metric)
        return db.query("experiment_samples", null, where, args, null, null, "recorded_at ASC").use { c ->
            buildList {
                while (c.moveToNext()) add(
                    ExperimentSample(c.getLong(0), c.getString(1), c.getString(2), c.getDouble(3), c.getString(4), c.getLong(5)),
                )
            }
        }
    }

    data class CommercialActionRow(
        val id: String, val dedupeKey: String, val planDay: String, val capabilityId: String,
        val target: String, val contentHash: String, val state: String,
        val rankingJson: String, val evidenceRef: String,
        val createdAtMs: Long, val updatedAtMs: Long,
    )

    @Synchronized
    fun upsertCommercialAction(action: CommercialActionRow): Boolean = runCatching {
        db.insertOrThrow(
            "commercial_actions", null,
            ContentValues().apply {
                put("id", action.id); put("dedupe_key", action.dedupeKey); put("plan_day", action.planDay)
                put("capability_id", action.capabilityId); put("target", action.target.take(300))
                put("content_hash", action.contentHash); put("state", action.state)
                put("ranking_json", action.rankingJson.take(6_000)); put("evidence_ref", action.evidenceRef.take(500))
                put("created_at", action.createdAtMs); put("updated_at", action.updatedAtMs)
            },
        )
    }.isSuccess

    @Synchronized
    fun updateCommercialActionState(id: String, state: String, evidenceRef: String, nowMs: Long): Boolean = db.update(
        "commercial_actions",
        ContentValues().apply { put("state", state); if (evidenceRef.isNotBlank()) put("evidence_ref", evidenceRef.take(500)); put("updated_at", nowMs) },
        "id = ?", arrayOf(id),
    ) == 1

    @Synchronized
    fun commercialActions(state: String? = null, planDay: String? = null): List<CommercialActionRow> {
        val wheres = mutableListOf<String>()
        val args = mutableListOf<String>()
        state?.let { wheres += "state = ?"; args += it }
        planDay?.let { wheres += "plan_day = ?"; args += it }
        return db.query(
            "commercial_actions", null,
            wheres.joinToString(" AND ").ifBlank { null }, args.toTypedArray(), null, null, "updated_at DESC", "300",
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    CommercialActionRow(
                        c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                        c.getString(5), c.getString(6), c.getString(7), c.getString(8), c.getLong(9), c.getLong(10),
                    ),
                )
            }
        }
    }

    /** Executed-verified commercial actions inside the owner's LOCAL day so far. */
    fun executedActionCountToday(zone: java.time.ZoneId, nowMs: Long): Int {
        val dayStart = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()
        return commercialActions(state = "EXECUTED_VERIFIED").count { it.updatedAtMs in dayStart..nowMs }
    }

    @Synchronized
    fun saveDailyPlan(dayKey: String, planJson: String, nowMs: Long): Boolean {
        db.execSQL(
            """INSERT INTO daily_commercial_plans(day_key, plan_json, created_at) VALUES(?,?,?)
               ON CONFLICT(day_key) DO UPDATE SET plan_json=excluded.plan_json""",
            arrayOf<Any>(dayKey, planJson.take(50_000), nowMs),
        )
        return true
    }

    @Synchronized
    fun dailyPlan(dayKey: String): String? = db.rawQuery(
        "SELECT plan_json FROM daily_commercial_plans WHERE day_key = ?", arrayOf(dayKey),
    ).use { if (!it.moveToFirst()) null else it.getString(0) }

    @Synchronized
    fun saveDailyBrief(dayKey: String, revisionId: Long, briefJson: String, nowMs: Long): Boolean {
        db.execSQL(
            """INSERT INTO daily_commercial_briefs(day_key, revision_id, brief_json, created_at) VALUES(?,?,?,?)
               ON CONFLICT(day_key) DO UPDATE SET revision_id=excluded.revision_id, brief_json=excluded.brief_json""",
            arrayOf<Any>(dayKey, revisionId, briefJson.take(50_000), nowMs),
        )
        return true
    }

    @Synchronized
    fun dailyBriefs(limit: Int = 30): List<Pair<String, Long>> = db.query(
        "daily_commercial_briefs", arrayOf("day_key", "revision_id"), null, null, null, null, "day_key DESC", limit.coerceIn(1, 90).toString(),
    ).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0) to c.getLong(1)) }
    }

    // ---------- durable consent / contact-eligibility ledger ----------

    /** One durable grant (or its revocation state). Append-only: history is never deleted. */
    data class ContactConsent(
        val id: Long,
        val contactKey: String,
        val source: String,
        val scope: String,
        val grantedAtMs: Long,
        val expiresAtMs: Long?,
        val revokedAtMs: Long?,
        val revokeReason: String,
        val evidenceRef: String,
        val permittedProducts: Set<String>,
        val permittedChannels: Set<String>,
    )

    @Synchronized
    fun grantContactConsent(
        contactKey: String, source: String, scope: String, grantedAtMs: Long,
        expiresAtMs: Long?, evidenceRef: String,
        permittedProducts: Set<String> = emptySet(), permittedChannels: Set<String> = emptySet(),
    ): Boolean {
        if (contactKey.isBlank() || source.isBlank() || evidenceRef.isBlank()) return false
        if (grantedAtMs <= 0) return false
        if (expiresAtMs != null && expiresAtMs <= grantedAtMs) return false
        db.insertOrThrow(
            "contact_consent", null,
            ContentValues().apply {
                put("contact_key", contactKey.take(200)); put("source", source.take(120))
                put("scope", scope.take(120)); put("granted_at", grantedAtMs)
                put("expires_at", expiresAtMs); put("evidence_ref", evidenceRef.take(500))
                put("permitted_products", permittedProducts.joinToString(",").take(1_000))
                put("permitted_channels", permittedChannels.joinToString(",").take(500))
            },
        )
        return true
    }

    /** Revocation is sticky: it stamps every currently-live row for the contact. */
    @Synchronized
    fun revokeContactConsent(contactKey: String, reason: String, nowMs: Long): Int = db.update(
        "contact_consent",
        ContentValues().apply { put("revoked_at", nowMs); put("revoke_reason", reason.take(300)) },
        "contact_key = ? AND revoked_at IS NULL",
        arrayOf(contactKey),
    )

    /**
     * Live consent only: a non-blank string is never enough — the lookup requires a real
     * ledger row that is granted, unrevoked, unexpired, and (when specific) covering the
     * requested product and channel. Blank permitted sets in a row permit nothing.
     */
    @Synchronized
    fun hasLiveConsent(contactKey: String, productRef: String?, channel: String?, nowMs: Long): Boolean =
        consentsFor(contactKey).any { c ->
            c.revokedAtMs == null && c.grantedAtMs <= nowMs &&
                (c.expiresAtMs == null || nowMs < c.expiresAtMs) &&
                (productRef == null || productRef in c.permittedProducts) &&
                (channel == null || channel in c.permittedChannels)
        }

    @Synchronized
    fun consentsFor(contactKey: String): List<ContactConsent> = db.query(
        "contact_consent", null, "contact_key = ?", arrayOf(contactKey), null, null, "id ASC",
    ).use { c -> buildList { while (c.moveToNext()) add(consentRow(c)) } }

    @Synchronized
    fun consents(limit: Int = 300): List<ContactConsent> = db.query(
        "contact_consent", null, null, null, null, null, "id DESC", limit.coerceIn(1, 2_000).toString(),
    ).use { c -> buildList { while (c.moveToNext()) add(consentRow(c)) } }

    private fun consentRow(c: android.database.Cursor): ContactConsent {
        fun set(col: Int): Set<String> =
            if (c.isNull(col)) emptySet() else c.getString(col).split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return ContactConsent(
            id = c.getLong(0), contactKey = c.getString(1), source = c.getString(2),
            scope = c.getString(3), grantedAtMs = c.getLong(4),
            expiresAtMs = if (c.isNull(5)) null else c.getLong(5),
            revokedAtMs = if (c.isNull(6)) null else c.getLong(6),
            revokeReason = c.getString(7), evidenceRef = c.getString(8),
            permittedProducts = set(9), permittedChannels = set(10),
        )
    }

    // ---------- owner policy document (fail-closed when absent) ----------

    @Synchronized
    fun savePolicy(policy: CommercialPolicy, nowMs: Long): Boolean {
        db.execSQL(
            """INSERT INTO commercial_policy(id, config_json, updated_at) VALUES(1, ?, ?)
               ON CONFLICT(id) DO UPDATE SET config_json=excluded.config_json, updated_at=excluded.updated_at""",
            arrayOf<Any>(policy.toJson().take(8_000), nowMs),
        )
        return true
    }

    /** Null when the owner never configured a policy — callers MUST fail closed. */
    @Synchronized
    fun loadPolicy(): CommercialPolicy? = db.rawQuery(
        "SELECT config_json FROM commercial_policy WHERE id = 1", null,
    ).use { c -> if (!c.moveToFirst()) null else CommercialPolicy.fromJson(c.getString(0)) }
}
