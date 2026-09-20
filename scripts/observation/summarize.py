"""Summarize observation events; queue completion is never delivery evidence."""
import collections
import json
import pathlib
import sys

folder = pathlib.Path(sys.argv[1])
files = sorted(folder.glob('evaluation-*.jsonl'))
if not files:
    raise SystemExit('No observation files')
rows = []
malformed = 0
for line in files[-1].read_text().splitlines():
    try:
        rows.append(json.loads(line))
    except json.JSONDecodeError:
        malformed += 1
counts = collections.Counter(r['event'] for r in rows)
effects = collections.Counter((r['fields'].get('capability'), r['fields'].get('status'))
                              for r in rows if r['event'] == 'external_effect')
outcomes = collections.Counter(str(r['fields']) for r in rows if r['event'] == 'outcome')
report = {'file': files[-1].name, 'first_event_ms': rows[0]['at'] if rows else None,
          'last_event_ms': rows[-1]['at'] if rows else None, 'events': dict(counts),
          'external_effects': [{'capability': k[0], 'status': k[1], 'count': v} for k, v in effects.items()],
          'outcomes': dict(outcomes), 'malformed_lines': malformed,
          'note': 'Started/completed queue work is not delivery proof. Only VERIFIED external effects establish confirmation.'}
(folder.parent / 'summary.json').write_text(json.dumps(report, indent=2))
print(json.dumps(report, indent=2))
