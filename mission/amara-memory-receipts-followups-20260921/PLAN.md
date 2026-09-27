# Implementation plan

Status: implementation and device certification complete; explicit live-only limits remain. Follow MISSION.md acceptance criteria; report dependencies explicitly.
State vocabulary: planned → implemented → tests passed → installed → live verified; blocked with the specific missing dependency.

Independent review (2026-09-21): receipts and WhatsApp memory remain partial integrations. Commitment extraction now records candidates without claiming an agreement; owner confirmation, durable due-work proposal, transaction-bound dispatch, and cancellation are implemented with focused tests. Live customer reminder validation remains pending. Finish receipt coverage and learning integration, release/device certification, and unresolved reliability acceptance items. See the latest SESSION checkpoint.

## 0. Reconcile evidence and map production paths (done at mission start)

- Read prior mission boards, SESSION, HANDOFF, LESSONS, progress.json; newest entries supersede stale boards.
- Baseline rechecked: both devices online at OPPO `192.168.1.66:38615` / TPS `192.168.1.67:5555`, both 0.10.26 (39), SHA256 `ce4453cf…`, native suite 1,033 passing.
- Mapped: Shorts dispatch chain, receipt/ledger foundations, WhatsApp memory + engines, durable scheduler. Findings recorded in SESSION.md.

## 1. Shorts dispatch diagnosis and repair (highest-priority concrete fix)

- Diagnosis (from source + live timing): release39 task `54135783…` reached ACTING 1789941440273 → FAILED 1789941443152 (2879 ms) with "external trigger was never dispatched". `ShortsDeviceSurface.dispatch()` never journals a stage, and its description re-entry guards (lines 128/129) are false whenever `prepare()` already filled the description: after returning from the description editor the row shows the entered text, not the `Add description` placeholder, so `tap("Show more")`/`tap("Add description")` find nothing. The final `clickExactLabel("Upload Short")` also has no OCR fallback or diagnostics.
- Fix: journal `youtube_dispatch_rejection` reason codes (channel/title/description/audience/visibility/owner/window/control), skip the description re-entry when the description is already filled and visible, keep the fill-and-verify path when the placeholder is present, add fresh uniquely-matched local OCR fallback for the final upload control, and record selector outcomes for it.
- Cleanup: `ShortsPublisher` discards the editor after a proven pre-dispatch failure (Failed = never dispatched); preserves editor evidence for UNCERTAIN.
- Regressions: pure guard object + captured-label-list tests reproducing the release39 failure shape; publisher cleanup behavior.
- Exit: tests pass; then live re-verify on device (authorized channel test) — separate live-verified state.

## 2. Canonical receipts integration

- Build on `SideEffectTransaction` / `AmaraMemory` ledger and `ModuleActivityStore`; no competing source of truth, no revival of legacy receipts tables as authority.
- Inventory publication/send paths; every attempted effect (including preparation failures before dispatch) gets an outcome record.
- Stable action identity linking attempts, transactions, source post, conversation or commitment; platform ID/URL when observable, explicitly absent otherwise.
- Append-only state/evidence history; crash/concurrent-safe; receipts screen with per-module links; retention/export behavior; learning-module aggregation.

## 3. WhatsApp long-term memory

- `ChatStore.getChatHistory` currently excludes messages older than two days (48h cutoff, limit 1..200); summaries exist; no confirmed-facts/commitments tables beyond `ConversationKnowledge` preference facts.
- Persist observed inbound/verified outbound with stable event identity, original vs observation time, provenance; dedupe re-delivery; scope by platform/account/shop/conversation; incremental summaries + structured facts + older evidence under a bounded prompt budget; wire into every production reply route.

## 4. Commitments, meetings, reminders

- No commitment/reminder scheduler exists today (workflow_runs rows + free-text next steps only). Implement durable commitments linked to conversation messages, IANA timezone + UTC instant, confirmation status, revision, reminder lead time (default 5 min), integrated with the durable scheduler and side-effect transaction boundary; restart-safe, idempotent per commitment revision/reminder instance; UI with edit/cancel controls.

## 5. End-to-end validation and deployment

- Meaningful tests (see MISSION checklist), then continuity-signed release, targeted `adb -s ... install -r` on BOTH devices, certification per the device recovery skill, app-data survival, updated boards.

## Dependencies and sequencing notes

- Area 1 is independent and started first.
- Areas 2–4 share the ledger exploration; implemented in that order because receipts feed memory/commitment links.
- Live device validation happens after the release build; live-verified states are recorded separately from implemented/tested.
