import os, sys, math, json, numpy as np, torch, torch.nn.functional as F
from pathlib import Path
os.environ.update({"TERRAIN_BRIDGE_WORLD_HEIGHT":"256","TERRAIN_BRIDGE_SEA_LEVEL":"64","TERRAIN_BRIDGE_OCEAN_METERS_PER_BLOCK":"48","TERRAIN_BRIDGE_LOWLAND_METERS_PER_BLOCK":"16","TERRAIN_BRIDGE_MIDLAND_METERS_PER_BLOCK":"40","TERRAIN_BRIDGE_HIGHLAND_METERS_PER_BLOCK":"96","TERRAIN_BRIDGE_SCALE":"1","TERRAIN_BRIDGE_DOWNSCALE":"2"})
sys.path.insert(0,"../terrain-bridge")
from bridge.config import BridgeConfig; from bridge.height_mapping import HeightCurve
import terrain_slm.world.generator as G
from terrain_slm.data import descriptors as D
curve=HeightCurve.from_config(BridgeConfig.from_env(seed=0))
dev="cuda:1"; SEED=0; SP="reports/diagnostics/"; __import__("os").makedirs(SP, exist_ok=True)  # run from Models/DaedalusTGM-Exp
orig=G.procedural_controls
def fixed(ci0,cj0,hc,wc,seed,device):
    ci=torch.arange(ci0,ci0+hc,device=device,dtype=torch.float32).view(-1,1).expand(hc,wc)
    cj=torch.arange(cj0,cj0+wc,device=device,dtype=torch.float32).view(1,-1).expand(hc,wc)
    continent=G._fbm(ci,cj,900.,seed,1,octaves=4)+0.7*torch.exp(-(ci**2+cj**2)/(2*G.HOME_RADIUS_CELLS**2))
    land=((continent+0.35)/0.2).clamp(0,1); land=land*land*(3-2*land)
    belts=(1-G._fbm(ci,cj,220.,seed,2,octaves=3).abs())**2
    m=((G._fbm(ci,cj,600.,seed,3,octaves=2)+0.05)/0.35).clamp(0,1); m=m*m*(3-2*m)
    hills=G._fbm(ci,cj,70.,seed,8,octaves=4)
    trend=-300+600*land*(0.6+0.5*continent)+land*(3200*belts*m+200*hills*(0.3+m))
    rough=(0.08+1.3*belts*m+0.25*(0.3+m)*hills.abs()).clamp(0,1)*land
    wild=math.log(2.)+rough*(math.log(40.)-math.log(2.))
    return {"wild":wild,"trend":trend,**G._climate(ci,cj,seed)}
def blocks_curve(e): return curve.to_block_height(e).astype(float)
def blocks_split(e, R):  # coarse elevation through the curve, local relief at R m/block
    t=torch.from_numpy(e)[None,None].float()
    base=D.blur(t,16.0)[0,0].numpy()   # 16 blocks ~ 1 km
    return np.clip(curve.to_block_height(base)+(e-base)/R,0,255)
def stats(y):
    g=np.maximum(abs(np.diff(y,axis=0))[:,:-1],abs(np.diff(y,axis=1))[:-1])
    return f"relief {y.max()-y.min():5.0f}  max-y {y.max():4.0f}  p90 step {np.percentile(g,90):4.1f}  p99 step {np.percentile(g,99):4.1f}"
def mappings(e):
    return [("curve (now)",np.floor(blocks_curve(e))),("split R=24",np.floor(blocks_split(e,24))),("split R=16",np.floor(blocks_split(e,16)))]
# where is the tallest trend near origin under the fixed controls
c=fixed(-1500,-1500,3000,3000,SEED,dev); k=int(torch.argmax(c["trend"])); pi,pj=k//3000-1500,k%3000-1500
print("fixed trend peak",round(c["trend"].max().item()),"at cell",pi,pj)
out={}
for label,pc in (("old controls",orig),("fixed controls",fixed)):
    G.procedural_controls=pc
    gen=G.WorldGenerator(Path("checkpoints/poc"),SEED,dev)
    i0,j0=pi*8-512,pj*8-512
    elev,_=gen.terrain(i0,j0,i0+1024,j0+1024)
    e=F.avg_pool2d(elev[None,None],2)[0,0].cpu().numpy().astype(np.float64)   # 512x512 blocks
    out[label]=e
    print(f"== {label}: native max {elev.max().item():.0f} m, block-mean p50/p99 {np.percentile(e,50):.0f}/{np.percentile(e,99):.0f} m")
    for n,y in mappings(e): print(f"   {n:12s} {stats(y)}")
# real alps reference, 512x512 block window around the highest area
dem=np.load("data/alps/dem.npy",mmap_mode="r"); rng=np.random.default_rng(1); best=None
for _ in range(300):
    i=rng.integers(0,dem.shape[0]-1024); j=rng.integers(0,dem.shape[1]-1024)
    s=float(dem[i+512,j+512])
    if best is None or s>best[0]: best=(s,i,j)
_,i,j=best; p=np.asarray(dem[i:i+1024,j:j+1024],dtype=np.float64); e=p.reshape(512,2,512,2).mean((1,3)); out["real alps"]=e
print(f"== REAL alps window: block-mean p50/p99 {np.percentile(e,50):.0f}/{np.percentile(e,99):.0f} m")
for n,y in mappings(e): print(f"   {n:12s} {stats(y)}")
np.savez(SP+"diag5.npz",**{k.replace(' ','_'):v for k,v in out.items()})
