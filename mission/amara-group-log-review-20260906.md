# Group delivery and completed five-hour evaluation

Window: 6 September 2026, 08:56:46–13:56:46 UTC (11:56–16:56 Uganda). Multiple builds and engineering interventions occurred. This is not five uninterrupted hours on the latest build.

## Observations

- Group promotions: 5 VERIFIED, 10 FAILED. All 10 failed receipts say no external trigger was dispatched. All 10 corresponding saved screenshot filenames identify the recipient-picker stage. Two inspected screenshots explicitly show no search results, including a community-style Announcements destination. Membership, exact saved names, and posting availability need checking; evidence does not justify guessing a different recipient.
- TikTok: 8 VERIFIED posts, 1 UNCERTAIN post; 5 public comments UNCERTAIN. Uncertain actions must not be counted as successful or automatically duplicated.
- Inbound: 12 accepted work items; 1 verified reply, 7 unresolved after supersession accounting. The single measured reply took 5.293 seconds, an insufficient sample for a latency promise. Seven persisted review cases report failure to select a single exact chat; other review cases concern unproven delivery. These preceded the originating-notification fix. The v4 segment has 15 group outcomes and no inbound outcomes, so it does not validate duplicate-name delivery.
- Return to Amara in v4: 14 verified returns, 1 unverified.
- Commercial telemetry: zero recorded inquiries/sales/revenue; commercial policy remained unconfigured. Posting activity is not evidence of earning money.
- Collection: 267 polls, 2 failed journal pulls, 5 process-absent samples. Installation interventions prevent attributing all process gaps to autonomous crashes.

## Changes in group recovery v5

- Preserve the photo workflow's precise stopping stage, with metadata-only evaluation events and readable group settings explanations.
- Pause scheduled promotions immediately for unavailable/ambiguous picker destinations or uncertain delivery; pause after two consecutive other failures. Healthy groups retain their schedules.
- Display pause reason, next eligible time and an explicit resume-after-checking control per group. Resume does not grant new permissions. Unknown delivery needs checking before resumption.
- Re-check due time before executing queued promotions; an old queued item cannot bypass a pause or send ahead of schedule.
- Successful verified outcomes reset the failure streak. Skipped work does not shift the schedule or overwrite the previous delivery result.

These changes stop repeated wasted work and expose the cause. They do not make a nonexistent, renamed or restricted WhatsApp destination deliverable. Saved directory/origin duplicates still need explicit owner reconciliation; name equality alone is insufficient.

Sources: completed collector summary/events, private on-phone transaction/queue database snapshots, and saved failure screenshots. Raw customer content and full contact details are excluded from this report. The original evaluation remains closed at its original end time.

## Validation

54 targeted Android tests passed, including destination failure holds, failure streak reset, permission isolation and cadence; both Flutter widget tests passed, including resuming only the selected paused group. Flutter analysis found no issues. APK build succeeded; installed APK SHA-256: `df7f797bbef31b658d52e1b6f8c4b506401c5b728181abdee1a0ed94cc1aaf37`.

Device certificate passed: running foreground service; Accessibility enabled/bound, not crashed; overlay, notification listener and battery exemption PASS; 66 scheduler matches; zero recent fatal/ANR evidence. No external test messages were sent.
