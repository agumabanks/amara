# Reusable, channel-aware creatives

Owner requested reusable photos/videos, stronger type hierarchy, varied brand backgrounds, group posters, relevant concise hashtags, better captions and TikTok's automatic audio after media import.

## Implemented

- TikTok chooses video for services and multi-image galleries; single-image products use a designed photo. WhatsApp group promotions use static artwork. This is a factual initial heuristic, not a claim of learned ROI optimisation.
- The shared creative library keys saved media by source URL and complete versioned ad specification. Price, contact, brand, gallery, format or design changes produce another key. Separate publication bindings retain their own immutable bytes; no prior uncertain transaction is re-armed. Identical current creative specifications reuse saved files across posts and static group promotions. Existing bound historical group jobs retain their original evidence.
- Brand-safe background variations are selected deterministically across items, so a saved creative remains stable. Both photo and video use the same scene, with 900-weight headlines, 700-weight prices/CTA, 500-weight medium display copy and 300-weight supporting labels on supported Android versions. Full product details remain in captions.
- Captions add a short invitation to inspect or discuss the offering. Hashtags are capped at five: Soko, the short product/service name, shop name, and up to two literal address locations. No unrelated trending tags or fabricated scarcity.
- The first TikTok media editor waits eight seconds before Next. This allows TikTok's own automatic audio assignment to settle; Amara does not open a music picker, download music, or embed sound in saved media. The wait does not guarantee TikTok supplies a soundtrack on every import.
- Settings describes the reusable studio. Preview tooling can prepare static and video variants separately and logs cache hit/miss and preparation time without customer data.

## Validation

39 selected Android tests passed, including cross-publication reuse, price invalidation, photo/video separation, specification round trips, captions, renderer previews, composer checks and persistence. Flutter analysis of the settings screen passed. Native rendered frames inspected for text hierarchy and full-image fit. On-device evidence follows below.

## Mission scope

Five real service records reviewed in SERVICE-FACTS-20260909.md. Published prices and turnaround facts are available, but business authority and commercial targets are not invented. Explicit identity linking, live follow-up/comment cases, attributable orders/costs and the seven-day acceptance run remain separate mission work. The mission remains NOT DEFENSIBLE until its gates have accepted evidence.

## On-device result

Installed APK SHA-256 `68331c1f41a5e54b8709a361886d85ad79105a1223919ed648b29eafb2d46b5a`. Update succeeded; Accessibility enabled/bound/not-crashed, overlay allowed, process running and no recent fatal/ANR matches.

Separate preview publication bindings proved shared-library reuse on OPPO:

| Format | First generation | Second request | Same media file |
|---|---:|---:|---|
| Photo | 792 ms | 6 ms | Yes |
| Video | 20,740 ms | 7 ms | Yes |

Photo SHA-256: `22d2fa8189f9d07982eb0fd4a13a9139695d378c8df7d0498bb01347e9cc6078`. Video SHA-256: `cde634fc8d616e91f6da36b3e4a9d5cc0778987e981d23d5f21fee179f40a2a5`. Logs retained privately in `artifacts/defence-migration-20260909/creative-reuse-device.txt`. Actual phone poster inspected; updated local preview at `artifacts/ad-preview-20260909/index.html` contains both formats. Phone caption preparation confirms `#soko24 #DateStamp #SanaaMedia #NasserRoad #Kampala` and the grounded product link, price and public WhatsApp.

Inspection pause released. The eight-second editor wait is installed; soundtrack presence is controlled by TikTok and has not been independently confirmed for every media import. No music-picker action was added. Existing post-verification and uncertain-delivery protections remain in place.
