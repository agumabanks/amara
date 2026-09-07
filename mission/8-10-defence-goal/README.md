# Amara — 8/10 defence goal

**Goal:** make Amara a dependable sales assistant for Sanaa Media, demonstrated through correct customer care, useful promotion, follow-through and attributable commercial outcomes.

**Current decision: NOT DEFENSIBLE.** Useful capabilities exist and some publications are verified. The available evidence does not prove reliable customer handling, an uninterrupted working week or profitable operation. No readiness percentage is assigned.

The owner requested “8/10-defence goal”. This directory uses `8-10-defence-goal` because `/` separates filesystem directories. Created 6 September 2026. This is the current 8/10 product acceptance pack; earlier mission folders remain historical references, not automatically accepted evidence.

## Start here

1. [Promise and scope](PROMISE.md): who we serve and what they should experience.
2. [Current state](CURRENT-STATE.md): implemented versus observed versus unproven.
3. [Defence standard](DEFENCE.md): proposed gates, sample requirements and score rules.
4. [Module roadmap](MODULE-ROADMAP.md): WhatsApp, groups, TikTok, Soko, learning and reporting.
5. [Task register](tasks.json): priorities, dependencies, verification and implementation status.
6. [Progress checker](PROGRESS.md): generated view of the task register and gates.
7. [Dashboard and charts](charts/dashboard.html): open locally in a browser; no server or internet required. [Printable snapshot](charts/defence-dashboard.pdf) and [preview](charts/dashboard-preview.png) are also included.
8. [Evidence index](evidence/INDEX.md) and [baseline](evidence/baseline.json): frozen historical observations.
9. [Proposals and implementation map](proposals/REGISTER.md): what is a suggestion and what exists.
10. [Evaluation plan](EVALUATION-PLAN.md), [decision log](logs/DECISIONS.md), [execution log](logs/EXECUTION.md).
11. [Improved working prompt](WORKING-PROMPT.md): hand this to the next agent/operator.

## Update discipline

Edit `tasks.json` and `scorecard.json`, add evidence and log entries, then run:

```bash
python3 mission/8-10-defence-goal/scripts/update_progress.py
```

The checker validates references and regenerates progress and the dashboard. It does **not** certify a rating or change the phone. Accept a gate only after a named reviewer checks its evidence; a code change or installed APK alone cannot pass a product gate. Do not edit generated files directly.

Keep raw chats, customer identities, screenshots and credentials outside this pack. Reference private evidence through the index; commit only aggregate/redacted information. Preserve historical windows when starting a new one.
