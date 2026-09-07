---
name: amara-apk-install-recovery
description: Safely build, replace-install, recover, and certify the Amara Android APK on its OPPO test phone. Use for new Amara APK deployments, post-update permission loss, unbound or crashed Accessibility, missing notification/overlay/battery access, or supervised live-device release checks.
---

# Amara APK Install and Recovery

Deploy Amara without wiping app data or bypassing Android's protected permission disclosures, then prove readiness from live device state.

## Safety invariants

- Resolve exactly one target device and one APK before installing.
- Use `adb install -r`. Never uninstall first unless the owner explicitly requests a destructive clean install and accepts loss of app data, encrypted configuration, and protected permissions.
- Never run `am force-stop co.sanaa.agent` on the OPPO. ColorOS can unregister or crash Amara's Accessibility service.
- Never direct-write Accessibility secure settings, notification-listener settings, overlay AppOps, or battery exemptions. Open the owner-visible Android Settings screen and wait for the owner-authorized choice.
- An update may preserve protected access, but this is not guaranteed. No app or skill can preserve it across uninstall, factory reset, or OS policy changes.
- Treat Accessibility as ready only when it is enabled, listed under `Bound services`, and absent from `Crashed services` in `dumpsys accessibility`.
- Do not expose backend tokens or Groq keys in commands, logs, screenshots, or reports. Groq credentials come from backend config sync and are not an onboarding field.
- Do not send WhatsApp messages, publish TikTok posts, place orders, enter a Terminal PIN, grant contacts/media access, or mutate Soko data merely to certify a build. Obtain separate authorization for those actions.

## Standard workflow

1. From the repository root, inspect the worktree and preserve unrelated user changes. Run the relevant Flutter and Android tests before a release build; do not conceal existing warnings.
2. Resolve the device with `adb devices -l` and the intended APK with an absolute path. Record its SHA-256, package, version, and device serial/model/Android version.
3. Run the deterministic helper:

   ```bash
   skills/amara-apk-install-recovery/scripts/deploy_and_certify.sh \
     --serial DEVICE_SERIAL --apk /absolute/path/to/app-debug.apk
   ```

4. When the helper prints `owner_action=...`, tell the user which visible authorization is waiting. Poll the same process at intervals shorter than 60 seconds. Do not replace the owner's action with a privileged settings write.
5. On the app's onboarding screen, verify the five device permissions. The owner must press **Start Amara**; backend-managed Groq credentials must not appear as a sixth setup step.
6. Re-run the helper without `--apk` after settings settle. Retain the earlier local hash for comparison with the reported installed hash, and inspect each service/permission result separately. A run with `--apk` performs the hash comparison automatically.
7. For human-like feature testing or backend-provisioning checks, read [references/supervised-testing.md](references/supervised-testing.md) and use only the applicable sections.

## Recovery and stopping conditions

- If Accessibility is not bound, stop phone-control testing and open Accessibility Settings. On the OPPO path, choose **Downloaded apps > Sanaa Agent**, use the visible toggle, then verify the bound/crashed sets again.
- If the service enters the crashed set, make one visible recovery attempt only. Then collect recent FATAL/ANR evidence and diagnose the bind/start path instead of toggling repeatedly.
- If Android presents a protected confirmation not covered by the current authorization, pause for the owner.
- A running process or enabled switch alone is not certification. Report APK identity, PID, Accessibility enabled/bound/crashed state, overlay, notification listener, battery exemption, AgentService/foreground state, jobs, and recent FATAL/ANR evidence.
- Distinguish `PASS`, `FAIL`, `OWNER ACTION REQUIRED`, and `NOT TESTED`. Never infer end-to-end WhatsApp/TikTok behavior from service readiness alone.
