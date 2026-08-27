#!/usr/bin/env bash
# Mechanical side-effect-boundary enforcement (Corrective checkpoint 3).
# Fails whenever an externally visible operation is invoked outside:
#   1. its defining class (AccessibilityActions.kt), or
#   2. the act lambda of a universal transaction execution.
set -euo pipefail
if [ -n "${1:-}" ] && [ -d "$1/app/src/main" ]; then ROOT="$1"; else ROOT="$(dirname "${BASH_SOURCE[0]}")/../../.."; fi
cd "$ROOT"

python3 - <<'PY'
import pathlib, re, sys

# Derive primitives from declaration-site @RequiresTransaction annotations so new
# external operations are enforced the moment they are marked. Union with the legacy
# list keeps enforcement stable even if an annotation is accidentally dropped.
import re as _re
PRIMITIVES = [
    "sendToWhatsAppPhone", "sendToWhatsAppContact", "sendToWhatsAppGroup",
    "sendWhatsAppAttachment", "sendInCurrentChat", "postWhatsAppTextStatus",
    "postWhatsAppMediaStatus", "postTikTok", "saveEditForm", "updateSokoListing",
    # monitorWhatsAppTarget/stopMonitoringWhatsAppTarget were deleted from
    # SecureConfig: monitoring consent now moves only through the ContactDirectory
    # inside side-effect transactions, so no untransacted primitive remains.
]
BOUNDARY_FILE = "AccessibilityActions.kt"
# Annotation derivation (and its drift guard) requires the declaration site to exist
# in the scanned root. Synthetic fixture trees intentionally contain only violating
# call sites, so they are scanned against the hardcoded primitive list instead.
_boundary_present = any(_p.name == BOUNDARY_FILE for _p in pathlib.Path("app/src/main").rglob("*.kt"))
_annotated = []
if _boundary_present:
    for _p in pathlib.Path("app/src/main").rglob("*.kt"):
        _src = _p.read_text()
        for _m in _re.finditer(r"@RequiresTransaction\([^)]*\)\s*(?:(?:private|internal|public|override|open)\s+)*(?:suspend )?fun\s+([A-Za-z0-9_]+)", _src):
            _annotated.append(_m.group(1))
    _missing = [n for n in PRIMITIVES if n not in _annotated]
    if _missing:
        print("ANNOTATION DRIFT — hardcoded primitives missing @RequiresTransaction:", _missing)
        sys.exit(1)
    PRIMITIVES = sorted(set(PRIMITIVES + _annotated))
    print(f"Boundary primitives derived from {len(_annotated)} @RequiresTransaction declarations.")
else:
    print("Declaration site absent from scanned root; scanning against hardcoded primitive list.")
violations = []

def strip_braced(text: str, open_idx: int) -> int:
    """Return index just past the balanced brace block opening at open_idx."""
    depth = 0
    j = open_idx
    while j < len(text):
        if text[j] == "{": depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0: return j + 1
        j += 1
    return len(text)

def strip_act_lambdas(text: str) -> str:
    """Remove bodies of `act = { ... }` lambdas and of functions marked TRANSACTION-ACT."""
    out = []
    i = 0
    while True:
        lam = re.search(r"act\s*=\s*\{", text[i:])
        marker = re.search(r"^\s*// TRANSACTION-ACT:.*\n([^\n]*\{)", text[i:], re.M)
        if marker and (not lam or marker.start() < lam.start()):
            open_idx = i + marker.end() - 1
            end = strip_braced(text, open_idx)
            out.append(text[i:open_idx]); out.append("/*ACT*/")
            i = end
            continue
        if lam:
            start = i + lam.end() - 1
            end = strip_braced(text, start)
            out.append(text[i:start]); out.append("/*ACT*/")
            i = end
            continue
        out.append(text[i:])
        break
    return "".join(out)

files = [p for p in pathlib.Path("app/src/main").rglob("*.kt")]
for path in files:
    raw = path.read_text()
    # Drop comments and strings crudely but consistently for scanning.
    scanned = re.sub(r"//.*", "", strip_act_lambdas(raw))
    for prim in PRIMITIVES:
        for m in re.finditer(rf"(?:\.|::|\b){prim}\s*\(", scanned):
            prefix = scanned[max(0, m.start()-12):m.start()]
            if re.search(r"\bfun\s+$", scanned[max(0, m.start()-10):m.start()]):
                continue  # definition
            if path.name == BOUNDARY_FILE:
                continue  # the boundary layer composes its own primitives
            line = scanned[:m.start()].count("\n") + 1
            violations.append(f"{path}:{line}: {prim} outside the transaction boundary")

# Runner call sites must reference CapabilityIds constants — never string literals.
for path in files:
    if path.name == "SideEffectTransaction.kt":
        continue
    text = path.read_text()
    for m in re.finditer(r"sideEffects\s*\.execute\(\s*(?:capabilityId\s*=\s*)?(\"[^\"]+\")", text):
        violations.append(f"{path}: literal capability id {m.group(1)} passed to the runner; use CapabilityIds")

if violations:
    print("SIDE-EFFECT BOUNDARY VIOLATIONS:")
    for v in violations: print(" -", v)
    sys.exit(1)
print(f"Side-effect boundary clean across {len(files)} Kotlin source files.")
PY
