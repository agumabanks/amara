# Amara fleet reliability and connected devices mission

Created 23 September 2026. **Implementation is in progress; P0 has an audit checkpoint with open live gates.**

Start with [MISSION.md](MISSION.md), then [BASELINE.md](BASELINE.md) and the ordered [PLAN.md](PLAN.md). Give the implementing agent [START_HERE.md](prompts/START_HERE.md). Each phase has its own prompt under `prompts/`.

- [Architecture and reuse boundaries](ARCHITECTURE.md)
- [JSON, identity and command contracts](CONTRACTS.md)
- [Acceptance tests](TEST_MATRIX.md)
- [Operator walkthrough and rollback](WALKTHROUGH.md)
- [Decisions, risks and unresolved evidence](DECISIONS.md)
- [Progress board](PROGRESS.md), [machine progress](progress.json), [checker](check_progress.py)
- [24-hour device evidence](evidence/device-summary.json), [live backend aggregate](evidence/backend-summary.json), [sources](SOURCES.md)

Run `python3 mission/amara-fleet-network-20260923/check_progress.py` from the Android repository. A valid plan is not proof of a working feature. The checker reports incomplete phases and refuses completion without evidence.

## Strengthened reliability requirements

Read [Reliability gates](RELIABILITY_GATES.md), [WhatsApp acceptance](WHATSAPP_ACCEPTANCE.md) and [Community growth](COMMUNITY_GROWTH.md) before implementing. Maintain the [issue register](issue-register.json); it covers every failure-class/work-kind pair in the captured summaries plus focused blockers. The checker now refuses overall completion with unresolved issues or unverified P1 tracks.

## Backend storage decision

[Keep PostgreSQL and extend it](DATABASE_DECISION.md): verified live PostgreSQL 16.15, JSONB event facts, relational identity/receipts, device SQLite outbox, protected media storage and measured scaling. No Firebase or MongoDB migration. Backup/restore and capacity evidence are required before fleet expansion.

## Latest handoff: both-device refresh and livestreaming gate

Start with [24 September fresh device evidence](evidence/REFRESH_20260924.md), then [finish-before-livestreaming prompt](prompts/FINISH_BEFORE_LIVESTREAMING.md). [BEFORE_LIVESTREAMING.md](BEFORE_LIVESTREAMING.md) defines the required final reports and exit gates. Both phones were reachable during this refresh; OPPO v69 and TPS v70 differ. Earlier “OPPO unavailable” checkpoints are historical.
