# Motion ad and comment-notification implementation

Owner requested engaging gallery/video ads, alternating WhatsApp/Soko stickers, varied typography/layouts, Soko24 closing animation and review of comments on ads.

## Implemented paths

- `AmaraMotionScene`: 720×1280 portrait, 20 fps, 12 seconds; three deterministic item-bound layouts (editorial, showcase, poster). Full-item images fit inside cards; gentle scale/rotation and eased gallery slides. Headline/price remain separate and readable. Up to four distinct catalogue images.
- CTA alternates every three seconds: WhatsApp when an item/configured public number exists, then Soko. No phone is invented. Final two seconds use an original Soko24 green wordmark reveal/light sweep. It is an original brand motion treatment, not a copy of another platform's ident. Silent video; no unlicensed music added.
- `AmaraVideoEncoder`: bounded device-side AVC/MP4 through Android MediaCodec/MediaMuxer. Frozen MP4 bytes are integrity-bound before publication; no silent plain-photo fallback after a render failure. Renderer/version/gallery/template changes affect the content fingerprint. Publication records include video digest/template.
- Backend `../app/Services/SokoReadRepository.php` resolves this shop's product gallery upload IDs to URLs. Real listing 2601 has four resolved images. Service rows without exposed galleries retain their cover image.
- `TikTokCommentNotifications`: listens to individual own-video comment notifications, excludes grouped alerts/likes/follows, persists duplicates and original routes. Buying questions go to governed work. An independent owner toggle controls replies to own-ad questions; it does not enable commenting on other accounts.
- `TikTokNotificationReview` / `TikTokCommentReplySurface`: assess usefulness, draft a short response, require exact author/body row and explicit reply-to target before sending. Missing context/expired routes/ambiguous controls remain review items. Unverified delivery remains uncertain; reservations survive restart. Twelve daily own-ad reply reservations maximum. Local inbox counts appear in Settings.

## Design and evidence limits

A more animated ad is not proof of higher ROI. Track verified delivery, qualified enquiries and orders against template/media identity. Existing records are preserved. No full 48-hour or seven-day claim is made. Native MP4 encoding and TikTok import require live device validation; unsupported comment notification languages/layouts are not guessed. Existing captured notifications only reconstruct routes when TikTok still exposes them.

