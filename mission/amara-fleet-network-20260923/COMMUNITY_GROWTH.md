# Community engagement: dedicated C1 workstream inside P1

Purpose: make existing authorized community interactions relevant, reliable and measurable. Growth is an outcome to observe, not a guaranteed follower count. This work adds no mass unsolicited messaging, automatic group joining or new engagement permissions.

Historical window: OPPO TikTok comment work recorded 39 DONE, 37 FAILED, 24 PARTIAL and 7 ESCALATED; TPS recorded 6 DONE, 53 FAILED, 13 PARTIAL and 1 ESCALATED. These are aggregate work outcomes, not numbers of replies or distinct defects. P0 must split own-post inbound replies from discovery/community cycles and resolve what PARTIAL represents. The shared work kind is not enough to infer which path failed.

Reuse `modules/TikTokSocialCycle.kt`, `core/social/TikTokSocialStore.kt`, `TikTokCommentFallback.kt`, `actions/TikTokSocialSurface.kt`, `TikTokSocialControls.kt`, `TikTokProfileIdentity.kt`, existing notification reply logic and SideEffectRunner. Inspect exact current paths before editing; preserve per-creator limits, uncertain reservations and shop-scoped learning already implemented.

## Required funnel

Record eligible enabled opportunity → discovery/read → target identity verified → relevant interaction selected or reasoned skip → authorized dispatch → observed outcome → reconciliation. Each stage needs reason code and timing. One cycle that scanned several posts must expose per-action results; PARTIAL must list completed, skipped, failed and uncertain sub-actions. Stop repeating the failing sub-action while retaining verified ones.

Keep replies anchored to actual visible comment/post content and verified business facts. Respect enabled channels, relevant language, quiet hours, per-target cooldowns and existing caps. Never invent having watched content, claim purchase experience or generate copied generic advertising to increase activity counters. No automatic follow/like/comment action unless already authorized and supported by current settings; feature absence is a scope decision, not a hidden requirement.

## C1 acceptance cases

1. Own-post unread comment versus own previous reply: reply only to eligible unanswered comment; correct author/post/account.
2. Notification opens wrong or deleted post, stale screen, keyboard/modal interruption: rediscover intended target or safely defer; never type into a different target.
3. Accessible and collapsed/custom UI on OPPO and TPS: verified controls and current evidence, no fixed-coordinate assumption.
4. Model fails or content is irrelevant: bounded fallback where appropriate or explicit skip; no invented engagement and no unrelated queue starvation.
5. Community cycle completes one action then fails another: per-action receipts survive; restart does not repeat the successful action.
6. Timeout after submit, duplicate notification, store reservation across restart: retain/reconcile uncertainty and no repeated comment.
7. Per-creator/device/channel limits, quiet hours and owner Off: honor limits; skipped opportunities are not failures or successes.
8. Simultaneous group ad, inbound reply and community work: fair access to screen lease, no interleaved targets.
9. Wrong shop/platform account or account changes mid-cycle: stop external action and show an actionable scoped blocker.
10. Measure outcome honestly: verified replies/comments versus discovered candidates; views/followers only from dated observations, no inferred conversion or fabricated follower growth.

Minimum live canary on each enabled compatible device: one authorized own-post reply, one authorized relevant community interaction, one duplicate-block check and one safe failure/hold demonstration. Fixture tests cover remaining cases; all sample sizes and missing live scenarios remain visible. Feed normalized stage results into P4 telemetry and P5 drill-down, retaining timestamps and proof references.
