"""External-domain evaluation, never used for calibration or threshold selection.
Requirements: numpy, scipy, wfdb. Data: https://physionet.org/content/mitdb/1.0.0/
Uses the actual Java engine compiled by ./gradlew selfTest.
"""
from pathlib import Path
import argparse
import hashlib
import json
import math
from collections import Counter
import subprocess
import numpy as np
from scipy.signal import resample_poly
import wfdb

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--java", required=True)
    parser.add_argument("--records", nargs="*")
    parser.add_argument("--output", type=Path, default=Path("build/benchmark"))
    parser.add_argument("--replay", default="BenchmarkReplay", choices=["BenchmarkReplay", "QrsReplay"])
    parser.add_argument("--classpath", default=None)
    parser.add_argument("--recursive", action="store_true")
    parser.add_argument("--limit", type=int, default=None)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    headers=args.data.rglob("*.hea") if args.recursive else args.data.glob("*.hea")
    records = args.records or sorted(p.relative_to(args.data).with_suffix("").as_posix() for p in headers if p.stem not in {"102","104","107","217"})
    if args.limit is not None:records=records[:args.limit]
    cp = args.classpath or __import__("os").pathsep.join(["app/build/self-tests","app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"])
    results = []
    for name in records:
        source = args.data / name
        record = wfdb.rdrecord(str(source))
        channel = record.sig_name.index("MLII") if "MLII" in record.sig_name else 0
        if record.units[channel] != "mV":
            raise ValueError("Expected mV; refusing silent amplitude conversion")
        divisor = math.gcd(130, int(record.fs))
        uv = np.rint(resample_poly(record.p_signal[:,channel]*1000,130//divisor,int(record.fs)//divisor)).astype("<i4")
        raw_path = args.output / (name+".raw")
        raw_path.parent.mkdir(parents=True,exist_ok=True)
        uv.tofile(raw_path)
        replay = subprocess.run([args.java,"-cp",cp,"com.local.polarh10monitor."+args.replay,str(raw_path)],check=True,capture_output=True,text=True)
        parsed = [line.split(",") for line in replay.stdout.splitlines() if "," in line]
        predicted = np.array([int(row[0])/130 for row in parsed])
        labels = np.array([row[1] for row in parsed])
        reasons = np.array([row[2] if len(row)>2 else "QRS_ONLY" for row in parsed])
        if np.any(np.diff(predicted) <= 0):
            raise ValueError("Predictions must be strictly chronological for matching")
        annotations = wfdb.rdann(str(source),"atr")
        beat_symbols = {"N","L","R","A","a","J","S","V","E","F","e","j","/","f","Q","?"}
        keep = np.array([s in beat_symbols for s in annotations.symbol])
        truth = annotations.sample[keep]/record.fs
        symbols = np.array(annotations.symbol)[keep]
        # Fixed warm-up and terminal classification margin, declared before evaluating.
        t0, t1 = 30.0, len(uv)/130-2.0
        keep = (predicted>=t0)&(predicted<=t1)
        predicted, labels, reasons = predicted[keep],labels[keep],reasons[keep]
        keep = (truth>=t0)&(truth<=t1)
        truth,symbols = truth[keep],symbols[keep]
        candidates = []
        for i,t in enumerate(truth):
            lo,hi = np.searchsorted(predicted,[t-.150,t+.150])
            candidates.extend((abs(predicted[j]-t),i,j) for j in range(lo,hi))
        used_ref,used_pred,matches = set(),set(),{}
        for _,i,j in sorted(candidates):
            if i not in used_ref and j not in used_pred:
                used_ref.add(i);used_pred.add(j);matches[i]=j
        row = {"record":name,"channel":record.sig_name[channel],"seconds":t1-t0,
               "reference_beats":len(truth),"predicted_beats":len(predicted),"qrs_tp":len(matches),
               "sha256_dat":hashlib.sha256(source.with_suffix(".dat").read_bytes()).hexdigest()}
        for group, codes, decision in [("V",{"V","E"},"V"),("S",{"A","a","J","S"},"A")]:
            ref = set(np.flatnonzero(np.isin(symbols,list(codes))).tolist())
            pred = set(np.flatnonzero(labels==decision).tolist())
            tp = sum(i in ref and j in pred for i,j in matches.items())
            row[group]={"tp":tp,"fn":len(ref)-tp,"fp":len(pred)-tp,"reference":len(ref)}
        row["unclassified"] = int(np.sum(labels=="?"))
        row["reasons_by_reference"]={}
        for group,codes in [("V",{"V","E"}),("S",{"A","a","J","S"}),("other",beat_symbols-{"V","E","A","a","J","S"})]:
            row["reasons_by_reference"][group]=dict(Counter(str(reasons[matches[i]]) if i in matches else "QRS_MISSED" for i,s in enumerate(symbols) if s in codes))
        results.append(row)
        print(json.dumps(row),flush=True)
    totals = {"records":len(results),"hours":sum(r["seconds"] for r in results)/3600}
    for group in ["V","S"]:
        tp=sum(r[group]["tp"] for r in results);fp=sum(r[group]["fp"] for r in results);fn=sum(r[group]["fn"] for r in results)
        totals[group]={"tp":tp,"fp":fp,"fn":fn,"sensitivity":tp/(tp+fn) if tp+fn else None,"ppv":tp/(tp+fp) if tp+fp else None,"false_positives_per_hour":fp/totals["hours"]}
    tp=sum(r["qrs_tp"] for r in results)
    totals["qrs_sensitivity"]=tp/sum(r["reference_beats"] for r in results)
    totals["qrs_ppv"]=tp/sum(r["predicted_beats"] for r in results)
    output={"replay":args.replay,"protocol":"MIT-BIH v1.0.0; MLII if present; resample_poly to 130 Hz; no annotation supplied to engine; 30 s warm-up, 2 s terminal margin; one-to-one 150 ms matching; ectopic beat labels BEFORE notification cooldown. QrsReplay evaluates beat location only, not classification.","warning":"Exploratory regression data already inspected in beta1, NOT blind validation, NOT validation on H10 and NOT certification.","totals":totals,"records":results}
    (args.output/"results.json").write_text(json.dumps(output,indent=2),encoding="utf-8")
    print(json.dumps(totals,indent=2))

if __name__=="__main__":
    main()
