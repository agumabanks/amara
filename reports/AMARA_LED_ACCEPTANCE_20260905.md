# Amara-led acceptance run — 2026-09-05

Status: BLOCKED at Soko Staff Login; not a completion certificate.

## Live command and evidence

Installed build at command time:
`9b14609bd1e40cc33e5d60a209cb6c3069e137c2c2e6b5f190225715091f0b61`.
OPPO serial `7aef1a4c`; accessibility bound and crashed set empty before command.

Command passed through Amara's `test_command_b64` ingress to CommandExecutor:
“Check all Soko inventory products. Read only: do not edit, publish, share or send
anything. Report whether account access works and what you actually inspected.”

Device SQLite task journal **16**: status `failed`, phase `report`.
Task step: `scan_soko_inventory`, status `failed`, reported Staff Login.
Codex did not navigate the inventory, submit credentials, send or publish on
Amara's behalf. Later foreground inspection found WhatsApp; the transient login
screen was not independently captured, so the journal is the login-state evidence.

## Diagnostic correction

The old scanner asserted “saved PIN was not accepted” whenever Staff Login remained
visible. This is insufficient to establish credential rejection. Inspection also
found that recovery could submit a PIN on each iteration of a 20-pass loop.
Repair limits submission to one per recovery invocation, refuses blank PINs, and
reports unverified login without asserting the credential is incorrect.
This does not impose a new global cross-task lockout or prove the login selector.

## Remaining gates

- Owner signs in successfully to Soko Terminal or participates in a supervised
  login-screen check. Do not guess credentials or repeatedly submit the saved PIN.
- Ads and payment-link UI access cannot be certified until that prerequisite passes.
- A controlled WhatsApp incoming-message test needs an owner-controlled test sender
  and confirmation of the exact recipient; do not use arbitrary customer chats.
- Published-photo comparison, uncertain-post reconciliation, scheduled repeat,
  restart recovery and OFF suppression remain open.

Diagnostic repair build passed; 11 targeted credential-routing and side-effect
structure tests passed. `git diff --check` passed. These tests do not certify a
successful login or directly simulate a delayed login response on this device.
APK SHA-256: `d6a47dc2875a1059c644fe0cc32fb0e8124b5e7df04a7ac7106be34b2baa22c9`.
In-place installation succeeded. Recovery helper: PID 24492, accessibility enabled
and bound with no crashed service, overlay allowed, recent fatal/ANR matches zero.
Notification listener remains enabled and battery whitelist entry remains present.
No further credential attempt was commanded after task 16.
Repository device certificate also passed: installed checksum matches, AgentService
present and foreground, 61 JobScheduler text matches (not distinct jobs), zero
recent fatal/ANR matches. Service readiness is not workflow completion.
