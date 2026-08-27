# INTERFACE REQUESTS — Campaign AMARA-REL-20260826

Requests for cross-file changes. Contract owner (Agent 1) records approval here.

## REQ-3-01 — SokoIntelligenceModule:93 schema wiring (owner: Agent 2)

Requested by Agent 3, 2026-08-26T03:55Z.

`SokoIntelligenceModule.proposeTextImprovements` calls `groq.completeJson(prompt)`
without a schema. The matching schema constant is now PUBLISHED in
api/ModelSchemas.kt:

```
ModelSchemas.SOKO_SERVICE_TEXT_PROPOSALS   // name "soko_service_text_proposals"
// {"proposals":[{"current_name","proposed_name","proposed_description","reason"}]}
// elements require non-blank current_name / proposed_name / proposed_description
```

Request: Agent 2 passes `co.sanaa.agent.api.ModelSchemas.SOKO_SERVICE_TEXT_PROPOSALS`
(and a correlation id per contract §3, e.g. `"soko-intelligence-$runId"`) at the
SokoIntelligenceModule.kt:93 call site. Per contract §6 Agent 2 may also thread
the correlation id into their own finalize path via
`BrainFailureFinalizer.finalizeFailed/markRecovered` (api/ModelGateway.kt).

## NOTE-3-02 — WhatsApp inbound classification schema (for Agent 1)

Published 2026-08-26T03:55Z by Agent 3 in api/ModelSchemas.kt:

```
ModelSchemas.WHATSAPP_INBOUND_CLASSIFICATION // "whatsapp_inbound_classification"
ModelSchemas.CONTACT_MESSAGE_GENERATION      // "contact_message_generation"
```

WHATSAPP_INBOUND_CLASSIFICATION mirrors CONVERSATION_REPLY plus a closed
`missed_call` enum value and strict conditional rules (response required when
`escalate=false`; escalation_reason required when `escalate=true`). No action
required by Agent 1 unless they choose to tighten ConversationEngine's inbound
stage; CONVERSATION_REPLY remains untouched and valid.

## NOTE-3-03 — RuntimeStatusBus additive extension (approved scope §6)

Agent 3 added additively to core/RuntimeStatusBus.kt (2026-08-26):
`OverlayChipState`, `canonicalChipState(...)`, `setProviderOffline/isProviderOffline`,
`severity` ordering, and `STALE_AFTER_MS`. No existing signature changed;
AutonomyController/MainActivity call sites keep compiling. OverlayService now
renders exclusively from `canonicalChipState()`.

## NOTE-2-04 — URGENT: targeted verification blocked on AmaraMemory v13→v14 (for Agent 1)

Filed 2026-08-26T06:30Z by Agent 2.

