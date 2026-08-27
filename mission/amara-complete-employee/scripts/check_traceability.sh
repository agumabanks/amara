#!/usr/bin/env bash
# Traceability integrity checker (hardened re-audit implementation).
#
# Validates, for every CE- row in TRACEABILITY_MATRIX.md:
#   1. unique IDs and legal state vocabulary;
#   2. blocker explanation for device-blocked/pending rows;
#   3. EVERY cited implementation path exists (not merely the first);
#   4. every cited evidence artifact path exists;
#   5. focused tests exist as ClassName.method( in the test sources;
#   6. JUnit XML parsed with a real XML parser: suite healthy, exact method ran;
#   7. freshness: XML postdates BOTH the implementation files and the test source;
#   8. locally_verified rows carry an evidence-kind tag, show no contradictory scope
#      claims, depend only on non-open prerequisites, and prove production reachability
#      (focused test exercises the cited production class, or wired(...) names a symbol
#      that really exists under app/src/main);
#   9. matrix state counts match the '[counts]' block recorded in EXECUTION_LOG.md;
#  10. normative MISSION.md requirements are covered by atomic rows;
#  11. EVERY physical '| CE-' line in the file is parsed exactly once: secondary
#      requirements tables can never be silently ignored (physical == parsed == unique
#      == state-total, four-way guard);
#  12. the release APK postdates every production source file and matches the sha256
#      recorded in EXECUTION_LOG.md (stale APK detection);
#  13. EXECUTION_LOG's declared JUnit test total matches the fresh XML results;
#  14. single-authority rule: forbidden split-brain duplicate implementations of the
#      same metric domain cannot coexist under app/src/main;
#  15. cited production classes are actually constructed outside their own file and
#      cited production methods are not exercised only from test sources.
#
# `negative-fixture` runs the checker against independently mutated temporary
# matrices/environments and proves each defect is genuinely rejected.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../../.."
export TRACEABILITY_SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
exec python3 - "$@" <<'PY'
import glob, os, pathlib, re, shutil, subprocess, sys, tempfile
import xml.etree.ElementTree as ET

SCRIPT = os.environ.get("TRACEABILITY_SCRIPT", "mission/amara-complete-employee/scripts/check_traceability.sh")
DEFAULT_MATRIX = "mission/amara-complete-employee/TRACEABILITY_MATRIX.md"
DEFAULT_RESULTS = "app/build/test-results/testDebugUnitTest"
DEFAULT_LOG = "mission/amara-complete-employee/EXECUTION_LOG.md"
DEFAULT_MISSION = "mission/amara-complete-employee/MISSION.md"
DEFAULT_CHARTER = "mission/amara-complete-employee/REVENUE_OPERATOR_CHARTER.md"
DEFAULT_APK = "app/build/outputs/apk/release/app-release.apk"
DEFAULT_MAIN = "app/src/main"

# Single-authority rule: for each metric domain the first list names the ONLY allowed
# authority symbols; any forbidden symbol declared or constructed under app/src/main is
# a split-brain implementation of the same metric and fails the audit.
AUTHORITY_RULES = [
    ("revenue metric ledger", {"RevenueMetricEngine", "RevenueStore", "RevenueOperatorRuntime"},
     {"CommerceEngine"}),
]

ALLOWED_STATES = {"not_started","in_progress","locally_implemented","locally_verified",
                  "device_pending","device_blocked","device_verified","deferred","failed","superseded"}
OPEN_STATES = {"not_started", "in_progress", "failed", "deferred", "superseded"}
UNRESOLVED_MARKERS = ("not_started", "pending", "absent", "todo", "tbd")
EVIDENCE_KINDS = ("structural", "mock", "device", "provider", "production")

CONTRA_PATTERNS = [
    r"\b(needs?|requires?|awaits?)\b[^.|]*\b(oppo|device|provider|credentials|soak)\b",
    r"\b(device|provider)[- ]gated\b",
    r"\bsimulation\b|\bsimulated\b|\bfake\b|\bstub\b",
    r"\bunverified\b|\boutstanding\b|\bnot proven\b|\bfidelity unverified\b|\bfidelity review\b",
]

MISSION_ANCHORS = [
    (r"documents,\s+spreadsheets, presentations, PDFs, images",
     ["CE-C-ART-MD-01","CE-C-ART-CSV-01","CE-C-ART-PPTX-01","CE-C-ART-PDF-01","CE-C-ART-IMG-01","CE-C-ART-JSON-01"]),
    (r"Typed skills with declared inputs", ["CE-A1-SPEC-01","CE-A1-SCHEMA-01"]),
    (r"universal side-effect transactions", ["CE-A2-STATE-01","CE-A2-PERSIST-01"]),
    (r"prompt-injection defenses", ["CE-A5-TYPED-01","CE-A5-ADV-01"]),
    (r"telemetry opt-in/redaction", ["CE-A6-OPTIN-01","CE-A6-RED-01"]),
    (r"data retention", ["CE-A6-RETAIN-01","CE-A6-DEL-01"]),
    (r"approval-to-execution state machines", ["CE-A4-EXEC-01","CE-A4-EXPIRY-01"]),
    (r"typed work contracts", ["CE-B-CONTRACT-01"]),
    (r"resumable checkpoints", ["CE-B-CKPT-01"]),
    (r"deadline/budget management", ["CE-B-BUDGET-01","CE-B-DEADLINE-01"]),
    (r"missed-work states", ["CE-B-MISSDEF-01"]),
    (r"scoped retrieval", ["CE-A6-SCOPE-01","CE-C-RETRIEVE-01"]),
    (r"provenance/freshness", ["CE-C-PROV-01","CE-C-FRESH-01"]),
    (r"artifact review rubrics", ["CE-C-RUB-FACT-01","CE-C-RUB-PRIV-01"]),
    (r"approved connectors for email, calendar, files, projects, and CRM", ["CE-D-SPEC-01","CE-D-GRANT-01","CE-D-REAL-01"]),
    (r"revocation|revoke", ["CE-D-REVOK-01"]),
    (r"Package complete workflows", ["CE-E-DEF-01"]),
    (r"adversarial evaluation, real-device matrix", ["CE-F-METRICS-01","CE-F-LADDER-01","CE-F-SOAK-01"]),
    (r"30-day supervised production trial", ["CE-F-TRIAL-01"]),
]

