#!/usr/bin/env python3
"""Read-only mission progress. Evidence paths are checked, outcomes are not inferred."""
import json
from pathlib import Path
from collections import Counter
import sys
root = Path(__file__).resolve().parents[3]
board = json.loads((Path(__file__).resolve().parents[1] / 'progress.json').read_text())
allowed = {'pending', 'implemented', 'tested', 'installed', 'verified', 'blocked'}
errors = []
counts = Counter()
seen = set()
for item in board['items']:
    key, status = item['id'], item['status']
    if key in seen or status not in allowed:
        errors.append(f'Invalid/duplicate item: {key} ({status})')
    seen.add(key)
    counts[status] += 1
    if not (root / item['evidence']).is_file():
        errors.append(f'Missing evidence: {key}: {item["evidence"]}')
    print(f'{key:24} {status:12} {item["next"]}')
print('\nEvidence-backed board (file presence is not independent outcome verification):')
for status in sorted(counts):
    print(f'{status:12} {"#" * counts[status]} {counts[status]}')
print(f'Accepted: {counts["verified"]}/{len(board["items"])}; target {board["target_release"]}')
for error in errors:
    print(error, file=sys.stderr)
sys.exit(1 if errors else 0)
