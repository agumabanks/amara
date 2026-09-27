# Amara reliability fixes, receipts, memory and commitments mission

Owner: project owner. Implementation and evidence: coding agents and live-device certification.
Created: 2026-09-21, Europe/Berlin.
Status: IMPLEMENTED AND DEPLOYED AS 0.10.36 (49); CORE TIKTOK/SHORTS PATHS LIVE VERIFIED, EXPLICIT LIVE LIMITS BELOW.

Source prompt: [CODING_AGENT_PROMPT.md](CODING_AGENT_PROMPT.md).
Prior missions: [OPPO recovery](../amara-oppo-recovery-20260920/MISSION.md), [platform reliability](../amara-platform-reliability-20260920/MISSION.md). Newest session evidence supersedes stale boards.

## Outcome

1. Remaining reliability fixes: Shorts dispatch diagnostics and repair, exact-source YouTube publication, TikTok sound/community diagnosis, cadence capture, Doctor/stats, Accessibility recovery, WhatsApp blockers.
2. A durable, owner-visible receipt for every post and send attempt on the canonical `SideEffectTransaction` / `AmaraMemory` ledger — no competing source of truth.
3. Long-term WhatsApp conversation memory beyond the current two-day/20-message window.
4. Durable commitments (meetings, callbacks, reminders) linked to conversation messages with restart-safe, idempotent reminders.

Completion requires evidence per acceptance check. A build or a successful button tap is not proof of publication. Distinguish planned → implemented → tests passed → installed → live verified; use blocked with the specific missing dependency.

## Current authorization (rechecked 2026-09-21)

- Both devices connected: OPPO CPH1933 `192.168.1.64:37207`, TPS450M `192.168.1.67:5555`.
- Both run 0.10.36 (49), continuity-signed APK SHA256 `b6b4c923fa5e8dd3692318f83923c9ffafc394dab3f980b2c4a46c0362fb99d9`; native suite 1,068 passing and Flutter suite 37 passing.
- Existing publication authorization covers the named OPPO YouTube channel `@sanaasanaa1774` with cleared soundtrack, and the previously authorized mission tests. TPS's separate shop/account configuration is NOT authorized by that channel decision.
- New meeting/reminder development does not authorize unsolicited test messages to real customers; use synthetic integration tests and a designated authorized test conversation for live sends.
- Never uninstall or force-stop Amara; never silently grant secure permissions; targeted `adb -s ... install -r` only; preserve continuity signing.

## Status board

| Area | Current state | Evidence / next acceptance check |
| --- | --- | --- |
| Shorts dispatch/publication | live verified on OPPO | Release 46+ reached final dispatch and exact-title/channel/visibility verification on `@sanaasanaa1774`; prior uncertain transaction remains uncertain and was not replayed |
| TikTok feed posting | live verified on OPPO | Owner Chat command and later autonomous intervals reached canonical VERIFIED transactions; sound selections were verified before dispatch |
| TikTok community | discovery live verified; public comment conditional | Release 49 Chat command observed four complete posts and safely skipped all; full caption/current IDs and profile `@sanaamedia` are live verified. No relevant >=0.8 candidate appeared, so public comment delivery is not fabricated |
| TikTok Story | installed; live outcome unresolved | Historical Story remains uncertain and is not replayed; current bounded soundtrack diagnostics remain installed |
| Owner Chat commands | live verified on OPPO | Device status, immediate TikTok post, YouTube Short, and TikTok community commands are accepted through the same governed work queue |
| Cadence | tested and naturally exercised | Autonomous verified TikTok posts occurred; exact original grid deadline capture remains incomplete |
| Receipts | installed | Canonical transaction projection, append-only history, filters/search, per-module links and preservation tests pass; unavailable platform references stay explicit |
| WhatsApp memory | installed; long live customer thread pending | Scoped stable events, dedup, restart/500+ recall, corrections, bounded older evidence and owner controls are implemented/tested |
| Commitments/reminders | installed; real-customer send unverified | Confirmation/cancel UI, durable wakes, revision claims, governed reminder send and no-replay tests pass; no unsolicited customer test was sent |
| Ad rotation | installed | History window derives from catalogue size, interval and cap; unseen-first and repeat spacing apply; only grounded recent market evidence permits early reuse |
| Jobs/analysis UI | installed | Dedicated Overview, Jobs, Modules and Reports views plus receipt and meeting pages |
| Settings version | live verified | Footer exposes version/build and device model; observed on OPPO and release package now reports 0.10.36 (49) |
| Devices | 0.10.36 (49) certified on both | First-install dates preserved; Accessibility bound/not crashed, overlay, notification listener, battery whitelist, processes/jobs and zero recent fatal/ANR pass |

