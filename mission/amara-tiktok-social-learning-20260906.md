# TikTok contextual interaction and durable learning

Owner requested a few relevant public interactions, audience discovery, continuous learning, and phone/backend persistence.

## Implementation

- Optional owner setting `tikTokSocialEnabled` enables contextual public comments, including a short emoji reaction when supported by context. Existing posting and comment-monitoring switches remain in force.
- Scheduled comment work runs a bounded four-post research cycle when enabled. Read own profile identity and visible counts, inspect full post caption and existing public comments, and ask the configured model to skip or propose one relevant response. Posts must overlap the business; the model receives public content as untrusted data and cannot authorize dispatch.
- Bind identity to exact creator and full post caption. Refuse mismatched surfaces before dispatch. Verify the exact submitted text under the account's own display name on that same post. Taps alone do not establish publication.
- Universal side-effect runner owns dispatch. Three attempts per rolling 24 hours, at least one hour apart, one creator per day, one reservation per post, and no repeated recent response. Durable reservations survive crashes and uncertain sends. Never retry an uncertain comment automatically.
- Save public observations, decisions/evidence, own responses, reservations, outcomes and profile samples in `amara_tiktok_social.db`. Relevant creators and their post context are research candidates, not promised followers. No follow-for-follow, bulk follows or automated direct-message campaign was introduced.
- Record follower changes as observations; unavailable counts remain unknown and changes are not attributed to a comment without evidence. Recent response history and outcome summaries inform later choices.
- Include bounded detailed public-post research plus permanent compact reservation records in the existing authenticated, encrypted-at-rest backend memory snapshot. Local writes precede network work. Failed uploads remain available for scheduled retry. Restore merges reservations conservatively rather than rearming them.
- Removed the unused legacy TikTok reply method that bypassed exact post binding and transaction verification.

## Validation

Live deployment, model decisions, publication checks, phone database and backend snapshot checks are recorded below as they complete. A skipped irrelevant post or provider failure is not counted as a successful interaction.

## First live evidence

The first cycle saved three public-post observations and three model skip decisions. The posts were unrelated entertainment, a personal/medical tragedy, and political content; none was commented on. Own profile showed @sanaamedia, 794 followers, 2,439 following, and 2,378 likes. These are baseline observations, not growth attributed to Amara.

Backend verification found snapshot ID 1 at 2026-09-06T04:06:14Z containing three social observations, one profile sample and zero interaction claims, matching device storage. The existing AgentMemorySnapshot model uses encrypted:array storage and device-authenticated read/write endpoints. No separate backend schema was necessary.

The initial build passed 66 tests (social persistence/policy, transaction and autonomous-loop coverage). Subsequent search work replaces ineffective IME submission with the observed explicit TikTok Search button. Live search for printing Uganda exposed relevant Kampala printing and product-design results. Discovery rotates six business topics and retains the query with each observation. Prior model decisions and observed outcomes inform future responses; they cannot change permissions or posting limits.

## Discovery build

APK `202e62f72d66d55635ae339c7fdd6d5f33a524a5b375d9c9fc113552fff36dba` built and installed successfully. Thirty-five targeted tests passed (28 transaction, 5 durable social-store, 2 response/count policy). The additional tests cover old reservations beyond the detailed backup limit, account-isolated follower comparisons, and cooldown of unchanged reviewed posts. Accessibility remained enabled/bound/not crashed, overlay allowed, with no sampled fatal/ANR. Social research is also included as untrusted audience context in future product-caption generation; it cannot override listing facts.

## Live interaction evidence and limitation

A later cycle observed PACKAGING HUB UGANDA and chose one contextual packaging comment. Its durable transaction is UNCERTAIN: verification reported that the post identity surface changed after Send. It is not counted as a verified comment and is permanently protected from automatic resend. Both the phone and encrypted backend snapshot contain this attempt (four observations, two profile samples, one reservation). The two observed follower samples both showed 794; no follower growth is claimed.

A verifier follow-up reopens only the current video's expanded caption after TikTok rebuilds the surface, then requires exact creator/caption identity again. Read-only recovery searches for the original creator and attempts to locate the same caption; it never dispatches another comment.

The read-only lookup did not verify the existing comment. Its screenshot showed a blank TikTok loading screen after opening a search result. Replaced the result-opening fixed delay with a bounded 12-second wait for visible post controls, and added a bounded wait for the full caption surface. The uncertain comment remains protected from resend; no verified comment or acquired follower is claimed.

## Final deployment

Final installed APK SHA-256: `3b270bf67422d9ae3d7a4e39f120a1d4093bbee70183152095342d74b250a16d`. Build passed; the preceding transaction/social test suite passed 35 tests with zero failures/errors. Installation retained enabled/bound Accessibility, allowed overlay, and no sampled fatal/ANR. The final read-only lookup still did not verify the original comment; it made no new dispatch. Comment delivery and follower acquisition therefore remain unverified, despite successful local/backend persistence and live contextual selection. The owner toggle remains available under Settings → TikTok → Relevant public comments (up to 3 per day).

Final device certificate passed: Accessibility enabled/bound/not crashed, overlay and notification access allowed, battery exemption present, foreground AgentService active, 58 scheduler matches and zero sampled fatal/ANR. Certificate remains private at `/tmp/amara-audit/social-final-certificate.log`. Inspection pause cleared to resume normal operation.
