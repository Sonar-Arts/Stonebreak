import torch, numpy as np, json
from pathlib import Path
from terrain_slm.train.planner_data import PlannerData
from terrain_slm.models import planner as P
from terrain_slm.data import descriptors as D
dev="cuda:1"
pd=PlannerData([Path("data/alps"),Path("data/norway"),Path("data/east_africa")],dev,crop=64)
pk=torch.load("checkpoints/poc/planner.pt",map_location=dev,weights_only=False)
net=P.Planner(P.PlannerConfig(**pk["config"])).to(dev).eval(); net.load_state_dict(pk["model"])
def hp_rms(h, s=4.0):  # relief below ~ (2*pi*4 cells)=6 km: residual over a sigma-4-cell blur
    return float((h-D.blur(h,s)).std())
rows=[]
for x,t in pd.val_batches(64):
    f_h=t["height"]*1000
    for sig in (3.0,8.0,16.0):
        xx=x.clone(); xx[:,P.IN_TREND:P.IN_TREND+1]=D.blur(f_h,sig)/1000
        with torch.no_grad(): o=net(xx)[:,0:1].float()*1000
        rows.append((sig,hp_rms(f_h),hp_rms(D.blur(f_h,sig)),hp_rms(o)))
import collections; agg=collections.defaultdict(list)
for r in rows: agg[r[0]].append(r[1:])
print("valley-scale relief RMS (m) [<~6 km wavelengths], val tiles alps/norway/east_africa")
for s,v in agg.items():
    v=np.array(v).mean(0); print(f"  trend blur sigma {s:4.0f} cells ({s*0.24:.1f} km): real {v[0]:6.1f}  in-trend {v[1]:6.1f}  planner out {v[2]:6.1f}")
