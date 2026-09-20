# Amara live product assessment — September 13

Verdict: **not ready for broad commercial sale; suitable only for a supervised pilot**. This is a bounded live usability/readiness test, not a seven-day reliability certification or market-demand study.

Test device: OPPO CPH1933, Android 11, serial `7aef1a4c`. Installed Amara 0.10.0 (13), SHA-256 `a8d12cb23109ae8815523fd2f2fdb771d6cc385be673cedb0828dfc8c77ffb03`. Tests used visible Android UI interaction, read-only observation export, service dumps and screenshots. The only submitted test message was an internal Amara status question; no test WhatsApp message, public post, payment, sale or listing edit was dispatched by the tester. Existing autonomous settings resumed after the Off test.

## Live results

| Check | Result | Evidence/meaning |
|---|---|---|
| Launch and render Home | PASS | Live work status, queue totals and navigation visible. |
| Android service readiness | PASS at sample | PID 14409; Accessibility enabled/bound/not crashed, overlay, notification listener, battery exemption and foreground AgentService present. Scheduler entries present; 62 text matches are not an exact count. Recent certificate has zero fatal/ANR matches. |
| Work navigation | PASS | My work opens with pending tasks and expandable 89-review section. |
| Owner Off | PASS | Settings and Home show Off; fresh export reports ownerOn=false and running=false. Three pending tasks and 89 review holds retained. |
| Owner On restoration | PASS | Final export reports ownerOn=true, running=true. |
| Yield to phone use | PASS at sample | Final loop explicitly says it observed the phone in use and yielded to the owner. |
| Basic owner conversation | FAIL | Submitted “Are you online? Answer only. Do not take any phone actions.” Response: “I understand the outcome, but I don’t yet have a safe phone action for it.” Zero verified steps; header says unsupported. A plain status question is incorrectly treated as an unsupported action. |
| Consistent Chat status | FAIL | Chat still displays Active and stop controls while owner power is Off; Home/Settings correctly show Off. |
| Chat usability | NEEDS POLISH | Duplicate Amara headings/stop controls; a large scrollable technical blocker panel consumes much of the conversation viewport. Screenshot records the actual layout. |
| Autonomous delivery reliability | NOT ACCEPTED | Live destination failure observed; 89 historical review holds and three pending tasks in final sample. Service readiness does not establish customer delivery. |
| Commercial outcome proof | NOT ESTABLISHED | Home shows 0 enquiries and 0 sales. No end-to-end paid-order attribution was tested. Attempts/success counters are not sales or a customer-response success rate. |
| Sustained recovery | NOT TESTED | No reboot, unattended repeated lock cycle, multi-day run or deliberate protected-permission revocation was performed. |

Private evidence is under `artifacts/product-test-20260913`: `oppo-certificate.txt`, `home.xml/png`, `work.xml/png`, `settings.xml`, `off.xml`, `off-home.xml`, `off-observation`, `chat.xml`, `chat-response.xml/png` and `final-observation`. Do not publish private screenshots or raw logs as marketing evidence.

## Release priorities

1. Route ordinary owner questions to conversational/status answers instead of requiring a phone-action plan. Reproduce the exact question above after repair.
2. Derive Chat's Active/Off status and stop controls from the same owner/runtime state as Home. Reduce duplicate headings and make blocker details secondary to the conversation.
3. Resolve the observed destination/identity failures with exact evidence; verify controlled customer reply and handoff journeys. Keep uncertain deliveries held.
4. Complete commercial facts, attribution and sustained acceptance testing before promising an autonomous employee for sale.

## Requested Terminal installation on 192.168.1.64:41923

This endpoint is a separate TPS450M device, not the OPPO. Soko Seller Terminal is already installed: version 1.0.4 (8), package `com.soko24.soko_seller_terminal`, installer `com.android.vending` (Google Play).

A data-preserving `adb install -r` of the current 149 MB release was attempted and rejected with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: signing certificates differ. Installed certificate SHA-256: `ee816e0f0c8a9eb2db2030a43803ad5e9d868abbbc510a1877166654a196539b`; local release certificate: `fafee3cb48bcf783f69220f0271f315c93c22dd16fa265892b162687dcebeec0`. Another available release APK has the same incompatible local certificate.

The existing Terminal app was launched successfully and its POS catalogue rendered. Its Google Play listing was then opened to seek a compatible update; Play reports no internet connection. No compatible update was installed. The existing application/data were preserved, and Terminal was reopened after the check. A compatible signed build or working Google Play access is required for a non-destructive update. No uninstall was attempted.

Evidence: `terminal-install.txt`, `terminal-existing.apk` (base APK only, not a full data/split backup), `terminal-package.txt`, `terminal-launch.txt`, `terminal-screen.xml`, and `play-store-unlocked.xml` in the artifact directory.
