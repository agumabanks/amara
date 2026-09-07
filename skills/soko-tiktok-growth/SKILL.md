---
name: soko-tiktok-growth
description: Prepare, supervise, diagnose, or improve Amara's governed Soko-to-TikTok product posting workflow. Use for live OPPO canaries, posting failures, repeat avoidance, or readiness checks; do not use it for unrelated TikTok content.
---

# Soko TikTok Growth

Keep public posting inside `AmaraWorkLoop`: Soko inventory selection → work queue → safety governor → `WorkExecutor` → side-effect transaction → target-bound verification. A shell/debug receiver may enqueue an owner-authorized canary, but must never perform TikTok UI actions itself.

- Use the installed OPPO and the `amara-apk-device-recovery` skill for deployment/certification. Preserve app data with `adb install -r`; never force-stop Amara.
- Select only active Soko listings with usable media. Avoid every attempted product from the preceding 30 days. When the catalog is exhausted, exclude the most recent third and randomize among the older pool.
- Normalize downloaded product media into a real JPEG and explicitly grant TikTok URI read access.
- On TikTok's share chooser select `Photo`, never `Message`.
- Before tapping `Post`, prove the exact caption is visible in TikTok's editable post screen. Missing caption means stop safely.
- Treat a return to the feed as ambiguous until the profile/newest-post surface proves the expected content. Never retry an uncertain public post, because that can duplicate it.
- Record both attempted products and verified outcomes so future selection and learning use evidence rather than assumptions.

Reusable owner command: `Post a Soko product on TikTok.` For recurrence: `Post a Soko product on TikTok every 30 minutes.` Autopilot and owner-configured caps remain authoritative.

When a live UI mismatch occurs, capture package, activity, accessibility labels, transaction outcome, and screenshot. Make the narrowest selector or media-contract correction, test it, then run at most one newly authorized public canary.
