# Commerce, community and learning — 10 September 2026

Owner requested delivery quotations, Terminal payment details, manager WhatsApp handoffs, better conversation tone, engagement after ads and stronger market/learning feedback.

## Implemented scope

- Authenticated, business-scoped commerce projection supplies Terminal merchant codes, active public product/service payment links and delivery configuration. Order/private links and other sellers' links are excluded. No payment credential or payment creation API is exposed.
- WhatsApp can ask for an explicit map pin/coordinates, calculate an estimate with Terminal origin, enabled/verified status, coverage radius, base/per-km rates, min/max and UGX rounding. Unknown locations/rates are referred for confirmation. This is a seller-delivery estimate, not a road-route or courier quotation.
- Payment replies use deterministic Terminal instructions. A payment link requires one unambiguous full-title match in customer history, correct product/service ID and type, active link, explicit positive UGX amount and canonical HTTPS Soko URL. Unmatched items require clarification; private customer-specific order links are not guessed. A customer saying they paid is not payment verification.
- Manager WhatsApp is a separate, validated setting. Verified customer replies can queue inquiries/order-intent reports. A periodic read-only order monitor establishes a baseline, then queues subsequent paid/delivered changes using durable report identities. Exact destination/content verification remains required. Missing manager configuration leaves external reporting disabled.
- WhatsApp defaults to no emoji. A friendly acknowledgement can retain one whole emoji; payment, price, complaint and problem messages have none. Prompt guidance asks for concise, warm professionalism without forced slang or repeated greetings.
- A verified TikTok feed post can spawn four sequential community opportunities, one comment maximum each. Steps are separated by at least a minute, expire after 25 minutes and yield to priority customer/posting work. Queries vary across relevant Ugandan business topics. Distinct creators, contextual evidence, duplicate reservations, a 48-attempt rolling daily ceiling and phone budgets still apply. Four opportunities do not guarantee four useful comments.
- Queue compaction now preserves customer comment notifications and post-ad community work when routine scans refresh. Order monitoring and market review also have separate replacement buckets.
- Own-ad comment review accepts useful feedback beyond buying questions, uses catalogue facts only when one item matches the visible own post, and can rebuild routes from still-present notifications.
- Market review persists hourly category snapshots and current catalogue comparisons. Jiji/Jumia now record a first/daily price observation as well as changes. Sparse samples remain UNKNOWN; recommendations do not authorize price changes.
- Learning records which evidence-backed timing adjustment was used for an executed work item and its actual outcome. Scoring candidates alone does not count as application. These are execution outcomes, not proven conversion improvements or autonomous code repair.

## Live business prerequisites

The current Sanaa Media projection has one active public payment link, no configured MTN/Airtel merchant codes and no seller delivery profile. The owner was asked for the manager number and delivery/payment settings. Do not invent these values or substitute the public advertising contact for the manager.

## Validation

Backend projection tests passed (3 tests, 9 assertions), including cross-seller/private/expired link exclusion. Final Android suite: 109 tests, zero failures/errors/skips; APK assembly passed. Flutter settings screen analysis and diff checks passed. Tests cover fee calculation, missing/invalid configuration, payment code handling, emoji policy, queue isolation, comment reservations, market persistence, learning decisions and existing workflow boundaries.

Installed in place on OPPO CPH1933 `7aef1a4c`, Android 11, version 0.10.0 (13), process 17132. Local and installed APK SHA-256 match: `9d464c8fae94c65a6a9f881c7622dc715718f15c85adf9b351e686e6bd86b943`. Accessibility enabled/bound/not crashed; overlay, notification listener permission, battery exemption and foreground service passed. 60 scheduler package matches, zero recent fatal/ANR matches. Inspection pause cleared and work loop woken.

The live market review persisted one category snapshot and 96 catalogue comparison records. All 96 positions are UNKNOWN because matching competitor evidence is insufficient; this is not proof of competitive pricing. Price history is still empty until the next successful capture. Learning schema migration succeeded; no new applied-decision outcome existed in the immediate post-install snapshot.

Private evidence under `artifacts/defence-migration-20260909/`: `growth-commerce-before.tar`, `growth-commerce-after.tar`, `growth-commerce-install.txt`, `growth-commerce-certificate.txt`. No customer message or payment was generated merely to test installation. New manager/delivery/payment flows require the missing business configuration and live customer validation; a full four-opportunity post-ad cycle has not yet been demonstrated on this build.

No seven-day acceptance gate or commercial outcome is implied by these changes.
