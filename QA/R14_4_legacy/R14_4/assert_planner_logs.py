#!/usr/bin/env python3
"""Strict seed-plan road gate: logs are not a PASS just because JVM returned 0."""
import re
import sys
from pathlib import Path

root=Path(__file__).resolve().parent
expected={
    '13': 620,
    '42': 50,
    '1750414970189483479': 30,
    '123456789': 630,
    '987654321': 560,
}
errors=[]
for seed, min_rects in expected.items():
    path=root/f'seed_{seed}_final.log'
    if not path.exists():
        errors.append(f'{seed}: missing {path.name}')
        continue
    s=path.read_text(errors='replace')
    hits=re.findall(r'^ROAD_RULES seed=([^\s]+) (.+)$',s,re.M)
    if len(hits)!=1:
        errors.append(f'{seed}: expected exactly one ROAD_RULES line; got {len(hits)}')
        continue
    got_seed, row=hits[0]
    fields={k:int(v) for k,v in re.findall(r'\b([A-Za-z0-9_]+)=(-?\d+)\b',row)}
    if got_seed!=seed: errors.append(f'{seed}: reported seed {got_seed}')
    if fields.get('rects',0)<min_rects:
        errors.append(f'{seed}: insufficient rectangle coverage {fields.get("rects")}')
    for key in ('missingTotal','urbanBad','ruralNonLikeBad',
                'urbanMissingCorners','missingPlan','erasedByMorphomos',
                'unshaped','degree1'):
        if fields.get(key)!=0:
            errors.append(f'{seed}: {key}={fields.get(key)} (expected 0)')
    if fields.get('kept',0)==0 or fields.get('dissolved',0)==0:
        errors.append(f'{seed}: did not exercise both retained roads and dissolved seams')
    adjacency=re.findall(r'^REGION_ADJACENCY (.*)$',s,re.M)
    if len(adjacency)!=1 or 'unlikeOrUnowned=0' not in adjacency[0]:
        errors.append(f'{seed}: invalid region adjacency summary')
    if re.search(r'^(?:MISSING |DEAD_END |UNSHAPED_ROAD )',s,re.M):
        errors.append(f'{seed}: individual failure diagnostic present')
    if 'COMMITTED ROAD FRAME GAPS' in s:
        errors.append(f'{seed}: committed district-frame gap warning')
    print(f"{seed}: {fields.get('rects',0)} rectangles, kept={fields.get('kept')}, "
          f"dissolved={fields.get('dissolved')}, missing={fields.get('missingTotal')}, "
          f"unshaped={fields.get('unshaped')}, deadEnds={fields.get('degree1')}")

# Ensure no silently timed-out seed invocation.
status=(root/'batch_status.log').read_text(errors='replace') if (root/'batch_status.log').exists() else ''
for seed in expected:
    if not re.search(r'^END '+seed+r' rc=0\b',status,re.M):
        errors.append(f'{seed}: missing successful JVM completion record')
if errors:
    for error in errors: print('FAIL:',error,file=sys.stderr)
    sys.exit(1)
print(f'PASS: {len(expected)} seed windows; no measured required-edge gaps, unshaped roads, or one-ended roads')
