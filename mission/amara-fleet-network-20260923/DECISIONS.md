# Decisions and unresolved questions

| Item | Working decision | Resolution owner / phase |
| --- | --- | --- |
| `.co` versus `.ug` | Owner confirmed `cards.sanaa.ug` as the canonical API host on 2026-09-24 Europe/Berlin. Keep the existing configured `.ug` endpoint; do not migrate clients to `.co`. | Decided by owner; backend implementer verifies deployed TLS/routing and token audience during P3 |
| Actual Shorts Notice | Unknown; do not infer from thumbnails or Merchant Center advice | Device/YouTube audit P0; record exact impact before claim of resolution |
| Different YouTube content | Accepted user direction; same product truth, independent variant and cadence | P2; supersedes old export-only plan |
| Interval semantics | Minimum between dispatch opportunities; uncertain consumes slot; no offline catch-up burst | P2 validates UI and migrates settings transparently |
| Reporting consent | Keep existing opt-in, separate operational reporting/artifacts/memory/learning | P3/P4 onboarding; no silent consent migration |
| Owner Off reporting | Existing fetch/config requires On. Define whether an Off transition can send final consented status; no hidden continued work | P1/P4 lifecycle policy and explicit UI |
| Missing manager target | Show configuration blocker; never guess recipient or silently substitute user phone | P1; validate owner-configured destination |
| Shared account auth | Reuse Passport/membership; audit OAuth vs OIDC support and client inventory | P6; protocol implementation gated on inventory |
| Sanaa Chat codebase | Not identified in the inspected backend app files; integration API/client owner still to be located | P6/P7 discovery deliverable, do not claim chat already supports pairing |
| Physical map | First deliver relationship graph; location optional and consented later | P5 |
| Node learning | Reviewed recipes/config/prompts and de-identified evaluations, not peer token/memory sharing or arbitrary code | P8 |
| Performance targets | Initial targets in TEST_MATRIX are proposed, require measured baselines | Each phase |
| Off/online truth | Absence of heartbeat is unknown, not proof of power off or no internet | P4/P5 |
| Fleet size | 21 registry records; only two live ADB devices audited | P0/P5; detect inactive/duplicate registrations |
| Host disk | Full during capture; 94% used at 01:47 local on 24 September; the shared MySQL backup directory became empty before its documented 02:00 cleanup, restoring 54 GB free but leaving backup coverage unverified | P0 infrastructure action: identify the responsible retention/transfer job, verify restorable off-host coverage, budget and alerting |

Do not add random behavior or device fingerprint spoofing to avoid platform enforcement. Content variation should improve usefulness and originality, and publishing must respect user settings and platform rules. No automatic copyright dispute or removal is in this mission.

## Resolved backend choice

Keep the live Cards PostgreSQL database (verified 16.15). JSON event storage uses the relational + JSONB design in DATABASE_DECISION.md. MongoDB/Firebase are not prerequisites for the fleet map, offline sync or remote commands. Recovery targets remain proposed until operator/owner confirmation and a measured restore. Disk headroom was approximately 53 GB at the initial audit, fell to 26 GB at 01:47 on 24 September and returned to 54 GB when the shared MySQL backup directory became empty before the documented cleanup. Full-disk and backup-coverage evidence remain relevant to retention design.
