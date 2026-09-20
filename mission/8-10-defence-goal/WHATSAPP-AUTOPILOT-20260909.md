# WhatsApp autopilot and group repair

Live queue investigation found 27 pending rows: 25 WhatsApp group promotions, one TikTok post, and one Soko audit. Repeated logs reported all three work kinds circuit-broken while the governor remained NORMAL. No incoming reply was queued. Phone UI showed master autopilot, incoming replies, follow-ups, groups and 24/7 cover enabled. The app was running; the queue count represented waiting work rather than executable work.

## Changes

- Destination-managed group promotions now use existing per-group failure/uncertainty pauses instead of a channel-wide WA_BROADCAST breaker. Legacy broadcasts retain that breaker. Global halt, permissions, budgets, exact destination checks and per-group schedules still apply.
- Group settings changes wake the loop immediately.
- Group notification identities are discovered while autopilot/replies are off so owners can configure them. Reading bodies still requires Listen; automatic replies still require master, inbound and group permissions.
- Empty work sessions display actual governor waiting reasons instead of the misleading generic “No work was done.”
- Shorter WhatsApp autopilot labels and descriptions; group master correctly says “Group replies & ads.” Group topics/rules are collapsed, with concise delivery/recovery controls.

## Validation

Flutter analysis of both settings screens passed. Targeted Android group, notification, durability and breaker-isolation tests run before deployment. No historical uncertain messages are resent or relabelled as verified. Live group outcomes follow after installation.

## Installed and live

Ten targeted Android tests passed, including historical cross-group breaker isolation, global halt preservation, per-group pause/resume, notification parsing and durability. APK SHA-256 `591f7d2d797217ab36ea895285f441d103caba747d02f33fe807d393324d8ed6` installed successfully; process 24635, Accessibility enabled/bound/not-crashed, overlay allowed, no recent fatal/ANR matches.

After deployment, the queue moved from 27 pending rows to active group execution (24 pending and one in flight at the first snapshot). One group publication is VERIFIED in the side-effect ledger: confidence 0.9, package com.whatsapp, deliveryState sent. The following group showed WhatsApp's explicit no-results destination for `soko.ug`; do not infer a replacement recipient. Each unresolved destination retains its own pause and requires correction. This proves resumed group work and one delivery, not universal group reachability or successful customer replies.

Private evidence: whatsapp-live-queue.tar, whatsapp-live-loop.txt, whatsapp-autopilot-install.txt, whatsapp-results.tar and subsequent outcome snapshots in artifacts/defence-migration-20260909/.

Later inspection shows one VERIFIED group send, one UNCERTAIN photo delivery and one FAILED-before-dispatch missing recipient. Both problematic groups have paused=true independently; another group is IN_FLIGHT. The uncertain outcome is retained without resend. Queue progress is not represented as all-successful delivery.