CHARTER_ANCHORS = [
    (r"produce at least one verified qualified customer inquiry", ["CE-RO2-METRIC-DEF-01"]),
    (r"three completed sales", ["CE-RO2-POLICY-01", "CE-RO2-SALE-CORR-01"]),
    (r"exceed.{0,40}operating and subscription costs|attributable gross profit should exceed",
     ["CE-RO2-PROFIT-02-1"]),
    (r"never report an inquiry, sale, revenue contribution, or target completion without durable supporting evidence",
     ["CE-RO2-METRIC-DEF-01", "CE-RO2-SALE-CORR-01"]),
    (r"Draft orders and unverified promises do not count", ["CE-RO2-SALE-CORR-01"]),
    (r"distinguish influenced revenue from directly attributable", ["CE-RO2-ATTR-02-1"]),
    (r"hypothesis;", ["CE-RO-EXP-01"]),
    (r"one clearly identified change", ["CE-RO-EXP-01"]),
    (r"rollback rule", ["CE-RO-EXP-01"]),
    (r"must not compensate for weak performance by increasing message volume",
     ["CE-RO-EXP-02"]),
    (r"read-only and draft work: catalog auditing", ["CE-RO-IDLE-01"]),
    (r"end-of-day commercial brief with evidence", ["CE-RO-BRIEF-01"]),
]

def find_impl(ref: str):
    m = re.search(r"[A-Za-z0-9_/.-]+\.(?:kt|java|dart|sh|md|json)", ref)
    if not m:
        return None
    rel = m.group(0)
    candidates = [rel,
                  f"app/src/main/kotlin/co/sanaa/agent/{rel}",
                  f"app/src/main/{rel}",
                  f"app/src/test/kotlin/co/sanaa/agent/{rel}",
                  f"{DEFAULT_RESULTS}/{rel}",
                  f"flutter_ui/{rel}",
                  f"mission/amara-complete-employee/{rel}"]
    for c in candidates:
        p = pathlib.Path(c)
        if p.is_file():
            return p
    name = rel.rsplit("/", 1)[-1]
    roots = ["app/src/main", "app/src/test", "mission"]
    override = os.environ.get("TRACEABILITY_MAIN")
    if override:
        roots.insert(0, override)  # fixture/negative-harness main trees resolve first
    for base in roots:
        hits = list(pathlib.Path(base).rglob(name))
        if hits:
            return hits[0]
    return None

def parse_matrix(text):
    """Parse EVERY requirements table in the entire file.

    Any physical line that begins a requirement row is a row, no matter how many
    separate tables, headings or blank-line gaps the file uses. Secondary tables can
    never be silently ignored again.
    """
    return [[c.strip() for c in line.strip().strip("|").split("|")]
            for line in text.splitlines() if line.lstrip().startswith("| CE-")]

MIN_COLUMNS = 13  # header: Req ID .. Last verified

def physical_row_count(text):
    return sum(1 for line in text.splitlines() if line.lstrip().startswith("| CE-"))

def state_of(r):
    cell = next((c for c in r if c in ALLOWED_STATES), None)
    idx = r.index(cell) if cell else -1
    evidence = r[idx - 1] if idx > 0 else ""
    blocker = r[idx + 1] if 0 <= idx + 1 < len(r) else ""
    return cell, idx, evidence, blocker

def parse_xml_results(xml_path, cls, meth):
    try:
        root = ET.parse(str(xml_path)).getroot()
    except ET.ParseError as e:
        return [f"{cls}: result XML does not parse ({e})"]
    problems = []
    failures = int(root.get("failures", "0")); errors_n = int(root.get("errors", "0"))
    total = int(root.get("tests", "0"))
    if failures > 0 or errors_n > 0:
        problems.append(f"{cls}: suite reports failures={failures} errors={errors_n}")
    if total == 0:
        problems.append(f"{cls}: suite declares zero tests")
    match = [c for c in root.iter("testcase")
             if c.get("name") == meth and str(c.get("classname", "")).endswith(cls)]
    if not match:
        problems.append(f"{cls}.{meth}: absent from parsed XML results")
    else:
        for c in match:
            children = list(c)
            if children:
                problems.append(f"{cls}.{meth}: recorded {','.join(ch.tag for ch in children)} in fresh results")
    return problems

