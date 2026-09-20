# TPS450M wrong-shop investigation — 2026-09-13

## Confirmed cause and containment

Cards account 24 was registered as Sanaa Media, while TPS Terminal profile is Osa Gadgets (database name `Osa  Gadgets`, seller 319 / shop 37). Amara queried Cards using the registered name. Terminal v1 exposed discovery metadata only, so installing Terminal and syncing Cards never verified its logged-in shop. The previous readiness claim was too narrow to establish shop-safe operation.

TPS Amara was switched Off through its visible Settings and rechecked. During the continuation it was observed On again and was switched Off and verified again. No data was cleared, no existing posts deleted, and no test message/post sent. Current TikTok account is @soko24.co; its authorization to publish for Osa Gadgets is not established.

## Changes

- Soko full-seller authenticated endpoint issues a 180-second RSA-signed read-only shop assertion, using server-authenticated user ID and a single active shop. Staff-only tokens remain excluded by existing full-seller middleware. No Terminal access token is exported.
- Terminal periodically publishes only that assertion to Amara's caller-checked provider. Login transitions, logout and failed refresh clear it. Generation checks discard late old-shop responses. Local POS checkout remains available when cloud identity is unavailable.
- Cards validates signature/audience/expiry, verifies shop-to-seller ownership, and scopes catalogue reads by immutable IDs. It records last observed Terminal shop separately from the legacy registered name. Name-only catalogue and ad fallbacks are rejected.
- Amara verifies the signature locally and checks response identity. Missing/expired proof blocks business reads and external effects. Fresh catalogue posts carry shop scope; old bound drafts without matching scope are rejected. Scope is checked again at transaction dispatch and immediately before final publication.
- Other commercial channels remain held pending complete shop-scoped memory/target support. This is containment, not a completed migration of existing memory/queues.
- Read-only Settings → Verify logged-in shop works while Amara is Off, showing signed identity and sample catalogue titles without posting.
- Coordinate taps must lie within the current active window, including offsets/rotation. Device summary includes current viewport, density and installed app versions. Own TikTok profile handle no longer depends on an obfuscated display-name ID.
- Removed forced entry into TikTok's optional sound panel. The TPS journal showed `automatic_sound_panel` as its failed preparation stage. Existing media audio is preserved and the verified caption editor remains required.

## Device observations

TPS Android 11: 800×1280, 213 dpi. OPPO: 720×1600, density override 360. TPS Amara observed PSS ~107 MiB while paused (single sample, not peak or leak evidence). Terminal displays a four-column iPhone catalogue; current profile confirms Osa Gadgets, Owner. TikTok 46.8.3 opens and its profile is readable. The initial blank accessibility dumps were transient and are not proof of app failure. TikTok warm launch observed 1292 ms; Terminal hot launch observed 1101 ms (single samples).

Fresh pre-fix exported journal has one recorded TikTok ACTING → FAILED effect and no VERIFIED TikTok publication in that exported window. Community reads observed no relevant posts. This does not disprove reports of earlier posts; it limits what this journal proves. A screenshot confirms Amara's Off overlay during TikTok inspection.

## Validation

Cards: 16 targeted tests / 50 assertions passed, including signature/expiry/audience, immutable shop ownership, existing per-device memory/auth/admin regressions. Terminal identity publisher: 2 tests passed for logout race and failed refresh. Existing prerelease auth source-check suite initially reported two failures: broad Timer.periodic check and pre-existing splash-expiry expectation; publisher timer was moved out of the auth controller. No offline authentication policy was weakened to satisfy the old splash test.

Amara: 42 targeted tests passed for signed identity, stale/changed shop dispatch, side-effect ledger, profile identity and viewport bounds. Final artifact/deployment and live catalogue verification results follow.

## Rollout decision

Do not add more shop devices yet. Require live signed-shop catalogue proof, approved TikTok account association, shop-scoped memories/queues for all channels, WhatsApp login/delivery validation and a sustained unattended test. Signed identity availability depends on Terminal refreshing in the background; expiration fails closed. No claim of cross-device autonomous publication certification is made.

## Final installed and live result

Installed TPS Amara 0.10.3 (16), SHA-256 `147af16fa5618ff17fd161f15600862ee1d683d159c7a96586c02b815186538f`, and Terminal 2.0.4 (2029), SHA-256 `51f3bfddcba9c2094424c9d3901f1c37c5f09919e6cd6379f3807805d594310f`. Both device APK checksums match the built files. Replace-install preserved existing data. Accessibility bound, crashed set empty; overlay allow, notification authorization and battery exemption pass. Amara remains Off by design; foreground-autonomy or scheduled-job presence is not a readiness claim while paused.

Live Settings verification succeeded while Off: Osa Gadgets, scope 319:37, 50 active listings returned, examples Samsung S6 edge, Samsung S7 edge, Samsung S8, Samsung S8 plus 64gb Dubai Used, Samsung S9. Cards account 24 independently records observed seller 319 / shop 37 and verification time 2026-09-13 12:37:13 UTC. Its legacy registered name remains Sanaa Media and is explicitly distinguished from verified identity; it no longer selects the catalogue. Provider freshness is required, not merely the persisted observed admin label.

Live WhatsApp remains at Welcome / Agree and continue. TikTok profile is @soko24.co. No posting, message delivery, account login, shop switching, or deletion of prior posts was performed. Do not enable additional devices or mark TPS production-ready based on these targeted tests.

Evidence: `artifacts/tps-shop-audit-20260913/`, including signed-shop result UI XML/screenshot, installation logs, before-fix journal, final package/accessibility state and test logs. Temporary device inspection files are private test evidence; no credentials or signed assertions were printed in results.
