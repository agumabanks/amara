#!/usr/bin/env python3
"""Validate mission bookkeeping; does not certify evidence quality or live behavior."""
import argparse
import json
from pathlib import Path


def validate(root, data):
    errors = []
    phases = data.get('phases', [])
    ids = [p.get('id') for p in phases]
    expected = [f'P{i}' for i in range(9)]
    if ids != expected:
        errors.append('Phases must be P0 through P8 in order.')
    lookup = {p.get('id'): p for p in phases}
    stages = ['planned', 'implemented', 'tests_passed', 'installed', 'live_verified']
    gates = ['implementation', 'tests', 'deployment', 'live']
    for i, p in enumerate(phases):
        pid, status = p.get('id'), p.get('status')
        dependencies = [] if i == 0 else [f'P{i-1}']
        if p.get('depends_on') != dependencies:
            errors.append(f'{pid}: invalid prerequisite list')
        if status not in stages + ['blocked']:
            errors.append(f'{pid}: invalid status {status}')
            continue
        if status == 'blocked' and not p.get('blocker'):
            errors.append(f'{pid}: blocked requires a reason')
        rank = stages.index(status) if status in stages else 0
        for gate in gates[:rank]:
            if p.get('checks', {}).get(gate) is not True:
                errors.append(f'{pid}: {gate} gate missing')
        if rank:
            for dep in dependencies:
                if lookup.get(dep, {}).get('status') != 'live_verified':
                    errors.append(f'{pid}: prerequisite {dep} not live_verified')
            if not p.get('evidence'):
                errors.append(f'{pid}: evidence required')
        for rel in p.get('evidence', []):
            path = (root / rel).resolve()
            if not path.is_relative_to((root / 'evidence').resolve()) or not path.is_file() or not path.stat().st_size:
                errors.append(f'{pid}: missing/invalid evidence {rel}')
    if data.get('implementation_status') == 'complete' and any(p.get('status') != 'live_verified' for p in phases):
        errors.append('Mission cannot be complete while phases remain unverified.')
    return errors



def audit_coverage(root, data, require_complete=False):
    errors = []
    closed = {'verified_fixed', 'expected_policy', 'historical_not_reproduced'}
    states = closed | {'untriaged', 'confirmed_current', 'investigating', 'fixed_pending_verification', 'blocked'}
    registry = json.loads((root / 'issue-register.json').read_text())
    issues = registry.get('issues', [])
    ids = [issue.get('id') for issue in issues]
    if len(ids) != len(set(ids)):
        errors.append('Duplicate issue IDs')
    baseline = json.loads((root / 'evidence/device-summary.json').read_text())
    expected = {(device, f['kind'], f['class']) for device, d in baseline['devices'].items() for f in d['failure_classes']}
    observed = {(i.get('device'), i.get('kind'), i.get('failure_class')) for i in issues if i.get('id', '').startswith('OBS-')}
    if expected != observed:
        errors.append('Issue register does not cover exactly the baseline observation signatures')
    focused = {'WA-ORIGIN', 'WA-GROUP', 'WA-RECOVERY', 'COMMUNITY', 'FAIRNESS', 'TIKTOK', 'YT-SOURCE', 'YT-DISPATCH', 'YT-NOTICE', 'CONFIG-500', 'HEARTBEAT', 'TELEMETRY', 'HEALTH', 'DISK', 'SOKO', 'JIJI'}
    if not focused.issubset(set(ids)):
        errors.append('Missing mandatory focused issue rows')

    def evidence_ok(rel):
        path = (root / rel).resolve()
        return path.is_relative_to((root / 'evidence').resolve()) and path.is_file() and path.stat().st_size > 0

    for issue in issues:
        status = issue.get('status')
        if status not in states:
            errors.append(f"{issue.get('id')}: invalid issue status")
        if issue.get('phase') not in {f'P{i}' for i in range(9)}:
            errors.append(f"{issue.get('id')}: phase assignment required")
        for rel in issue.get('evidence', []):
            if not evidence_ok(rel):
                errors.append(f"{issue.get('id')}: invalid evidence {rel}")
        if status in closed and (not issue.get('evidence') or not issue.get('closure_reason') or issue.get('current_build_recurrence') in (None, 'unknown')):
            errors.append(f"{issue.get('id')}: closure requires evidence, reason and current-build classification")
        if status == 'blocked' and not issue.get('blocker'):
            errors.append(f"{issue.get('id')}: blocked issue requires blocker")
        if require_complete and status not in closed:
            errors.append(f"{issue.get('id')}: unresolved issue")
    p1 = next((p for p in data.get('phases', []) if p.get('id') == 'P1'), {})
    tracks = p1.get('tracks', [])
    if sorted(t.get('id', '') for t in tracks) != ['C1', 'H1', 'Q1', 'W1', 'W2']:
        errors.append('P1 requires all five reliability tracks')
    for track in tracks:
        if track.get('status') not in {'planned', 'implemented', 'tests_passed', 'installed', 'live_verified', 'blocked'}:
            errors.append(f"{track.get('id')}: invalid track status")
        if track.get('status') == 'blocked' and not track.get('blocker'):
            errors.append(f"{track.get('id')}: blocker required")
        for rel in track.get('evidence', []):
            if not evidence_ok(rel):
                errors.append(f"{track.get('id')}: invalid evidence")
        if track.get('status') == 'live_verified' and not track.get('evidence'):
            errors.append(f"{track.get('id')}: live evidence required")
        if (require_complete or p1.get('status') == 'live_verified') and track.get('status') != 'live_verified':
            errors.append(f"{track.get('id')}: track not live_verified")
    final_gate = data.get('pre_livestream_gate', {})
    required_reports = ['evidence/FINAL_RELEASE_REPORT.md', 'evidence/FINAL_ACCEPTANCE_MATRIX.md', 'evidence/FINAL_SOAK_REPORT.md', 'evidence/FINAL_BACKEND_RECOVERY.md']
    if final_gate.get('required_reports') != required_reports:
        errors.append('Missing or altered pre-livestream report requirements')
    if final_gate.get('status') not in {'pending', 'blocked', 'verified'}:
        errors.append('Invalid pre-livestream status')
    if final_gate.get('status') == 'blocked' and not final_gate.get('blocker'):
        errors.append('Pre-livestream blocked status requires reason')
    if require_complete or final_gate.get('status') == 'verified':
        if final_gate.get('status') != 'verified':
            errors.append('Pre-livestream gate not verified')
        if any(p.get('status') != 'live_verified' for p in data.get('phases', [])):
            errors.append('Pre-livestream gate requires all phases live_verified')
        if any(i.get('status') not in closed for i in issues):
            errors.append('Pre-livestream gate requires all issue closures')
        for report in required_reports:
            if not evidence_ok(report):
                errors.append('Missing final gate report: ' + report)
    return errors


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--require-complete', action='store_true')
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    data = json.loads((root / 'progress.json').read_text())
    errors = validate(root, data)
    errors.extend(audit_coverage(root, data, args.require_complete or data.get('implementation_status') == 'complete'))
    incomplete = [p['id'] for p in data['phases'] if p['status'] != 'live_verified']
    for error in errors:
        print('ERROR:', error)
    print('Incomplete phases:', ', '.join(incomplete) or 'none')
    print('Bookkeeping valid.' if not errors else 'Bookkeeping invalid.')
    print('Evidence content and live results require review.')
    return 1 if errors or (args.require_complete and incomplete) else 0


if __name__ == '__main__':
    raise SystemExit(main())
