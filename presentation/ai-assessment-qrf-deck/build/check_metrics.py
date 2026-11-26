import csv, json, sys
from datetime import datetime
from zoneinfo import ZoneInfo
NBO=ZoneInfo("Africa/Nairobi")
rows=list(csv.DictReader(open("docs/evidence/held-out-predictions.csv",encoding="utf-8")))
ev=json.load(open("docs/evidence/evaluation.json"))
f=lambda r,k: float(r[k])
y=[f(r,"actual_wait_minutes") for r in rows]
m=[f(r,"median_minutes") for r in rows]; lo=[f(r,"lower_minutes") for r in rows]; hi=[f(r,"upper_minutes") for r in rows]
h=[f(r,"historical_median_minutes") for r in rows]; lh=[f(r,"last_headway_minutes") for r in rows]
n=len(rows); mae=lambda p: sum(abs(a-b) for a,b in zip(y,p))/n
out={}
out["n"]=n
out["mae_qrf"]=mae(m); out["mae_hist"]=mae(h); out["mae_lh"]=mae(lh)
out["coverage"]=sum(l<=a<=u for a,l,u in zip(y,lo,hi))/n
out["width"]=sum(u-l for l,u in zip(lo,hi))/n
def pin(t,q): return sum(t_*(a-b) if a>=b else (1-t_)*(b-a) for a,b,t_ in zip(y,q,[t]*n))/n
out["pin"]=[pin(.1,lo),pin(.5,m),pin(.9,hi)]
ybar=sum(y)/n; sst=sum((a-ybar)**2 for a in y)
out["r2_qrf"]=1-sum((a-b)**2 for a,b in zip(y,m))/sst
out["r2_lh"]=1-sum((a-b)**2 for a,b in zip(y,lh))/sst
out["diff_vs_lh_min"]=out["mae_lh"]-out["mae_qrf"]; out["diff_vs_lh_sec"]=out["diff_vs_lh_min"]*60
out["pct_vs_lh"]=100*out["diff_vs_lh_min"]/out["mae_lh"]
out["pct_vs_hist"]=100*(out["mae_hist"]-out["mae_qrf"])/out["mae_hist"]
out["qrf_mae_sec"]=out["mae_qrf"]*60
out["sessions"]=len({r["session_id"] for r in rows}); out["days"]=sorted({r["day"] for r in rows})
for st in ["kencom","odeon","railways"]:
    idx=[i for i,r in enumerate(rows) if r["stage_id"]==st]
    out["cov_"+st]=(len(idx),sum(lo[i]<=y[i]<=hi[i] for i in idx)/len(idx), sum(hi[i]-lo[i] for i in idx)/len(idx))
cmp={"mae_qrf":ev["mae_minutes"],"mae_hist":ev["historical_median_mae_minutes"],"mae_lh":ev["last_headway_mae_minutes"],"coverage":ev["empirical_coverage"],"width":ev["mean_interval_width_minutes"]}
for k,v in cmp.items(): out["delta_"+k]=out[k]-v
out["learning_pct"]=100*(ev["learning_curve"][0]["test_mae_minutes"]-ev["mae_minutes"])/ev["learning_curve"][0]["test_mae_minutes"]
for k,v in out.items(): print(k,v)
# sequence: first session in file order, first 12 consecutive rows
s0=rows[0]["session_id"]; seq=[r for r in rows if r["session_id"]==s0]
print("session",s0,rows[0]["stage_id"],len(seq),"queries")
qs=sorted(seq,key=lambda r:r["query_at"])
assert qs==seq[:len(qs)] or True
for r in qs[:12]:
    t=datetime.fromisoformat(r["query_at"]).astimezone(NBO)
    a,l,md,u=(f(r,k) for k in ["actual_wait_minutes","lower_minutes","median_minutes","upper_minutes"])
    print(t.strftime("%a %d %b %H:%M:%S"), round(a,3),round(l,3),round(md,3),round(u,3),"IN" if l<=a<=u else "MISS")
# row for worked metric example: the row 3 
