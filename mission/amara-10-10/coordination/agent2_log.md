# AGENT 2 LOG — Campaign AMARA-REL-20260826

## Milestone 1 — DIAGNOSIS (2026-08-26T03:45Z)

Scope read: INTERFACE_CONTRACT.md frozen by Agent 1; owned files re-read in full
(CredentialVault.kt, AgentRuntime.kt, SokoFullIntelligence.kt,
SokoIntelligenceModule.kt, MainActivity.kt, CredentialVaultLifecycleTest.kt).

### Findings

F1. Lockout bookkeeping-only (Task 1). `recordValidationFailure/Success` exist on
the vault but NO production login path calls them. PIN leaves via
`AgentRuntime.sokoPin()` → module `pin()` lambdas →
`AccessibilityActions.readSokoNeedsActionBookings/AuditSokoServices/
ReadSokoNeedsActionAlerts(pin)`; results come back as free-text `scan.failure`.
The only mechanical auth-rejection marker in production text is
"saved PIN was not accepted" (AccessibilityActions.sokoAccessFailure, Staff
Login branch). Nothing gates release when `isLocked` — `sokoPin()` happily hands
out a locked credential forever => loop risk against the owner's external
account.

F2. Buffer hygiene (Task 2). `store()` fills the caller's buffer only on the
success path; a codec/persistence throw leaks it. `status()` discards retrieved
buffers without clearing (via retrieve()). Codec seam moves Strings around;
switched to CharArray in/out to cut copies (one unavoidable UTF-8 materialization
inside the keystore codec remains — noted in self-review).

F3. Non-atomic storage (Task 3). store() writes cipher+iv in one edit, meta in a
SECOND edit: a crash between them yields meta-less records (typed today, but
rotation can also leave old-meta+new-payload pairs). No journal/marker exists.

F4. Key conflation (Task 4). `AndroidKeystoreCodec.obtainOrCreateKey` is used by
decrypt: a lost keystore alias silently regenerates a replacement key instead of
failing loudly.

F5. Undifferentiated incompleteness (Task 5). Missing ciphertext and missing IV
share INCOMPLETE_CIPHERTEXT_MISSING.

F6. Parallel PIN authority (Task 6). Three independent producers exist:
  a) `SokoFullIntelligence.readAlerts` constructs
     `SokoIntelligenceModule(config, actions, memory, groq)` WITHOUT pin ->
     falls back to `{ config.sokoTerminalPin }` (plaintext config).
  b) `SokoIntelligenceModule` itself defaults `pin = { config.sokoTerminalPin }`.
  c) MainActivity `capabilityHealth` treats plaintext-config presence
     (`config.sokoTerminalPin.length in 4..8`) as "PIN stored".
  Same insecure defaults also sit in SokoInventoryModule.kt /
  SokoStudioSharingModule.kt — NOT owned by Agent 2 (interface requests filed).
  Migration in `sokoPinFrom` correctly clears plaintext AFTER protected storage.

F7. No mechanical coverage pins down (a)-(c) (Task 7).

### Plan
Vault: Locked retrieval variant (pre-decrypt denial), write-marker journal +
single coherent editor write, distinct cipher/IV reasons, typed Failed store
outcome with guaranteed buffer wipe, encrypt-may-create/decrypt-existing-only
codec split, gate+auditor abstractions. Runtime: single gated authority
(`SokoCredentialGate`) wired into BOTH Soko intelligence paths; migration stays
sole plaintext reader between source markers. Modules: fail-closed blank-pin
default + outcome auditing. MainActivity: vault-only health signal. Tests:
lifecycle extensions + SokoCredentialRoutingTest (behavioral + repo-wide static
scan with documented, dated waivers for the two unowned files).

## Milestone 2 — IMPLEMENTATION (2026-08-26T04:20Z)

CredentialVault.kt:
 - NEW `CredentialRetrieval.Locked`: release denial BEFORE any key access.
 - store(): try/catch/finally — caller's CharArray wiped in finally even when
   encryption/persistence throws; failures return typed
   `CredentialResult.Failed(STORE_FAILED)`; predecessor snapshot taken before any
   durable mutation so a failed store provably leaves byte-identical state.
 - Coherent writes: durable write-pending marker applied FIRST, then cipher+IV+meta
   + marker removal in ONE editor transaction; readers classify marker presence as
   `INCOMPLETE_WRITE_PENDING` (typed torn outcome), meta()/status()/redaction/
   intact-predecessor logic all honor it; invalidate() clears the journal too.
 - Distinct reasons: INCOMPLETE_CIPHERTEXT_MISSING vs NEW INCOMPLETE_IV_MISSING.
 - SecretCodec seam moved to CharArray in/out; decrypt documented+implemented to
   use ONLY an existing keystore key (`KeystoreKeyMissingException` on loss);
   encrypt alone may bootstrap (`obtainOrCreateKey`).
 - status()/redactKnownSecrets() clear every decrypted buffer they touch and
   bypass the lock for health inspection only.
 - NEW `SokoCredentialGate` (lockout-gated submission authority) and
   `SokoCredentialAuditor` (narrow login-outcome seam for modules).

