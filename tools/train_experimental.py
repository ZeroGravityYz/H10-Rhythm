"""Reproducible small ECG classifier experiment. Not clinical validation.
Training: fixed 22-record MIT-BIH development set, optionally 40 Icentia records.
Icentia evaluation: next 20 records with patient-disjoint IDs.
MIT-BIH evaluation: remaining records, excluding
paced records and 202 (precaution: same subject as development record 201).
Both sets were previously inspected with the old engine: NOT a blind test.
Annotations never enter the Java feature extractor or personal baseline.
No H10/user data are read. Output is a research candidate, not an automatic release.
"""
from pathlib import Path
import argparse, base64, hashlib, json, struct, subprocess, math, gzip
import numpy as np
import wfdb
from scipy.signal import resample_poly
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import confusion_matrix

TRAIN=set('101 106 108 109 112 114 115 116 118 119 122 124 201 203 205 207 208 209 215 220 223 230'.split())
EXCLUDE={'102','104','107','217','202'}
CODES={'N':0,'L':0,'R':0,'e':0,'j':0,'V':1,'E':1,'A':2,'a':2,'J':2,'S':2}
ALL_BEATS=set(CODES)|{'F','/','f','Q','?'}

def extract(name,args,root=None,tag='mitdb'):
    root=root or args.data
    cache=args.output/tag/(name+'.npz');cache.parent.mkdir(parents=True,exist_ok=True)
    sources=[Path('app/src/main/java/com/local/polarh10monitor')/n for n in ['EcgEngine.java','StreamingQrs.java','ShapeReference.java','MorphologyModel.java']]+[Path('tools/com/local/polarh10monitor/BenchmarkReplay.java')]
    fingerprint=hashlib.sha256(b''.join(p.read_bytes() for p in sources)).hexdigest()
    if cache.exists() and args.reuse:
        z=np.load(cache)
        if 'fingerprint' not in z or str(z['fingerprint'])!=fingerprint:raise ValueError('Stale feature cache: run again without --reuse')
        return z['x'],z['y'],z['clean'],int(z['missed_v']),int(z['missed_s'])
    rec=wfdb.rdrecord(str(root/name));c=rec.sig_name.index('MLII') if 'MLII' in rec.sig_name else 0
    if rec.units[c]!='mV':raise ValueError('Unexpected acquisition')
    divisor=math.gcd(130,int(rec.fs));raw=np.rint(resample_poly(rec.p_signal[:,c]*1000,130//divisor,int(rec.fs)//divisor)).astype('<i4')
    raw_path=cache.with_suffix('.raw');raw.tofile(raw_path)
    run=subprocess.run([args.java,'-cp',args.classpath,'com.local.polarh10monitor.BenchmarkReplay',str(raw_path),'features'],capture_output=True,text=True,check=True)
    rows=[r.split(',') for r in run.stdout.splitlines() if len(r.split(','))==53]
    times=np.array([int(r[0])/130 for r in rows]);x=np.array([[float(v) for v in r[5:]] for r in rows],dtype=np.float32)
    clean=np.array([r[2] not in {'GLOBAL_SQI','WIDTH_OR_SHOCK','ENERGY_DISAGREEMENT','LOCAL_SQI','CONTEXT_UNCERTAIN','MOTION_UNCERTAIN','USER_FEEDBACK_UNCERTAIN'} for r in rows])
    ann=wfdb.rdann(str(root/name),'atr');ref=[(s/rec.fs,k) for s,k in zip(ann.sample,ann.symbol) if k in ALL_BEATS and 30<=s/rec.fs<=len(raw)/130-2]
    keep=(times>=30)&(times<=len(raw)/130-2);times,x,clean=times[keep],x[keep],clean[keep]
    candidates=[]
    for i,(t,_) in enumerate(ref):
        lo,hi=np.searchsorted(times,[t-.15,t+.15]);candidates.extend((abs(times[j]-t),i,j) for j in range(lo,hi))
    used_ref=set();used_pred=set();y=np.full(len(times),3,dtype=np.int32)
    for _,i,j in sorted(candidates):
        if i not in used_ref and j not in used_pred:used_ref.add(i);used_pred.add(j);y[j]=CODES.get(ref[i][1],-1)
    mv=sum(k in {'V','E'} and i not in used_ref for i,(_,k) in enumerate(ref))
    ms=sum(k in {'A','a','J','S'} and i not in used_ref for i,(_,k) in enumerate(ref))
    np.savez_compressed(cache,x=x,y=y,clean=clean,missed_v=mv,missed_s=ms,fingerprint=fingerprint)
    print('features',name,len(x),flush=True);return x,y,clean,mv,ms

def metric(tp,fp,fn):
    return dict(tp=int(tp),fp=int(fp),fn=int(fn),sensitivity=float(tp/(tp+fn)) if tp+fn else None,ppv=float(tp/(tp+fp)) if tp+fp else None)

def main():
    p=argparse.ArgumentParser();p.add_argument('--data',type=Path,required=True);p.add_argument('--icentia',type=Path);p.add_argument('--java',required=True);p.add_argument('--classpath',default='build/qrs-probe');p.add_argument('--output',type=Path,default=Path('build/experimental/supervised'));p.add_argument('--reuse',action='store_true');args=p.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    names=sorted(p.stem for p in args.data.glob('*.hea') if p.stem not in EXCLUDE)
    sets={n:extract(n,args) for n in names}
    training=set(TRAIN);source_files={n:args.data/(n+'.dat') for n in names}
    if args.icentia:
        selected=sorted(args.icentia.rglob('*.hea'))[:60]
        # Deterministic predeclared partition, no label-based record selection.
        train_patients={p.parent.name for p in selected[:40]};test_patients={p.parent.name for p in selected[40:]}
        if train_patients&test_patients:raise ValueError('Patient leakage')
        for i,path in enumerate(selected):
            relative=path.relative_to(args.icentia).with_suffix('').as_posix();n='icentia/'+relative
            sets[n]=extract(relative,args,args.icentia,'icentia');names.append(n);source_files[n]=path.with_suffix('.dat')
            if i<40:training.add(n)
    x=np.concatenate([sets[n][0] for n in names if n in training]);y=np.concatenate([sets[n][1] for n in names if n in training]);keep=y>=0
    model=RandomForestClassifier(n_estimators=16,max_depth=7,min_samples_leaf=15,class_weight='balanced_subsample',random_state=20260905,n_jobs=2,max_features=.6)
    model.fit(x[keep],y[keep]);assert list(model.classes_)==[0,1,2,3]
    # Numerical export parity fixture, development data only.
    fixture=x[keep][np.linspace(0,keep.sum()-1,200,dtype=int)]
    np.savetxt(args.output/'parity.csv',np.column_stack([fixture,model.predict_proba(fixture)]),delimiter=',',fmt='%.9g')
    # Packed little-endian model: forest size; each tree node count; nodes
    # int16 feature (-1 leaf), float32 threshold, int16 left/right, 4 uint16 votes.
    packed=bytearray(struct.pack('<H',len(model.estimators_)))
    for estimator in model.estimators_:
        t=estimator.tree_;packed.extend(struct.pack('<H',t.node_count))
        for i in range(t.node_count):
            v=t.value[i,0];v=v/v.sum();packed.extend(struct.pack('<hfhh4H',int(t.feature[i]),float(t.threshold[i]),int(t.children_left[i]),int(t.children_right[i]),*[round(float(a)*65535) for a in v]))
    (args.output/'model.bin').write_bytes(packed)
    (args.output/'model-base64.txt').write_text(base64.b64encode(packed).decode(),encoding='ascii')
    (args.output/'model-gzip-base64.txt').write_text(base64.b64encode(gzip.compress(packed,mtime=0)).decode(),encoding='ascii')
    evaluation=[]
    for n in names:
        if n in training:continue
        x,y,clean,mv,ms=sets[n];prob=model.predict_proba(x);pred=prob.argmax(axis=1)
        # Frozen before evaluation: confident class >= .85, margin >= .25; SQI abstains.
        ordered=np.sort(prob,axis=1);accepted=(ordered[:,-1]>=.85)&((ordered[:,-1]-ordered[:,-2])>=.25)&clean
        decided=np.where(accepted,pred,-1)
        row={'record':n,'coverage':float(accepted.mean()),'matrix_N_V_S_unmatched':confusion_matrix(y[y>=0],pred[y>=0],labels=[0,1,2,3]).tolist()}
        for key,k,miss in [('V',1,mv),('S',2,ms)]:
            tp=((y==k)&(decided==k)).sum();fp=((y!=k)&(decided==k)).sum();fn=((y==k)&(decided!=k)).sum()+miss
            row[key]=metric(tp,fp,fn)
        evaluation.append(row)
    totals={key:metric(sum(r[key]['tp'] for r in evaluation),sum(r[key]['fp'] for r in evaluation),sum(r[key]['fn'] for r in evaluation)) for key in ['V','S']}
    report=dict(experimental=True,claim='No clinical or H10 validation. MIT-BIH previously inspected non-blind regression cohort; Icentia fixed patient-disjoint partition, first 40 training, next 20 evaluation. Forest votes are not diagnostic probabilities.',training_records=sorted(training),evaluation_records=[r['record'] for r in evaluation],excluded=sorted(EXCLUDE),bytes=len(packed),model_sha256=hashlib.sha256(packed).hexdigest(),parameters=model.get_params(),decision_threshold=.85,decision_margin=.25,totals=totals,records=evaluation,source_sha256={n:hashlib.sha256(source_files[n].read_bytes()).hexdigest() for n in names})
    (args.output/'report.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps(dict(bytes=len(packed),totals=totals),indent=2))
if __name__=='__main__':main()
