# Amara supervised OPPO certification — 2026-09-03

## Verdict

The autonomous supervisor, durable queue, safety governor, phone-time budget,
thermal stop, learning recorder, scheduler, UI refresh, and locked-screen device
handoff are linked and running on the OPPO CPH1933. The device demonstrated a
screen-off governed wake, internal work completion, autonomous TikTok launch,
failure verification, durable requeue, learning persistence, and a thermal stop.

Amara is **not yet certified for unattended revenue production**. The orchestration
backend works, but the live business adapters still have unverified or failing
preconditions: no owner timezone/commercial policy, no verified market observations,
Soko returned no verified offerings, TikTok exposed no verifiable comments, and the
WhatsApp master autopilot is off. External send/post canaries were intentionally not
performed without an exact test recipient/account/content authorization.

## Live device certification

- Device: OPPO CPH1933, Android 11, serial `7aef1a4c`
- App: `co.sanaa.agent`, version `0.10.0` (`13`)
- Installed APK SHA-256: `1e981c71a454d3fc17a86fc5162ad32853ed730c693eba15a1310367e2ce4168`
- Installed/local APK identity: PASS
- Accessibility enabled and bound: PASS
- Overlay: PASS
- Notification listener: PASS
- Battery exemption: PASS
- Foreground `AgentService`: PASS
- Scheduler matches: 59
- Recent fatal crashes/ANRs: 0

## Supervised findings and corrections

1. Flutter screens now refresh while mounted and on app resume: Home/Work every 5s,
   Settings every 15s, Market every 30s; Chat already refreshes every 2s.
2. Locked/sleeping phones no longer count as owner-active merely because SystemUI was
   the latest usage-stat package.
3. Passive accessibility window transitions no longer masquerade as human touches;
   clicks, text edits, and scrolls still do.
4. An unlocked Amara foreground created by Amara's own wake no longer forces the loop
   to yield. Other/unknown foreground apps and recent human interactions still do.
5. Screen-required work now passes through `DeviceAvailabilityGuard`; secure
   PIN/password/pattern keyguards remain blocked. A non-secure swipe surface is not
   treated as a credential barrier, matching the OPPO's `deviceLocked=0` evidence.
6. Active sessions now re-check owner presence, battery, and thermal state between
   every item and stop at HOT or battery <=20%.
7. Jiji's cold-start score was raised above the work-loop execution floor so it cannot
   remain permanently wired-but-starved. Jiji and Jumia remain separate sources.
8. A commercial cycle without an owner timezone is now recorded as SKIPPED/policy
   blocked instead of a false success.

## Observed governed execution

- Work-source discovery produced Soko audit/inventory, TikTok comments, Jiji scrape,
  Jumia capture, market analysis, health, and commercial-cycle items in one queue.
- Internal health checks completed through the work loop.
- Market analysis completed and was written to learning memory.
- Commercial planning correctly skipped because owner timezone is not configured.
- From screen-off at thermal status 0, Amara woke the OPPO and launched TikTok herself.
- TikTok comment inspection found no verifiable comments, recorded a failure, and
  durably requeued attempt 1; no reply or post was sent.
- The phone reached ColorOS thermal status 3 during that attempt. The loop stopped
  before executing another queued screen task.

## UI evidence

- `07-work-wake-immediate.png`: state immediately after a governed wake.
- `08-work-auto-refreshed.png`: Work screen updated itself about 7 seconds later with
  the new cycle; no manual refresh was pressed.
- `09-settings.png`: TikTok controls and WhatsApp master state.
- `10-market.png`: Jiji and Jumia surfaces together; currently zero verified offers.

## Automated verification

- Full Android regression before final device-specific adjustment: 695 tests, 0 failures.
- Final targeted safety/autonomy/device tests: 26 tests, 0 failures.
- Flutter analyze: no issues.
- Flutter widget tests: 17 passed, including mounted Work-screen auto-refresh.
- Side-effect boundary: 12 transaction primitives derived; clean across 127 Kotlin files.
- `git diff --check`: PASS.
- Backend memory routes are registered; PHP controller/model/migration syntax is valid;
  the `agent_memory_snapshots` migration is applied.

## Remaining production gates

- Configure the owner's timezone and commercial policy.
- Enable WhatsApp master autopilot only after contacts, SEND grants, and commercial
  consent are populated.
- Enable memory backup only after the device is authenticated to `cards.sanaa.ug`.
- Repair/verify live Soko catalog extraction so it returns grounded offerings.
- Collect grounded Jiji and Jumia offers; current market intelligence has no verified
  observations to analyze.
- Run explicit canaries for WhatsApp sending and TikTok publishing with owner-provided
  test targets/content. Until those pass, 360-degree messaging/posting is not certified.

