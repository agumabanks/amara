#!/usr/bin/env python3
"""Read-only five-hour collector. Private metadata only; never drives app UI or sends messages."""
import argparse, collections, datetime, json, math, os, pathlib, subprocess, time


def summarize(events, end_ms, now_ms):
    work = {}; effects = {}; health = []
    for row in events:
        key=row.get('key'); event=row.get('event'); fields=row.get('fields',{}); at=row.get('at',0)
        if event=='health':
            health.append(row)
            for item in fields.get('queue',[]):
                w=work.setdefault(item['key'],{})
                w.update({k:v for k,v in item.items() if k!='key'})
                w.setdefault('accepted',at)
        if key and event not in ('external_effect','tiktok_reconciliation'):
            w=work.setdefault(key,{})
            if event=='offered':
                w.update(fields)
            if event=='accepted': w.setdefault('accepted',at)
            if event=='outcome': w.update(fields); w['outcome_at']=at
            if event in ('superseded','rejected_capacity'): w['disposition']=event
            if event=='deferred': w['retry_at']=fields.get('not_before');w['attempt']=fields.get('attempt')
        if event=='external_effect': effects[key]={'status':fields.get('status'),'capability':fields.get('capability')}
    inbound=[w for w in work.values() if w.get('kind')=='WA_REPLY_INBOUND' and 'accepted' in w]
    unresolved=[w for w in inbound if w.get('status')!='DONE' and w.get('disposition')!='superseded']
    latencies=sorted(max(0,w['outcome_at']-w['observed_at']) for w in inbound if w.get('status')=='DONE' and w.get('observed_at',0)>0 and 'outcome_at' in w)
    commercial_start=health[0]['fields'].get('commercial',{}) if health else {}
    commercial_end=health[-1]['fields'].get('commercial',{}) if health else {}
    return {'evaluation_complete':now_ms>=end_ms,'ends_at_utc':datetime.datetime.fromtimestamp(end_ms/1000,datetime.timezone.utc).isoformat(),
      'accepted_inbound':len(inbound),'verified_reply_outcomes':len(latencies),'inbound_not_verified':len(unresolved),
      'oldest_unverified_age_seconds':max([max(0,now_ms-w['observed_at'])/1000 for w in unresolved if w.get('observed_at',0)>0],default=None),
      'verified_reply_p95_seconds':latencies[math.ceil(len(latencies)*.95)-1]/1000 if latencies else None,
      'verified_replies_within_120_seconds':sum(x<=120000 for x in latencies),
      'work_outcomes':dict(collections.Counter(w.get('status','NO_OUTCOME') for w in work.values() if 'accepted' in w)),
      'external_effects':dict(collections.Counter(f"{e['capability']}:{e['status']}" for e in effects.values())),
      'unresolved_external_effects':sum(e['status'] in ('ACTING','UNCERTAIN','VERIFICATION_PENDING') for e in effects.values()),
      'owner_active_samples':sum(r.get('event')=='world' and r.get('fields',{}).get('owner_active',False) for r in events),
      'owner_rescue_count':'UNKNOWN: owner activity is not proof of a rescue; annotate interventions separately',
      'reporting_failures':sum(r.get('event')=='reporting_failure' for r in events),
      'capacity_rejections':sum(r.get('event')=='rejected_capacity' for r in events),
      'last_phone_event_at':max([r.get('at',0) for r in events],default=0),'phone_journal_stale':not events or now_ms-max(r.get('at',0) for r in events)>180000,
      'health_samples':len(health),'commercial_baseline':commercial_start,'commercial_latest':commercial_end,
      'limits':['No traffic means insufficient evidence, not a pass.','Work completion is distinct from verified external delivery.','Phone outages and collector gaps are reported separately.','Commercial totals may cover calendar periods; do not attribute their change to Amara without ledger evidence.','Superseded messages are retained as dispositions; the replacement still requires verification.']}


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--serial',required=True);parser.add_argument('--file',required=True)
    parser.add_argument('--end-ms',type=int,required=True);parser.add_argument('--output',required=True);args=parser.parse_args()
    if not (args.file.startswith('evaluation-') and args.file.endswith('.jsonl') and '/' not in args.file): raise ValueError('Invalid journal filename')
    os.umask(0o077);out=pathlib.Path(args.output);out.mkdir(parents=True,exist_ok=True)
    def adb(*command):
        try:
            p=subprocess.run(['/root/bin/adb','-s',args.serial,*command],capture_output=True,timeout=20)
            return p.returncode,p.stdout
        except subprocess.TimeoutExpired:return 124,b''
    poll_index=0
    while True:
        now=int(time.time()*1000); sample={'at':now}
        rc,pid=adb('shell','pidof','co.sanaa.agent');sample['process_present']=rc==0 and bool(pid.strip())
        if sample['process_present']:
            rc,_=adb('shell','am','broadcast','-n','co.sanaa.agent/.receivers.ProofOfConceptReceiver','-a','co.sanaa.agent.action.TEST_EVALUATION_HEALTH');sample['health_request_exit']=rc
        if poll_index % 5 == 0:
            arc,access=adb('shell','dumpsys','accessibility')
            sample['accessibility_read_exit']=arc
            if arc==0:
                decoded=access.decode(errors='replace')
                bound=decoded.split('Bound services:')[-1].split('Enabled services:')[0]
                crashed=decoded.split('Crashed services:')[-1]
                sample['accessibility_bound']='Sanaa Agent' in bound
                sample['accessibility_crashed']='co.sanaa.agent/co.sanaa.agent.services.AccessibilityAgentService' in crashed
        poll_index+=1
        rc,data=adb('shell','run-as','co.sanaa.agent','cat','files/'+args.file);sample['journal_fetch_exit']=rc
        if rc==0:
            # A concurrent append may leave one unfinished line; retain it next poll.
            data=data[:data.rfind(b'\n')+1]
            temp=out/'events.tmp';temp.write_bytes(data);temp.replace(out/'events.jsonl')
        with (out/'collector-health.jsonl').open('a') as f:f.write(json.dumps(sample)+'\n')
        rows=[];bad=0
        if (out/'events.jsonl').exists():
            for line in (out/'events.jsonl').read_text().splitlines():
                try:rows.append(json.loads(line))
                except ValueError:bad+=1
        result=summarize(rows,args.end_ms,int(time.time()*1000));result['malformed_event_lines']=bad
        polls=[json.loads(l) for l in (out/'collector-health.jsonl').read_text().splitlines()]
        result['collector_polls']=len(polls);result['failed_journal_polls']=sum(p['journal_fetch_exit']!=0 for p in polls)
        result['process_absent_polls']=sum(not p['process_present'] for p in polls)
        temp=out/'summary.tmp';temp.write_text(json.dumps(result,indent=2)+'\n');temp.replace(out/'summary.json')
        if int(time.time()*1000)>=args.end_ms:break
        time.sleep(min(60,max(0,(args.end_ms-int(time.time()*1000))/1000)))

if __name__=='__main__':main()
