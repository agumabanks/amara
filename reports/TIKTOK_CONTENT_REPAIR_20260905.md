# TikTok content binding and WhatsApp ingestion repair

Date: 2026-09-05. Status: implementation tested; end-to-end publication UNVERIFIED.

## Findings and changes

- Scheduled posting previously retained only listing ID/caption. It did not require
  a shopping link or bind the image and product details across retries.
- The separate `TikTokSkill.createPost` path never imported the supplied image;
  owner-command execution called it with null media. This is a concrete path for
  stale media/new text, not proof of which path caused the owner's affected posts.
- Both paths now use explicit image sharing. Owner commands resolve an exact
  catalogue title/ID (or rotate eligible products when no product is specified).
- `TikTokProductContent` builds factual title/price copy, `/product/{slug}` shopping
  link, and `#soko24 #sokoug` from one row. Missing title, usable media or valid slug
  fails closed. Free-standing generated captions are no longer used in these paths.
- Scheduled payloads persist image URL, shopping URL, caption and a content
  fingerprint before publishing. Changed row contents invalidate bound retries.
- The editor must be in TikTok, have one visible editable field, and return the
  entire exact caption before the Post tap. A matching 40-character prefix is no
  longer sufficient. Downloads are bounded even without Content-Length.
- WhatsApp's 30-second owner window now starts at notification observation,
  including the accessibility fallback, not after runtime initialization. Logs
  measure ingestion and observation-to-execution time without message text.

## Source and catalogue checks

Read-only `SokoReadRepository.activeListings()` returned five active products.
Their local thumbnail files were visually inspected: two R-532D stamps, thermal
printer, ID-card/lanyard artwork and Mi TV Stick. Images broadly match their titles;
this is not SKU/colour verification or a review of the actual published posts.
Soko's backend web route confirms `/product/{slug}`. A caption URL is not a
verified native TikTok shopping attachment or proof of clickable checkout.

The new Soko seller endpoints exist for generating product/service payment links.
They require seller authentication and enforce item ownership. Amara currently
has a read-only catalogue bridge, not that authenticated payment-link integration.
No payment links were generated, no payment amounts changed, and no credentials
were copied. Payment-link creation/sharing remains NOT INTEGRATED.

## Remaining acceptance gates

1. Reconcile existing uncertain posts; do not replay them to test this repair.
2. Supervise Amara importing a specific product and inspect actual composer media,
   complete caption and destination URL together before certifying publication.
3. Verify the published post's media, not just its caption. URL fingerprints do
   not detect an image replaced at the same URL or prove TikTok used the stream.
4. Verify scheduled repeat, no-repeat rotation, restart and owner OFF behavior.
5. Measure WhatsApp takeover with owner unanswered/already answered cases. Timing
   instrumentation is not a guarantee of a reply at exactly 30 seconds.

## Verification

- Final targeted run: 48 tests, zero failures/errors across product content,
  autonomous work integration, WhatsApp notification parser, target-bound
  verification, side-effect structure and autonomy plan guards.
- `:app:assembleDebug` passed. `git diff --check` passed.
- OPPO CPH1933, serial `7aef1a4c`: non-streaming `adb install -r` succeeded,
  preserving app data; no force-stop, uninstall or protected-settings write.
- APK SHA-256: `7bf8f3f5c000c545a8348c98fb2f4355ae5712813c2b1d452be09faa02fa4f3c`.
- No public post or customer message was manually sent for this deployment test.
- Device checks at approximately 10:16 UTC: installed APK checksum matches;
  version 0.10.0 (13), PID 12600; accessibility enabled/bound, not crashed;
  overlay, notification access and battery exemption pass; AgentService present
  and foreground. Recent fatal/ANR matches: 0. JobScheduler text matches: 57
  (not a count of distinct scheduled tasks). These certify service readiness,
  not successful autonomous sales or content publication.
