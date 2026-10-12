"""Summarize actual PresentMon records without confusing submitted frames with displayed frames."""
import csv, datetime, json, statistics, sys
from collections import Counter, defaultdict
from pathlib import Path

def summarize(path):
    rows=list(csv.DictReader(Path(path).open(encoding='utf-8-sig')))
    first=datetime.datetime.fromisoformat(rows[0]['TimeInDateTime']);groups=defaultdict(list)
    for row in rows:
        seconds=(datetime.datetime.fromisoformat(row['TimeInDateTime'])-first).total_seconds()
        groups[int(seconds//5)*5].append(row)
    windows=[]
    for seconds,group in sorted(groups.items()):
        def values(key):
            return [float(r[key]) for r in group if r.get(key) not in (None,'NA','') and float(r[key])>0]
        presented=values('MsBetweenPresents');displayed=values('MsBetweenDisplayChange')
        windows.append(dict(startSeconds=seconds,submittedFrames=len(group),displayedFrames=len(displayed),submittedFps=1000/statistics.mean(presented) if presented else None,displayedFps=1000/statistics.mean(displayed) if displayed else None,p95SubmittedFrameMs=sorted(presented)[min(len(presented)-1,int(len(presented)*.95))] if presented else None))
    return dict(frames=len(rows),presentationModes=dict(Counter(r['PresentMode'] for r in rows)),windows=windows)

if __name__=='__main__':
    print(json.dumps(summarize(sys.argv[1]),indent=2))
