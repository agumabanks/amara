# Sanaa Media: engineering hardening and five-hour evaluation

## Scope

First client: Sanaa Media, confirmed by owner. Owner reports it is signed into Soko; verify current device state rather than carrying forward an old sign-in blocker. Weekly paid-order/profit acceptance target is awaiting owner input. Five hours is an observation window, not proof of an 8/10 rating or a controlled revenue experiment.

## Changes

- Timing learning now uses only samples from the actual local-hour bucket and exposes sample counts. `TIMING_V2` avoids relying on old incorrectly labelled patterns. Existing patterns update their conclusion as evidence changes. These are execution reliability observations, not sales conversion estimates.
- Queue completion/requeue happens before fallible outcome analytics. Reporting errors are isolated, recorded, and cannot strand an already finalized item. Cancellation still propagates.
- Follow-up reads and external-action/verification boundaries propagate cancellation. An interrupted dispatch remains uncertain and cannot be blindly retried.
- Promotion rotation retains unseen-product exploration and product/service diversity, and can prefer offerings with admitted qualified-inquiry evidence. This does not invent margin or audience-fit data that is absent.
- Uncertain TikTok comments get bounded read-only reconciliation (one candidate, 45 seconds, at least six hours between checks of the same claim). Evidence is stored separately; checking does not re-arm the send or rewrite the original uncertain transaction.
- Reply dashboard adds a verified-outcome p95. The evaluation additionally observes inbound notifications, queue offers, accepted/capacity-rejected work, supersession, starts, retries, dispositions and outcomes so missing replies do not disappear from the denominator.
- External-action journal events distinguish ACTING from VERIFIED, FAILED and UNCERTAIN. A completed scheduler task is not counted as a published post.

## Logger

`EvaluationJournal` stores a timestamped JSONL file in Amara's private phone storage. Owner-started window expires after five hours and survives process restart. Records contain hashed work identifiers, statuses, timing and numeric business/device counters; no customer message bodies, contact labels or credentials.

`scripts/evaluation/collect_five_hours.py` copies that journal into private server storage each minute, writes collector availability evidence, and refreshes `summary.json`. It checks whether Amara's process exists before requesting health; it does not launch UI, send messages or restart a dead app. Failed reads preserve the last good journal and are counted as collection gaps. At the five-hour deadline it writes the final summary and exits. Phone-local logging continues through bridge outages until its own deadline.

Reports distinguish work completion, verified external effects, unverified inbound work, latency and collector gaps. Commercial baseline/latest totals retain their original reporting periods; changes are not automatically attributed to Amara. Superseded messages have a disposition and their replacement still requires verification. Low traffic, missing cost inputs and unobserved outcomes remain limitations.

## Remaining evidence requirements

A live paid-order attribution chain, profitable operation, broad WhatsApp navigation coverage, successful reconciliation of previously uncertain TikTok comments, and recovery across real OS/network failures require runtime evidence. Passing tests and installing the logger do not establish those outcomes. Neither unrestricted autonomy nor a survival metaphor substitutes for these measurements.

Deployment identity, test totals and evaluation start/end times will be appended after verification.

Read-only Soko check on the existing OPPO session returned `homeVerified=true` at device log time 11:26:32. Soko sign-in is therefore no longer carried as an observed blocker. This verifies access to home, not a new listing write or paid order.

Evaluation interpretation: owner-active samples are observed phone use, not a count of rescues. Rescue count stays unknown unless interventions are separately annotated. The collector also checks Accessibility binding periodically and flags a stale phone journal.

Validation: final debug APK build passed. 84 focused Android/JVM tests passed with zero failures/errors, including cancellation-after-dispatch, reporting isolation, timing-bucket correctness, journal restart/deadline behavior, social reconciliation cooldown and demand-aware rotation. Two Python evaluation-summary tests passed. `git diff --check` passed. These are focused regression tests, not a full unattended acceptance run.

## Deployment and active window

Installed APK SHA-256: `eb624f051c985c9cd822939ef47a74419cf23501a70fc96875c47371838f4fe0`. OPPO CPH1933 serial `7aef1a4c`, version 0.10.0 (13), PID 26619. Installed checksum matches the built APK. Full certificate passed: Accessibility enabled/bound/not crashed, overlay, notification listener, battery exemption, foreground agent service; 62 scheduler matches; zero recent fatal/ANR entries.

Window: 2026-09-06 08:56:46 UTC to 13:56:46 UTC (11:56:46–16:56:46 Uganda time). Service: `amara-evaluation-1788685006698.service`, active. Evidence directory: `/var/lib/amara-evaluation/1788685006698`. First journal copy and phone health sample received; first collector poll had no fetch failure and journal was fresh. Inspection pause cleared.

The certificate overlapped the very beginning of the journal window; initial startup/inspection events should be treated as supervised baseline, not unattended performance. Normal operation was restored before this report entry. The five-hour results are not yet complete.
