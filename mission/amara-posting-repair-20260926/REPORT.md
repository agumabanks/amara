# Posting investigation — 26 September 2026

Inspected OPPO CPH1933 (192.168.1.64:38271) and TPS450M (192.168.1.66:5555). Both were connected with bound Accessibility; installed builds were 85 and 84 respectively. Existing workspace changes were preserved.

## Findings

OPPO current journal: TikTok headline preparation failures, a caption readback failure (`prepare_step_69`), and posting cooldowns. Its last initially sampled DONE was 08:04:59 UTC; a later export proved VERIFIED TikTok delivery at 11:39:07 UTC, before this deployment. This later success is not evidence of a release 86 fix.

TPS current journal: two TikTok share_foreground failures and Soko HTTP 429 incorrectly classified UNKNOWN/FAILED. Model calls also returned HTTP 429.

OPPO WhatsApp groups: 31 sampled recovery attempts held for prior possibly dispatched effects, 12 exact group search failures, and one recipient_not_unique publication failure. Counts are attempts, not unique groups. A stale Terminal shop session was also reported. No new verified group delivery was observed. TPS had no group outcomes in its current sampled journal.

## Applied recovery

Installed the already-built release 86 / 0.10.73 in place on both devices. APK SHA-256: 6b07bc9f1a057dd0920ba418ad83bd6b05750a2fabbd3329457e9a2237f77243. This existing build includes typed Soko rate-limit deferral, hidden-market polling suppression, owner queue preservation, grounded stamp headlines, and the TikTok importer change missing from TPS. No new application source changes were made in this investigation.

Both deployment helpers and full device certificates passed. Installed APK hashes match. Accessibility enabled/bound/not crashed; overlay, notification listener, battery exemption, AgentService foreground all pass. OPPO 84 scheduler matches; TPS 80. Zero sampled recent fatal/ANR. Transport became very slow during APK transfer but both installations completed.

Invoked the bounded CLEAR_PREUPDATE_TIKTOK_BREAKERS recovery hook on each phone. Each journal reports two old cooldowns cleared and no owner tasks released. Existing uncertain transactions were retained. No manual post or message was sent as a deployment test.

## Remaining issues

After deployment, OPPO group recovery still reports unresolved prior dispatch and non-unique exact group search. These are not fixed by this update. Reconcile the actual historical photo receipts and saved group identities before resuming those groups; do not wipe receipts or bypass exact destination matching. Asked owner to open/sign into Soko Terminal if required. TPS manager number is missing, preventing manager updates. New-build TikTok publication is not yet certified; both schedulers are running.

Raw logs, certificate results, and journal exports are in ignored artifacts/posting-repair-20260926/. Focused regression results recorded when finished.

Focused fresh regression run: {'tests': 14, 'failures': 0, 'errors': 0, 'skipped': 0}. Gradle BUILD SUCCESSFUL; git diff --check passed.
