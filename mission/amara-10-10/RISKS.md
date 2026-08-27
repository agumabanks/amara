# Risk Register

| Risk | Trigger | Impact | Mitigation | State |
|---|---|---|---|---|
| Terminal session expires | account phone screen appears | Soko work blocked | distinct auth-state report; owner-assisted login | Active |
| ColorOS clears Accessibility binding | app update/reboot | no phone control | live binding health; direct repair walkthrough | Controlled |
| UI layout changes | selector fails/screen signature differs | wrong or stalled action | semantic selectors, learned ranking, recovery, fail closed | Active |
| Duplicate external action | retry after uncertain send/save/post | customer harm | idempotency ledger; never blind-retry side effects | High priority |
| Image meaning unavailable | Accessibility exposes no image semantics | false listing audit | screenshot visual evidence or explicit unsupported result | Active |
| Group sender confusion | notification/chat context ambiguous | wrong reply or privacy leak | sender attribution, group-only context, escalation | Active |
| User and Amara collide | scheduled task while owner uses phone | disruption/wrong screen | user-activity guard and exclusive device queue | Planned |
| Secret leakage | PIN/OTP enters prompt/log/report | security incident | encrypted references, redaction, prompt boundary tests | High priority |
| WorkManager timing is inexact | Android batching/Doze | late task | record window, constraints, occurrence ledger, report delay | Active |
| Protected Android surface | biometric/CAPTCHA/OTP | automation blocked | owner handoff; never bypass | Accepted |
