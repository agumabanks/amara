# Amara diagnosis and recovery

## Before changing anything

1. Read SYSTEM_STATUS.md and the latest mission record. Identify installed APK versus
   current source. Preserve app data, work queue, transaction ledger and dirty worktree.
2. Verify exact device with `adb devices`. Read `dumpsys accessibility`: enabled alone
   is insufficient; Amara must be bound and absent from crashed services.
3. Inspect foreground AgentService, notification listener, overlay, battery and jobs.
   Use the non-destructive certificate script under
   `skills/amara-apk-install-recovery/scripts/deploy_and_certify.sh` after reading it.
4. Read task journal and step journal together, then side-effect transaction receipts
   and learning action errors. A completed queue row can mean dropped/escalated work;
   it is not proof of success. `Command result: true` in the debug log means no thrown
   exception, not `CommandResult.success == true`.

## Symptom map

| Symptom | Check | Recovery / verification |
| --- | --- | --- |
| Waiting, nothing useful happens | Latest loop summary, due times, pending queue, settings, device health, error history | Identify actual blocker; do not simply reset counters. Confirm later verified task result. |
| Soko unavailable | Exact scanner failure and account-vs-staff-login surface | Account sign-in/OTP is owner action. Staff PIN is entered through Settings → Soko access. Never log PIN or repeatedly guess. Rerun read-only inventory via Amara afterward. |
| TikTok uncertain | Existing transaction state/content key and profile-verifier evidence | Inspect/reconcile read-only. Do not mint a new key or delete ledger to bypass uncertainty. Preserve exact caption binding. |
| WhatsApp wrong/unknown chat | Navigation stage, canonical target and header/composer evidence | Fix selector with live hierarchy evidence. No partial-name or message-body match, no arbitrary test recipient. |
| Reply provider HTTP 400 | Model gateway stage/config and redacted failure classification | Verify configured model/schema request. Do not persist raw payloads/customer messages or blindly retry permanent rejection. |
| Market shows no useful data | Grounded observations and model preflight | Fix extraction/configuration first; a generated dashboard is not successful market intelligence. |
| False success | Typed section failures, step outcomes, report/journal status | Reproduce with failure fixture; require failure to propagate to task and learning record. |

## Safe deployment

Use the `amara-apk-device-recovery` skill. Build/test first; record checksum.
Install in place using `adb -s 7aef1a4c install --no-streaming -r /absolute/path/app-debug.apk`.
Never uninstall or force-stop on this OPPO. Never directly write accessibility/AppOps.
Run the recovery helper without APK afterward, then the repository certificate.
If protected owner confirmation is required, stop and ask. Keep prior build evidence.

## Command-led certification

Use owner chat or its existing debug `test_command_b64` ingress to CommandExecutor.
Encode the complete command so shell spacing does not truncate it. Give read-only
scope explicitly where intended. Agent-controlled execution must produce a journal
and target-bound evidence; Codex must not complete the external task manually.

For every certified workflow record: build checksum, command/settings, expected
result, task/receipt IDs, actual result, failure path, restart repeat, and owner-OFF
behavior. Remove private message bodies and credentials from exported artifacts.

Passing tests supports a repair; it never substitutes for these live workflow gates.
