# Release freeze — campaign AMARA-REL-20260826-D (supersedes A/B/C; no device evidence collected under any prior build)

- frozen_at_utc: 2026-08-26T19:07:36Z
- apk_sha256: e977e8a0f8e81e1a22a3f326b37bbfd2bab06959f0390a7ac21c571d42eec095
- source_manifest_sha256: 38099d2e72238041c14f0e930c65e9daacfdce0a45f7beb1465868dca60fa44d
- signer_cert_sha256: 55e237c9c2079f3e413d2b3d00e84a6c577a5c731c837bbefd49f64fd41ea6c4
- versionName: 0.9.0 versionCode: 12
- apksigner verify: PASS
- production-source files newer than APK: 0 (must be 0)
- reason for refreeze: two source files (AmaraMemory.kt + a Flutter screen) received test/UI refinements after campaign C freeze; per campaign rule 10 a new release is required and all device evidence must be re-collected.
