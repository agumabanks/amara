# Owner power, companion and persistent context

Owner requested an off-until-on switch, useful overlay controls, remembered recovery, two-day conversational recall and Soko shared files. Existing unresolved delivery and blocker evidence is in BLOCKAGE-HEALTH-20260913.md; this work must not relabel those as fixed.

Implemented a separately persisted OwnerPower setting which remote config and self-healing cannot change. Loop admission, between-item execution, owner commands, queued command workers, health recovery/alerts, model requests, backend sync and the durable outgoing-action boundary check it. Off preserves queued work and ledgers. An already dispatched action cannot be undone; its verification must settle honestly. Some active atomic operations can finish before their next boundary; this is not a force-stop.

The overlay has owner controls while idle: drag/expand, open Amara, turn off and hide companion. External-action and keyguard phases are untouchable; no protected system prompt is obscured or dismissed. Existing entrance and breathing motion remain; reduced-animation policy stops the pulse. This is not a full-screen cartoon redesign.

ChatStore recalls at most the last two days per conversation, bounded to 200 records per query. Older stored records and relationship summaries are preserved; this is a recall window, not a destructive retention migration. SQLite message/summary writes already use transactions. Unobserved notifications or storage failure can still prevent capture; never promise total recall. RecoveryExperience stores verified outcomes for the bounded navigation strategy. A repeated failure invalidates reuse; prior success never overrides fresh foreground safety classification.

Owner-selected Android document-tree support saves a persisted folder grant and exports prepared WhatsApp/TikTok image/video files with content-derived names. It excludes chats, credentials and ledgers, skips existing content names and removes failed partial exports. The owner must select a folder in Android and choose the same folder from Soko Terminal's import UI. No claim that Soko's private folder or automatic bidirectional edits are enabled.

Validation and installation evidence to follow. Preserve all unrelated preexisting work.

## Validation and live device evidence

Final installed release SHA-256: `8a9744cfd065eb882576924590e27288749619bb74abed1dbb134fceb3dc3397`. Replace-install preserved app data. PID 24611, Accessibility enabled/bound/not crashed, overlay allowed, notification listener and battery exemption present, AgentService foreground, scheduled job entries present and zero recent fatal/ANR matches. Certificates: `artifacts/amara-24h-20260913/companion-final-{install,settled,device}.txt`.

67 final targeted tests passed: 32 outgoing-transaction, 32 overlay lifecycle, 3 owner power/memory. Earlier queue validation passed 46 autonomous integration tests. The preflight power-change regression proves cancellation before dispatch, and the initial-Off regression proves no ledger claim or action. Flutter settings/home/bridge analysis and whitespace checks passed. Initial test fixture issues (AndroidKeyStore on JVM, SDK28 AutoCloseable compatibility) and a missing test import were corrected; no failed run was installed as validated.

Live Settings Off produced checked=false and Home reported: “Amara is off. Queued work is held until the owner turns me on.” Four pending items remained visible. Owner On was restored and checked=true verified. A misleading “loop online” header during Off was corrected in the final build. Evidence: `owner-off.xml`, `owner-off-home.xml`, `owner-on.xml`.

The live companion initially collapsed its recovery controls because FAILED counted as working. Final fix distinguishes blocked/failed attention from moving work. A new regression and live screenshot `companion-final-open.png` prove the Stalled panel remains expanded over the launcher with Open Amara status, Turn Amara off and Hide companion. Open Amara status was tapped, but the subsequent foreground check still showed the launcher; its launch action is not live-verified. Amara was returned to foreground through an explicit activity start. No public message or listing mutation was used for validation.

Still not established: owner folder grant and actual Soko import (owner must choose the working folder), automatic bidirectional Terminal file editing, full cartoon character redesign, complete chat capture under storage/notification failure, manager-warning delivery, Naalya recovery and TikTok comment completion. These remain limits of this release; the whole reliability/social mission is not complete. The persistent background service stays available for owner controls while business work is off; this is not Android force-stop.