## Acceptance checklist

### Reliability

- [x] Shorts stage/rejection diagnostics distinguish channel, title, full description, audience, visibility, owner state, window readiness and final control failures.
- [x] Failed dispatch guard repaired without guessed coordinates, removed verification gates, or unsafe replay classification; editors cleaned after proven pre-dispatch failure.
- [ ] Exact-source YouTube publication bound to the exact transaction with platform ID/URL when observable.
- [x] TikTok Story/community sound-confirmation failures diagnosed from captured layouts.
- [ ] Two genuine cadence opportunities captured with intended due time, actual start, dispatch and verification.
- [ ] Doctor and module statistics: refresh after repairs/Settings return, backend vs internet diagnosis, requested/attempted/observed repair results.
- [ ] Accessibility recovery validated during a genuine disconnected state when available; untested branches recorded.

### Receipts

- [x] Every attempted effect (including preparation failures before dispatch) has an outcome record across all production send paths.
- [x] Stable action identity linking attempts, side-effect transaction, source post, conversation or commitment; platform ID/URL stored when available, explicitly absent otherwise.
- [x] Append-only history distinguishing prepared, held, dispatched/unverified, verified, failed before dispatch, uncertain, cancelled, expired.
- [x] Receipts survive crashes and concurrent workers; DuplicateBlocked refers to the original receipt.
- [x] Searchable/filterable receipts screen with per-module links and readable explanations.
- [x] Durable storage, safe migrations, indexed/paginated reads, explicit retention/export/deletion.
- [x] Receipts connected to learning modules as evidence-backed lessons.

### WhatsApp memory

- [x] Observed inbound/verified outbound messages persisted with stable event identity, original vs observation time, provenance; notification re-delivery deduplicated.
- [x] Scoped by platform/account/shop/conversation/contact/group; speakers tracked inside groups.
- [x] Recall after 20, 100, 500+ messages and after days/weeks/restarts under a bounded prompt budget.
- [x] Latest confirmed correction supersedes older information; inferred facts stay uncertain.
- [x] Posting/reply continuity: remembered ad/offer, promises, customer asks, next actions.
- [x] Memory writes/retrieval wired into every production reply and follow-up route; owner-visible inspection/correction/deletion.

### Commitments

- [x] Candidate meetings/callbacks/follow-ups extracted; suggestion vs confirmed agreement distinguished.
- [x] Stable commitment ID, scoped participants, type, subject, agreed time, IANA timezone, UTC instant, source messages, confirmation status, revision, receipt links.
- [x] Ambiguous dates/times clarified before external scheduling; "tomorrow" resolved against message time.
- [x] Default reminder five minutes before a confirmed meeting, configurable; recipients distinguished.
- [x] Due commitments integrated with durable scheduler, side-effect transaction boundary; revalidation before sending.
- [x] Reminder claiming/dispatch restart-safe and idempotent per commitment revision and reminder instance; reschedule invalidates old queued reminders.
- [x] Human replies/opt-outs suppress unnecessary follow-ups; isolation enforced.
- [x] Upcoming/due/sent/missed/cancelled/needs-clarification commitments exposed in Amara with edit/cancel controls.

### Tests and deployment

- [x] Existing databases upgrade without losing receipts, transcripts, summaries or settings.
- [x] Concurrent duplicate message observations produce one logical message.
- [x] Interrupted receipt persistence and restart cannot produce another external send.
- [x] 100+ message conversation recalls early agreement after restart, incorporates later correction, excludes other contact/shop details.
- [x] Memory/model failures do not corrupt the last valid summary or invent commitments.
- [x] Confirmed meeting gets one reminder under explicit five-minute policy; ambiguous time gets clarification; reschedule/cancel suppresses old job; reboot/clock/timezone/owner Off/offline/concurrent workers cannot create duplicates.
- [x] Human reply or opt-out suppresses an unnecessary follow-up; proactive policy disabled prevents customer reminders.
- [x] Receipt counts correct across retries, DuplicateBlocked, migration and restart; UI statuses match stored evidence.
- [x] Regressions for each captured Shorts/TikTok failure and the side-effect boundary checker; repository release checks.
- [x] Both devices replacement-installed and certified for installed bytes, Accessibility, overlay, notification listener, battery exemption, process, scheduled jobs and recent FATAL/ANR; app data/first-install continuity survives. Runtime permission differences are retained per-device rather than silently changed.

## Links

- [Plan](PLAN.md) · [Session evidence](SESSION.md) · [Handoff](HANDOFF.md) · [progress.json](progress.json)
