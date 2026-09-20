# Amara platform reliability mission

Owner: project owner. Implementation and evidence: Codex.
Created: 2026-09-20, Europe/Berlin.
Status: ACTIVE — authenticated catalogue restored; final release and live platform acceptance remain incomplete.

Follow-up execution and handoff: [OPPO recovery mission](../amara-oppo-recovery-20260920/MISSION.md).

## Outcome

Amara reliably prepares, publishes and verifies catalogue ads on TikTok, then reuses the finished TikTok video and soundtrack on YouTube Shorts. Each platform has its own visible settings and truthful activity statistics. Doctor distinguishes current operational faults from historical failures, performs only supported repairs, and verifies repair results. Normal UI layout changes and slow-but-progressing operations should be recoverable without blind publication retries.

This mission is complete only when the acceptance checks below have evidence. A build, accepted tap, dispatched upload, or repaired permission alone is not proof of publication. No claim of universal reliability across every device, language or future app version is made.

## Current owner decisions

- TikTok reliability comes first; implement YouTube settings, diagnostics and stats while external TikTok dependencies are being restored.
- Latest audio choice: reuse the finished TikTok video with its TikTok-added soundtrack. This supersedes the earlier original-ad/YouTube-music choice.
- YouTube needs an independent module section, enable switch, settings and activity statistics.
- Doctor and all existing owner-facing modules need a clear record of what was done.
- Keep this mission and its plan updated as work proceeds.
- Preserve existing app data, unrelated repository changes and permission bindings during updates.

## Status board

| Area | Current state | Evidence / next acceptance check |
| --- | --- | --- |
| TikTok sound flow | Release 33 live verified with limits | Five attached-track selections and verified posts; two missing-window attempts stopped before dispatch. Release 34 builds on this fix |
| TikTok timing and verification | Implemented and installed | Progress deadlines, bounded final dispatch, own-profile/exact-caption verification; final-build live post still required |
| TikTok Stories | Repairs installed; live validation pending | Same content fingerprint, verified source and signed shop binding; verify one eligible Story |
| OPPO readiness | Release 38 installed and service checks passed | 0.10.25 (38) on both phones, matching bytes; TPS feed post verified with sound |
| Fresh catalogue/backend | Live verified on 0.10.18 | Installed app returned 47 active listings for signed shop 708:128; catalogue-check.xml |
| YouTube export | Release 37 live preparation passed | Fresh exact-source TikTok download; video 12.000s, AAC audio 12.006s; channel/details preparation and cleanup passed |
| YouTube module/settings | Implemented; source repair tested | Legacy scrubber altered transaction IDs; release 34 reconnects retained sources to existing receipts. Destination and soundtrack clearance confirmed |
| YouTube UI adaptation | Implemented; live acceptance pending | Semantic stage recognition plus bundled local OCR for missing accessibility labels |
| Platform/module statistics | Implemented and installed | Durable task attempts and separate publication receipts; 24-hour counts and recent outcomes |
| Doctor | Repairs installed; live inspection underway | Refresh fixes and cached backend diagnosis installed; owner-action statistics installed; full final-release inspection pending |

## Acceptance checklist

### TikTok

- [x] Diagnose sound-picker failure using device logs and actual UI.
- [x] Install semantic sound/track checks, progress-aware waits and publication verification improvements.
- [x] Pass focused regression tests and certify installed services.
- [x] Restore authenticated fresh-catalogue access through the intended backend.
- [ ] Observe a complete post on the final release, including exact own-profile caption and attached sound evidence.
- [ ] Validate enabled Stories, rotation, cadence, daily cap and comment/analytics behavior; record individual limits.
- [ ] Confirm slow upload, transient window loss and changed labels/IDs cannot cause false success or duplicate posting.

### YouTube Shorts

- [x] Visible dedicated Settings section with enable/disable, channel, cadence, daily cap, audience/visibility, soundtrack policy and queue status.
- [ ] Durable source queue: only verified TikTok ads, exact source/channel/shop/media identity, restart-safe dedupe.
- [x] Retrieve the finished TikTok video through a supported export flow; retain its soundtrack and full video duration.
- [x] Confirm selected destination channel and cross-platform soundtrack permission before an actual upload.
- [ ] Enter and verify title and full description, including relevant ad links.
- [ ] Handle accessible controls and custom-rendered controls using fresh evidence, without fixed OPPO coordinates.
- [ ] Transaction-bound upload, exact own-channel verification, and no automatic replay after uncertain dispatch.
- [ ] Passing automated tests, installed UI inspection and an authorized end-to-end upload to the configured channel.

### Doctor and statistics

- [ ] Doctor refreshes after repair/review actions and on return from Settings without a stuck busy guard.
- [ ] Doctor distinguishes device internet from backend DNS/authentication/server availability.
- [ ] Recovery actions show requested, attempted and observed results separately; unresolved faults stay visible.
- [ ] Each owner-facing module has activity statistics and recent outcomes; public posting metrics derive from publication receipts.
- [ ] Stats distinguish completed tasks, verified publications, failures, skipped/deferred work and uncertain results.
- [ ] Stats survive restart and updates, with explicit time window and no duplication from retries.
- [ ] Widget/native integration tests and OPPO inspection confirm sections, controls and stats actually appear.

## Completion rules

Use these states: planned → implemented → tests passed → installed → live verified. Use blocked with the specific missing dependency. Never mark a whole feature complete because one utility or its tests passed.

After each meaningful milestone, update this board, PLAN.md and EVIDENCE.md. Record release identity, test command/result, device evidence, remaining uncertainty and the next action. Do not clear historical uncertain-publication records to make counters appear healthy.

## Links

- [Implementation plan](PLAN.md)
- [Evidence and checkpoint log](EVIDENCE.md)
- [Earlier repair report](../../reports/tiktok-repair-20260919/repair.md)
- [YouTube investigation](../../reports/tiktok-repair-20260919/youtube-shorts-design.md)