Android encoding implementation checked against [MediaCodec reference](https://developer.android.com/reference/android/media/MediaCodec), particularly flexible YUV input planes and output format/muxer handling.

Validation and live outcomes follow below.

## Local validation

20 targeted Android tests passed; Flutter settings analysis passed; PHP syntax and two existing Soko URL tests passed. Real shop projection returned 46 products, including four image URLs for listing 2601. Native preview frames inspected for readable title/price/contact hierarchy and closing identity. A minor shadow alpha correction followed visual inspection and will be included in the final package. Hardware encoding and publication still pending at this checkpoint.

## Shop location and device findings

Shop metadata now comes from the seller's matching Soko shop for both product and service projections. The public address `Nasser road Kampala` produces `#NasserRoad #Kampala` beside the existing Soko tags; blank/unrecognised addresses add no inferred location. Address changes participate in the bound caption fingerprint.

OPPO hardware encoding succeeded: AVC, 720×1280, 20 fps, 12 seconds; extracted file and sampled frames are retained privately under `artifacts/defence-migration-20260909/`. Sharing then exposed a missing FileProvider root for `tiktok-bound-video/`. The root is now registered, URI creation is preflighted before dispatch, and a regression test reads the bound video through the manifest's provider. Earlier uncertain records remain unchanged. Version `amara-motion-v4` invalidates old rendered assets after the visual alpha correction.

Final targeted build passed 13 tests (media binding/provider, motion scene, location extraction and factual captions), including the sharing regression. APK SHA-256: `16b0353dccaf26a38a0c509986777a4a2cf2db8b7f425763c029e2348d143bfc`. Deployment and public delivery are recorded separately below. The phone now shows a configured public WhatsApp line and stored Soko PIN. The own-ad reply toggle was enabled through the app Settings; no own-video comment notification has yet been available for live verification.

## Final reliability checks

- Native Date Stamp MP4 validated with ffprobe: H.264, 720×1280, 20 fps, exactly 12 seconds; 1,088,784 bytes. Frame inspection confirms the catalogue gallery, corrected soft shadow and alternate Soko CTA. A shareable local preview is `artifacts/ad-preview-20260909/index.html`.
- Phone preparation logs contain the full Date Stamp product URL, configured WhatsApp and `#soko24 #sokoug #NasserRoad #Kampala`. A fresh governed canary is queued. The existing TikTok breaker from 09:21:12 UTC remains in force until approximately 09:51:12 UTC; it was not reset or bypassed.
- Own-ad replies remain enabled after installation. Refreshed notifications now deduplicate independently of post-time changes. Reservations use a SQLite transaction and persist the actual reservation timestamp so delayed questions count against today's twelve-reply ceiling. Reply work declares model and external-communication capabilities for ordinary scheduling limits.
- Six final comment/inbox/journal tests pass, including notification refresh deduplication, restart-safe reservations and the late-notification rate-limit regression. An additional factual-caption integration test passed for both product and service location tags. Broader group, service-checklist and transaction-boundary checks also passed.
- New journal rows identify the motion generation and Android package update time, distinguishing future deployment segments. Earlier migration builds all carried the old `defence-ad-v7` label and cannot be retrospectively separated by that label alone. The recovered 25,955-row pre-final journal contains a 370-second maximum event gap and no accepted WhatsApp inbound cases; it is not clean seven-day evidence.

Final APK installed successfully with `adb install -r`: SHA-256 `8c3f805e8643f0e7c9e16ffe72cd58ffbe49a77ec8adef17f28ba45dc1f8108c`. Process 25330 started; Accessibility enabled/bound/not-crashed, overlay allowed, no recent fatal/ANR match in the deployment sample. Full device certificate follows separately. This supersedes the intermediate `16b0353d…` package above.

The full certificate confirms the installed checksum matches the final local APK; Accessibility enabled/bound/not-crashed; overlay, notification listener and battery exemption pass; AgentService is present and foreground; zero recent fatal/ANR matches. Jobscheduler output has 62 text matches, not 62 unique jobs. The read-only collector shows new segment `defence-motion-v4:1788947104349`, fresh journal events and zero failed fetches in its first three final polls.

## Encoding bottleneck found during live run

The 09:51 resumed work was an ordinary scheduled Face Masks ad, not the Date Stamp canary: `AgentRuntime` deliberately cancels pending owner canaries at app startup/update. It failed before entering the side-effect ledger; the native encoder emitted 115 of 240 frames before the 180-second timeout. No public delivery is claimed. Per-pixel direct-buffer writes have been replaced with bulk row transfers; chroma padding is read before each write to preserve overlapping U/V planes. Stride values are cached outside pixel loops. A newly queued canary after installation will validate this change.

The five-hour window reached its configured end. The final collector made 12 polls with one failed fetch; the combined journal still contains no accepted WhatsApp inbound work and mixes historical migration segments. It cannot satisfy the seven-day acceptance gate.

## Published motion ad observed

With import-transition build `f4ab3fe54d358a5b556e716503da434d24b8af8bccaa38a8fcabe0c70a2174a0`, Amara prepared and tapped Post for Date Stamp at about 10:06:53 UTC. Bound MP4 digest `d14364efcdd17b0662d5cf961b38f8b82352aadf02d8044205cbad0271240bcf`. Read-only inspection then opened the newest post on `@sanaamedia` and expanded its full caption: the exact Date Stamp title, UGX 85,000 / Pc, configured WhatsApp, product URL and `#soko24 #sokoug #NasserRoad #Kampala` were visible. The profile thumbnail is a video and the post displayed a recent timestamp. Private screenshot/XML evidence: `published-date-stamp-caption.png` and `published-date-stamp-caption.xml` in the migration archive.

The automatic immediate verifier returned UNCERTAIN because the profile window was temporarily absent after upload. That original ledger record remains untouched; this later inspection is separate reconciliation evidence, not a resend. A final verifier adjustment waits for the TikTok window and Profile control within bounded deadlines. Public delivery is now directly observed; ROI and engagement remain unproven.

## Final installed build and read-only verification

Final installed APK SHA-256: `de7cc8721c2ac5f77c71b543fbef11d1b8bd677cbe9e87063dc264553005334f`. Installation succeeded; process 7686, Accessibility enabled/bound/not-crashed, overlay allowed, zero recent fatal/ANR matches. Seven targeted publication/composer/transaction checks passed before installation.

At 10:12:27 UTC, `TEST_TIKTOK_VERIFY_READ` ran against the exact full caption of the already published Date Stamp ad. Result: `verified=true`, confidence `0.8`, package `com.zhiliaoapp.musically`, delivery state `published`, blocker `null`. Evidence: private `published-read-verification.txt`. No second Post action was used for reconciliation, and the original uncertain transaction remains available for audit. Inspection pause was released; normal owner-configured cadence remains enabled. Live own-ad comment reply verification and commercial ROI still require real customer traffic.