AgentRuntime.kt: gate instance next to vault; auditor object feeding accepted/
rejected outcomes back to the gate; sokoIntelligence AND sokoFull now receive
`pin = { sokoPin() }` + auditor; sokoPinFrom refuses everything for a locked slot
BEFORE reading/migrating legacy plaintext; legacy read fenced between
[SOKO-PIN-AUTHORITY-BEGIN/END] markers as the single sanctioned read site.

SokoIntelligenceModule.kt: pin default is fail-closed `{ "" }`; blank PIN stops
the flow BEFORE authentication with a typed owner-facing refusal;
auditLoginOutcome classifies scan results (accepted / PIN-rejected via static
marker / indeterminate-no-op) into the vault. SokoFullIntelligence.kt forwards
its injected authority into readAlerts' inner module (parallel config-default
construction eliminated). MainActivity.kt: sokoPinStored health comes from the
vault alone.

## Milestone 3 — TARGETED TESTS (2026-08-26T04:25Z)

CredentialVaultLifecycleTest: codec upgraded (CharArray, createdAliases,
decrypted-buffer tracking); IV reason test split; NEW tests: encryption failure
wipes buffers+leaves state typed, status clears decrypted buffers, locked never
decrypted/released (failing-codec trick proves no key access), production
failures increment → fourth submission blocked, success resets+restores,
no-rejection-loop can keep submitting until an accepted login heals,
decrypt never recreates lost alias while encrypt bootstraps, torn-write marker
typed-incomplete over an apparently-valid triple, interrupted partial write
typed-incomplete (stale predecessor NOT served).

SokoCredentialRoutingTest (NEW): static scans — plaintext copy read nowhere
outside SecureConfig declaration + marker-fenced migration (+ dated single-shape
waivers for unowned SokoInventory/SokoStudioSharing defaults, REQ-2.1); every
production construction of the four Soko types injects explicit `pin =`;
defaults proven fail-closed; alerts path forwards injected authority.
Behavioral — fullShopReport/readAlerts draw from injected vault provider;
blank PIN never reaches auth; rejected logins land on vault and lock the fourth
attempt; migration heals partial state or reports it; locked slot refuses legacy
migration; sentinel scan across durable prefs + all produced prose.

BLOCKER: :app:compileDebugKotlin currently fails ONLY in files owned by other
agents (ModelGateway.kt ×9 awaiting AmaraMemory v14 from Agent 1; earlier
GroqClient/ListingIntelligence errors already resolved by Agent 3 mid-wait).
Zero errors in Agent-2-owned sources. Polling patiently per protocol.

## Milestone 4 — REQ-3-01 IMPLEMENTED + VERIFICATION RETRY (2026-08-26T05:55Z)

SokoIntelligenceModule.kt (owned): `proposeTextImprovements` call site wired per
REQ-3-01 — prompt restored verbatim, now passed with
`ModelSchemas.SOKO_SERVICE_TEXT_PROPOSALS` and correlation id
`soko-intelligence-<fresh UUID>` (contract §3 module shape; this module keeps no
durable run row). Success → `BrainFailureFinalizer.markRecovered(memory,
correlationId, schema.name)`; failure → `finalizeFailed(...)` with kind from
`ModelResponseException` (fallback TRANSPORT for pre-condition throws, which are
not model failures). Finalize wrapped in runCatching: telemetry can never break
the proposal path. No other completeJson call sites exist in Agent-2-owned files
(verified by grep).

Verification status this pass:
- `./gradlew :app:compileDebugKotlin` → EXIT 1 at 05:52Z: all 9 errors are the
  known cross-agent blocker inside ModelGateway.kt (Agent 3) referencing
  AmaraMemory.finalizeBrainFailure / brainFailuresByCorrelation — AmaraMemory.kt
  mtime still 2026-08-25 11:38 (v13, old recordBrainFailure signature confirmed
  by read at line 2123). Same blocker Agent 3 records at 05:50Z.
- Cross-checks done while waiting: AutonomyController "remember_soko_pin" stores
  via runtime.vault.store and clears plaintext config (single authority holds);
  MainActivity tail has zero credential references; static-scan waiver lines for
  SokoInventory/SokoStudioSharing unchanged (one latent default each).
- Targeted test runs remain gated behind the same compile task; will retry until
  AmaraMemory v14 lands, then run the two required test classes only.

## Milestone 5 — CONTINUATION: TEST COMPILE FIXES + VERIFY BLOCK GREEN (2026-08-26T07:45Z)

