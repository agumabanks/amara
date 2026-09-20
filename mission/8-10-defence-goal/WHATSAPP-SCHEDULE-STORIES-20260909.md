# WhatsApp scheduling, delivery and TikTok sound/Stories

## Findings

Live counters showed 59 WhatsApp messages against a shared hardcoded limit of 40. Replies consumed this allowance and blocked owner-scheduled group ads. KCU-CRIC had last succeeded at 14:34 EAT and was due at 16:34. Several other two-hour groups had separate delivery/destination holds. Group proposal also imposed an undocumented 08:00–20:00 window in addition to configured quiet hours.

TikTok's video editor remained on Add sound after the existing wait. On the connected build, opening Add sound loaded TikTok's own default track without selecting any song; closing the panel left Fati attached. A later published Restaurant Management ad exposed Original sound by Tambwe-Océan in its post UI.

## Implemented

- Scheduled destination-managed group ads use their per-group cadence and holds without the shared 40-message cap. Global halt, device budgets, quiet hours and session limits remain enforced.
- Group proposals follow configured quiet hours instead of the extra 08:00–20:00 window. Settings show last confirmed time and whether due.
- WhatsApp media sharing starts a fresh importer with CLEAR_TOP and ClipData, preventing stale task reuse.
- Read-only owner recovery resumes pre-dispatch failures only after exact chat verification; uncertain groups require verification of their bound captioned photo. Historical effect outcomes are retained.
- Shorter, word-boundary group descriptions with HTML quote/apostrophe decoding keep price/link visible.
- Legacy queued direct replies now resolve their destination and recheck exact conversation and enabled switches before dispatch. Group replies recheck current topics/permissions at dispatch.
- TikTok waits first; if Add sound remains, opens TikTok's own recommendation panel once, waits, closes it and requires attached-sound controls before continuing. Amara selects no song. Missing sound ends with a precise blocker instead of silently treating the wait as success.
- TikTok Stories have a separate setting, work kind, transaction key and six-attempt daily cap. They reuse saved artwork from verified feed ads, revalidate catalogue facts and use Your Story in the editor. Unconfirmed publication is held without retrying the feed or Story trigger.

## Live recovery

KCU-CRIC exact chat verified and schedule resumed. Lending a HAPPY HAND's previously uncertain captioned photo was read-verified and its schedule resumed. Business Centre Uganda remained held because delivery could not be proved. Naalya Community Neighborhood remained held because exact chat navigation failed. Other destinations still require individual checks; this is not blanket group success.

## Validation and limits

Initial 52 Android tests passed, followed by four Story confirmation/deduplication tests and the updated integration/group-settings checks. Both Flutter settings screens passed analysis. Broader WhatsApp tests and final live outcomes are recorded below when complete. The module still has unresolved origin-route/destination cases and needs sustained live coverage; the full mission is not accepted.

## Confirmed live results

The recovered Lending a HAPPY HAND promotion completed DONE at approximately 20:23:05 EAT; KCU-CRIC completed DONE at 20:23:45 EAT. Both are unpaused with nextDue exactly two hours later. Configured quiet hours still defer any due send falling inside them. Two inbound reply actions also completed successfully during this run. Private evidence: `whatsapp-recovered-results.tar`.

The Restaurant Management feed ad was read-verified with Original sound by Tambwe-Océan. Its separately dispatched Story was visually confirmed on @sanaamedia with the exact Restaurant Management artwork, From UGX 200,000, Soko CTA and branded closing. Story UI exposed Sound: Dust on the Couch by Bea Tangle. Private screenshots: `tiktok-story-ad-frame.png`, `tiktok-story-live.png`; feed sound UI: `tiktok-restaurant-sound-verified.xml`. The Story's original ledger outcome remains UNCERTAIN because the automatic verifier received no explicit fresh success text. It was not resent. Automatic Story confirmation remains a known gap.

The broader WhatsApp regression run passed 32 tests covering notification parsing, origin identity, follow-up identity/isolation, durability, target matching, group settings/captions, Story contracts and transaction boundaries. Both settings screens passed Flutter analysis. Remaining live issues include unresolved group destinations, older uncertain deliveries and originating-notification route expiry. Follow-up outreach remains subject to configured commercial policy and explicit contact consent; those are not silently bypassed.

Final installed APK: `c6fa382b22f8982b1cf39123352800f0bed69425e807680a92e7e659977816c0`, process 11317. Accessibility enabled/bound/not-crashed, overlay, notification listener, battery exemption and foreground service all passed; 58 scheduled-job matches, zero recent fatal/ANR matches. Inspection pause cleared and normal work woken. Certificate: `whatsapp-module-final-certificate.txt`.
