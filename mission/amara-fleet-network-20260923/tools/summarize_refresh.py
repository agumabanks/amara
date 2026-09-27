#!/usr/bin/env python3
"""Aggregate local exported journals without retaining message bodies or identifiers."""
import collections, datetime as dt, hashlib, json, pathlib, re, sys
root=pathlib.Path(__file__).resolve().parents[1]
raw=pathlib.Path(sys.argv[1]); end=int(dt.datetime.fromisoformat(sys.argv[2]).timestamp()*1000); start=end-86400000

def iso(t):return dt.datetime.fromtimestamp(t/1000,dt.timezone.utc).isoformat() if t else None

def count(rows):
 c=collections.Counter(); reasons=collections.Counter();effects=collections.Counter();events=collections.Counter();distinct=collections.defaultdict(set)
 for row in rows:
  ev=row.get('event');events[ev]+=1;f=row.get('fields',{})
  if ev=='outcome':
   key=(f.get('kind'),f.get('status'));c[key]+=1;distinct[key].add(row.get('key',row.get('at')))
   reason=f.get('failure_reason','')
   if reason:
    reason=re.sub(r'https?://\S+|[\w.+-]+@[\w.-]+|\+?\d[\d -]{8,}\d','[redacted]',reason)[:350]
    reasons[(f.get('kind'),f.get('status'),f.get('failure_class'),reason)]+=1
  if ev=='external_effect':effects[(f.get('capability'),f.get('status'))]+=1
 return {'events':len(rows),'first_at':iso(rows[0]['at']) if rows else None,'last_at':iso(rows[-1]['at']) if rows else None,'event_counts':dict(events),'outcomes':[{'kind':k,'status':s,'attempt_events':v,'distinct_work_keys_or_timestamp_fallback':len(distinct[k,s])} for (k,s),v in c.items()],'failure_reasons':[{'kind':k,'status':s,'class':c,'reason':r,'count':v} for (k,s,c,r),v in reasons.most_common()],'effects':[{'capability':c,'state':s,'transitions':v} for (c,s),v in effects.items()]}
out={'capture_cutoff':iso(end),'window_start':iso(start),'method':'Exact-line dedup; occurred_at window; build segment is package lastUpdateTime, not a version number; no claims of continuous coverage','devices':{}}
for name,build in [('oppo',69),('tps',70)]:
 rows=[];seen=set();files=[];bad=0
 for p in sorted((raw/name/'evaluation').glob('*.jsonl')):
  b=p.read_bytes();files.append({'file':p.name,'sha256':hashlib.sha256(b).hexdigest(),'bytes':len(b)})
  for l in b.splitlines():
   try:x=json.loads(l)
   except Exception:bad+=1;continue
   h=hashlib.sha256(l).digest()
   if h in seen:continue
   seen.add(h)
   if start<=x.get('at',0)<=end:rows.append(x)
 rows.sort(key=lambda r:r['at']);segments=collections.defaultdict(list)
 for x in rows:segments[x.get('build_segment','unknown')].append(x)
 current=next((x.get('build_segment') for x in reversed(rows) if x.get('build_segment')),None)
 d={'installed_version_code':build,'files':files,'invalid_lines':bad,'last24h':count(rows),'segments':{k:count(v) for k,v in segments.items()},'current_segment':current}
 latest={x['event']:x for x in rows};q=latest.get('queue_inspection',{});f=q.get('fields',{})
 d['queue_snapshot_at']=iso(q.get('at'));d['queue_counts']=dict(collections.Counter(str(x.get('kind'))+'/'+str(x.get('queue_status')) for x in f.get('queue',[])))
 d['loop']={k:v for k,v in f.get('loop',{}).items() if k in ['running','state','ownerOn','itemsExecuted','itemsFailed','lastCycleAt']}
 d['world']=latest.get('world',{}).get('fields',{});d['world_at']=iso(latest.get('world',{}).get('at'))
 out['devices'][name]=d
(root/'evidence/REFRESH_20260924.json').write_text(json.dumps(out,indent=2)+'\n')
for name,d in out['devices'].items():
 print(name,'build',d['installed_version_code'],'current segment',d['current_segment'],'queue',d['queue_counts'],'world',d['world'])
 s=d['segments'].get(d['current_segment'],{})
 print(json.dumps({k:s.get(k) for k in ['first_at','last_at','outcomes','failure_reasons','effects']},indent=2))
