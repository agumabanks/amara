# Safety and Approval Policy

## Risk levels

| Level | Examples | Default policy |
|---|---|---|
| Observe | Read screens, inspect listings, collect booking/order status | Allowed when requested; proactive only under enabled audit policy |
| Recommend | Draft listing changes, captions, replies, follow-ups | Allowed; no external change |
| Low impact | Save approved text correction, send standing-policy routine update | Requires explicit approval or matching standing policy |
| External communication | WhatsApp reply, group message, Status, TikTok draft/publish | Explicit request or narrow standing policy; target/content verification required |
| High impact | Price/image/publish-state change, booking cancel, delete, refund, purchase | Fresh approval for the exact action |
| Security/identity | Account login, OTP, biometric, password reset, permission grant | Owner-assisted; never guessed or bypassed |
| Financial | Payment, transfer, refund, checkout, paid promotion | Fresh approval plus amount/payee confirmation |

## Approval requirements

- An approval includes the exact target, proposed change, risk level, expiry, and evidence.
- “Approve” applies only to the referenced pending request.
- Materially changed proposals invalidate old approval.
- Standing policies are narrow: app, action, targets, time window, frequency, content constraints, and expiry.
- An owner can stop the current task immediately; no queued side effect may execute afterward.

## Privacy

- Do not place PINs, passwords, OTPs, tokens, private chat history, or unrelated customer data in model prompts or reports.
- Group replies use group-visible context and never leak private direct-message memory.
- Screenshots and receipts remain device-local unless the owner explicitly exports them.

## Failure behavior

- Report the observed state, failed expectation, safe attempts, and exact owner action needed.
- Never infer “sent,” “saved,” “published,” “paid,” or “cancelled” from a tap alone.
- Never retry a potentially completed external action unless independent evidence proves it did not happen.