`:app:compileDebugKotlin` fails with 9 errors, ALL inside api/ModelGateway.kt
(Agent 3's file) referencing the §2 API AmaraMemory has not shipped yet:
`finalizeBrainFailure`, `brainFailuresByCorrelation`,
`BrainFailureRecord.{correlationId,terminalOutcome,ownerExplanation}`,
`recordBrainFailure(correlationId=, terminalOutcome=, ownerExplanation=)`.
AmaraMemory.kt remains at v13 (mtime 2026-08-25 11:38; old 9-param
recordBrainFailure confirmed at line 2123).

This blocks ALL targeted unit tests for Agents 2 AND 3 (testDebugUnitTest compiles
main sources first): CredentialVaultLifecycleTest, SokoCredentialRoutingTest and
Agent 3's six classes cannot run until v14 lands. Contract §2 already freezes this
exact API — no design decision pending, only the drop. Requesting it be prioritized;
Agents 2/3 will rerun their VERIFY blocks immediately after.

## REQ-3-05 — AmaraMemory v14 drop (owner: Agent 1) — consumer-side exact shape

Filed 2026-08-26T06:15Z by Agent 3 (reinforces NOTE-2-04; ~4h past the original
30–45 min ETA). All Agent-3 code+tests are complete and compile against ONLY this
missing API. Exact consumer expectations (from api/ModelGateway.kt record path +
BrainFailureFinalizer + ModelGatewayBehaviorTest / ProductionPathCorrelationTest):

```kotlin
// schema v13 → v14; brain_failures gains columns
//   correlation_id TEXT NOT NULL DEFAULT ''
//   terminal_outcome TEXT NOT NULL DEFAULT ''
//   owner_explanation TEXT NOT NULL DEFAULT ''
fun recordBrainFailure(
    stage: String, model: String, requestId: String, responseHash: String,
    attemptCount: Int, retryable: Boolean, validationErrorsJson: String,
    correctiveAction: String, disposition: String,
    correlationId: String = "", terminalOutcome: String = "",
    ownerExplanation: String = "",
): Long

// Updates ONLY the newest brain_failures row matching correlationId AND stage
// with terminal_outcome = '' ; never inserts; returns true iff a row updated.
fun finalizeBrainFailure(
    correlationId: String, stage: String,
    terminalOutcome: String, ownerExplanation: String,
): Boolean

data class BrainFailureRecord(
    /* existing fields incl. stage, disposition, attemptCount, requestId,
       responseHash, correctiveAction, validationErrorsJson */
    val correlationId: String = "",
    val terminalOutcome: String = "",
    val ownerExplanation: String = "",
)
fun recentBrainFailures(limit: Int): List<BrainFailureRecord>          // existing
fun brainFailuresByCorrelation(correlationId: String, limit: Int = 50):
    List<BrainFailureRecord>
```

Callers depend on: default-empty new params (legacy call sites keep compiling);
`finalizeBrainFailure` returning false when no open row matches (tests assert a
second finalize does not double-write); `brainFailuresByCorrelation` returning rows
newest-first or any order (tests use single/last/first consistently on small sets).
Terminal-outcome vocabulary per contract §2. No other behavior is assumed.

## REQ-4-01 — URGENT: device transport lost, wireless debugging disabled on CPH1933 (owner: Agent 1 / owner)

Filed 2026-08-26T10:20Z by Agent 4.

No ADB transport exists to the Oppo CPH1933 (serial 7aef1a4c). Evidence dossier:
`amara-10-10/evidence-device-AMARA-REL-20260826-C/G0_connectivity_sweep.txt`.

Facts (all verbatim-recorded): bridge tunnel UP (196.250.64.88 since 01:27Z); last known
endpoint 192.168.1.66:41167 refuses; :5555 refuses; mDNS silent via bridge server; exhaustive
read-only port sweeps 1024-65535 (64,512 SYNs, two passes, ~49 min total) found ZERO adbd
listeners while the handset itself answers RST on every port. Conclusion: wireless debugging
is OFF on the device right now (rotated off after the 02:00Z vaultfix install window).

Consequence: ALL 24 campaign gates recorded BLOCKED/INCOMPLETE with per-gate blockers in
`amara-10-10/evidence-device-AMARA-REL-20260826-C/MANIFEST.json`; zero device side effects
were produced (no install, no reboot, no timezone change, no wifi toggles — restore-first
preconditions could not be guaranteed without transport).

OWNER ACTION NEEDED (any one):
1. On handset: Settings > Developer options > Wireless debugging ON (leave on), then open
   Amara > Phone access and confirm Accessibility + notification bindings are still live;
   report the host:port shown OR leave it and Agent 4 will rediscover within ~50 min.
2. Preferred durable fix: one-time USB plug from bridge host + `adb tcpip 5555`.
3. If the Terminal/Soko sign-in screen reappeared after whatever event disabled wireless
   debugging, complete the account session first (runbook preflight step 5).

Campaign AMARA-REL-20260826-C remains frozen-hash-clean locally
(app-release.apk sha256 085f9a64… verified before any attempt); no halt condition triggered
(no install occurred, so no mismatch possible).
