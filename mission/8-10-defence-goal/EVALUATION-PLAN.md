# Evaluation and evidence collection

## Before the run

1. Resolve owner decisions D1–D6. Fix scope, business facts, coverage hours, eligible audiences and acceptance thresholds.
2. Record APK hash, settings snapshot with credentials excluded, catalogue version, device identity and notification/accessibility readiness.
3. Reconcile historical uncertain actions read-only. Keep unresolved cases visible and separately labelled; do not reset their ledgers to get a cleaner start.
4. Review group membership, exact identity, posting rights and audience fit. Existing failed destinations are not automatically repaired by a pause feature.
5. Capture baseline enquiries, paid orders, fulfilment costs and owner time over a comparable period where available. Record seasonal/context limitations.

## Three stages

**A. Controlled journey checks:** use owner-designated test conversations/groups with explicit task-specific external-action authorisation. Cover same names, unavailable destination, delayed send evidence, route loss, opt-out, customer correction and owner handoff. Mark all test traffic; exclude it from demand/revenue claims.

**B. Real pilot:** observe Sanaa Media for seven consecutive days or longer to reach the minimum samples. Do not manufacture customer demand. Keep the build/scope stable. Record owner/developer interventions and outage periods. If changes are necessary, segment the data and restart affected acceptance evidence.

**C. Defence review:** reviewer checks all denominators and exclusions, content quality, delivery evidence, paid-order attribution, costs and owner independence. Accept only the evidenced business/device scope.

## Existing logger and its limitation

The current collector is `scripts/evaluation/collect_five_hours.py`; phone journal is `EvaluationJournal`. Both implement a bounded five-hour window. The recorded September 6 window is **complete**, not still running. This mission pack does not start another one.

A seven-day proof requires a separately implemented and verified observation plan: e.g. longer bounded journalling/collection with retention and safe restarts, or explicitly documented contiguous windows with coverage checks. Do not call discontinuous five-hour samples an uninterrupted week. Task OBS-01 tracks this missing capability.

Keep collector reads non-destructive: do not open apps or restart Amara merely to observe health. Capture failures and preserve last good data. Standard operational logs and per-group state may remain available after the bounded evaluation ends, but are not a substitute for complete measured coverage.

## Evidence bundle per run

- Aggregate events and source hashes; raw records in private storage.
- Build/settings manifest and evaluation start/end timestamps.
- Opportunity register including eligible, excluded-with-reason, superseded, pending and unresolved items.
- Delivery receipts separate from work completion; quality review separate from delivery.
- Order/payment/refund/cost references and direct/assisted/unknown attribution.
- Owner intervention log and daily reports.
- Learning/change ledger and before/after listing/content evidence.

Use the templates in `templates/`. Add a new run folder under `evidence/` rather than overwriting the baseline. Do not put PINs, API keys, personal message bodies, full numbers or customer payment details into repository files.
