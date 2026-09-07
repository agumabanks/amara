# Amara Supervised Device Testing

Read the section matching the requested depth. This reference covers live-device checks beyond the install helper.

## 1. Build and static gate

Work from the Sanaa-Agent repository root. First inspect `git status --short`; never discard or overwrite unrelated user changes.

Use the repository's current build commands. At minimum, run the Flutter tests and Android JVM tests before assembling the intended variant. A typical debug gate is:

```bash
(cd flutter_ui && flutter test)
./gradlew test assembleDebug
```

Treat `BUILD SUCCESSFUL` and passing Flutter tests as evidence, but report existing Robolectric/SQLite CloseGuard warnings rather than misclassifying them as failures. Locate the APK rather than assuming its path, and record `sha256sum` before installation.

## 2. Safe installation and identity

- Select exactly one `device` state from `adb devices -l`; reject offline, unauthorized, or ambiguous targets.
- Capture the serial, manufacturer/model, and Android release.
- Replace-install with `adb -s SERIAL install -r ABSOLUTE_APK`.
- Never uninstall as a workaround for signing, downgrade, or install errors. Surface the error because uninstalling destroys protected access and app state.
- Start the launcher activity without force-stopping the package.
- Compare the local APK hash to the installed base APK:

  ```bash
  adb -s SERIAL shell pm path co.sanaa.agent
  adb -s SERIAL shell sha256sum /data/app/.../base.apk
  ```

The helper performs this comparison without pulling the large APK from the phone.

## 3. Owner-visible permission recovery

The onboarding readiness gate has exactly five items:

1. Accessibility
2. Keep alive / battery optimization exemption
3. Display over apps
4. Notification access
5. Send notifications (automatically satisfied before Android 13)

On the supported OPPO CPH1933 / Android 11, an APK replacement can drop Accessibility even when other access survives. Use the Android Settings screen. If navigation assistance is already authorized, use human-paced taps and swipes; do not use secure-settings writes or AppOps writes.

Verify state from system truth:

- Accessibility: enabled component plus bound and crashed sections of `dumpsys accessibility`.
- Overlay: `appops get co.sanaa.agent SYSTEM_ALERT_WINDOW` reports `allow`.
- Notification listener: `settings get secure enabled_notification_listeners` contains `co.sanaa.agent`.
- Battery: `dumpsys deviceidle whitelist` contains `co.sanaa.agent`.
- Runtime notifications: Android 11 requires no `POST_NOTIFICATIONS` runtime grant.

Contacts, media access, and the Soko Terminal PIN are feature-specific owner permissions/credentials, not onboarding requirements. Do not grant or enter them during deployment certification.

## 4. App startup and runtime readiness

Opening the dashboard does not prove the agent runtime started. On a fresh setup, the owner must press **Start Amara**. That explicit action records setup completion, starts `AgentService`, and schedules work.

Afterward verify:

- `pidof co.sanaa.agent` returns a PID.
- `dumpsys activity services co.sanaa.agent` shows `AgentService` and the expected Accessibility, notification-listener, and overlay services as applicable.
- `AgentService` is a foreground service, not merely a declared or cached service.
- `dumpsys jobscheduler` contains the package UID/jobs after scheduling settles.
- recent logcat contains no package-related FATAL EXCEPTION or ANR.

Avoid `am force-stop`; it invalidates the very lifecycle behavior being tested and is known to destabilize Accessibility on ColorOS.

## 5. Backend-managed Groq intelligence

Groq keys are backend configuration, not user onboarding input. The intended path is:

```text
backend register/config -> BackendSync.registerAndSync -> SecureConfig.saveRemoteConfig
-> encrypted groq_api_key + groq_api_key_2 -> GroqClient
```

The primary key is used first; one fallback attempt is allowed on HTTP 429. Confirm this wiring with Android tests and a controlled, explicitly authorized model request when required.

Security requirements:

- Never print `.env` values, API response bodies containing configuration, bearer tokens, or encrypted preference contents.
- For backend/Groq connectivity, capture status codes and redacted success/failure only.
- The registration endpoint must return a valid HTTP status such as 200/201; a boolean must never be used as the response status.
- Config sync is best-effort during onboarding. A temporary backend failure must not prevent local permission discovery or setup.
- A local test that proves key import or fallback behavior is preferable to inspecting production secrets.

## 6. Human-like functional checks

Begin with reversible, read-only interactions and observe the screen after each action. Keep a short evidence log of intent, visible result, and system/log result.

Suggested order:

1. Open Amara and verify onboarding/dashboard routing and permission cards.
2. Press **Start Amara** only after all five readiness items pass.
3. Lock/sleep and wake the phone; confirm process/service continuity without forcing lifecycle events.
4. Verify installed target packages for WhatsApp, TikTok, and Soko before attempting integrations.
5. Test notification monitoring with a real inbound event only when a second account/device is available and the owner authorizes it.
6. Test outbound messages, posts, attachments, orders, or Soko writes only under a request that explicitly authorizes that exact external action and target.

A broadcast that merely navigates into WhatsApp, a missing result log, or a successful Accessibility tap does not prove end-to-end messaging. Mark such paths `NOT TESTED` or `INCONCLUSIVE`.

## 7. Final certificate

Report these independently:

- build/tests and APK variant
- local versus installed SHA-256
- serial/model/Android version, package version, and PID
- Accessibility: enabled, bound, crashed
- overlay, notification listener, runtime notifications, battery exemption
- AgentService foreground state and supporting services
- scheduled jobs
- recent FATAL/ANR evidence
- backend config/Groq wiring evidence without secrets
- each supervised feature scenario: pass, fail, inconclusive, or not tested
- owner-only follow-ups and limitations

Do not call the device fully operational when Accessibility is unbound, when **Start Amara** was not pressed, or when required owner action remains.
