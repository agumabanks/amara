# Implementation plan

Status: active. Follow MISSION.md acceptance criteria; report dependencies explicitly.

## 1. Establish current state and unblock fresh ads

- Recheck device serial, installed version, service bindings, master power and current module settings.
- Recheck cards.sanaa.ug DNS from the phone and an independent resolver. If still unavailable, expose the exact fault in Doctor and retain pending work without a false publication failure.
- Obtain restored domain or owner-confirmed backend hostname. Never hardcode an unverified IP, disable TLS checks, or reuse stale catalogue facts to claim success.
- Pull only the recent journal segment and classify remaining TikTok failures by preparation, dispatch and verification stage.

Exit: current blockers documented; authenticated same-shop catalogue read works before live fresh-ad acceptance.

## 2. Build honest module stats and repair Doctor

- Audit Doctor native health checks, repair paths, nightly cleanup and Flutter refresh behavior.
- Fix the observed busy-guard issue: deepRepairGroups and closeAllReviewHolds call load while busy is true, so the status refresh returns immediately.
- Separate passive refresh from repair execution and avoid treating an owner-paused app as a successful repair.
- Add cached, timestamped backend diagnosis without network work on the UI thread.
- Create a shared module activity view from durable task outcomes and publication receipts. Do not count preparation as publication or DuplicateBlocked as a new post.
- Show time range, attempts/completions, verified external effects, failures, uncertain results, queued/held work and recent outcomes as applicable. Doctor additionally shows checks and observed repair results.
- Integrate stats into each current module section and the YouTube section. Inventory all owner-facing modules so none are silently omitted.

Exit: native aggregation tests, widget refresh tests and visible device stats with truthful zero/empty states.

## 3. Finish TikTok acceptance

- Exercise the final sound/composer/profile checks after backend restoration.
- Validate cadence, caps, catalogue rotation, Stories and enabled social features independently.
- Add regressions for newly observed failures only; preserve prior verified/uncertain receipts.
- Capture one final-release feed publication and one eligible Story if enabled, with task-specific authorization and exact evidence.

Exit: all TikTok acceptance checks completed or precisely scoped external blockers documented. Do not label untested features bulletproof.

## 4. Implement YouTube as a separate module

- Add persistent module settings, Flutter section and stats first, alongside truthful readiness/hold messages.
- Add a bounded durable cross-post queue referencing verified TikTok source transactions and exact channel/shop identity.
- Export the finished TikTok media, inspect video/audio tracks and normalize short audio tails without shortening the video or replacing its soundtrack.
- Resolve the native video permission flow through owner-visible Android controls as required.
- Implement semantic upload preparation. For custom-rendered details missing from Accessibility, evaluate local OCR with package/window/freshness checks and unique-control matching; do not ship guessed coordinates.
- Verify configured channel, title, full description, visibility and audience before the final upload.
- Integrate a dedicated work kind/capability with the existing scheduler, screen lease, shop policy, side-effect ledger and non-replay rules.
- Keep platform switches and budgets independent: YouTube failures must not halt TikTok or discard its evidence.

Exit: complete module implementation and meaningful tests for disable, limits, restart, changed account/media, UI drift, duration preservation and uncertain uploads.

## 5. Deploy and certify

- Build a versioned release; preserve signing identity and replace-install with the device recovery skill.
- Verify native services plus actual Flutter Settings/Doctor/stats screens on the OPPO.
- Run reversible YouTube preparation, then an authorized publication to the confirmed channel when its settings and soundtrack policy permit it.
- Record evidence by stage; verify the actual published item and metadata, not just a success toast.
- Restore the intended master-power state and leave no accidental test upload pending.

Exit: final release/device certificate, acceptance evidence, updated mission board and explicit remaining limits.

## Immediate next work

1. Release 0.10.22 (35) installed and certified; 1,016 native tests passed. Preserve source receipts and app data.
2. Shorts preparation passed on the OPPO: channel/details checked, editor closed, no upload dispatched. Actual authorized publication remains outstanding.
3. Obsolete-media cleanup removed one file (0.7 MiB); Doctor repair ran and retained genuine group-workflow holds.
4. Observe final-release TikTok opportunity after its existing circuit-breaker cooldown. Verify attached sound, public contact, publication and subsequent due slots.
5. Validate Accessibility popup when genuinely disconnected; connected On and owner Off states already observed.
6. Keep the [follow-up mission](../amara-oppo-recovery-20260920/MISSION.md) progress and handoff current.

## Pending external facts

- Backend catalogue restored on 0.10.18; continue monitoring configured-host availability.
- Owner confirmed YouTube destination @sanaasanaa1774 on this follow-up; verify it at upload.
- Owner confirmed soundtrack clearance for the final YouTube upload test; visible clearance setting enabled.
- A short idle-device window for reproducible UI testing if the owner is using the phone.
