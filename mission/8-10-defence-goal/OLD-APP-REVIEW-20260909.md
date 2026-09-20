# Old app log review and clean migration — 9 September 2026

Owner explicitly requested log preservation, learning, uninstall and installation of the improved-ad build.

## Evidence collected before uninstall

Private archive: `artifacts/defence-migration-20260909/`. App databases, files, preferences, available logcat and supplementary data are retained locally. `manifest.json` records archive checksums. All nine SQLite databases passed integrity checks. Raw customer data and credentials are excluded from this report.

The queried interval was 7 September 04:40 UTC to 9 September 04:40 UTC. Retained action history spans 33.96 hours, with 103 actions. It cannot establish continuous operation for the complete requested 48 hours. Logcat is a rolling buffer, not a two-day journal.

| Observation | Count |
|---|---:|
| TikTok posts verified in transaction ledger | 16 |
| TikTok posts failed | 16 |
| TikTok posts uncertain | 1 |
| Public comments verified | 5 |
| Public comments uncertain | 7 |
| Soko alerts successful / failed | 2 / 7 |
| Soko bookings successful / failed | 2 / 7 |
| Recorded WhatsApp reply outcomes | 0 |
| Configured commercial-policy records | 0 |

Publication verification rate is 16/33 (48.5%) for the retained attempts. This is a delivery measure, not audience value or sales attribution. Empty WhatsApp records do not establish whether customers contacted the business outside this telemetry.

## Lessons

- Nine publication failures reported no external trigger; seven failed to show published content within the observation window; one accepted tap remained uncertain. Do not guess a common UI cause from these coarse records. Inspect composition and retain precise preparation-stage failures on the new build.
- Seven uncertain comments require read-only reconciliation, not duplicate comments.
- Six failures in each Soko audit surface explicitly reported account sign-out; one reported unverified Staff Login. A saved staff PIN cannot replace account authentication. Owner account sign-in remains necessary.
- Activity volume is not commercial success. Policy, attributable enquiries/orders, costs and customer usefulness still need evidence.
- New ad artwork makes headline, price and CTA visible in the image. Both scheduled and owner-command publication paths use the rendered, immutable asset. Verify a real offering on the actual TikTok composer before judging visual quality.

## Migration safeguards

Preserve the original archive unchanged. Restore durable databases, evidence/media and nonencrypted preferences before launching the replacement app. Preserve uncertain transaction and social claims so reinstall does not re-arm them. Exclude old encrypted preferences/vault ciphertext and stale Accessibility-recovery preferences because the old Android keystore keys do not survive uninstall. Reconfigure credentials and OS access, and do not claim the original phone service permissions survived.

## Completed replacement and restored-state check

Old app uninstalled with explicit owner authorization; new app installed successfully. The initial installed checksum exactly matches `0267e6461ca85e94a7d8342416b00b1fe84b1bbe89f853e7ff3c6585b8fc6cc0`. Android Settings disclosures were used to restore phone access. The device certificate passed Accessibility enabled/bound/not-crashed, overlay, notification listener, battery exemption, foreground service and zero recent fatal/ANR matches.

Post-restore database inspection confirmed 307 contacts, 103 historical actions and all 45 side-effect transactions: 21 VERIFIED, 16 FAILED, 8 UNCERTAIN. Original archives remain unchanged. Model/catalogue backend access works after fresh registration. TikTok posting was enabled and the prior 30-minute / 48-daily-cap setting restored. Encrypted per-channel switches were not copied as plaintext; other channel enablement and Soko PIN must be reviewed separately. Public ad WhatsApp is still unspecified, so Soko is the current CTA fallback.

A five-hour post-migration observation journal started at 2026-09-09 04:56:20 UTC, with collector output in the private `new-build-observation` folder. It is a new interrupted/migration-segmented observation, not a seven-day acceptance run.

A real catalogue title exposed an editorial issue: first-two-word shortening produced “Shiny Printer” for a date stamp. A factual phrase selector now prefers “Date Stamp” (and other exact product/service phrases present in the title) while preserving the full title in the caption. Preview preparation now renders the catalogue-bound artwork before publication and uses factual caption copy.

## Later configuration and motion update

The owner-configured public WhatsApp line and Soko PIN are now present on the replacement phone. Subsequent motion-ad builds, location hashtags, native-video checks and the final installation certificate are recorded in [MOTION-ADS-20260909.md](MOTION-ADS-20260909.md). Earlier missing-contact/PIN statements above describe the initial restored state.
