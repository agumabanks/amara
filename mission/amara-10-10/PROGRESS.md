# Progress Dashboard

Last updated: 2026-08-23

## Headline

The device-independent foundations are substantial but not certification-complete. The code includes a typed capability/risk policy, durable approvals/findings/schedules/side-effect receipts, recurring-language parsing, duplicate-occurrence protection, owner-activity guards, human pacing, screenshot evidence, protected-screen handoff, grounded Terminal alerts and Buyer-service audits, visual-analysis provenance guards, and a Work control UI. The 2026-08-23 audit found and corrected mission-ledger drift plus safety gaps in exact approval enforcement, side-effect retry classification, test-command replay, secret defaults, and prompt privacy. The Oppo is disconnected, and the last Terminal state was account sign-in, so no device-facing gate was promoted based on code alone.

### 2026-08-23 Trust Kernel 1.0 progress (Complete-Employee Phase A)

Implemented and locally verified this session; all device gates remain pending because no Oppo is connected:

- **Unified capability catalog** (`CapabilityCatalog`): every capability now carries risk, approval requirement, initiators, idempotency strategy, verifier kind, retry/recovery policy, receipt fields, and planner exposure. The planner prompt, plan guard, and recovery allow-list are derived from it, eliminating duplicated capability definitions.
- **Universal side-effect transaction** (`SideEffectRunner` + `side_effect_transactions` table, schema v6): WhatsApp sends/replies/escalations, WhatsApp Status, TikTok publish, Soko approved edits, Studio shares, follow-ups, and morning-broadcast sends all run claim → act → verify → finalize with persisted state; `acting`/`verification_pending`/`uncertain` work is never auto-repeated, and process death marks orphaned transactions uncertain at startup.
- **Target-bound verification** (`TargetBoundVerifiers` + `SendVerificationLogic`): sends are verified against expected package, exact target chat, normalized content, draft-vs-sent discrimination, and delivery state — replacing text-in-window checks.
- **Approve-and-execute**: the owner can approve and apply an exact Soko edit in one atomic turn; approval consumption remains immediately before save.
- **Prompt-injection controls**: typed trusted/untrusted content envelopes wrap screen text, customer messages, notifications, documents, and stored history; an injection guard scans them and adversarial fixtures pass.
- **Privacy/telemetry governance**: backend logging is opt-in (default off) and every exported string is redacted (PIN/OTP/token/long-digit patterns) via a tested `TelemetryPolicy`/`Redactor` layer; escalation content is redacted too.
- Full local gate passed (95 Android tests, Flutter clean, signed release verified). See `EVIDENCE_INDEX.md` 2026-08-23 entry and `../amara-complete-employee/EXECUTION_LOG.md`.

## Phase chart

| Phase | Code | Local tests | Oppo | Current blocker |
|---|---:|---:|---:|---|
| M0 Mission control | 100% | pass | n/a | none |
| M1 Access and health | 85% | pass | blocked | Terminal account session signed out |
| M2 Phone kernel | 85% | pass | pending | reliability suite needs Oppo |
| M3 Soko intelligence | 55% | parser tests pass | blocked | Terminal sign-in and Buyer coverage |
| M4 Visual intelligence | 75% | pass | pending | labelled screenshot gate needs Oppo |
| M5 Approval and edits | 60% | pass | pending | exact save/reopen mapping needs Oppo |
| M6 WhatsApp | 80% | pass | partial historical evidence | full reliability suite pending |
| M7 Scheduling/proactive | 90% | pass | pending | reboot/exactly-once/soak device gate |
| M8 TikTok | 25% | compiles | pending | installed-version mapping required |
| M9 General phone | 75% | pass | pending | representative app/device gate |
| M10 Certification | 0% | n/a | pending | prior phases |

## Immediate queue

1. Reconnect the Oppo, restore the Terminal account session, and run `OPPO_RECONNECT_RUNBOOK.md` in order.
2. Use captured live hierarchies to finish exact service-edit save/reopen verification and Buyer coverage without guessing selectors.
3. Map the installed TikTok build and validate draft-only creation before enabling any explicit publish path.
4. Complete quiet-hours/standing-policy controls, then run reliability, reboot, and soak gates.

## Rules

- Never convert a device-pending task to verified based on unit tests.
- Never proceed from read-only Soko work to edits before M1-04 and M3-07 pass.
- Every public post, message, destructive change, security action, or financial action follows `SAFETY_POLICY.md`.