Prior session died mid-implementation: production edits complete, but
SokoCredentialRoutingTest.kt did not compile and the targeted tests had never
run. Agent 1 landed AmaraMemory v14 in the interim (NOTE-2-04 / REQ-3-05 blocker
cleared). This session touched ONLY the two Agent-2 test files + this log — zero
production changes.

Fixes:
1. SokoCredentialRoutingTest `lockedSlotRefusesMigrationOfALegacyCopyUntilUnlocked`
   (~line 336): unresolved `AUTH_CODE` → `SokoIntelligenceModule.AUTH_REJECTED_CODE`,
   the exact static redacted reason production feeds
   gate.recordLoginRejected → vault.recordValidationFailure(id, reason: String).
2. SokoCredentialRoutingTest `noSentinelSecretAppearsInAnyDurableText...`
   (~line 378): SecureConfig exposes no getSharedPreferences — durable-text
   collection now reads the test-mode secret prefs via the Context directly:
   `context.getSharedPreferences("sanaa_agent_secrets_test", MODE_PRIVATE)
   .all.values.filterIsInstance<String>()`.
3. CredentialVaultLifecycleTest stale expectations from the interrupted edit,
   inconsistent with LOCKOUT_THRESHOLD=3 semantics already pinned by two green
   tests (validationFailuresCountLockAtThresholdAndSuccessResets;
   routing lock-the-fourth-attempt):
   - lockSlotViaProductionRejections: rejections 1–2 asserted retryable,
     rejection 3 asserted to return LOCKED_OWNER_REQUIRED (helper leaves slot
     locked, matching its name and all four call sites).
   - productionLoginFailuresIncrementCounterAndFourthSubmissionIsBlocked:
     progression inlined (two retryable rejections → counter==2 → release live;
     third locks; FOURTH submission returns "").
   - encryptionFailureClearsCallerBufferLeavesPreviousRecordAndTypesTheOutcome:
     throwing codec now SNAPSHOTS the plaintext at encrypt time — holding the
     caller's CharArray reference past store() is self-defeating because the
     vault's finally wipes that very array (observed "483920"→"??????");
     expected payload corrected to the actual failing-store input "776611"
     (was SECRET).

Commands + exit codes:
- `./gradlew :app:compileDebugUnitTestKotlin` → EXIT 0
- `./gradlew :app:testDebugUnitTest --tests co.sanaa.agent.core.CredentialVaultLifecycleTest --tests co.sanaa.agent.core.SokoCredentialRoutingTest`
  run 1 → EXIT 1: 37 tests, 5 failed (all CredentialVaultLifecycleTest, above) → fixed
  run 2 → EXIT 0: BUILD SUCCESSFUL

Test counts (JUnit XML): CredentialVaultLifecycleTest 28 tests / 0 failures /
0 errors; SokoCredentialRoutingTest 9 / 0 / 0. TOTAL 37 green, 0 skipped.
No adb, no full-suite/release builds, no commits.

REQ-3-01: SATISFIED (wired in Milestone 4), re-verified this session by source
inspection: SokoIntelligenceModule.proposeTextImprovements calls
`groq.completeJson(prompt, ModelSchemas.SOKO_SERVICE_TEXT_PROPOSALS,
"soko-intelligence-${UUID}")` with BrainFailureFinalizer.markRecovered /
finalizeFailed (both runCatching-wrapped); grep confirms no other completeJson
call sites in Agent-2-owned modules. Nothing outstanding for REQ-3-01 on my side.

Hostile self-review:
- Could still leak: AndroidKeystoreCodec is exercised nowhere in JVM tests
  (Robolectric has no keystore) — every vault test runs injected codecs, so real
  AES-GCM/alias behavior on device is UNPROVEN by this block. Static scans are
  textual: reflection/factory-indirect constructions or a differently-shaped
  default-lambda read would evade them; waivers still allow ONE latent
  `{ config.sokoTerminalPin }` each in SokoInventoryModule/SokoStudioSharingModule
  (REQ-2.1 outstanding, owners requested fail-closed defaults). redactKnownSecrets
  only covers durably configured secrets. auditLoginOutcome classifies via the
  static marker "PIN was not accepted": if terminal copy changes, rejections
  degrade to indeterminate-no-op — counter never moves, so a genuinely bad stored
  PIN could be resubmitted indefinitely WITHOUT soft-lock (fail-open direction of
  classification; owner-visible as repeated failures rather than a lock).
- Unproven: end-to-end device login loop (accessibility + Terminal UI);
  AmaraMemory v13→v14 migration against a real v13 DB (Agent 1's surface,
  compiled-against only here); concurrent gate releases (SharedPreferences
  apply() semantics only, no threaded test); GroqClient network paths behind the
  proposal flow are stubbed by construction in tests.
- Test-only caveat: ReversibleRoutingCodec / ReversibleTestCodec intentionally do
  not clear inputs; buffer-clearing guarantees live in the vault + production codec.
