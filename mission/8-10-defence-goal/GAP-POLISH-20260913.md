# Gap audit and conversation polish — September 13

This audit checked the mission register, recent handoffs and the current conversation, commerce, queue, work-screen and Terminal discovery code. Existing uncommitted work was preserved. It is not a claim that every historical request or module has passed acceptance.

## Implemented in this continuation

- Review holds now show the conversation and a redacted message excerpt. An explicit owner choice can close one hold as handled elsewhere or no longer needed. The original reason and disposition remain stored; the dedupe tombstone survives ordinary expiry. Closing a hold never sends, retries, or rewrites the delivery ledger as verified.
- A verified customer reply requiring a manager handoff now has a separate successful delivery outcome. Previously it returned the same escalation result used for unresolved sends, creating an unnecessary review hold. Amara posts a local attention notification even if the subsequent reply fails; manager WhatsApp delivery remains governed by its own queue and receipts.
- A narrow deterministic policy ends repeated acknowledgement exchanges after a closing reply. It does not suppress questions, payment problems or acknowledgements of an outstanding question. These tasks record no reply needed, not a delivery receipt.
- Chat intent cannot set a confirmed-order stage. Summaries retain their newest context when bounded; prompts prioritize corrections, complaints and missing next steps over scripted sales stages.
- Relevant product/service selection now considers the latest question and recent customer context across up to 200 synced rows per type. It no longer always presents the first five of twenty rows. Catalogue selections are explicitly described as cached context, not proof of live stock or complete inventory.
- Delivery estimates do not reuse an old pin after a changed address or invalid replacement coordinates. The assistant answers both parts of a combined payment/delivery question and avoids repeatedly asking for a landmark. Paid/refund/failed-payment messages are left to the contextual conversation instead of triggering another set of payment instructions.
- Work-screen wording is more natural, the Off state is explicit, and review closure has a clear confirmation and failure message.
- Contact refreshes preserve explicit DENY permissions in either record. Previously an incoming ALLOW could undo an owner-disabled operation. Explicit owner reauthorization remains available; this does not merge separate same-name identities.

## Validation

66 targeted Android tests pass: 51 autonomous work integration, 7 commerce policy, 3 commerce assistant, 3 conversation policy and 2 tone tests. New regressions cover durable owner closure with no replay, combined payment/delivery answers, changed addresses, payment complaints, acknowledgement boundaries and relevant catalogue selection. One Flutter widget test proves review closure requires an owner choice and affects only the selected hold. Changed Flutter files and the widget test pass analysis with no issues. These are targeted checks, not a full-suite certification.

The pre-update phone export under `artifacts/gap-polish-20260913/before` shows 78 review holds, 12 pending items and one in flight. The earlier 72 count is historical. No historical hold was closed as part of this deployment test.

The final run passes 81 targeted Android tests, including 12 contact-directory and 3 group-settings tests in addition to the 66 above. The combined final test/release build succeeded in 3m 53s. Logs: `artifacts/gap-polish-20260913/validated-release.log`, `flutter-test.log` and `flutter-analysis.log`. The earlier successful build was superseded by this validated build containing the permission correction.

That build was replace-installed with data preserved: Amara 0.10.0 (13), SHA-256 `803c68d0ac132890e3cefc36dbcda2a8afe338f882cb8ecec51aad22ee30dd96`. Installed hash matches, PID 6402. Accessibility enabled/bound/not crashed, overlay, notification access, battery exemption, foreground service and scheduled-job presence passed; zero fatal/ANR matches in the recent sample. Evidence: `device-certificate.txt` and `install.txt` in the artifact directory. At 07:14:06 UTC the new build was running and WAITING, with 87 review holds and two pending tasks. The short new-build segment contains no external effects. Holds accumulated before this sample; no hold was manually closed.

## Ingestion follow-up

Further source review found two reproducible defects, not yet proven as the cause of every live hold:

