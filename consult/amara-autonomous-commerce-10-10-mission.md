# Amara Autonomous Commerce — 10/10 Mission and Certification Plan

Date: 2026-09-03

## Improved mission prompt

> Operate Amara as an owner-authorized, always-available commerce employee whose objective is to increase verified, sustainable business revenue. Observe demand, prioritize the highest-value eligible work, serve customers through WhatsApp, turn approved Soko catalogue material into TikTok work, learn from measured outcomes, and preserve compact relationship knowledge across reinstalls. Every external action must remain inside the single governed work loop, respect the owner's channel and contact controls, verify the exact target and outcome, stop immediately when the owner disables a channel, yield when the owner uses the phone, and never invent stock, price, delivery, payment, or revenue. Optimize for verified customer value and profit—not message volume, posting volume, or unsafe activity.

“Make money” is an optimization objective, not a guarantee. Amara records inquiries, verified actions, conversions and sales so its scheduling can improve from evidence.

## Architecture decision

WorkManager is now only an alarm clock. Its workers wake `AmaraWorkLoop`; they do not directly send, publish, edit, or transact. The canonical path is:

`wake/event → WorkSource → durable WorkQueue → learned scoring → phone budget → SafetyGovernor → WorkExecutor → SideEffectRunner → verification → learning/outcome ledger`

Owner channel switches are standing authorization, not a safety bypass. Exact-contact permissions, anti-spam limits, owner presence, quiet-hour policy, phone budget, transaction evidence, and the kill switch still apply.

## Ten certification gates

| # | Gate | Status | Evidence / remaining proof |
|---|---|---|---|
| 1 | One execution authority | ✅ Complete | Legacy proactive, briefing and TikTok workers only wake `AmaraWorkLoop`; structural bypass tests pass. |
| 2 | 24/7 durable wake-up | ✅ Complete | Foreground AgentService and Android jobs are live; OPPO battery exemption is active. Android may defer a requested 10-minute pulse under Doze/ColorOS, so cadence is best-effort rather than wall-clock exact. |
| 3 | WhatsApp 360° owner controls | ✅ Complete | Master autopilot, inbound, proactive follow-up, authorized groups, 24/7 inbound, follow-up timing, and per-contact control are visible on device. Disabling a channel removes its pending work and prevents new execution. |
| 4 | Long-lived relationship memory | ✅ Complete | Rolling summaries retain customer stage and activity; raw transcripts are excluded from cloud snapshots. A four-week-old customer remains addressable when their summary remains inside retention policy. |
| 5 | Reinstall recovery | ✅ Implemented | Authenticated, encrypted-at-rest cards.sanaa.ug snapshot API and production migration are live; backup is explicit opt-in and restore merges newer data. Live upload/clean-reinstall restoration still needs an owner-approved data canary. |
| 6 | Soko → TikTok production path | ✅ Implemented | Governed executor obtains approved Soko media/caption, uses the Android share path inside `SideEffectRunner`, and verifies the action. 10-minute through 8-hour choices, daily cap, peak-only/all-day, comments and analytics are configurable. A real public post remains an owner-approved canary. |
| 7 | Market intelligence as work | ✅ Complete | Jiji and Jumia coexist. Scheduled capture feeds comparable listings, price signals, opportunity ranking and work proposals rather than only a static dashboard. Jumia vision export remains consent-gated. |
| 8 | Executable commands/templates | ✅ Complete | 19 department templates, their inputs, gates and recurring schedules are displayed; Run invokes the real workflow backend. Consequential template steps still pause at their declared approval gate. |
| 9 | Evidence-based learning | ✅ Complete | Outcomes update success/timing patterns after sufficient observations; scoring uses bounded factors and memory backup includes learned evidence. Learning cannot silently remove safety policy. |
| 10 | Device and regression readiness | ✅ Complete for non-destructive certification | 692 Android tests, 16 Flutter tests, static analysis and side-effect boundary audit pass. OPPO CPH1933 runs version 0.10.0 (13); Accessibility, overlay, notification listener, foreground service and battery exemption pass with zero recent fatal/ANR events. |

## Owner-approved live canaries still required for a literal 10/10 production sign-off

- Send one clearly identified WhatsApp test conversation through the authorized contact policy, including a reply and one follow-up, then verify delivery and memory recall.
- Publish one owner-approved Soko item to a test TikTok account, then verify the public post and analytics/comment ingestion.
- Enable memory backup, create a non-sensitive test relationship summary, back it up, reinstall, and confirm merge restoration.
- Leave the OPPO idle and powered for a 24-hour soak; verify wake latency, daily caps, owner-yield behavior and no duplicate side effects under ColorOS.

Until those external canaries pass, the honest rating is **9/10 implementation-ready and device-certified**, not a claim of proven unattended revenue production. WhatsApp autopilot and cloud backup intentionally remain off on the deployed phone until the owner opts in; TikTok posting is enabled but all-day mode is off.

## Operating checklist

1. In Settings, grant each customer or group only the required contact capability.
2. Turn on WhatsApp Master autopilot, then choose inbound, follow-ups, groups and optional 24/7 coverage.
3. Turn on relationship-memory backup only after accepting the on-screen data description.
4. Select TikTok cadence and daily cap; use all-day only when that standing authorization is intended.
5. Keep the phone charged, connected and idle. Amara yields while the owner is actively using it.
6. Review Work, Market and verified outcomes daily. Treat projected KES as prioritization estimates until a sale is verified.
