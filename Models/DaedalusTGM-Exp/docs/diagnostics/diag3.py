import os, sys, numpy as np, torch, json
env={"TERRAIN_BRIDGE_WORLD_HEIGHT":"256","TERRAIN_BRIDGE_SEA_LEVEL":"64","TERRAIN_BRIDGE_OCEAN_METERS_PER_BLOCK":"48","TERRAIN_BRIDGE_LOWLAND_METERS_PER_BLOCK":"16","TERRAIN_BRIDGE_MIDLAND_METERS_PER_BLOCK":"40","TERRAIN_BRIDGE_HIGHLAND_METERS_PER_BLOCK":"96","TERRAIN_BRIDGE_SCALE":"1","TERRAIN_BRIDGE_DOWNSCALE":"2"}
os.environ.update(env); sys.path.insert(0,"../terrain-bridge")
from bridge.config import BridgeConfig; from bridge.height_mapping import HeightCurve
c=HeightCurve.from_config(BridgeConfig.from_env(seed=0))
print("lowland_top",c.lowland_top_m,"highland_base",c.highland_base_m)
E=np.array([0,100,300,600,1000,1500,2000,2500,3000,4000,4800],float)
print("elev->y", dict(zip(E.astype(int).tolist(), c.to_block_height(E).tolist())))
# real alps DEM: 2x2 mean -> blocks; measure block relief in 64x64-block windows and slope
meta=json.load(open("data/alps/meta.json")); dem=np.load("data/alps/dem.npy",mmap_mode="r")
print("dem shape",dem.shape)
H,W=dem.shape; rng=np.random.default_rng(0)
rel=[];slopes=[];maxy=[]
for _ in range(400):
    i=rng.integers(0,H-256);j=rng.integers(0,W-256)
    p=np.asarray(dem[i:i+256,j:j+256],dtype=np.float64)
    if p.mean()<1500: continue
    b=p.reshape(128,2,128,2).mean((1,3)); y=c.to_block_height(b).astype(float)
    rel.append(y.max()-y.min()); maxy.append(y.max())
    g=np.maximum(abs(np.diff(y,axis=0))[:, :-1],abs(np.diff(y,axis=1))[:-1]); slopes.append(np.percentile(g,90))
    realg=np.hypot(*np.gradient(p,30.0)); 
print(f"REAL Alps (mean>1500m) 128x128-block windows, n={len(rel)}: block relief median {np.median(rel):.0f}, max-y median {np.median(maxy):.0f}, p90 block step median {np.median(slopes):.1f}")
# what a 35deg real slope becomes
for band,mpb in (("lowland",16),("midland",40),("highland",96)):
    print(f"  35deg real slope in {band}: {np.tan(np.radians(35))*60/mpb:.2f} blocks rise per block")
