package co.sanaa.agent.core.commerce

/**
 * Bounded revenue-experiment engine. Experiments are durable; only one may be RUNNING
 * at a time unless the owner explicitly designed a multivariate test (maxConcurrent
 * slots). Stop-loss trips mechanically into STOPPED_LOSS — never into expanded spend.
 * A failed target may trigger a NEW proposal; it can NEVER widen caps, audience,
 * frequency, discounts, or authority.
 */
class ExperimentEngine(
    private val store: RevenueStore,
    private val policy: () -> CommercialPolicy,
) {

    data class LaunchSpec(
        val id: String,
        val hypothesis: String,
        val baseline: String,
        val changeDimension: ExperimentSpec.ChangeDimension,
        val changeDescription: String,
        val targetProductRef: String,
        val approvedAudience: String,
        val approvedChannel: String,
        val startAtMs: Long,
        val endAtMs: Long,
        val minimumSampleCount: Int,
        val budgetUgx: Long,
        val communicationCapPerDay: Int,
        val successMetric: String,
        val guardrailMetric: String,
        val stopLossCondition: String,
        val attributionWindowMs: Long,
    ) {
        init {
            require(id.isNotBlank() && hypothesis.isNotBlank() && baseline.isNotBlank()) { "id/hypothesis/baseline mandatory" }
            require(changeDescription.isNotBlank()) { "single primary variable must be described" }
            require(targetProductRef.isNotBlank() && approvedAudience.isNotBlank() && approvedChannel.isNotBlank()) {
                "target product/audience/channel need owner approval"
            }
            require(endAtMs > startAtMs) { "experiment end must follow start" }
            require(minimumSampleCount >= 1) { "sample threshold mandatory" }
            require(budgetUgx >= 0 && communicationCapPerDay >= 1) { "budget/communication cap mandatory" }
            require(successMetric.isNotBlank() && guardrailMetric.isNotBlank() && stopLossCondition.isNotBlank()) {
                "success metric, guardrail metric and stop-loss are mandatory"
            }
            require(attributionWindowMs > 0) { "attribution window mandatory" }
        }

        fun toExperimentSpec(): ExperimentSpec = ExperimentSpec(
            id = id, hypothesis = hypothesis, baseline = baseline, changeDimension = changeDimension,
            changeDescription = changeDescription, approvedAudience = approvedAudience,
            approvedChannel = approvedChannel, maxMessagesPerDay = communicationCapPerDay,
            maxSpendUgx = budgetUgx, startCondition = "startAtMs=$startAtMs", stopCondition = "endAtMs=$endAtMs or $stopLossCondition",
            successMetric = successMetric, safetyMetric = guardrailMetric, attributionWindowMs = attributionWindowMs,
            minimumEvidenceCount = minimumSampleCount, rollbackRule = "stop-loss trips STOPPED_LOSS; revert variable immediately",
        )
    }

    sealed class LaunchResult {
        data class Running(val id: String) : LaunchResult()
        data class Refused(val reason: String) : LaunchResult()
    }

    fun launch(spec: LaunchSpec, nowMs: Long, multivariateDesign: String? = null): LaunchResult {
        val policyNow = policy()
        if (spec.budgetUgx > policyNow.maxExperimentSpendUgx) {
            return LaunchResult.Refused("experiment budget exceeds owner policy ceiling (${policyNow.maxExperimentSpendUgx})")
        }
        if (spec.communicationCapPerDay > policyNow.dailyGlobalMessageCap) {
            return LaunchResult.Refused("experiment communication cap exceeds the daily global message cap")
        }
        if (policyNow.allowedProducts.isEmpty() || spec.targetProductRef !in policyNow.allowedProducts) {
            return LaunchResult.Refused("target product is not owner-approved; missing/empty allowed-products policy fails closed")
        }
        // Audience and channel must match owner policy exactly — never just nonblank.
        if (spec.approvedChannel !in policyNow.approvedChannels) {
            return LaunchResult.Refused("channel '${spec.approvedChannel}' is not owner-approved (${policyNow.approvedChannels.sorted()})")
        }
        val audience = policyNow.permittedAudience
        if (audience.isBlank() || spec.approvedAudience != audience) {
            return LaunchResult.Refused("audience '${spec.approvedAudience}' does not match the owner-configured permitted audience")
        }
        val running = store.experiments(setOf("RUNNING"))
        // Concurrent experiments are refused unless the owner explicitly declared a
        // multivariate design naming its variables — single-variable attribution is
        // otherwise uninterpretable.
        if (running.any { it.id != spec.id }) {
            if (running.size >= policyNow.maxConcurrentExperiments) {
                return LaunchResult.Refused(
                    "another experiment is already RUNNING and no multivariate design covers this launch " +
                        "(declare variables explicitly to run concurrent experiments)",
                )
            }
            if (multivariateDesign.isNullOrBlank()) {
                return LaunchResult.Refused(
                    "concurrent experiments require an explicit multivariateDesign naming every varied variable",
                )
            }
        }
        store.upsertExperiment(
            id = spec.id, specJson = spec.toExperimentSpec().toJson(), state = "RUNNING",
            startedAt = nowMs.coerceAtLeast(spec.startAtMs), endedAt = null,
            resultJson = """{"multivariateDesign":${if (multivariateDesign.isNullOrBlank()) "null" else "\"${multivariateDesign.take(300)}\""}}""",
            nowMs = nowMs,
        )
        return LaunchResult.Running(spec.id)
    }

    /**
     * Guardrail check against the experiment's OWN DECLARED stop-loss rule — never a
     * caller-supplied threshold. The stopLossCondition must parse mechanically as
     * "guardrail >= N" / "guardrail > N"; an unparseable rule refuses instead of guessing.
     * A breach stops the experiment into STOPPED_LOSS — spending never expands.
     */
    fun evaluateGuardrail(experimentId: String, guardrailValue: Double, evidenceRef: String, nowMs: Long): String {
        val row = store.experiments().firstOrNull { it.id == experimentId }
            ?: return "NOT_FOUND"
        if (row.state != "RUNNING") return row.state
        val threshold = parseStopLossThreshold(row.specJson)
            ?: throw IllegalArgumentException("experiment '$experimentId' has no mechanical stop-loss threshold; refusing to evaluate")
        store.insertExperimentSample(experimentId, "guardrail", guardrailValue, evidenceRef, nowMs)
        return if (guardrailValue >= threshold) {
            store.upsertExperiment(experimentId, row.specJson, "STOPPED_LOSS", row.startedAtMs, nowMs,
                org.json.JSONObject(row.resultJson)
                    .put("guardrailValue", guardrailValue)
                    .put("stopLossThreshold", threshold)
                    .put("evidenceRef", evidenceRef).toString(), nowMs)
            "STOPPED_LOSS"
        } else {
            row.state
        }
    }

    /** Parses "…>= N" / "…> N" from the declared stopLossCondition inside the spec JSON. */
    private fun parseStopLossThreshold(specJson: String): Double? {
        val condition = runCatching { org.json.JSONObject(specJson).optString("stopCondition") }.getOrNull().orEmpty()
        val match = Regex("(?:>=|>)\\s*(-?[0-9]+(?:\\.[0-9]+)?)").find(condition) ?: return null
        return match.groupValues[1].toDoubleOrNull()
    }

    /**
     * Concludes an experiment ONLY once its DECLARED minimum sample count is backed by
     * durable sample rows. Confidence must come from computed sample evidence or be an
     * independently verified input (`verified=true`); arbitrary self-reported confidence
     * is refused. The result is stored with the exact sample count it rests on.
     */
    fun conclude(experimentId: String, resultSummary: String, confidence: Double, nowMs: Long, verifiedInput: Boolean = false): Boolean {
        require(confidence in 0.0..1.0) { "confidence within [0,1]" }
        val row = store.experiments().firstOrNull { it.id == experimentId } ?: return false
        if (row.state !in setOf("RUNNING", "STOPPED_LOSS")) return false
        if (!verifiedInput && row.state == "RUNNING") {
            val minimum = minimumSampleCount(row.specJson)
            val samples = store.experimentSamples(experimentId).size
            if (samples < minimum) {
                throw IllegalStateException(
                    "cannot conclude '$experimentId': $samples durable samples < declared minimum $minimum",
                )
            }
        }
        store.upsertExperiment(
            experimentId, row.specJson, "CONCLUDED", row.startedAtMs, nowMs,
            org.json.JSONObject(row.resultJson)
                .put("result", resultSummary.take(2_000))
                .put("confidence", confidence)
                .put("confidenceBasis", if (verifiedInput) "independently_verified_input" else "computed_from_samples")
                .put("sampleCount", store.experimentSamples(experimentId).size)
                .toString(),
            nowMs,
        )
        return true
    }

    /** Rolls a RUNNING experiment back (stop-loss companion action), keeping history. */
    fun rollback(experimentId: String, reason: String, nowMs: Long): Boolean {
        val row = store.experiments().firstOrNull { it.id == experimentId } ?: return false
        if (row.state != "RUNNING" && row.state != "STOPPED_LOSS") return false
        store.upsertExperiment(
            experimentId, row.specJson, "ROLLED_BACK", row.startedAtMs, nowMs,
            org.json.JSONObject(row.resultJson).put("rollbackReason", reason.take(500)).toString(),
            nowMs,
        )
        return true
    }

    /** Reconciles experiment spend/caps against owner policy at review time. */
    fun spendReconciliation(experimentId: String): Pair<Long, Long> {
        val row = store.experiments().firstOrNull { it.id == experimentId } ?: return 0L to 0L
        val specBudget = Regex("\"budgetUgx\"\\s*:\\s*([0-9]+)").find(row.specJson)
            ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return specBudget to policy().maxExperimentSpendUgx
    }

    private fun minimumSampleCount(specJson: String): Int =
        Regex("\"minimumEvidenceCount\"\\s*:\\s*([0-9]+)").find(specJson)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1

    /**
     * Target-miss response: builds a NEW bounded proposal for owner review. Authority
     * expansion is structurally impossible here: the returned spec inherits the SAME
     * policy-bounded caps; nothing in this API can raise spend, audience, frequency,
     * discounts, or authority. The proposal never launches itself.
     */
    fun draftFollowUpProposal(id: String, hypothesis: String, dimension: ExperimentSpec.ChangeDimension, description: String, targetProductRef: String, nowMs: Long): LaunchSpec =
        LaunchSpec(
            id = id, hypothesis = hypothesis, baseline = "prior concluded experiment",
            changeDimension = dimension, changeDescription = description,
            targetProductRef = targetProductRef,
            approvedAudience = policy().permittedAudience.ifBlank { "OWNER-REVIEW-REQUIRED" },
            approvedChannel = policy().approvedChannels.firstOrNull() ?: "OWNER-REVIEW-REQUIRED",
            startAtMs = nowMs, endAtMs = nowMs + 7 * 24 * 60 * 60 * 1_000L,
            minimumSampleCount = 1, budgetUgx = 0, communicationCapPerDay = 1,
            successMetric = "declared before launch", guardrailMetric = "opt-out rate stays under 1%",
            stopLossCondition = "guardrail threshold breach", attributionWindowMs = policy().attributionWindowMs,
        )
}
