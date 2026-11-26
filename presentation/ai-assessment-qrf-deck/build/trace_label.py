from nganyaflow.ml import build_samples
base=dict(session_id="s1",stage_id="kencom",route_id="cbd-study-simulated",direction="townbound",event_type="arrival")
recs=[dict(base,observed_at=f"2026-10-16T07:{m:02d}:00+03:00") for m in (0,6,14)]
for s in build_samples(recs):
    print(s["query_at"], {k:round(v,3) if isinstance(v,float) else v for k,v in s["features"].items()}, "wait", s["wait"])
try:
    build_samples(recs+[dict(base,observed_at="2026-10-16T07:06:00+03:00")])
except ValueError as e: print("duplicate:",e)
try:
    build_samples([dict(base,observed_at="2026-10-16T07:00:00")])
except ValueError as e: print("naive:",e)
# a session ending after 07:06 -> no labels
print("tail-only:",len(build_samples(recs[:2])))