- Structured notification messages with no sender fell back to the customer title, misclassifying phone-owner outgoing messages as inbound. These are now rejected; plain legacy notifications still use their title, and older structured sender fields remain supported. Android documents null senders as the current user in [MessagingStyle.Message](https://developer.android.com/reference/android/app/Notification.MessagingStyle.Message). Route refresh now selects an incoming entry even when the bundled last entry is outgoing.
- Queue supersession previously followed arrival order rather than original message time. Newly ingested messages now carry their source timestamp, and an older late-arriving message cannot replace a newer question for the same origin identity, including one already handled. Missed calls no longer erase queued text questions. Historical rows without source timestamps retain their uncertainty; no timestamp was fabricated or migrated from observation time.

Route failures now distinguish expired/unavailable notification routes from a launched chat whose exact title and incoming text could not be verified together. Read-only observation exports include redacted review reasons and owner dispositions to make future triage possible without exporting raw customer messages.

Follow-up validation: 76 tests pass with zero failures/errors (52 autonomous queue integration, 12 contact-directory, 5 notification durability, 4 notification parser and 3 conversation policy). Combined regression/release build succeeded in 3m 58s. Replace-installed SHA-256 `a8d12cb23109ae8815523fd2f2fdb771d6cc385be673cedb0828dfc8c77ffb03` matches the local build; PID 14409. Settled certificate passes Accessibility enabled/bound/not crashed, overlay, notification access, battery exemption, foreground service and scheduled-job presence, with zero recent fatal/ANR matches. Notification access was briefly reported absent and the helper opened Settings, then observed access present without an agent permission toggle. This is a transient observation, not proof of permanent permission stability.

At 07:29:53 UTC there are 87 NEEDS_REVIEW and three PENDING rows; the loop is running and WAITING. Redacted review triage is recorded in `artifacts/gap-polish-20260913/review-triage.json`:

| Recorded reason | Holds |
|---|---:|
| Originating conversation not verified | 44 |
| Exact queued destination unavailable | 38 |
| Delivery uncertain; bubble not visible | 3 |
| Business decision needing owner attention | 2 |

The two business decisions are an order-value approval and a custom service request. Their historical records were not rewritten as delivered; the new handoff outcome prevents the same conflation going forward. Neither the parser bugs nor notification ordering are claimed to explain every held item. All historical holds remain intact. Follow-up artifacts: `ingestion-release.log`, `ingestion-install.txt`, `ingestion-certificate.txt`, and `ingestion-installed/evaluation`.

## Gaps still requiring work or evidence

| Area | Actual remaining gap |
|---|---|
| Historical WhatsApp reviews | Inspect the actual conversation or obtain an owner disposition for each hold. A code change cannot establish whether an old message was delivered. |
| WhatsApp identity | Expired notification routes and same-name identities still require exact evidence. Directory/origin linking remains an unfinished workflow; name guessing is not a repair. |
| Business configuration | Manager number, approved payment methods, delivery rates/coverage and commercial authority must come from the owner or authenticated Terminal settings. A clarification was requested; unconfigured facts remain unset. |
| TikTok | Exact-thread replies and Story confirmation exist, but consistent live confirmation and useful audience outcomes remain unproven. Uncertain publications/comments must not be replayed. |
| Terminal | Protocol v1 exposes discovery metadata. Automatic unsynced inventory access, bidirectional edits and commercial writes are not implemented; they need an authenticated, seller-scoped command contract and receipts. |
| Listings and orders | Listing checklists and order-state monitoring exist. Approved listing edits, enquiry-to-paid-order attribution and operating-cost measurement remain incomplete. |
| Learning and reporting | Persistent learning and manager report work exist; measured improvements and reliable daily report delivery are not established. |
| Device continuity | Sampled service readiness and resumed work are evidenced. Repeated unattended lock recovery, seven-day coverage and owner acceptance remain open. |

The mission decision remains NOT DEFENSIBLE. More natural replies, passing tests and installation do not establish reliable delivery, revenue or a human identity.
