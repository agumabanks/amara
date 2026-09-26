# Audit findings and repairs — 2026-09-26

Audit of the working tree at `1cd15aa` plus ~120 modified / 78 untracked files
(154 untracked `.kt`). Prior mission reports were read first so these are
*new* findings, not restatements.

## Fixed in this pass

### 1. Mission gates were never enforced by CI (highest impact)

`.github/workflows/ci.yml` ran only `:app:testDebugUnitTest`, `flutter analyze`
and `flutter test`. The three mission gates ran exclusively inside
`mission/amara-10-10/scripts/run_local_gates.sh`, which a human invokes by hand.

Consequence: the traceability audit was failing with **150 errors** and nothing
was blocking a push. The side-effect boundary and evidence-redaction checks were
green locally but unenforced.

Fix: added a `mission-gates` CI job running
`check_side_effect_boundary.sh`, `check_evidence_redaction.sh`, and the
traceability `negative-fixture` harness self-test. All three are source-only and
need no keystore, device, or release APK.

The full traceability audit is a separate `traceability-audit` job marked
`continue-on-error: true`, because its stale-APK guard needs a signed release APK
whose sha256 is recorded in `EXECUTION_LOG.md`. CI has no signing secret, so
enforcing it there would fail every run. It stays enforced by
`run_local_gates.sh`. This is a real residual gap: the full audit is still a
human-in-the-loop step.

Also added the `dart format --set-exit-if-changed` check that
`run_local_gates.sh` already ran but CI did not.

### 2. Traceability false positive: method references read as dead code

`check_traceability.sh` flags production methods "only called from tests" using
a `\bfn\s*\(` invocation pattern only. Kotlin method references
(`config::saveRemoteConfig`) are invisible to that pattern, so
`SecureConfig.saveRemoteConfig` — live in production at
`api/BackendSync.kt:63` — was reported as dead weight.

Fix: the guard now also matches `::fn`. Verified against the fixture harness.

### 3. Real lock-screen credential leak (found via finding 2)

`CredentialVault.redactKnownSecrets` had zero production call sites, so a
literal credential could reach a notification. Notifications render on the lock
screen and in the shade.

`NotificationReporter.report()` passed `title` and `message` straight through.
Wired the vault in:

- `notifications/NotificationReporter.kt` — added an optional `vault`
  constructor param and a `safe()` scrub applied to title, text and BigText.
- `core/AgentRuntime.kt:77` — vault is now constructed *before* the reporter so
  it can be injected (the previous declaration at line 113 was too late); the
  duplicate declaration was removed.
- `core/work/WorkBlockers.kt:16` — same injection.

`Redactor.redactForExport` alone is deliberately not enough and is not relied on:
shape-based redaction would mangle legitimate owner-facing content such as
"UGX 35,000". Literal vault secrets are the actual risk and are precise.

New regression test: `app/src/test/kotlin/co/sanaa/agent/notifications/NotificationCredentialRedactionTest.kt`
— 4 tests, including one proving ordinary business text (`Self-Inking Stamp —
UGX 35,000`) is left intact, and one proving the reporter still works with no
vault (backward compatibility for existing call sites).

## Open findings — not fixed, need a decision

### 4. `ConversationEngine` is constructed but never invoked

`modules/ConversationEngine.kt` is a 523-line class. It is constructed at
`core/AgentRuntime.kt:248` and held as a field on `WorkExecutor`
(`core/work/WorkExecutor.kt:23`), but **no production code path ever calls any of
its methods.**

Inbound WhatsApp is handled by a *different* class,
`modules/HumanConversationEngine.processMessage`, called from
`core/work/WorkExecutor.kt:509`. That is why the whole `ConversationEngine`
surface — including `pollSoko()` at line 103, the only caller of
`SokoApiClient.unreadMessages()` — is dead in production.

This is not merely cosmetic:

- `SokoApiClient.unreadMessages()` (`api/SokoApiClient.kt:66`) has exactly one
  caller in the entire repo, and it is the dead `pollSoko()`. **Soko inbound
  message polling does not run in production.**
- Rows `CE-CONTACT-AUTH-01` and `CE-CONTACT-PRIV-01` in
  `TRACEABILITY_MATRIX.md` are marked `locally_verified` on the strength of that
  dead path. Those two matrix claims are overstated and should be downgraded
  until the path is wired or the rows are re-scoped.
- `AmaraMemory.conversationHistory()` (`core/AmaraMemory.kt:2166`) is likewise
  test-only; `ChatStore` is the real history authority in production.

Two defensible resolutions, and this needs an owner decision rather than a
guess:

- **Delete** `ConversationEngine` and `pollSoko`, and re-scope those two matrix
  rows. Honest, but drops any future intent to poll Soko messages.
- **Wire** inbound Soko messages into the work loop, which makes the two matrix
  rows truthful and restores a capability the API client already exposes.

I did not choose between these unilaterally.

### 5. No `androidTest` source set

Confirmed: `app/src/` contains only `main` and `test`. Nothing in any workflow,
script, or document references `androidTest`. Every real-device claim (WhatsApp
sends, TikTok posts, Accessibility recovery, 24-hour soak) has zero automated
coverage and is verified only by manual ADB evidence in `mission/`.

Documented honestly in `README.md` rather than papered over. Adding an
instrumented smoke suite for the accessibility send path is real work, not a
config change.

### 6. Unpushed work

`HEAD` is `1cd15aa` (2026-09-20). `git log origin/main..HEAD` returned 0 before
this pass, meaning the tree was committed locally but the remote had nothing
beyond that commit. ~4500 lines of uncommitted-at-the-time work sat in the
working tree. **Not pushed by this pass** — see the handover note.

## Verified good — do not re-audit

- **Money is correct.** All UGX is `Long`; no `Double` money math. Unknown
  margins are typed `OpportunityRanker.ValueBasis.UNKNOWN`, never guessed from
  price. `RevenueMetricEngine.ProfitResult.Unknown` names missing cost
  components instead of reporting revenue as profit.
- **The side-effect boundary is real.** 15 `@RequiresTransaction` primitives,
  clean across 236 Kotlin files.
- **Evidence redaction passes** with no contact names or secrets in evidence text.
- **Keystore was never committed** in any tree in any revision of history.
- The full Kotlin suite passes: **1189 tests, 0 failures, 0 errors, 0 skipped**
  (196 result files). Release build signs correctly with the same signing
  identity (`CN=Sanaa Agent, O=Sanaa Media, C=UG`).

## Keystore relocation

`sanaa-agent.keystore` was at the repo root, mode `0644`, world-readable.

- Copied to `/root/.secrets/sanaa-agent.keystore`, mode `0600`.
- SHA-256 verified identical before and after the move:
  `b01a01bf3cbed57dff28d7cdfcc649a2e8e4cd13fe628617a4874e5f88f1aa0f`.
- Repo copy deleted. Still gitignored, still never committed.
- `app/build.gradle` now resolves the path from `SANAA_KEYSTORE_PATH`, defaulting
  to the secure location.
- `mission/amara-10-10/scripts/run_local_gates.sh` exports the path and fails
  fast with a clear message if the key is missing.
- `RELEASE.md` updated to match.

Release build after the change: signed, `apksigner verify` clean, certificate
SHA-256 `55e237c9c2079f3e413d2b3d00e84a6c577a5c731c837bbefd49f64fd41ea6c4`,
DN unchanged — so in-place device upgrades still work.
