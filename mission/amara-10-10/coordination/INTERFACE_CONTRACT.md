# INTERFACE CONTRACT — Campaign AMARA-REL-20260826 (frozen by Agent 1)

Frozen at 2026-08-26T01:35:36Z by Agent 1 (lead). Other agents READ this file;
changes only via Agent 1 after a handoff request in `INTERFACE_REQUESTS.md`.

## 1. File ownership (strict)

| Agent | Owned files |
|---|---|
| 1 | AutonomyController.kt, AmaraMemory.kt (+schema), CredentialGuard.kt, SecretScrubber.kt, Redactor.kt, BackendSync.kt, ConversationEngine.kt, FollowUpEngine.kt, MorningBroadcastModule.kt, ActionVerifier.kt, ModuleStateStore.kt, mission ledgers/docs, validation scripts, release manifest |
| 2 | CredentialVault.kt, AgentRuntime.kt, SokoFullIntelligence.kt, SokoIntelligenceModule.kt, MainActivity.kt, credential lifecycle + Soko credential-routing tests |
| 3 | ModelGateway.kt, GroqClient.kt, ModelSchemas.kt, ListingIntelligenceModule.kt, SokoStudioSharingModule.kt, TikTokSkill.kt, VisualListingIntelligence.kt, WhatsAppInbound.kt, ContactDirectory.kt, OverlayService.kt + overlay layout/resources, model/schema/contact/overlay tests |
| 4 | No repo source edits. Device operations + evidence under mission/amara-10-10/evidence-device-AMARA-REL-20260826/ |

Editing another agent's file requires a written handoff in
`coordination/INTERFACE_REQUESTS.md` and Agent 1's approval recorded there.

## 2. Failure-recording contract (Agent 1 provides; single writer rule)

**Single-writer rule:** ONLY `ModelGateway` may INSERT into `brain_failures`.
No controller/module may insert its own duplicate row for the same logical
model failure. Task-level terminal disposition goes to `failure_records`
(device failures) or to the finalize update below.

AmaraMemory (schema v13 → v14) provides:

```kotlin
// Extended columns: correlation_id TEXT, terminal_outcome TEXT, owner_explanation TEXT
fun recordBrainFailure(
    stage: String, model: String, requestId: String, responseHash: String,
    attemptCount: Int, retryable: Boolean, validationErrorsJson: String,
    correctiveAction: String, disposition: String,
    correlationId: String = "", terminalOutcome: String = "",
    ownerExplanation: String = "",
): Long

// Called ONCE per logical task failure by the module/controller that owns the
// task. Updates the newest brain_failures row with matching correlationId+stage
// that has an empty terminal_outcome. Never inserts.
fun finalizeBrainFailure(
    correlationId: String, stage: String,
    terminalOutcome: String, ownerExplanation: String,
): Boolean

data class BrainFailureRecord(/* existing fields */ correlationId, terminalOutcome, ownerExplanation)
fun recentBrainFailures(limit): List<BrainFailureRecord>
fun brainFailuresByCorrelation(correlationId: String, limit: Int = 50): List<BrainFailureRecord>
```

Terminal outcome vocabulary: `TASK_FAILED_NO_SIDE_EFFECT`,
`TASK_FAILED_SAFE_STATE`, `RECOVERED_SCHEMA_VALID`, `AWAITING_OWNER_RETRY`.

`ownerExplanation` is the redacted owner-facing sentence; it must never contain
exception text, stack traces, or provider bodies.

## 3. Correlation threading (Agent 3 implements in GroqClient/ModelGateway)

```kotlin
suspend fun completeJson(prompt: String, schema: ModelSchema?, correlationId: String = ""): JSONObject
suspend fun complete(prompt: String, jsonOnly: Boolean = false, correlationId: String = ""): String
suspend fun completeVisionJson(prompt: String, imageFile: File, schema: ModelSchema?, correlationId: String = ""): JSONObject
```

`ModelGateway.execute(stage, model, allowRepair, correlationId) { ... }` threads
`correlationId` into every internal `record(...)` → `recordBrainFailure`.
Provider request IDs ride the existing cause-chain carrier and never replace
the logical correlation ID.

Callers pass:
- AutonomyController: `"task-$taskId"` (Agent 1).
- Modules (listing, sharing, tiktok, visual, inbound): `"<module>-<runId>"` where runId is
  the module's own durable run identifier; if none exists use a fresh UUID persisted with
  the module's outcome row (Agent 3).

## 4. Exception-leakage rule (all agents)

Never pass `stackTraceToString()`, `throwable.message` raw, or exception
objects to: backend payloads, ModuleStateStore.failure, notifications, chat,
exports, or Log calls. Use a typed constant diagnostic code plus optional
redacted detail through `Redactor.redact()` and secret-shape checks.

## 5. Coordination protocol

- Each agent appends milestones to its own log: `agent<N>_log.md`
  (diagnosis / implementation / targeted tests / cross-review / handoffs).
- Before editing any file, re-read its current content; workspace has no git history.
- Never accept "done" without rerunning/inspecting.
- No ADB/device mutation except Agent 4.
- Temporary probe files must be removed via finally/trap; negative-fixture
  checkers must never be interrupted mid-run.

## 6. Scoped handoffs granted 2026-08-26T01:50Z

- RuntimeStatusBus.kt: Agent 3 may EXTEND it (additive only: new fields/helpers
  for canonical overlay runtime state). No signature removals; AutonomyController
  and MainActivity call sites must keep compiling. Agent 1 reviews.
- ModelSchemas.kt: Agent 3 adds schemas; Agent 1 updates call sites ONLY inside
  Agent-1-owned files (ConversationEngine, FollowUpEngine, MorningBroadcastModule,
  AutonomyController) after Agent 3 publishes the schema constants.
