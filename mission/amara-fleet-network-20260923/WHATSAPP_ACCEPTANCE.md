# WhatsApp acceptance: W1 and W2

Extend existing conversation, target-matching, work-queue and effect-ledger code. Preserve the owner's recent improvements. Diagnose shop/session churn separately from an actual WhatsApp media-send failure.

| ID | Scenario | Required observation |
| --- | --- | --- |
| W1-01 | Normal inbound, duplicate notification, notification replay after reboot | One logical inbound item and at most one verified reply; no wrong chat |
| W1-02 | Collapsed long incoming message, similar prefixes in two conversations | Complete intended input or explicit inability; prefix match alone never authorizes send |
| W1-03 | Long generated reply, emoji/newlines, model timeout | Persisted complete draft with version/hash; intentional fallback clearly recorded; no silent truncation |
| W1-04 | Switch chat during generation/preparation | Recheck originating chat/message and cancel safely; other chat receives nothing |
| W1-05 | Restart before send / after possible send / before receipt commit | Recover draft safely; reconcile uncertain effect; no duplicate reply |
| W1-06 | Reply needs multiple parts | Persist part order and per-part outcome; resume only proven unsent parts; one failed part does not re-send earlier parts |
| W1-07 | New message arrives during an older pending reply | Deliberate merge/supersede policy with auditable origin IDs; no accidental loss of unanswered requests |
| W1-08 | Stale context, scheduled follow-up or meeting commitment | Correct shop/contact/timezone and cancellation behavior; history survives restart and version update |
| W2-01 | User-selected group/schedule with fresh product data | Exact intended group, selected product, truthful price, full caption and intended media observed |
| W2-02 | Two groups with identical/similar display names; group renamed | Stable verified destination or safe hold; display name alone insufficient |
| W2-03 | Picker/search lag, unsupported/missing media, send control absent | Stage-specific proven pre-dispatch failure; bounded retry; never claim sent |
| W2-04 | Terminal signed out/shop changed/stale catalogue | Correct scoped dependency hold; no wrong-shop product; independent replies continue |
| W2-05 | One group read-only/removed/no posting permission | Hold that group with actionable reason; next eligible authorized group progresses |
| W2-06 | Crash after group A, before group B | A remains completed/uncertain as evidenced; only eligible unsent B proceeds |
| W2-07 | Offline then reconnect, interval/daily cap/quiet hours/Off | No catch-up flood; explain next due time; restart preserves settings and progress |
| W2-08 | Repeated product rotation and already-sent media | Rotation history scoped by shop/group; meaningful product selection; no duplicated opportunity |
| W2-09 | Failure/repair/recovery notification | Manager destination verified; deduplicated alert; WhatsApp unavailable cannot recursively create alert jobs |

For each row, store scenario ID, build/device/platform version, expected and observed behavior, effect IDs, redacted evidence and limitations. Distinguish accepted send, visible outgoing message, server delivery/read status if actually observable, and unknown; never promise a recipient read the message. Use existing transaction/receipt definitions consistently.

“Self-clearing” means resolving a proven recovered condition or superseding uncommitted obsolete work with an audit event. It never means deleting unresolved uncertain sends, clearing all failures, or changing FAILED into success without evidence.
