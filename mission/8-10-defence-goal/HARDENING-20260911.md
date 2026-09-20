# OPPO overnight hardening — September 11

Read-only observation export: `artifacts/hardening-20260911/initial/`. Hardware serial 7aef1a4c is again connected through USB; battery 63%, Accessibility bound and not crashed. Initial sample had 13,648 events. The phone reported owner activity, so no manual public action or competing UI inspection was attempted.

Confirmed external effects in the sampled window: eight TikTok publications, eleven WhatsApp group ads, one TikTok public comment. Four comment attempts remained uncertain; eight community cycles failed to read the own profile. Three TikTok preparation failures were automatic_sound_not_assigned; two share_foreground and one composer_foreground failures observed com.android.systemui. These are distinct issues; successful builds do not prove all fixed.

Changes in this pass:

- Ignore zero-size Profile labels, and retry the observed tab twice within the existing bounded identity wait. A successful gesture dispatch does not imply navigation completed. Full handle/display identity remains required.
- Preserve an active 24-hour observation file and deadline across APK updates. Record release_updated to distinguish builds without resetting coverage.
- Emit one capacity_reached event instead of silently stopping at the log cap. No automatic ledger deletion or uncertain replay.

Remaining: uncertain comment verification, intermittent profile readiness, automatic soundtrack assignment and foreground interruptions need fresh live evidence. Existing full-suite baseline failures remain documented in RELEASE-24H-20260910.md. This is a bounded hardening pass, not whole-mission completion.

Validation: 42 targeted tests, zero failures/errors/skips; release assembly and diff check passed. Replace-install preserved app data. Installed SHA-256 `4c60a72f9fe31068d1bdd04ae16dad138cdced3a2fb1ad24f4738ae530bd374c` matched the local APK. Certificate: `artifacts/hardening-20260911/certificate.txt`; Accessibility bound/not crashed, overlay/listener/battery exemption/foreground service pass, 58 job matches, zero recent fatal/ANR.

Live export after deployment retained the same `evaluation-1789064293564.jsonl` window and original deadline, with a release_updated event. Both exported files parsed successfully after transfer finished. Fresh post-update comment/profile reliability remains to be established; previous verified posts are historical evidence, not proof of this build's future behavior.

## Follow-up: fresh exports and locked foreground

The host collector previously treated any existing exported JSONL file as evidence that a new asynchronous export had finished. It now waits for a new timestamped Observation export completion record from the DUMP-protected receiver before pulling files, and fails explicitly when freshness cannot be confirmed. Shell syntax validation and a live export passed; summary reports zero malformed lines. No APK replacement was needed for this host-script fix.

Fresh post-update logs recorded six return_to_amara_unverified events and a WhatsApp attachment foreground failure. Read-only dumpsys window showed NotificationShade foreground with isKeyguardShowing=true; Accessibility remained bound/not crashed. This is an observed device availability issue, not enough evidence to weaken destination matching or declare a delivery failure. No lock-screen bypass or uncertain replay was attempted. Screen workflows require an available, unlocked foreground for further verification.

## Recheck: repeated blocked cycles

Phone recheck confirmed Amara foreground and keyguard no longer showing. Latest 1,500 events included 74 circuit-breaker deferrals each for TikTok Story and Soko audit, and 60 for WhatsApp broadcast. These pending items had no durable scheduling delay despite known cooldowns.

Added pending-only scheduling deferral to the existing queue. A blocked kind now waits until its existing breaker cooldown expires; unavailable device work waits one minute and records the specific availability blocker. The update does not reset state or attempts and cannot re-arm completed/in-flight work. Existing consent, quiet hours and uncertainty protection remain authoritative. Regression tests cover delayed eligibility and completed-work protection.

## Operational readiness and owner visibility — September 12

Added an owner-visible operational-health snapshot which combines Android's
validated network state, battery level, charging state, Accessibility binding,
foreground service state, and autonomous-loop freshness. Low battery now warns
at **15% or lower when not charging**, consistent with the owner's USB policy.
The health sweep stores its last observed state, rate-limits the same manager
warning to once per six hours, and queues that warning through the existing
verified WhatsApp work/ledger path when a manager number is configured. A queued
warning is not represented as delivered until the normal WhatsApp verifier
confirms it.

Safe self-healing is limited to starting Amara's own foreground service and
waking its own work loop. It does not force-stop the app, alter Accessibility or
other secure settings, bypass an unavailable network or low battery, or replay
uncertain social/WhatsApp actions. The owner chat now shows the live readiness
card and can run a fresh health check.

Groq configuration now supports a bounded encrypted credential pool through
`groq_api_keys` (newline/comma/semicolon separated), in addition to the two
existing slots. It advances only after 429 or 401 credential failures; malformed
requests are not retried under another key. Key values are not logged, displayed,
or sent to prompts.

Validation: Kotlin debug compilation and targeted `ModelGatewayBehaviorTest`
passed; Flutter analysis of the owner chat and bridge passed. At validation time
the USB ADB list did not include serial `7aef1a4c`, so no live install, manager
message, scheduled group delivery, or TikTok community completion is claimed by
this section.

An optimized continuity-signed release was assembled with
`-PdeviceContinuitySigning` after the normal distribution signing configuration
was unavailable in this environment. SHA-256:
`05f0498ba261bdcbe8ad93847b6d37133460a48ba44ec429beab52c20da4a027`.
It is ready for a data-preserving `adb install -r` when the USB device reconnects;
it has not been installed or device-certified in this pass.

## Deployed operational-health release — September 13

The owner authorized wireless ADB after confirming that the OPPO was on power.
The phone reported AC charging and 63% battery before deployment. Recent logs
contained no Amara fatal exception or ANR; AgentService was foreground and the
Accessibility and notification-listener services were bound.

An initial continuity release was data-preservingly installed, then live logs
showed a `WA_REPLY_INBOUND` circuit-breaker deferral. This was a legitimate
two-minute safety cooldown, not a delivery failure. A final narrow patch adds
the active breaker list to operational health, so the owner can see the hold
instead of assuming Amara is inactive. It neither clears the breaker nor retries
or duplicates the pending message.

Final deployed release SHA-256:
`42bb6bf39ee75913f16fe35352b45be353f7c259097111dffc2737ea4994bbb5`.
The installed base APK matched the hash after `adb install -r`. At certification:
AC charging=true, battery=65%, foreground AgentService, bound Accessibility and
notification listener, overlay allowed, 70 JobScheduler matches, and no fresh
Amara fatal/ANR evidence. Certificate:
`artifacts/hardening-20260911/operational-health-install-certificate-20260913.txt`.

The screen remained lock-protected with the notification shade visible, so the
owner chat UI was not interacted with and no WhatsApp/TikTok action was sent for
testing. This certifies installation and device readiness, not future delivery
or TikTok-community completion.
