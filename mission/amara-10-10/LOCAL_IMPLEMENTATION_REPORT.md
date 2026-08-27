# Local Implementation Field Report — 2026-08-21

## Outcome

The device-independent part of the Amara 10/10 mission is implemented and locally releasable. A single reproducible gate validated mission bookkeeping, Kotlin behavior, Flutter behavior, release compilation, signing, and APK production. No new Oppo claim was made while the device was disconnected.

## What was built

- A complete mission workspace with ordered tasks, machine and human progress ledgers, scorecard, architecture, safety policy, risks, decisions, walkthroughs, Oppo runbook, evidence rules, and field-report templates.
- Typed phone capabilities, action-risk authorization, protected-screen detection, bounded Observe–Analyze–Act–Report execution, exclusive phone use, owner-activity detection, human pacing, and no-blind-retry verification behavior.
- App-private operational memory for approvals, shop findings, recurring tasks, and idempotent side-effect receipts. Credentials use encrypted preferences; the SQLite operational database is not itself encrypted.
- Natural-language recurring schedules, deterministic next-run calculation, enable/disable controls, and duplicate-occurrence suppression.
- An owner-controlled proactive read-only shop audit every six hours, gated by phone-idle detection and overnight quiet hours; it defaults off and cannot edit or send.
- Screen-only Soko intelligence for Terminal bookings/alerts/services, Buyer-visible services, grounded shop-health findings, and Groq-generated text proposals that never edit without approval.
- Accessibility screenshot capture with precise capability/failure reporting, plus a pluggable visual listing assessment that requires matching evidence and confidence.
- A Flutter **Work control** screen for approvals, findings, and recurring work, in addition to the conversational Amara interface and Phone Access health page.
- Safety hardening: API-era automatic Soko/social workers are cancelled, TikTok defaults to saving a draft rather than publishing, high-impact/security/financial actions require exact fresh approval, and uncertain external actions are not blindly repeated.

## Local test result

Command: `bash mission/amara-10-10/scripts/run_local_gates.sh`

- Mission checker: passed, 52 tasks synchronized.
- Android unit tests: 41 passed.
- Flutter static analysis: zero issues.
- Flutter widget tests: 3 passed.
- Release assembly: passed.
- APK: Amara `0.9.0` (`versionCode 12`), `app/build/outputs/apk/release/app-release.apk`, 21,269,267 bytes.
- APK SHA-256: `ca5ddf9d5b4fdf508f97cd82bd2045641f0cc8ff04e802a270cb8676a678871a`.
- Signature verification: APK Signature Scheme v2 passed; signer `CN=Sanaa Agent, O=Sanaa Media, C=UG`.

## Honest remaining gates

The build is not certified as a 10/10 phone manager yet. The Oppo must restore the signed-out Soko Terminal account, then validate screenshots, pacing, interruption recovery, Buyer coverage, the exact approved edit/save/reopen flow, full WhatsApp reliability, installed-version TikTok drafting/publishing, reboot scheduling, and the 24-hour soak. Exact service-edit selectors are deliberately not guessed from stale evidence; they remain gated behind fresh Oppo hierarchies so Amara cannot modify the wrong listing or field.

## Next field action

Connect the unlocked Oppo with USB debugging, keep it charged, sign into the Terminal account if prompted, and begin at Gate 0 in [OPPO_RECONNECT_RUNBOOK.md](OPPO_RECONNECT_RUNBOOK.md). Create a report from [FIELD_REPORT_TEMPLATE.md](FIELD_REPORT_TEMPLATE.md) for every gate and only promote tasks to `oppo_verified` when the required screen hierarchy, screenshot, and receipt agree.