def validate(matrix_path, results_dir, log_path, mission_path, strict=True, charter_path=None):
    errors = []
    raw_text = matrix_path.read_text()
    rows = parse_matrix(raw_text)
    if not rows:
        return ["No CE- rows found"], rows

    # ---- Four-way row-count guard: physical lines == parsed rows == unique ids ==
    # == final state totals. Any drift means the parser ignored part of the file or
    # the matrix carries malformed/duplicated requirement rows.
    physical = physical_row_count(raw_text)
    if len(rows) != physical:
        errors.append(f"row-parse guard: {physical} physical '| CE-' lines but parser produced {len(rows)} rows")
    malformed = [r[0] for r in rows if len(r) < MIN_COLUMNS]
    if malformed:
        errors.append(f"malformed requirement rows (fewer than {MIN_COLUMNS} cells): {malformed[:6]}")
    ids = [r[0] for r in rows if r]
    dupes = sorted({i for i in ids if ids.count(i) > 1})
    if dupes:
        errors.append(f"Duplicate requirement IDs (incl. across tables): {dupes}")
    if len(set(ids)) != len(ids):
        errors.append(f"row-uniqueness guard: {len(ids)} parsed IDs but only {len(set(ids))} unique")
    row_by_id = dict(zip(ids, rows))

    # ---- Split-brain authority guard ------------------------------------------
    main_root = pathlib.Path(os.environ.get("TRACEABILITY_MAIN", DEFAULT_MAIN))
    manifest_text = ""
    if main_root.is_dir():
        main_texts = {p: p.read_text() for p in list(main_root.rglob("*.kt")) + list(main_root.rglob("*.java"))}
        for domain, authorities, forbidden in AUTHORITY_RULES:
            live_authority = [a for a in authorities
                              if any(re.search(rf"\b(class|object|interface)\s+{a}\b", t) for t in main_texts.values())]
            if not live_authority:
                continue  # authority not present in this tree; nothing to protect here
            for path, text in main_texts.items():
                for bad in forbidden:
                    if re.search(rf"\b(class|object|interface)\s+{bad}\b|\b{bad}\s*\(", text):
                        errors.append(
                            f"split-brain implementation of {domain}: '{bad}' coexists with canonical "
                            f"{live_authority} in {path.relative_to(main_root)}; migrate or delete it")
                        break
        manifest = main_root / "AndroidManifest.xml"
        if manifest.is_file():
            manifest_text = manifest.read_text()

    mission_text = mission_path.read_text() if mission_path.is_file() else ""
    for pattern, covered in MISSION_ANCHORS:
        if mission_text and not re.search(pattern, mission_text, re.I | re.S):
            errors.append(f"mission anchor drifted from MISSION.md; update checker map: /{pattern[:40]}/")
        for req in covered:
            if req not in ids:
                errors.append(f"MISSION.md normative requirement uncovered by matrix: {req}")

    if charter_path is not None and charter_path.is_file():
        charter_text = charter_path.read_text()
        for pattern, covered in CHARTER_ANCHORS:
            if not re.search(pattern, charter_text, re.I | re.S):
                errors.append(f"charter anchor drifted from the Revenue Operator charter; update checker map: /{pattern[:36]}/")
            for req in covered:
                if req not in ids:
                    errors.append(f"Revenue Operator charter requirement uncovered by matrix: {req}")

    state_counts = {}
    main_sources = None
    for r in rows:
        rid = r[0]
        _src, text, deps, impl_ref, focused, negative = r[1], r[2], r[3], r[4], r[5], r[6]
        state, sidx, evidence, blocker = state_of(r)
        if state is None:
            errors.append(f"{rid}: no valid state cell among {r[:6]}"); continue
        state_counts[state] = state_counts.get(state, 0) + 1

        for dep in re.findall(r"CE-[A-Z0-9-]+", deps):
            if dep != rid and dep not in ids:
                errors.append(f"{rid}: unknown prerequisite {dep}")
        if state == "locally_verified":
            for dep in re.findall(r"CE-[A-Z0-9-]+", deps):
                dep_state = state_of(row_by_id[dep])[0] if dep in row_by_id else None
                if dep_state in OPEN_STATES:
                    errors.append(f"{rid}: locally_verified aggregate depends on open subrequirement {dep} ({dep_state})")
            row_l = "|".join(r).lower()
            for marker in UNRESOLVED_MARKERS:
                if re.search(rf"\b{marker}\b", row_l):
                    errors.append(f"{rid}: locally_verified row contains unresolved marker '{marker}'")
            scope_text = f"{evidence} | {blocker}".lower()
            for pattern in CONTRA_PATTERNS:
                if re.search(pattern, scope_text):
                    errors.append(
                        f"{rid}: contradictory scope claim while locally_verified "
                        f"(pattern /{pattern[:34]}/); downgrade to locally_implemented or close the gap")
                    break
            if not any(evidence.startswith(k + ":") for k in EVIDENCE_KINDS):
                errors.append(f"{rid}: evidence lacks kind tag ({'/'.join(EVIDENCE_KINDS)}): '{evidence[:44]}'")

        # Device-verified evidence discipline: independently auditable target-surface proof.
        if state == "device_verified":
            dev_text = f"{evidence} | {blocker}"
            if not evidence.strip().startswith("device:"):
                errors.append(f"{rid}: device_verified evidence must carry kind tag 'device:'")
            artifacts = re.findall(r"[A-Za-z0-9_/.-]+\.(?:png|xml|log|txt|md|json|csv|jpg)", dev_text)
            if not artifacts:
                errors.append(f"{rid}: device_verified cites no raw evidence artifact (png/xml/log/...)")
            else:
                import hashlib as _hashlib
                found_any = False
                for art in artifacts:
                    resolved = pathlib.Path(art)
                    candidates = [resolved, results_dir.parent.parent / art, pathlib.Path("mission") / art]
                    if any(c.is_file() for c in candidates):
                        found_any = True
                        break
                if not found_any:
                    errors.append(f"{rid}: device_verified artifact(s) {artifacts[:2]} do not exist on disk")
            if not (re.search(r"\b(serial|model)\s*[:=]", dev_text, re.I) or "CPH1933" in dev_text):
                errors.append(f"{rid}: device_verified lacks device identity (serial=/model=/CPH1933)")
            if not re.search(r"\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}", dev_text):
                errors.append(f"{rid}: device_verified lacks a timestamp (UTC recommended)")
            if not re.search(r"\b(cmd|command|procedure)\s*:", dev_text, re.I):
                errors.append(f"{rid}: device_verified lacks cmd:/command:/procedure: describing how it ran")
            if not re.search(r"\bexpected\s*:", dev_text, re.I):
                errors.append(f"{rid}: device_verified lacks 'expected:' result")
            if not re.search(r"\bobserved\s*:", dev_text, re.I):
                errors.append(f"{rid}: device_verified lacks 'observed:' result")
            effect_row = re.search(r"\b(send|post|publish|edit|status|tiktok|reply|broadcast|apply)", rid + " " + r[2], re.I)
            if effect_row and not re.search(r"\breceipt\s*:", dev_text, re.I):
                errors.append(f"{rid}: consequential device effect requires receipt: identity")
            if not re.search(r"\bsurface\s*:", dev_text, re.I):
                errors.append(
                    f"{rid}: device_verified requires surface: target-app proof (hierarchy/screenshot of the "
                    f"acted-on app); Amara's own chat statements alone never suffice")

        if state in {"device_blocked", "device_pending"} and len(blocker.strip()) < 4:
            errors.append(f"{rid}: {state} requires a blocker explanation")
        if state in {"not_started", "deferred", "device_blocked"}:
            continue

        impl_files = []
        for part in re.split(r"\+\s*|;", impl_ref):
            part = part.strip()
            if not part:
                continue
            resolved = find_impl(part) if "/" in part or part.endswith((".kt", ".java")) else find_impl(part)
            # Name-only references resolve through rglob fallback inside find_impl.
            if resolved is None:
                errors.append(f"{rid}: implementation '{part}' does not exist")
            else:
                impl_files.append(resolved)
        primary = impl_files[0] if impl_files else None

        m = re.search(r"([A-Z][A-Za-z0-9_]+)\.([A-Za-z0-9_]+)\(", focused)
        if state == "locally_verified":
            if not m:
                errors.append(f"{rid}: locally_verified needs a ClassName.method( focused test"); continue
            cls, meth = m.group(1), m.group(2)
            decl = [f for f in pathlib.Path("app/src/test").rglob("*.kt")
                    if re.search(rf"(?:class|object)\s+{cls}\b", f.read_text())]
            if not decl:
                errors.append(f"{rid}: test class {cls} not found in sources"); continue
            src_file = decl[0]; src = src_file.read_text()
            if not re.search(rf"fun\s+{meth}\s*\(", src):
                errors.append(f"{rid}: method {cls}.{meth} not declared"); continue
            hits = sorted(glob.glob(str(results_dir / f"*.{cls}.xml")))
            xml = pathlib.Path(hits[0]) if hits else None
            if xml is None:
                errors.append(f"{rid}: no fresh XML results for {cls} under {results_dir.name}"); continue
            errors.extend(parse_xml_results(xml, cls, meth))
            kt_impls = [p for p in impl_files if p.suffix == ".kt"]
            if strict and kt_impls:
                newest_ref = max([os.path.getmtime(p) for p in kt_impls] + [os.path.getmtime(src_file)])
                if os.path.getmtime(xml) < newest_ref:
                    errors.append(f"{rid}: stale evidence — {xml.name} older than implementation/test sources; rerun tests")

            if primary is not None and "src/main" in str(primary):
                # Production reachability: the focused test must exercise one of the types
                # DECLARED by the implementation file, or explicit wired(...) evidence must
                # name a symbol that really exists under app/src/main (Kotlin or manifest).
                try:
                    impl_text = primary.read_text()
                    declared = set(re.findall(
                        r"\b(?:public|private|internal|sealed|abstract|open|data|enum)?\s*"
                        r"\b(?:class|interface|object)\s+([A-Z][A-Za-z0-9_]*)", impl_text))
                    if not declared:
                        declared = {primary.stem}
                except OSError:
                    declared = {primary.stem}
                test_exercises = bool(declared.intersection(re.findall(r"\b([A-Z][A-Za-z0-9_]{2,})\b", src)))
                wired_syms = re.findall(r"wired\(([A-Za-z0-9_.]+)\)", evidence + " " + blocker)
                if main_sources is None:
                    main_sources = list(pathlib.Path("app/src/main").rglob("*.kt")) + \
                        list(pathlib.Path("app/src/main").rglob("*.xml"))
                main_kt_texts = [(h, h.read_text()) for h in main_sources if h.suffix == ".kt"]
                for sym in wired_syms:
                    tail = sym.split(".")[-1]
                    found_in_main = any(tail in t for _, t in main_kt_texts)
                    found_in_manifest = bool(manifest_text) and tail in manifest_text
                    if not found_in_main and not found_in_manifest:
                        errors.append(f"{rid}: wired({sym}) not found under app/src/main — fabricated reachability evidence")
                if not test_exercises and not wired_syms:
                    errors.append(f"{rid}: no production-reachability proof (test never exercises {primary.stem}; add wired(...) evidence)")

                # Constructed-in-production guard: at least one symbol DECLARED by this
                # file must be referenced from a DIFFERENT production source file, or the
                # class must be an Android component DECLARED in AndroidManifest.xml
                # (manifest components are constructed by the operating system itself).
                others_text = "\n".join(t for p, t in main_kt_texts if p != primary)
                constructed = any(
                    re.search(rf"\b{sym}\b", others_text) for sym in declared)
                manifest_constructed = bool(manifest_text) and any(
                    re.search(rf"(?:^|[/\s.\"]){sym}\"", manifest_text, re.M) or f".{sym}\"" in manifest_text
                    for sym in declared)
                if not constructed and not manifest_constructed:
                    errors.append(
                        f"{rid}: production class never constructed — no other app/src/main source "
                        f"references any symbol declared by {primary.name}")
                # Test-only reachability guard: public functions of this file that appear
                # in test sources but nowhere else in production code.
                own = impl_text
                funs = set(re.findall(r"\bfun\s+([a-z][A-Za-z0-9_]{5,})\s*[(<]", own))
                test_corpus = pathlib.Path("app/src/test")
                orphan = []
                for fn in sorted(funs):
                    pattern = rf"\b{fn}\s*\("
                    in_own_calls = len(re.findall(pattern, own)) > 1  # declaration alone doesn't count
                    in_other_main = re.search(pattern, others_text) is not None
                    in_tests = any(re.search(pattern, p.read_text()) for p in test_corpus.rglob("*.kt"))
                    if in_tests and not in_other_main and not in_own_calls:
                        orphan.append(fn)
                if orphan:
                    errors.append(
                        f"{rid}: methods only called from tests — {sorted(orphan)[:6]} in {primary.name} "
                        f"have zero production call sites; wire them into the runtime or downgrade")
        elif state == "locally_implemented" and m:
            cls, meth = m.group(1), m.group(2)
            decl = [f for f in pathlib.Path("app/src/test").rglob("*.kt")
                    if re.search(rf"(?:class|object)\s+{cls}\b", f.read_text())]
            if not decl:
                errors.append(f"{rid}: test class {cls} not found in sources")
            elif not re.search(rf"fun\s+{meth}\s*\(", decl[0].read_text()):
                errors.append(f"{rid}: method {cls}.{meth} not declared")

        for ev in re.findall(r"[A-Za-z0-9_/.-]+\.(?:xml|md|json|csv)", f"{evidence} {blocker}"):
            direct = results_dir / ev
            resolved = find_impl(ev)
            ok = direct.is_file() or (resolved is not None and resolved.is_file())
            if not ok:
                errors.append(f"{rid}: evidence artifact '{ev}' does not exist")

    if log_path.is_file():
        log_text = log_path.read_text()
        blocks = re.findall(r"\[counts\]\s*total=(\d+)\s*([^;\n]*)", log_text)
        if not blocks:
            errors.append("EXECUTION_LOG.md lacks a '[counts] total=…' block; record fresh checker counts there")
        else:
            total_declared = int(blocks[-1][0])
            declared = dict(re.findall(
                r"(locally_verified|locally_implemented|device_blocked|device_pending|not_started|in_progress|device_verified|deferred|failed|superseded)=(\d+)",
                blocks[-1][1]))
            if total_declared != len(rows):
                errors.append(f"matrix/log count mismatch: log total={total_declared}, matrix rows={len(rows)}")
            for key, val in declared.items():
                actual = state_counts.get(key, 0)
                if int(val) != actual:
                    errors.append(f"matrix/log count mismatch: {key} log={val} actual={actual}")

        # Fresh JUnit totals: the log's declared totals must equal the FRESH parsed XML
        # results — stale execution-log test totals are a defect, not paperwork.
        tests_m = re.findall(r"\[tests\]\s*suites=(\d+)\s+tests=(\d+)\s+failures=(\d+)\s+errors=(\d+)\s+skipped=(\d+)", log_text)
        results_root = pathlib.Path(os.environ.get("TRACEABILITY_RESULTS", DEFAULT_RESULTS))
        fresh_tests = 0
        for x in sorted(results_root.glob("*.xml")) if results_root.is_dir() else []:
            try:
                root = ET.parse(str(x)).getroot()
                fresh_tests += int(root.get("tests", "0"))
            except ET.ParseError:
                pass
        if tests_m:
            _s, t_decl, f_decl, e_decl, k_decl = map(int, tests_m[-1])
            if t_decl != fresh_tests:
                errors.append(f"stale execution-log test totals: log declares {t_decl} tests but fresh XML has {fresh_tests}")

        # Stale APK guard: the release APK must postdate every production source file
        # and match the sha256 recorded in the log after the rebuild.
        apk = pathlib.Path(os.environ.get("TRACEABILITY_APK", DEFAULT_APK))
        src_times = []
        for base in ("app/src/main", "flutter_ui/lib"):
            root = pathlib.Path(base)
            if root.is_dir():
                src_times += [os.path.getmtime(p2) for p2 in list(root.rglob("*.kt")) + list(root.rglob("*.java")) + list(root.rglob("*.dart"))]
        newest_src = max(src_times) if src_times else None
        import hashlib
        hashes = re.findall(r"\[apk\]\s*sha256=([0-9a-f]{64})", log_text)
        if apk.is_file():
            actual_hash = hashlib.sha256(apk.read_bytes()).hexdigest()
            if newest_src is not None and os.path.getmtime(apk) < newest_src:
                errors.append("stale APK: release APK predates production source files; rebuild before claiming completion")
            if hashes and hashes[-1] != actual_hash:
                errors.append("stale APK: recorded sha256 in EXECUTION_LOG.md does not match the built APK; rebuild and re-record")
    else:
        errors.append("execution log not found")
    return errors, rows

