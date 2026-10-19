import torch, numpy as np, json
from pathlib import Path
from terrain_slm.world.generator import procedural_controls
dev="cuda:1"
# 1) trend distribution over a big area (2048x2048 cells = 490 km)
c = procedural_controls(-1024,-1024,2048,2048,0,dev)
t=c["trend"]; land=t>0
q=torch.quantile(t[land][::7].float(), torch.tensor([.5,.9,.99,.999],device=dev))
print("TREND land frac %.2f  land quantiles p50/p90/p99/p99.9:"%land.float().mean(), [round(x) for x in q.tolist()], "max", round(t.max().item()))
for thr in (1000,1500,2000,2500): print(f"  frac land cells > {thr} m: {(t[land]>thr).float().mean().item():.4f}")
# 2) real data coarse distribution
for reg in ["alps","norway","colorado_plateau","east_africa"]:
    d=np.load(f"data/{reg}/cells.npz"); co=d["coarse"]; co=co[co>0]
    print(reg, "coarse p50/p90/p99/max", [int(np.percentile(co,p)) for p in (50,90,99)], int(co.max()), "frac>2000 %.3f"%(co>2000).mean())
