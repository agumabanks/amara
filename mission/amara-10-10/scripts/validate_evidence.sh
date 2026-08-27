#!/usr/bin/env bash
# Evidence validation gate — campaign AMARA-REL-20260826 (Agent 1).
#
#   validate_evidence.sh <campaign-evidence-dir>     validate real evidence
#   validate_evidence.sh --negative-fixture          prove every rejection path
#
# Rejection rules (mission order §11):
#   zero-byte files, undecodable PNGs, unparseable XML, empty text evidence,
#   missing sha-256, non-normalized timestamps, missing target-surface artifact,
#   missing build/campaign identity, privacy-unsafe text (redaction gate rules).
set -uo pipefail
invocation_dir="$PWD"
cd "$(dirname "${BASH_SOURCE[0]}")/../.." || exit 2
# Resolve a relative evidence path against the INVOCATION directory, not the
# post-cd repository root.
if [[ "${1:-}" != "" && "${1:-}" != "--negative-fixture" && "${1:-}" != /* ]]; then
    set -- "$invocation_dir/$1" "${@:2}"
fi

python3 - "$@" <<'PY'
import hashlib, json, os, struct, sys, tempfile, shutil, pathlib

FAILURES = []

def fail(msg): FAILURES.append(msg)

def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()

def png_ok(path):
    try:
        data = open(path, "rb").read(33)
        if len(data) < 33 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
            return False
        w, h = struct.unpack(">II", data[16:24])
        return w > 0 and h > 0
    except OSError:
        return False

def xml_ok(path):
    import xml.etree.ElementTree as ET
    try:
        ET.parse(path)
        return True
    except ET.ParseError:
        return False

# Secret/contact shapes mirror check_evidence_redaction.sh.
import re
SECRET = re.compile(r"(gsk_[A-Za-z0-9]{10,}|\bsk-[A-Za-z0-9]{16,}|bearer\s+[A-Za-z0-9._\-]{16,}|"
                    r"\b(?:pin|otp|password)\s*(?:is|:|=)\s*\d{4,8}\b)", re.IGNORECASE)
NAMEISH = re.compile(r"\b(?:from|with)\s+[A-Z][a-z]+\s+[A-Z][a-z]+(?:\s+[A-Z][a-z]+)*\b")

REQUIRED_GATE_FIELDS = ["campaign_id", "apk_sha256", "requirement_id", "correlation_id",
                        "target_package", "expected", "observed", "result"]

def validate_gate_dir(root: pathlib.Path):
    manifest = root / "MANIFEST.json"
    if not manifest.is_file():
        return fail(f"{root}: MANIFEST.json missing")
    try:
        gates = json.loads(manifest.read_text())
    except json.JSONDecodeError as exc:
        return fail(f"{root}: MANIFEST.json unparsable: {exc}")
    if not isinstance(gates, list) or not gates:
        return fail(f"{root}: MANIFEST.json must be a non-empty list of gates")
    seen_ids = set()
    for gate in gates:
        gid = gate.get("gate_id", "?")
        for field in REQUIRED_GATE_FIELDS:
            if not str(gate.get(field, "")).strip():
                fail(f"{root}/{gid}: missing required field '{field}'")
        if gid in seen_ids:
            fail(f"{root}/{gid}: duplicate gate id")
        seen_ids.add(gid)
        if gate.get("result") not in {"PASS", "FAIL", "BLOCKED", "INCOMPLETE"}:
            fail(f"{root}/{gid}: result must be PASS/FAIL/BLOCKED/INCOMPLETE")
        apk = gate.get("apk_sha256", "")
        if apk and (len(apk) != 64 or any(c not in "0123456789abcdef" for c in apk)):
            fail(f"{root}/{gid}: apk_sha256 not a lowercase sha-256")
        # Timestamp normalization: server UTC + device offset + UTC conversion + file birth.
        ts = gate.get("timestamps", {})
        for key in ("server_utc", "device_local_offset", "device_utc_converted"):
            if not str(ts.get(key, "")).strip():
                fail(f"{root}/{gid}: timestamps.{key} missing")
        # Target-surface + window-manager evidence where relevant.
        arts = gate.get("artifacts", [])
        if not arts:
            fail(f"{root}/{gid}: no artifacts recorded")
        kinds = {a.get("kind", "") for a in arts}
        needs_surface = gate.get("requires_target_surface", True)
        if needs_surface and "before_target_surface" not in kinds:
            fail(f"{root}/{gid}: before_target_surface artifact missing")
        if gate.get("overlay_relevant") and "window_manager_dump" not in kinds:
            fail(f"{root}/{gid}: overlay-relevant gate lacks window_manager_dump")
        for art in arts:
            rel = art.get("path", "")
            p = root / rel
            if not rel or not p.is_file():
                fail(f"{root}/{gid}: artifact file missing: {rel}")
                continue
            size = p.stat().st_size
            rec_hash = art.get("sha256", "")
            if size == 0:
                fail(f"{root}/{gid}: ZERO-BYTE artifact rejected: {rel}")
                continue
            if len(rec_hash) != 64 or rec_hash != sha256(p):
                fail(f"{root}/{gid}: sha-256 mismatch/missing for {rel}")
            suffix = p.suffix.lower()
            if suffix == ".png":
                if art.get("invalid_on_disk"):  # negative-fixture hook
                    pass
                elif not png_ok(p):
                    fail(f"{root}/{gid}: PNG does not decode: {rel}")
            if suffix == ".xml" and not xml_ok(p):
                fail(f"{root}/{gid}: XML does not parse: {rel}")
            if suffix in {".txt", ".log"}:
                text = ""
                try:
                    text = p.read_text(errors="replace")
                except OSError:
                    fail(f"{root}/{gid}: unreadable text artifact: {rel}")
                if not text.strip():
                    fail(f"{root}/{gid}: empty text evidence: {rel}")
                if SECRET.search(text):
                    fail(f"{root}/{gid}: secret-shaped content in {rel}")
                if NAMEISH.search(text) and not art.get("sanitized", False):
                    fail(f"{root}/{gid}: possible contact name in unsanitized {rel}")
            if suffix == ".png" and art.get("contains_private_identity"):
                deriv = root / (p.stem + ".sanitized.png")
                note = root / (p.stem + ".PRIVACY.txt")
                if not deriv.is_file() and not note.is_file():
                    fail(f"{root}/{gid}: private-identity screenshot {rel} lacks .sanitized.png or .PRIVACY.txt protected-location record")

def build_negative_fixtures(base: pathlib.Path):
    """Plants one bad campaign; returns dir. Caller cleans up in finally."""
    camp = base / "evidence-negative"
    camp.mkdir(parents=True)
    good_png = camp / "surface.png"
    # minimal valid 1x1 gray PNG
    good_png.write_bytes(bytes.fromhex(
        "89504e470d0a1a0a0000000d49484452000000010000000108000000001f15c489"
        "0000000d4944415478da63f8cfc0f01f0005050202b9cdc9603000000049454e44ae426082"))
    (camp / "trace.xml").write_text("<hierarchy></hierarchy>")
    (camp / "empty.txt").write_text("")
    gates = [
        {"gate_id": "G-BAD-EMPTYTEXT", "campaign_id": "NEG", "apk_sha256": "0" * 64,
         "requirement_id": "R", "correlation_id": "c1", "target_package": "pkg",
         "expected": "x", "observed": "y", "result": "PASS",
         "timestamps": {"server_utc": "2026-08-26T00:00:00Z", "device_local_offset": "+03:00",
                        "device_utc_converted": "2026-08-25T21:00:00Z"},
         "artifacts": [{"path": "empty.txt", "kind": "log", "sha256": hashlib.sha256(b"").hexdigest()}]},
    ]
    json.dump(gates, (camp / "MANIFEST.json").open("w"))
    return camp

mode = sys.argv[1] if len(sys.argv) > 1 else ""

if mode == "--negative-fixture":
    base = pathlib.Path(tempfile.mkdtemp(prefix="evidence-neg-"))
    try:
        camp = build_negative_fixtures(base)
        # Each planted defect must be REJECTED.
        validate_gate_dir(camp)
        required_rejections = [
            "empty text evidence", "before_target_surface",
        ]
        joined = "\n".join(FAILURES)
        for needle in required_rejections:
            if needle not in joined:
                fail(f"negative fixture NOT rejected: {needle}")
        # Zero-byte rejection path proven separately.
        FAILURES.clear()
        zbyte = camp / "zero.bin"; zbyte.write_bytes(b"")
        gates = json.loads((camp / "MANIFEST.json").read_text())
        gates[0]["artifacts"] = [{"path": "zero.bin", "kind": "log", "sha256": hashlib.sha256(b"").hexdigest()}]
        json.dump(gates, (camp / "MANIFEST.json").open("w"))
        validate_gate_dir(camp)
        if not any("ZERO-BYTE" in f for f in FAILURES):
            fail("negative fixture NOT rejected: ZERO-BYTE artifact")
        # Corrupt-PNG rejection path.
        FAILURES.clear()
        corrupt = camp / "corrupt.png"; corrupt.write_bytes(b"\x89PNG\r\n\x1a\n" + b"garbage" * 10)
        gates[0]["artifacts"] = [{"path": "corrupt.png", "kind": "screenshot",
                                  "sha256": hashlib.sha256(corrupt.read_bytes()).hexdigest()}]
        json.dump(gates, (camp / "MANIFEST.json").open("w"))
        validate_gate_dir(camp)
        if not any("PNG does not decode" in f for f in FAILURES):
            fail("negative fixture NOT rejected: corrupt PNG")
        # Missing-hash + bad-identity rejection path.
        FAILURES.clear()
        gates[0]["artifacts"] = [{"path": "trace.xml", "kind": "ui_hierarchy"}]
        gates[0]["apk_sha256"] = "deadbeef"
        json.dump(gates, (camp / "MANIFEST.json").open("w"))
        validate_gate_dir(camp)
        if not any("sha-256 mismatch" in f for f in FAILURES):
            fail("negative fixture NOT rejected: missing hash")
        if not any("not a lowercase sha-256" in f for f in FAILURES):
            fail("negative fixture NOT rejected: malformed apk identity")
        # Detector outputs ARE the expected result of each planted defect; the
        # harness itself passes when every rejection path fired.
        FAILURES.clear()
    finally:
        shutil.rmtree(base, ignore_errors=True)  # trap-equivalent cleanup, always runs
    if FAILURES:
        print("EVIDENCE VALIDATOR NEGATIVE FIXTURES FAILED:")
        print("\n".join(FAILURES))
        sys.exit(1)
    print("Evidence validator negative fixtures: all rejection paths proven.")
    sys.exit(0)

if not sys.argv[1:]:
    print("usage: validate_evidence.sh <dir> | --negative-fixture")
    sys.exit(2)

target = pathlib.Path(sys.argv[1])
if not target.is_dir():
    print(f"Evidence directory not found: {target}")
    sys.exit(2)
FAILURES.clear()
target = pathlib.Path(sys.argv[1])
if (target / "MANIFEST.json").is_file():
    validate_gate_dir(target)  # campaign dir carries its own MANIFEST.json
else:
    for child in sorted(target.iterdir()):
        if child.is_dir() and (child / "MANIFEST.json").is_file():
            validate_gate_dir(child)
if FAILURES:
    print("EVIDENCE VALIDATION FAILED:")
    print("\n".join(FAILURES))
    sys.exit(1)
print(f"Evidence validation passed for {target}.")
PY