def run_checker_env(matrix, results, log, extra_env=None):
    env = dict(os.environ)
    env.update({
        "TRACEABILITY_MATRIX": str(matrix),
        "TRACEABILITY_RESULTS": str(results),
        "TRACEABILITY_LOG": str(log),
    })
    if extra_env:
        env.update(extra_env)
    return subprocess.run(["bash", SCRIPT], env=env, capture_output=True, text=True)

def run_negative_harness():
    base_matrix = pathlib.Path(os.environ.get("TRACEABILITY_MATRIX", DEFAULT_MATRIX)).read_text()
    real_results = pathlib.Path(DEFAULT_RESULTS)
    tmp_root = pathlib.Path(tempfile.mkdtemp(prefix="trace-fixtures-"))
    cases_run, failures = [], []

    def matrix_with(mutation):
        lines = base_matrix.splitlines()
        out = []
        inserted_header_seen = False
        for line in lines:
            out.append(line)
            if line.startswith("|---") and not inserted_header_seen:
                inserted_header_seen = True
                mutated = mutation(line)
                if mutated is not None:
                    out.append(mutated)
        text = "\n".join(out)
        if callable(mutation) and getattr(mutation, "replace_full", False):
            text = mutation.__self__ if False else mutation(None)  # full-text mutator
        path = tmp_root / "matrix.md"
        path.write_text(text)
        return path

    def first_row_line():
        for line in base_matrix.splitlines():
            if line.startswith("| CE-C-KIND-01"):  # stable, verified, Knowledge-backed row
                return line
        raise SystemExit("anchor row missing")

    def add_row(extra_row):
        def mutate(_line):
            return extra_row
        return mutate

    knowledge_row = first_row_line()

    def replace_in_anchor(**cells_overrides):
        cells = [c.strip() for c in knowledge_row.strip("|").split("|")]
        return cells

    # Case builders ------------------------------------------------------------
    broken_row = ("| CE-X-BRK-99 | fixture | fixture requirement | — | core/NoSuchImpl.kt | "
                  "GhostTest.nope( | GhostTest.nope( | — | none | structural: none | locally_verified | — | 2026-08-24 |")

    def case_missing_impl():
        row = knowledge_row.replace("core/knowledge/Knowledge.kt", "core/knowledge/Nowhere.kt")
        return add_row(row), None, None

    def case_missing_class():
        row = re.sub(r"KnowledgeTest\.[a-zA-Z]+\(", "GhostTest.nope(", knowledge_row, count=1)
        return add_row(row), None, None

    def case_missing_method():
        row = re.sub(r"KnowledgeTest\.[a-zA-Z]+\(", "KnowledgeTest.noSuchMethod(", knowledge_row, count=1)
        return add_row(row), None, None

    def case_missing_xml(tmp):
        empty_dir = tmp / "empty-results"; empty_dir.mkdir(parents=True, exist_ok=True)
        return add_row(knowledge_row), empty_dir, None

    def case_failed_xml(tmp):
        res = tmp / "failed-results"; res.mkdir(parents=True, exist_ok=True)
        src_xml = next(real_results.glob("*KnowledgeTest.xml"))
        doctored = src_xml.read_text().replace('failures="0"', 'failures="1"', 1)
        (res / src_xml.name).write_text(doctored)
        return add_row(knowledge_row), res, None

    def case_stale_xml(tmp):
        res = tmp / "stale-results"; res.mkdir(parents=True, exist_ok=True)
        src_xml = next(real_results.glob("*KnowledgeTest.xml"))
        target = res / src_xml.name
        shutil.copy(src_xml, target)
        old = 0  # epoch: older than any source
        os.utime(target, (old, old))
        return add_row(knowledge_row), res, None

    def case_unknown_prereq():
        row = knowledge_row.replace("| CE-C-KIND-01 | prompt Phase C | Five-way claim epistemics | — |",
                                    "| CE-C-KIND-01 | prompt Phase C | Five-way claim epistemics | CE-GHOST-99 |")
        return add_row(row), None, None

    def case_unresolved_marker():
        row = knowledge_row.replace("Five-way claim epistemics", "Five-way claim epistemics (todo)")
        return add_row(row), None, None

    def case_open_aggregate(tmp):
        text = base_matrix.replace(
            "| CE-E-SIM-01 | prompt Phase E exit |",
            "| CE-E-SIM-XX | prompt Phase E exit |", 1)  # keep totals consistent later via log override
        # Make CE-E-SIM-01's prerequisite CE-E-TOPO-01 open.
        text = re.sub(r"(\| CE-E-TOPO-01 \|[^\n]*\| )locally_verified( \|)", r"\1not_started\2", text, count=1)
        tmp.mkdir(parents=True, exist_ok=True)
        path = tmp / "aggregate-matrix.md"; path.write_text(text)
        log = tmp / "log.md"; log.parent.mkdir(parents=True, exist_ok=True); log.write_text("[counts] placeholder")
        return ("full", path), None, log

    def case_count_mismatch(tmp):
        tmp.mkdir(parents=True, exist_ok=True)
        log = tmp / "log.md"; log.write_text("[counts] total=999 locally_verified=999\n")
        return ("plain", None), None, log

    def case_mission_omitted():
        text = "\n".join(l for l in base_matrix.splitlines() if not l.startswith("| CE-C-ART-IMG-01"))
        path = tmp_root / "omitted-matrix.md"; path.write_text(text)
        return ("full", path), None, None

    def case_fake_reachability():
        row = knowledge_row.replace(
            "TEST-co.sanaa.agent.core.knowledge.KnowledgeTest.xml",
            "TEST-co.sanaa.agent.core.knowledge.KnowledgeTest.xml wired(SymbolThatDoesNotExistAnywhere)")
        return add_row(row), None, None

    good_log = tmp_root / "good-log.md"
    good_log.write_text("[counts] total=999\n")

    # ---- Extended fixture machinery (secondary tables, env overrides, temp mains) ----
    SECONDARY_HEADER = ("| Req ID | Source | Requirement text | Depends on | Implementation component | "
                        "Focused test | Negative-path test | Integration test | Device requirement | Evidence | State | Blocker | Last verified |\n"
                        "|---|---|---|---|---|---|---|---|---|---|---|---|---|\n")

    def matrix_from_text(text):
        path = tmp_root / f"matrix-{len(list(tmp_root.glob('matrix-*')))}.md"
        path.write_text(text)
        return path

    def full_matrix_with(extra):
        """Appends a SECOND requirements table to the full matrix text."""
        return matrix_from_text(base_matrix.rstrip() + "\n\n## Appended secondary requirements table\n\n" + extra)

    # --- Fixture 13: a defective row hidden ONLY in a secondary table must be parsed ---
    def case_secondary_table_row_parsed():
        bad = ("| CE-X-SEC-01 | fixture | row in a SECONDARY table | — | core/NoSuchSecondary.kt | "
               "GhostTest.secNope( | — | — | none | structural: none | locally_verified | — | 2026-08-24 |\n")
        return ("full", full_matrix_with(SECONDARY_HEADER + bad)), None, None, None

    # --- Fixture 14: duplicate requirement IDs ACROSS tables ---
    def case_duplicate_across_tables():
        return ("full", full_matrix_with(SECONDARY_HEADER + knowledge_row + "\n")), None, None, None

    # --- Fixture 15: stale execution-log test totals vs fresh XML ---
    def case_stale_log_test_totals():
        log = tmp_root / "stale-tests-log.md"
        log.write_text("[counts] total=999\n[tests] suites=1 tests=1 failures=0 errors=0 skipped=0\n")
        return ("plain", None), None, log, None

    # --- Fixture 16: release APK predating implementation sources ---
    def case_stale_apk():
        apk_dir = tmp_root / "apk"; apk_dir.mkdir(parents=True, exist_ok=True)
        apk = apk_dir / "app-release.apk"; apk.write_bytes(b"stale")
        os.utime(apk, (0, 0))
        log = tmp_root / "apk-log.md"
        log.write_text("[counts] total=999\n")
        return ("plain", None), None, log, {"TRACEABILITY_APK": str(apk)}

    # --- Fixture 17: source-only production claims (illegal state cell) ---
    def case_source_only_state():
        bad = ("| CE-X-SRC-01 | fixture | source-only claim | — | core/knowledge/Knowledge.kt | "
               "KnowledgeTest.recordedConflictsRefusalsAreTyped( | — | — | none | structural: none | source_present | — | 2026-08-24 |\n")
        return ("full", full_matrix_with(SECONDARY_HEADER + bad)), None, None, None

    # --- Fixtures 18+19: temp main tree with an unconstructed class / test-only method ---
    ORPHAN_METHOD = "orphanProbeMethodXq"
    PROBE_TEST_REL = pathlib.Path("app/src/test/kotlin/co/sanaa/agent/OrphanFixtureProbeTest.kt")

    def build_temp_main():
        # Path MUST contain src/main so the production-reachability guards treat these
        # files as implementation sources, and it carries the REAL manifest so rows
        # whose components are platform-constructed do not muddy the fixture signal.
        main = tmp_root / "temp-main" / "src" / "main"
        main.mkdir(parents=True, exist_ok=True)
        real_manifest = pathlib.Path("app/src/main/AndroidManifest.xml")
        if real_manifest.is_file():
            shutil.copy(real_manifest, main / "AndroidManifest.xml")
            os.utime(main / "AndroidManifest.xml", (0, 0))
        wired = main / "Wiring.kt"
        wired.write_text("val wiringAnchor = \"FOLLOW_UP_WHATSAPP\"\nobject RevenueStore {}\n")
        dead = main / "DeadThing.kt"
        dead.write_text(
            f"class DeadThing {{\n"
            f"    fun {ORPHAN_METHOD}() = 1\n"
            f"    fun neverCalledAnywhereZz() = 2\n"
            f"}}\n")
        # Backdate so the fresh-XML evidence rule does not fire before the structural
        # guards under test.
        for f in (wired, dead):
            os.utime(f, (0, 0))
        return main

    def case_unconstructed_class():
        main = build_temp_main()
        row = ("| CE-X-DEAD-01 | fixture | dead class | — | DeadThing.kt | "
               "CapabilityContractTest.everySpecHasStableIdentityAndDescription( | — | — | none | "
               "structural: TEST-co.sanaa.agent.core.CapabilityContractTest.xml wired(FOLLOW_UP_WHATSAPP) | locally_verified | — | 2026-08-24 |\n")
        return ("full", full_matrix_with(row)), None, None, {"TRACEABILITY_MAIN": str(main)}

    def case_test_only_method():
        main = build_temp_main()
        probe = pathlib.Path(os.environ.get("TRACEABILITY_ROOT", ".")) / PROBE_TEST_REL
        probe.parent.mkdir(parents=True, exist_ok=True)
        probe.write_text(
            f"""package co.sanaa.agent

import org.junit.Test

// Checker negative-fixture probe: exists ONLY to prove the orphan-method guard fires.
class OrphanFixtureProbeTest {{
    @Test fun probe() {{
        val x = {ORPHAN_METHOD}()
        check(x >= 0)
    }}
}}
""")
        row = ("| CE-X-ORPH-01 | fixture | test-only method | — | DeadThing.kt | "
               "CapabilityContractTest.everySpecHasStableIdentityAndDescription( | — | — | none | "
               "structural: TEST-co.sanaa.agent.core.CapabilityContractTest.xml wired(FOLLOW_UP_WHATSAPP) | locally_verified | — | 2026-08-24 |\n")
        return ("full", full_matrix_with(row)), None, None, {"TRACEABILITY_MAIN": str(main)}

    def cleanup_probe():
        probe = pathlib.Path(os.environ.get("TRACEABILITY_ROOT", ".")) / PROBE_TEST_REL
        if probe.exists():
            probe.unlink()
        try:
            probe.parent.rmdir()
        except OSError:
            pass
        try:
            probe.parent.parent.rmdir()
        except OSError:
            pass

    # --- Fixture 20: split-brain duplicate metric implementations ---
    def case_split_brain_metric():
        main = tmp_root / "split-main" / "src"
        main.mkdir(parents=True, exist_ok=True)
        (main / "Canonical.kt").write_text("class RevenueMetricEngine {}\nclass RevenueStore {}\n")
        (main / "LegacyCommerceEngine.kt").write_text(
            "class CommerceEngine {\n"
            "    fun monthlyStatus(): String = \"legacy\"\n"
            "}\n")
        row = ("| CE-X-SPLIT-01 | fixture | split brain | — | Canonical.kt | "
               "CapabilityContractTest.everySpecHasStableIdentityAndDescription( | — | — | none | "
               "structural: TEST-co.sanaa.agent.core.CapabilityContractTest.xml | locally_implemented | — | 2026-08-24 |\n")
        return ("full", full_matrix_with(SECONDARY_HEADER + row)), None, None, {"TRACEABILITY_MAIN": str(main)}

    # --- Fixtures 21-23: device_verified evidence discipline ---
    REAL_PNG = "mission/amara-10-10/O_01_ACCESSIBILITY_BOUND.png"

    def case_device_row_missing_surface():
        bad = (f"| CE-X-DEV-01 | fixture | device send without surface proof | — | core/knowledge/Knowledge.kt | "
               f"KnowledgeTest.factsRequireProvenance( | — | — | none | device: {REAL_PNG} "
               f"serial=7aef1a4c model=CPH1933 2026-08-25T10:00Z cmd: adb-shell-run procedure "
               f"expected: delivered observed: delivered receipt: tx-dev-1 | device_verified | — | 2026-08-25 |\n")
        return ("full", full_matrix_with(SECONDARY_HEADER + bad)), None, None, None

    def case_device_row_missing_artifact():
        bad = ("| CE-X-DEV-02 | fixture | device row citing a ghost artifact | — | core/knowledge/Knowledge.kt | "
               "KnowledgeTest.factsRequireProvenance( | — | — | none | device: mission/ghost_evidence_never_taken.png "
               "serial=7aef1a4c model=CPH1933 2026-08-25T10:00Z cmd: probe expected: x observed: y receipt: tx-2 "
               "surface: com.whatsapp hierarchy dump | device_verified | — | 2026-08-25 |\n")
        return ("full", full_matrix_with(SECONDARY_HEADER + bad)), None, None, None

    def case_device_row_missing_expected_observed():
        bad = (f"| CE-X-DEV-03 | fixture | device read without expected/observed | — | core/knowledge/Knowledge.kt | "
               f"KnowledgeTest.factsRequireProvenance( | — | — | none | device: {REAL_PNG} "
               f"serial=7aef1a4c model=CPH1933 2026-08-25T10:00Z cmd: read-bookings procedure "
               f"surface: com.soko24.soko_seller_terminal bookings hierarchy | device_verified | — | 2026-08-25 |\n")
        return ("full", full_matrix_with(SECONDARY_HEADER + bad)), None, None, None

    plan = [
        ("missing implementation", case_missing_impl, "does not exist"),
        ("missing test class", case_missing_class, "test class GhostTest not found"),
        ("missing test method", case_missing_method, "noSuchMethod not declared"),
        ("missing xml results", lambda tmp=tmp_root: case_missing_xml(tmp_root / "c4"), "no fresh XML results"),
        ("failed xml", lambda tmp=None: case_failed_xml(tmp_root / "c5"), "suite reports failures"),
        ("stale xml", lambda tmp=None: case_stale_xml(tmp_root / "c6"), "stale evidence"),
        ("unknown prerequisite", case_unknown_prereq, "unknown prerequisite CE-GHOST-99"),
        ("verified row with unresolved marker", case_unresolved_marker, "unresolved marker 'todo'"),
        ("verified aggregate over open subrequirement", lambda tmp=None: case_open_aggregate(tmp_root / "c9"), "open subrequirement"),
        ("matrix/log count mismatch", lambda tmp=None: case_count_mismatch(tmp_root / "c10"), "count mismatch"),
        ("mission requirement omitted from matrix", case_mission_omitted, "uncovered by matrix: CE-C-ART-IMG-01"),
        ("fabricated production-reachability evidence", case_fake_reachability, "wired(SymbolThatDoesNotExistAnywhere) not found"),
        # Extended fixtures: every defect class from the corrective directive.
        ("secondary-table row is parsed, not ignored", case_secondary_table_row_parsed, "does not exist"),
        ("duplicate requirement across tables", case_duplicate_across_tables, "Duplicate requirement IDs"),
        ("stale execution-log test totals", case_stale_log_test_totals, "stale execution-log test totals"),
        ("release APK predating implementation", case_stale_apk, "stale APK: release APK predates production source files"),
        ("source-only production claim (illegal state)", case_source_only_state, "no valid state cell"),
        ("production class never constructed", case_unconstructed_class, "production class never constructed"),
        ("methods only called from tests", case_test_only_method, f"methods only called from tests — ['{ORPHAN_METHOD}']"),
        ("split-brain duplicate metric implementations", case_split_brain_metric, "split-brain implementation of revenue metric ledger"),
        ("device_verified row without target-surface proof", case_device_row_missing_surface, "requires surface:"),
        ("device_verified row citing a nonexistent artifact", case_device_row_missing_artifact, "do not exist"),
        ("device_verified row without expected/observed results", case_device_row_missing_expected_observed, "lacks 'expected:'"),
    ]

    try:
        for label, builder, expected_fragment in plan:
            case_tmp = tmp_root / re.sub(r"\W+", "-", label)
            case_tmp.mkdir(parents=True, exist_ok=True)
            built = builder()
            if built is None:
                failures.append(f"{label}: builder returned nothing"); continue
            # Normalize to (spec, results_dir, log_override, extra_env).
            if len(built) == 3:
                spec, results_dir, log_override, extra_env = (*built, None)
            else:
                spec, results_dir, log_override, extra_env = built
            if isinstance(spec, tuple):
                matrix_path = spec[1] if spec[0] == "full" else pathlib.Path(os.environ.get("TRACEABILITY_MATRIX", DEFAULT_MATRIX))
            else:
                matrix_path = matrix_with(spec)
            if results_dir is None:
                results_dir = real_results
            if log_override is not None:
                log = log_override
            else:
                log = good_log
            proc = run_checker_env(matrix_path, results_dir, log, extra_env)
            combined = proc.stdout + proc.stderr
            if proc.returncode == 0:
                failures.append(f"{label}: checker ACCEPTED a defective matrix")
            elif expected_fragment not in combined:
                failures.append(f"{label}: rejected but without the specific detection ('{expected_fragment}'):\n{combined[-800:]}")
            else:
                cases_run.append(label)
    finally:
        cleanup_probe()
        shutil.rmtree(tmp_root, ignore_errors=True)
    if failures:
        print("NEGATIVE FIXTURE FAILURES:")
        for f in failures:
            print(" -", f)
        print(f"HARNESS_RESULT=FAIL ({len(cases_run)}/{len(plan)} rejected)")
        sys.exit(1)
    print(f"Negative fixtures OK: {len(cases_run)}/{len(plan)} independent defective matrices rejected:")
    for c in cases_run:
        print("   rejected:", c)
    print(f"HARNESS_RESULT=PASS ({len(cases_run)}/{len(plan)} rejected)")
    sys.exit(0)

if len(sys.argv) > 1 and sys.argv[1] in ("negative-fixture", "--negative-fixture"):
    run_negative_harness()

matrix = pathlib.Path(os.environ.get("TRACEABILITY_MATRIX", DEFAULT_MATRIX))
results = pathlib.Path(os.environ.get("TRACEABILITY_RESULTS", DEFAULT_RESULTS))
log = pathlib.Path(os.environ.get("TRACEABILITY_LOG", DEFAULT_LOG))
mission = pathlib.Path(os.environ.get("TRACEABILITY_MISSION", DEFAULT_MISSION))
charter = pathlib.Path(os.environ.get("TRACEABILITY_CHARTER", DEFAULT_CHARTER))

errors, rows = validate(matrix, results, log, mission, charter_path=charter)
state_counts = {}
for r in rows:
    st, _, _, _ = state_of(r)
    state_counts[st] = state_counts.get(st, 0) + 1

if errors:
    print("TRACEABILITY ERRORS:")
    for e in errors:
        print(" -", e)
    sys.exit(1)

print(f"Traceability OK: {len(rows)} atomic requirements. States: " +
      ", ".join(f"{k}={v}" for k, v in sorted(state_counts.items(), key=lambda kv: -kv[1])))
PY