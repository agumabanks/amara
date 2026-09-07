# WhatsApp failure investigation and repair

## Evidence

The evaluated inbound failed exact-search navigation first, then its next attempt reached the send transaction but finished with `NO_EFFECT_PROVEN: the message content was not found as a sent bubble in the target chat`. The conversation engine collapsed the outcome into a Boolean and returned a generic nonrecoverable failure. The work loop completed its queue entry on escalation. Therefore an empty pending queue hid an unresolved customer.

Read-only navigation of the retained case reached its exact conversation header. A private UI snapshot showed the actual WhatsApp composer, message rows and a Delivered marker. The saved draft did not match a visible message bubble. This is not proof that the draft was never sent; no historical resend is authorized by this observation alone.

## Fixes

- Live send verification polls for up to 12 seconds instead of evaluating one snapshot.
- Missing visible content remains uncertain, never `NO_EFFECT_PROVEN` merely because it is absent from one view.
- Only sent/delivered/read markers verify delivery. Pending, failed and other unknown statuses do not.
- Live WhatsApp verification requires the exact conversation header/composer, complete message text in a message row, and a delivery marker from that same row. It no longer accepts a generic matching line or another message's tick.
- Composer uses exact `com.whatsapp:id/entry`, checks full typed text, waits for the voice-note-to-Send transition, and dispatches only through the exact Send resource. No acknowledgement from the click remains uncertain, not proven non-dispatch.
- Search prioritizes exact contact-name resource nodes, supports the known conversation-row name in its bounds-checked fallback, and checks whether navigation already succeeded before attempting another click.
- Earlier-reply detection requires a valid sent/delivered/read marker, not simply the presence of any status icon.
- Preserve the actual Failed/Rejected/Uncertain explanation rather than flattening it into a Boolean.
- Unresolved inbound work transitions to NEEDS_REVIEW, survives stale cleanup and appears in the queue dashboard, while unrelated work continues. Historical failed case is restored to review without sending.

## Limits

This pass does not claim the historical reply was delivered or that all WhatsApp UI variants are fixed. Exact full-message verification may remain uncertain for collapsed long messages; that is preferable to a false success. This update is a marked intervention in the existing evaluation, not a clean uninterrupted baseline. No customer messages or credentials are included in this report.

## Validation and deployment

78 focused WhatsApp, queue and transaction tests passed with zero failures/errors. `git diff --check` passed. Installed APK SHA-256 `055a8b44a521b19b9cbc09116fed2da6a2b7dad46a8527ed3a5a270ac7cc59f9`, version 0.10.0 (13), PID 25839. Full OPPO certificate: Accessibility enabled/bound/not crashed, overlay allowed, notification listener, battery exemption and foreground service passed; 60 scheduler matches; zero recent fatal/ANR entries. Installed checksum matches the build.

The historical failed case was restored via a shell-protected, no-send hook and verified in the phone database as NEEDS_REVIEW with a persisted reason. Normal operation resumed. New evaluation events identify `whatsapp-deep-v3`. No new reply delivery is claimed from tests or deployment.
