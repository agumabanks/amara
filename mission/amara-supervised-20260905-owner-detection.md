# OPPO follow-up — 2026-09-05

## Verdict

Service readiness passes; unattended business execution is NOT certified.
This is a targeted follow-up, not a new full-repository certification.

## Completed

- Fixed delayed accessibility event attribution: the service now passes Android's
  event uptime to DeviceActivityMonitor. Events generated before an automation
  boundary ended cannot become fresh owner activity just because delivery was late.
  Events generated after the boundary still count; no blanket grace period was added.
- Added delayed-click and nested-boundary regression tests.
- Ran DeviceActivityMonitorTest (5), DeviceAvailabilityGuardTest (11), and
  AutonomousWorkIntegrationTest (22): 38 tests, zero failures/errors.
- Built and installed version 0.10.0 (13) with `install --no-streaming -r`.
  No uninstall, force-stop, data reset, or direct protected-permission writes.
- Installed/local SHA-256 match:
  `564e7276f7c7689068bd36c8b87559b30bd6da0a824699fff2add9e58b0addc4`.
- Recovery helper and repository device certificate passed on OPPO CPH1933,
  serial `7aef1a4c`: PID 29306; accessibility enabled/bound, not crashed;
  overlay and notification access enabled; battery exemption present;
  AgentService foreground; 57 scheduler matches; recent sampled fatal/ANR count 0.
  Scheduler matches are diagnostic text matches, not 57 distinct schedules.
- Startup logs confirm modules initialized and the existing 10-minute TikTok
  schedule enabled. This is scheduling evidence, not publication evidence.

## Live task evidence and remaining gates

- At 06:31:20 UTC, before this update, TIKTOK_POST_PUBLISH recorded uncertainty:
  the publish tap was accepted but the newest profile post could not be opened
  for caption verification. Do not treat this as either proven publication or
  proven absence. No extra publish was manually requested during this follow-up.
- WhatsApp inbound work was circuit-broken in the 09:31 EAT logs. Earlier exact-chat
  navigation failures remain unresolved; no cooldown reset or new reply canary
  was performed in this follow-up.
- Soko audit/inventory report rejected or unusable secure-vault Terminal PIN.
  Owner must enter a working credential on the device, not in an audit/chat log.
- Jumia capture reports no configured Groq vision model.
- The delayed-event fix is regression-tested and deployed, but it does not prove
  all owner-detection failure modes resolved. Asynchronous UI effects generated
  after a transaction ends can still resemble human activity.
- Next: reconcile the uncertain TikTok publication read-only; repair exact-chat
  navigation; configure Soko access and vision prerequisites; supervise successful
  governed business work before declaring autopilot ready.
