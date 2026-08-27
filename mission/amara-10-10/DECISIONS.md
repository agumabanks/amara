# Decision Log

## D-001 — Screen-first Soko operation

Amara uses the installed Soko apps through Accessibility for operational work. No new Soko API or backend dependency is introduced.

## D-002 — High-level skills own recovery

Trusted Soko skills perform bounded recovery internally. The model does not append contradictory recovery plans after they return a verified blocker.

## D-003 — Verification defines completion

A tap, intent launch, or accepted Android action is not completion. The expected post-state must be observed.

## D-004 — Risk-based autonomy

Read and recommend operations may be proactive under policy. External communication and modifications need explicit or standing approval. Security, financial, destructive, and material changes require fresh approval.

## D-005 — Device gates remain device gates

Unit and widget tests may mark code complete but cannot mark an Oppo gate verified.

## D-006 — Visual claims require visual evidence

Accessibility text alone cannot prove an image/title mismatch. Amara must use a supported screenshot/visual analyzer or report the limitation.

## D-007 — Explicit recurring commands are narrow standing policies

An owner-created recurring task is authorization only for its stored task text and deterministic schedule while its switch remains enabled. The policy does not authorize a different target, content class, capability, price/image/security/financial change, or a model-expanded side effect. Every occurrence is claimed before execution and is never blindly repeated after uncertainty.

## D-008 — External side effects have a zero blind-retry budget

The retry budget for a possibly completed message, post, or edit is zero unless independent observation proves it did not happen. Read-only navigation and observation may retry within bounded recovery. Quiet hours apply to proactive audits; an exact time explicitly chosen by the owner remains intentional.
