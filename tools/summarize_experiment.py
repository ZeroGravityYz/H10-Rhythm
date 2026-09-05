"""Summarize frozen evaluation cohorts with record-cluster bootstrap intervals.
Intervals describe sampling variability within these cohorts, not H10 transfer.
Unclassifiable reference Q is conservatively non-V in FP counting; no claim of
clinical specificity. No tuning or exclusion based on observed performance.
"""
from pathlib import Path
import json
import numpy as np

ROOT=Path('build/experimental')
manifest=json.loads((ROOT/'multidomain/report.json').read_text())
test=set(manifest['evaluation_records'])

def summarize(rows):
    hours=sum(r['seconds'] for r in rows)/3600
    tp=sum(r['V']['tp'] for r in rows);fp=sum(r['V']['fp'] for r in rows);fn=sum(r['V']['fn'] for r in rows)
    ref=sum(r['reference_beats'] for r in rows);pred=sum(r['predicted_beats'] for r in rows);qrs=sum(r['qrs_tp'] for r in rows)
    rng=np.random.default_rng(20260905);intervals=[]
    for _ in range(2000):
        sample=[rows[i] for i in rng.integers(0,len(rows),len(rows))]
        t=sum(r['V']['tp'] for r in sample);p=sum(r['V']['fp'] for r in sample);n=sum(r['V']['fn'] for r in sample)
        intervals.append([t/(t+n) if t+n else np.nan,t/(t+p) if t+p else np.nan])
    ci=np.nanpercentile(np.array(intervals),[2.5,97.5],axis=0)
    return dict(records=len(rows),hours=hours,reference_beats=ref,qrs_sensitivity=qrs/ref,qrs_ppv=qrs/pred,
                ventricular=dict(tp=tp,fp_conservative=fp,fn=fn,sensitivity=tp/(tp+fn) if tp+fn else None,ppv=tp/(tp+fp) if tp+fp else None,fp_per_hour=fp/hours,sensitivity_ci95=ci[:,0].tolist(),ppv_ci95=ci[:,1].tolist()),
                undecided_fraction_of_detected=sum(r['unclassified'] for r in rows)/pred)

mit=json.loads((ROOT/'lab-final-mitdb/results.json').read_text())['records']
ic=json.loads((ROOT/'lab-final-icentia/results.json').read_text())['records']
noise=json.loads((ROOT/'lab-final-noise/results.json').read_text())['records']
assert all('icentia/'+r['record'] in test for r in ic)
result=dict(policy='Laboratory only. Physical cardiac alerts disabled. No clinical/H10 validation. S class disabled. Bootstrap by record (2000, seed 20260905); noise records are correlated transformations, no noise CI interpretation.',
            mitdb_all_regression=summarize(mit),mitdb_evaluation=summarize([r for r in mit if r['record'] in test]),icentia_evaluation=summarize(ic),noise_stress=summarize(noise))
(ROOT/'lab-summary.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
print(json.dumps(result,indent=2))
