# Soldier agent startup prompt

Implement the mission in `mission/amara-fleet-network-20260923`, one evidence-gated phase at a time. Read MISSION, BASELINE, PLAN, ARCHITECTURE, CONTRACTS, TEST_MATRIX, DECISIONS and current progress.json before editing code. Start at the first incomplete phase; run its prompt. Respect the owner's “polish existing implementation” requirement and current project instructions.

First inspect Android/backend git status and relevant code. Preserve unrelated uncommitted work. Verify live device serials and capture baseline without secrets. Do not infer that both phones run the same build. Do not declare success from DONE counts, unit tests, taps or uploads alone. Document blocked external dependencies accurately and continue useful independent work.

Implement only the current phase's necessary changes, run meaningful regression and negative tests, and record changed files, compatibility/migration behavior, deployment identity and rollback. Before device recovery/deployment read the available amara-apk-device-recovery skill. Use existing authorization and owner-designated test destinations for live effects; if scope is missing, finish reviewable non-effect work and identify the specific missing scope. Never reset uncertain transactions, silently turn telemetry on, or weaken target verification.

After each milestone update progress.json and an evidence report, run check_progress.py, and leave exact next steps. Current planning evidence does not satisfy live acceptance. New remote commands and learning remain disabled until their gates pass. Do not dispatch other agents merely because this handoff uses the word soldier; follow current delegation instructions.

## Required issue coverage and current-build protection

Read RELIABILITY_GATES.md, WHATSAPP_ACCEPTANCE.md and COMMUNITY_GROWTH.md. Populate issue-register.json from fresh per-build evidence, including unresolved PARTIAL/ESCALATED/UNCERTAIN work and all historical OBS rows. Preserve working flows as regression cases. Use per-issue closure evidence and the five P1 track gates. No deleting issue rows or marking idle flows fixed to pass the checker.

Read DATABASE_DECISION.md before backend work. Keep Cards PostgreSQL, protect the separate Soko read-only connection, and satisfy capacity/restore gates. No engine migration is part of this mission.
