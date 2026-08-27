# Sanaa Agent

Android/Flutter agent runtime for the dedicated Oppo device. Amara combines an owner-facing work UI with bounded planning, Android Accessibility skills, durable task records, approval controls, and device-local operational memory.

## Project status

Amara is an advanced prototype, not a certified autonomous employee. Local code and UI gates pass, while core device gates—exact approved edits, full WhatsApp/TikTok reliability, restart behavior, and a 24-hour soak—still require the Oppo and real-screen evidence. See the [current progress](mission/amara-10-10/PROGRESS.md), [2026-08-23 repository audit](mission/amara-10-10/AUDIT_20260823.md), and [Amara Complete Employee mission](mission/amara-complete-employee/MISSION.md).

## Current foundation

- Native Kotlin Android host with a thin Flutter UI over `com.sanaa.agent/core`
- Oppo-focused onboarding for Accessibility, battery exemption, overlay, notification access, and notification delivery
- Sticky foreground service, boot receiver, Accessibility service, and notification listener
- Groq `openai/gpt-oss-120b` connectivity test with a dynamic system prompt
- API key and endpoint storage backed by Android Keystore encrypted preferences
- C++/JNI message-parser and drift-safe scheduling entry points
- ARM64-only release packaging for the Oppo deployment target

## Trust boundary

- Model output can select only registered capabilities; it cannot issue raw taps or coordinates.
- External side effects require explicit intent, exact targets, post-state verification, and no blind retry after uncertainty.
- Soko edits require an unexpired approval matching the exact listing, field, and value.
- API credentials and the Terminal PIN use encrypted preferences. Operational history uses the app-private SQLite database and is not described as encrypted at rest.
- OTP, biometric, CAPTCHA, account-login, and protected permission screens remain owner-assisted.

## Build

```bash
cd flutter_ui
flutter pub get
flutter analyze
flutter test
cd ..
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

For a signed release build, export `KEYSTORE_PASS` and `KEY_PASS` from the parent deployment `.env` into the Gradle process environment first. The passwords and keystore are intentionally ignored by source control.

The debug APK is intentionally large because Flutter includes JIT debugging assets. Use the release APK for the under-25 MB device package goal. Release signing must be configured before distribution.

## Oppo device check

Install with ADB, open the app, and complete each setup card. In ColorOS also open **Settings → Apps → App management → Sanaa Agent → Battery usage** and allow background activity/autostart if those switches are present. Menu wording varies by ColorOS version, so the app always opens the standard Android settings screen instead of relying on an undocumented Oppo component.

No Groq key is present in the repository or APK. Enter it during onboarding and use the connection icon to verify the model before starting Amara.
