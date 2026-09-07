# Command-led testing, chat, and error history

## Test method

Issued “Inspect the Soko shop and tell me what needs improving. Do not edit listings
or publish anything.” through MainActivity's debug command ingress, which invokes
the same CommandExecutor as owner chat. Inspected the durable task journal afterward.
No WhatsApp navigation, listing edits, customer sends, or TikTok publication were
manually performed to substitute for Amara's work in this test.

The first shell invocation did not preserve spaces correctly; the complete command
was then submitted as base64 through the existing debug ingress. A transport log
saying Command result: true means no exception, not verified business success.

## Evidence-driven changes

- Amara's journal marked an inspection completed while its own report described
  failed Soko login. Overall task success now requires all recorded steps successful;
  a successful recovery navigation no longer erases an earlier failed business step.
- Reporting prompt labels step outcomes as potentially failed, and distinguishes
  unreadable/unknown inventory from verified zero inventory.
- Owner planner now explicitly handles informal spelling, ordinary business language,
  negation, read-only intent, and one concise clarification for materially missing facts.
- WhatsApp replies no longer use forced regional slang. Instructions prioritize the
  latest question, earlier answers, specific corrections, grounded claims, and honest
  AI identity when asked. Prompt changes alone do not certify conversational quality.
- WhatsApp can reuse an already-open exact conversation. Header resource identity
  plus editable composer is required; matching text in a message body is insufficient.
- Failed navigation records the stage: search surface, input, exact result, or header.
- Owner-command outcomes now enter learning action history. Persisted action details
  and errors are redacted. Work shows an expandable Error History backed by existing
  durable action logs: grouped repeat counts and last-seen times. A later success does
  not erase past errors or falsely mark their cause resolved.

## Remaining gates

- Soko login still not proven accepted after owner credential storage.
- Exact-chat navigation and actual message sending need live target-bound validation.
- Existing learning aggregates outcomes and suggests routines; this is not automatic
  code repair, model training, or proof all features work.
- This run is targeted, not an all-features/end-to-end production certification.

## Follow-up root cause

The step journal showed `soko_full_report` itself marked verified despite every
section being inaccessible. Fixed its unconditional success via typed aggregate
section results. Failure returns for dashboard/orders/customers/refunds/suppliers
now explicitly populate `failure`. Alerts propagate their typed success flag.
Products delegate to the existing checked inventory scanner instead of parsing
whichever screen happens to be open. Home recovery now obtains the injected vault
credential rather than passing an empty string; login outcomes reach the auditor.

## Final verification

- 43 targeted Android tests passed; 18 Flutter tests passed; Flutter analysis clean.
- Installed APK SHA-256:
  `21b5cb9fa5f44b1e95be4090de35a82cbd5c7c1db2ee4087059fe702c9fcf5b6`.
- OPPO certificate passed: PID 28226, accessibility enabled/bound/not crashed,
  overlay, notification access, battery exemption, foreground AgentService; 66
  scheduler text matches; sampled recent fatal/ANR count zero; checksum matched.
- Reissued the complete read-only owner command on the installed build at 11:40 EAT.
  The journal now records `soko_full_report` FAILED and the owner command FAILED,
  with the failure persisted in learning history.
- Verified blocker from the scanner's step result, not only model prose:
  Soko Terminal is signed out at the account phone-number screen. A staff PIN
  cannot unlock it. Owner account sign-in/OTP is needed before further shop testing.
- No inventory count, successful login, publication, or revenue was claimed from
  this failed inspection. WhatsApp end-to-end conversation quality remains unproven.
