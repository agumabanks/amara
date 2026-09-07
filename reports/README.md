# Amara operational reports

Latest Amara-led acceptance evidence: [Task 16 and Soko login blocker](AMARA_LED_ACCEPTANCE_20260905.md).

Start with [SYSTEM_STATUS.md](SYSTEM_STATUS.md) for the current evidence and
[RECOVERY.md](RECOVERY.md) for diagnosis and recovery. Historical implementation
notes remain in [mission](../mission/); they are not proof of current functionality.

Latest content-integrity repair: [TikTok and WhatsApp ingestion](TIKTOK_CONTENT_REPAIR_20260905.md).

Follow-up: [Frozen media, catalogue fallback and WhatsApp navigation](AUTOPILOT_MEDIA_CATALOGUE_20260905.md).

## Certification rules

- COMPLETE: Amara performed the exact workflow, a target-bound verifier confirmed
  its effect, and a controlled repeat/restart and stop-control test passed.
- PARTIAL: some live outcomes are verified, but repeatability or required cases remain.
- BLOCKED: a concrete prerequisite prevents the next meaningful test.
- UNVERIFIED: implemented or unit-tested, but no sufficient live workflow proof.

Never upgrade status because compilation passed, a service is bound, an app opened,
the model said “done,” or a debug command returned without throwing.
Record failures alongside successes. Never store credentials or customer message
contents in these reports. Every run must identify build, date, task, evidence,
remaining gates, and any device/settings changes.

## Next workflow order

1. Owner signs into Soko; command Amara to inspect real inventory read-only.
2. Reconcile uncertain TikTok posts without publishing again; repair verification.
3. Supervise one Soko-media → TikTok publish and a scheduled repeat, checking no duplicates.
4. Validate WhatsApp unanswered takeover, already-answered suppression, exact identity,
   multi-message context, restart recovery, and owner OFF behavior.
5. Only then expand group ads, market-driven actions, and wider autonomy certification.
