import torch, numpy as np, math, sys
from pathlib import Path
import terrain_slm.world.generator as G
from terrain_slm.world.generator import procedural_controls, _fbm
from terrain_slm.models import planner as P
from terrain_slm.data import descriptors as D
dev="cuda:1"
ci=torch.arange(-1024,1024,device=dev).float().view(-1,1).expand(2048,2048); cj=ci.T
belts=(1-_fbm(ci,cj,220.,0,2,octaves=3).abs())**2; mnt=(0.5+0.5*_fbm(ci,cj,600.,0,3,octaves=2)).clamp(0,1)**1.5
f=_fbm(ci,cj,600.,0,3,octaves=2)
print("fbm600 std %.3f min %.2f max %.2f | mountainous p99 %.3f max %.3f | belts*mnt max %.3f p99.9 %.3f"%(f.std(),f.min(),f.max(),torch.quantile(mnt.flatten()[::13],.99),mnt.max(),(belts*mnt).max(),torch.quantile((belts*mnt).flatten()[::13],.999)))
# planner response: feed synthetic trend ramps, climate=alps archetype, wild high
gen=G.WorldGenerator(Path("checkpoints/poc"),0,dev)
W=64
yy,xx=torch.meshgrid(torch.arange(W,device=dev).float(),torch.arange(W,device=dev).float(),indexing="ij")
r=torch.sqrt((yy-32)**2+(xx-32)**2)
for peak in (1000,2000,3000,4000):
  trend=200+peak*torch.exp(-(r/12)**2)
  x=torch.zeros(1,P.N_IN,W,W,device=dev); x[0,P.IN_TREND]=trend/1000
  for k,(n,v) in enumerate(zip(P.CLIMATE_NAMES,G.CLIMATE_ARCHETYPES[0][1:])): x[0,P.IN_T0+k]=P.climate_input(n,torch.full((W,W),v,device=dev))
  la=math.log(2)+torch.exp(-(r/12)**2)*(math.log(40)-math.log(2))
  x[0,P.IN_WILD]=(la-P.WILD_NORM[0])/P.WILD_NORM[1]; x[0,P.IN_HAS_TREND]=1;x[0,P.IN_HAS_CLIMATE]=1;x[0,P.IN_HAS_WILD]=1
  with torch.no_grad(): o=gen.planner(x)[0].float()
  h=o[0]*1000; desc=o[1:9]*gen.desc_std.view(-1,1,1)+gen.desc_mean.view(-1,1,1)
  print(f"peak trend {200+peak:5d} -> planner height at peak {h[30:34,30:34].mean():7.0f}  band amps(m) at peak", [round(math.exp(v),1) for v in desc[:5,32,32].tolist()])
# real alps: band amps where coarse>2500
d=np.load("data/alps/cells.npz"); co=d["coarse"]; de=d["desc"]
m=co>2500; print("REAL alps coarse>2500 median band amps(m)",[round(float(np.exp(np.median(de[k][m]))),1) for k in range(5)])
m=(co>200)&(co<600); print("REAL alps 200-600m median band amps(m)",[round(float(np.exp(np.median(de[k][m]))),1) for k in range(5)])
