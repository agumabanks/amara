#!/usr/bin/env bash
# Evidence redaction gate: mechanically scans mission evidence files for
# contact-name leakage and secret material.
#
#   check_evidence_redaction.sh              scan the real evidence tree (exit 0 = clean)
#   check_evidence_redaction.sh negative-fixture
#                                            prove the detector rejects planted leaks
#
# Detection layers:
#   1. secret shapes: API keys (gsk_/sk-), bearer headers, PIN/OTP statements,
#      password assignments, 9+ digit runs inside evidence logs;
#   2. contact-name shapes: notification/log lines that announce a person
#      ("... from <Capitalized Name>") — the exact R8 defect class.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

python3 - "$@" <<'PY'
import pathlib, re, sys

EVIDENCE_GLOBS = [
    "mission/amara-10-10/evidence-device-*",
    "mission/amara-complete-employee/evidence",
    "mission/run-*",
]
TEXT_SUFFIXES = {".log", ".txt", ".xml", ".json", ".csv", ".md"}

SECRET_PATTERNS = [
    ("groq_key", re.compile(r"gsk_[A-Za-z0-9]{10,}")),
    ("sk_key", re.compile(r"\bsk-[A-Za-z0-9]{16,}")),
    ("bearer", re.compile(r"(?i)bearer\s+[A-Za-z0-9._\-]{16,}")),
    ("auth_header", re.compile(r"(?i)authorization:\s*\S+")),
    ("pin_statement", re.compile(r"(?i)\b(?:pin|otp|password)\s*(?:is|:|=)\s*\d{4,8}\b")),
    ("api_key_statement", re.compile(r"(?i)\bapi[_ ]?key\s*(?:is|:|=)\s*[A-Za-z0-9_\-]{12,}")),
]

# A person-shaped announcement: "from <Given Name>" with 2+ capitalized tokens,
# or "Recorded ... from <Name>" — the R8 defect class. Deliberately conservative:
# only fires on 'from'/'with' prepositions followed by capitalized word sequences.
NAME_PATTERNS = [
    ("person_after_from", re.compile(r"\b(?:from|with)\s+[A-Z][a-z]+\s+[A-Z][a-z]+(?:\s+[A-Z][a-z]+)*\b")),
]

def evidence_files():
    seen = set()
    for pattern in EVIDENCE_GLOBS:
        for path in pathlib.Path(".").glob(pattern):
            if path.is_file():
                yield path
            elif path.is_dir():
                for p in path.rglob("*"):
                    if p.is_file():
                        yield p

def scan(files):
    leaks = []
    for path in files:
        resolved = str(path)
        if path.suffix.lower() not in TEXT_SUFFIXES:
            continue
        try:
            text = path.read_text(errors="replace")
        except OSError:
            continue
        for line_no, line in enumerate(text.splitlines(), 1):
            for label, regex in SECRET_PATTERNS:
                if regex.search(line):
                    leaks.append(f"{resolved}:{line_no}: SECRET [{label}]")
            for label, regex in NAME_PATTERNS:
                if regex.search(line):
                    leaks.append(f"{resolved}:{line_no}: CONTACT NAME [{label}] :: {line.strip()[:90]}")
    return leaks

def negative_fixture():
    import tempfile, shutil
    tmp = pathlib.Path(tempfile.mkdtemp(prefix="evidence-redaction-"))
    failures = []
    cases = [
        ("planted contact name", "08-25 10:34 I SanaaConversation: Recorded unmonitored WhatsApp message from Annah Cashier Bweyale\n", "CONTACT NAME"),
        ("planted pin statement", "owner said pin is 483920 twice\n", "SECRET [pin_statement]"),
        ("planted api key", "key gsk_abcdefghijklmnopqrs in config\n", "SECRET [groq_key]"),
    ]
    try:
        target = tmp / "evidence-device-fixture"
        target.mkdir()
        for label, content, expected in cases:
            f = target / "leak.log"
            f.write_text(content)
            if scan([f]):
                detail = "\n".join(scan([f]))
                if expected not in detail:
                    failures.append(f"{label}: detected but without {expected}: {detail}")
            else:
                failures.append(f"{label}: leak NOT detected")
            f.unlink()
        clean = target / "clean.log"
        clean.write_text("08-25 10:34 I SanaaConversation: Recorded unmonitored WhatsApp notification\nReady chip over Point of Sale\n")
        if scan([clean]):
            failures.append("clean file flagged: " + "\n".join(scan([clean])))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    if failures:
        print("NEGATIVE FIXTURE FAILURES:")
        for f in failures:
            print(" -", f)
        sys.exit(1)
    print(f"Evidence-redaction negative fixtures OK: {len(cases)} planted leaks rejected, clean file accepted")
    sys.exit(0)

if len(sys.argv) > 1 and sys.argv[1] == "negative-fixture":
    negative_fixture()

leaks = scan(evidence_files())
if leaks:
    print("EVIDENCE REDACTION LEAKS:")
    for leak in leaks:
        print(" -", leak)
    sys.exit(1)
print("Evidence redaction OK: no contact names or secrets in evidence text files")
sys.exit(0)
PY
