# Device Evaluation Plan — Revenue Operator

**Status: IN PROGRESS (updated 2026-08-25).** The Oppo CPH1933 (serial 7aef1a4c, Android 11) is
connected over wireless ADB. Gate 0 preconditions have been partially executed: the release app is
installed, accessibility is bound, notification access / overlay / battery exemption are granted,
and permission preflight rows are Active (see TRACEABILITY_MATRIX CE-A1-PERM-01, device_verified
2026-08-25). No live revenue capability is claimed: Gates 1–10 remain to be executed against this
plan, and Gates 11–12 require real elapsed supervision time.
Every gate below requires a connected Oppo test device, the accessibility service bound,
and a supervised session. Local Robolectric suites prove logic only; they never substitute
for these gates, and no business outcome may be marked verified from generated test data.

## Elapsed-time authority

One authoritative distinction governs all duration requirements:
- **24-hour initial stability** — prerequisite before any certification claim: the stable signed
  release runs scheduled work for a real, unbroken 24-hour wall-clock period with zero duplicated
  side effects and honest failure records.
- **Seven-day certification soak** (Gate 11 technical soak) — starts only AFTER the 24-hour
  stability prerequisite completes on the same build hash.
- **30-day supervised commercial trial** (Gate 12) — separate final requirement after the soak.
Elapsed time is never simulated, compressed, or backdated; start timestamps are recorded with the
build SHA-256 in EXECUTION_LOG.md.

## Gate 0 — Preconditions

- ADB device visible; Sanaa Agent installed from a release APK; accessibility service bound.
- Owner account logged into: Soko Seller Terminal (com.soko24.soko_seller_terminal),
  WhatsApp (com.whatsapp), TikTok (com.zhiliaoapp.musically).
- Commercial policy configured by the owner in-app (allowed products, channels, caps,
  quiet hours) — the agent must refuse all outreach until this exists (fail-closed proof).

## Gate 1 — Soko inventory and product binding

1. Scan inventory on-device; compare parsed items against screen truth.
2. Bind ≥3 real product refs to workflow `product_id` subjects.
3. Prove: typed-input retrieval returns only claims whose subject values match bound ids;
   out-of-contract ids are refused before retrieval.
- **Pass:** inventory scan evidence stored with source refs; binding table matches screen.

## Gate 2 — Exact listing edit

1. Propose one field edit through catalog_health workflow; approve via owner chat.
2. Verify save by reopen-compare (SokoSaveVerification path).
3. Kill the app between approval and apply; resume; prove exactly-once apply.
- **Pass:** VERIFIED transaction with reopen-compare evidence; no duplicate saves.

## Gate 3 — WhatsApp target/content verification

1. Send to a controlled test number; capture bubble/delivery observation.
2. Attempt send to an unapproved target → runner rejection, zero dispatch.
3. Mismatched content vs approval → pre-act refusal, approval still consumable for exact content.
- **Pass:** ledger states match observations; no unapproved dispatch ever occurs.

## Gate 4 — Inquiry capture

1. Receive real inbound WhatsApp messages (owner sends from second phone): purchase-intent,
   greeting-only, spam, duplicate, own-number message.
2. Compare admitted qualified inquiries against the metric definition.
- **Pass:** only charter-qualified inquiries enter revenue_inquiries, each with evidence ref.

## Gate 5 — Follow-up delivery

1. Run approved_follow_up end-to-end on-device: park → owner approves → verified send.
2. Enforce per-customer cap: second follow-up same day must be blocked pre-dispatch.
- **Pass:** delivery state observed ("sent"/"delivered"); cap block recorded as BLOCKED_POLICY.

## Gate 6 — Status publication

1. status_campaign publish leg on-device; verify publication surface + content visibility.
2. Prove idempotency: rerun does not repost (DuplicateBlocked/VERIFIED).
- **Pass:** VERIFIED publication evidence; single post observable on second device.

## Gate 7 — TikTok draft/publication

1. Route tiktok_campaign publish leg once the device surface is wired.
2. Until routed, prove honest refusal (rejected-before-act parks the run).
- **Pass:** draft or publish verified by publication-surface rule.

## Gate 8 — Order/sale reconciliation

1. Create a real Soko order (second buyer account); admit sale from order evidence.
2. Cancel it in Soko; run correction; verify ACTIVE-sales views drop it and REFUNDS cost appears.
- **Pass:** sale ledger matches Soko ground truth; profit math reflects correction.

## Gate 9 — Reboot/timezone recovery

1. Mid-workflow reboot; confirm lease expiry + checkpoint resume without duplicate effects.
2. Change timezone across quiet-hours boundary; confirm quiet-hours enforcement follows local time.
- **Pass:** no duplicated sends; enforcement uses device-local time.

## Gate 10 — Offline behavior

1. Airplane mode during morning plan: plan degrades to read-only decision brief, zero external actions.
2. Restore connectivity; confirm no queued spam burst (caps re-checked at execution).
- **Pass:** offline day produces analysis only.

## Gate 11 — Seven-day supervised revenue pilot

- Daily cycle runs under supervision; every external action owner-visible same day.
- Collect: inquiries, sales, attribution labels, costs, suppressions, blocked actions.
- Success criteria: zero policy violations; dashboard figures reconcile against manual
  records within tolerance; all UNKNOWN profits honestly labeled.
- **Exit:** rows upgrade device_pending → device_verified with dated evidence.

## Gate 12 — Thirty-day commercial trial

- Unsupervised-with-audit operation under standing policy; weekly owner review of
  experiments and briefs.
- Real-customer evidence required for any locally_implemented → locally_verified promotion
  beyond logic rows; sale/attribution/profit rows require matched Soko/payment records.
- **Exit:** CE-E-PROD-01 evaluation may proceed; Revenue Operator business-outcome rows may
  move to device_verified / real_customer_verified ONLY with matched human-verified records.

## Evidence discipline

- Every gate writes dated evidence into TRACEABILITY_MATRIX.md rows; states used are exactly the
  checker's vocabulary: `not_started`, `in_progress`, `locally_implemented`, `locally_verified`,
  `device_pending`, `device_blocked`, `device_verified`, `deferred`, `failed`, `superseded`.
  (Business-outcome phrases such as "real_customer_verified" are descriptions inside evidence
  text, never row states.)
- A `device_verified` row additionally requires (enforced by check_traceability.sh): an existing
  raw evidence artifact, device identity, UTC timestamp, command/procedure, expected result,
  observed result, receipt/effect identity where an effect exists, and target-surface proof
  (screenshot/UI hierarchy of the app that was acted on) — Amara's own chat statements alone are
  never sufficient.
- Business outcomes are NEVER marked verified using generated test data.
