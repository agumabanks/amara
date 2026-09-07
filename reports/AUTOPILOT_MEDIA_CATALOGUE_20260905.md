# Autopilot media, catalogue and navigation repair

Date: 2026-09-05. Status: targeted tests passed; business workflows not certified.

## Implemented

- TikTok normalized image bytes are frozen in an atomic, SHA-256-addressed local
  store before the share intent. A persistent job binding reuses those exact bytes
  after restart rather than downloading potentially changed content at the same URL.
  Missing/tampered bound files fail closed. Media store cap: 128 MiB; individual
  files: 15 MiB; decoded source: at most 20 million pixels. No automatic deletion
  of unresolved publication evidence. Storage-full requires evidence review.
- Product fingerprints now preserve exact case/bytes rather than using the
  conversation hash normalizer, which lowercased URLs.
- TikTok caption fields require caption/description semantics or the exact imported
  caption; search/mention/hashtag-search inputs are rejected. Composer screenshots
  and media hashes are recorded locally for reconciliation, not uploaded.
- WhatsApp checks the exact current conversation before launching the app, exits
  search polling as soon as ready, and tests results before sleeping. Incoming
  replies read the current screen plus durable memory instead of always scrolling
  four older screens. Recipient verification and the 30-second owner window remain.
- Soko catalogue sync falls back to authenticated shop-scoped product/service reads
  when Terminal scanning yields no offerings or no PIN is available. Results enter
  ChatStore, which already feeds WhatsApp conversation prompts. UGX service prices
  only; unknown prices remain unknown. No Soko write API or copied seller token.
- Studio sharing stops before the caption if image delivery fails/is uncertain.
  Caption delivery reopens/verifies the intended WhatsApp target instead of trusting
  whichever chat is currently foreground.

## Inspected, not completed

Terminal exposes Soko Studio via `/home/more/ads`, gated by seller access and its
feature flag. Amara has an existing Studio-to-WhatsApp path, but current Ads layouts,
image verification and export-to-TikTok are NOT certified or fully integrated.
No live Ads export was manually performed in this repair.

The backend fallback uses Amara's existing authenticated SELECT-only bridge scoped
to the registered business name. It is NOT a general marketplace sourcing or affiliate
authorization. If the owner's shop has no products/services remotely either, it
does not fabricate offerings or advertise other sellers as the owner's stock.
Payment-link generation remains separate and not integrated.

Exact file bytes and URI import do NOT prove TikTok displays/publishes those pixels.
Published-media comparison, stale-composer rejection, pinned-post reconciliation,
scheduled repeat and restart/OFF acceptance gates remain open. Old unresolved
publications are not replayed as deployment tests.

## Verification

53 targeted tests passed, zero failures/errors: media binding 4; caption-field
checks 2; WhatsApp matching 3; side-effect structure 2; credential routing 9;
catalogue fallback 1; product content 4; autonomous integration 28.
APK build and `git diff --check` passed. Initial test compilation caught an outdated
fake transaction signature; updated it and reran successfully.

Build SHA-256: `9b14609bd1e40cc33e5d60a209cb6c3069e137c2c2e6b5f190225715091f0b61`.
Live deployment at approximately 11:03 UTC: OPPO CPH1933 Android 11 serial
`7aef1a4c`, installed checksum matches, version 0.10.0 (13), PID 21509.
In-place non-streaming install succeeded without clearing data or force-stop.
Accessibility enabled/bound/not crashed; overlay, notification access and battery
exemption pass; AgentService present and foreground. Recent fatal/ANR matches: 0.
JobScheduler text matches: 61 (not distinct jobs). No new ingestion/takeover timing
samples were found in the inspected log slice; reply latency remains unmeasured.
This is not a full-system 10/10 claim.
