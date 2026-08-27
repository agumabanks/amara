# Evidence Index

## 2026-08-23 Trust Kernel 1.0 local gate (no device attached)

- Scope: Phase A trust-kernel implementation (capability catalog, side-effect transaction ledger,
  target-bound verification, approve-and-execute, prompt-injection guard, telemetry/redaction governance).
- Release APK: `app/build/outputs/apk/release/app-release.apk`
- APK version: Amara `0.9.0` (`versionCode 12`).
- APK SHA-256 (re-audit final): `8c3af782bc4256977b6f8e1838bfbc133948c2e64933ffff59e368e70fd46369`
- APK size: 21,620,864 bytes class build; final signed artifact verified by `apksigner verify` inside the gate script.
- Android/JVM (re-audit final, --rerun-tasks): **236 tests, 0 failures, 0 errors across 37 suites** — including Robolectric production-SQLite suites, golden-hierarchy verifier fixtures, production-path integration tests over MockWebServer, durable-workflow crash-window/concurrency tests, artifact PDF/PPTX/CSV/tone suites. Earlier line:
  Robolectric production-SQLite suites (`AmaraMemoryPersistenceTest` 14, `DataEgressPayloadTest` 7),
  golden-hierarchy verifier fixtures (`GoldenHierarchyTest` 16), transaction enforcement
  (`SideEffectTransactionTest` 27), knowledge/artifact/connector/workflow/certification suites
  (Phases B–F simulation cores).
- Flutter: canonical formatting, zero analysis issues, 3 widget tests passed.
- Mission checker: 52 task records consistent between JSON and CSV.
- Device gates: **not run**; `adb devices -l` showed no device on 2026-08-23.
  No device-facing task was promoted.
- Execution log: [`../amara-complete-employee/EXECUTION_LOG.md`](../amara-complete-employee/EXECUTION_LOG.md)

## 2026-08-21 Oppo reconnect — O-01 pass, O-02 blocked

- Field report: [FIELD_O_01_O_02_20260821.md](FIELD_O_01_O_02_20260821.md)
- Accessibility settings: `O_01_ACCESSIBILITY_SETTINGS.png`, `O_01_ACCESSIBILITY_SETTINGS.xml`
- ColorOS downloaded-app state: `O_01_ACCESSIBILITY_SCROLLED.xml`
- Service detail and bound proof: `O_01_ACCESSIBILITY_DETAIL.xml`, `O_01_ACCESSIBILITY_BOUND.png`
- Terminal blocker: `O_02_TERMINAL_START.png`, `O_02_TERMINAL_START.xml`

## 2026-08-21 local implementation gate

- Release APK: `app/build/outputs/apk/release/app-release.apk`
- APK version: Amara `0.9.0` (`versionCode 12`).
- APK SHA-256: `ca5ddf9d5b4fdf508f97cd82bd2045641f0cc8ff04e802a270cb8676a678871a`
- APK signature: Android APK Signature Scheme v2, one signer, `CN=Sanaa Agent, O=Sanaa Media, C=UG`
- Android: 41 unit tests passed.
- Flutter: analysis passed with zero issues; 3 widget tests passed.
- Mission checker: 52 task records consistent between JSON and CSV.
- Full result: [LOCAL_IMPLEMENTATION_REPORT.md](LOCAL_IMPLEMENTATION_REPORT.md)

## Naming

Use `GATE_YYYYMMDD_HHMM_KIND.ext`, where `KIND` is `before`, `action`, `result`, `hierarchy`, `receipt`, or `logs`.

## Existing baseline evidence

| Capability | Evidence |
|---|---|
| Phone health | `../../PHONE_ACCESS_SCREEN.png`, `../../PHONE_ACCESS_SCREEN.xml` |
| Terminal access blocker | `../../SOKO_INTELLIGENCE_BLOCKER_FINAL.png`, `../../SOKO_INTELLIGENCE_BLOCKER_FINAL.xml` |
| Adaptive Soko crawl | `../../STEP_ADAPTIVE_SOKO_REPORT.md`, `../../V08_FINAL_CRAWL.png`, `../../V08_FINAL_RESULT.xml` |
| Soko/WhatsApp Studio share | `../../SOKO_STUDIO_SHARE_REPORT.md`, `../../SOKO_STUDIO_WHATSAPP_RESULT.png` |
| Command-screen WhatsApp | `../../STEP1_REPORT.md`, `../../COMMAND_STEP1_PROOF.png` |
| WhatsApp attachment | `../../WHATSAPP_ATTACHMENT_PROOF.png`, `../../WA_ATTACHMENT_RESULT.xml` |
| Observe–Analyze–Act receipt | `../../V07_OAAR_RECEIPT.png`, `../../V07_OAAR_RECEIPT.xml` |

Historical evidence proves only the named build and scenario; it does not automatically pass the final certification gates.
