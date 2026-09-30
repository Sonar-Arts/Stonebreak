import numpy as np, torch, matplotlib; matplotlib.use("Agg"); import matplotlib.pyplot as plt
from terrain_slm.data import descriptors as D
SP="reports/diagnostics/"; __import__("os").makedirs(SP, exist_ok=True)  # run from Models/DaedalusTGM-Exp
z=np.load(SP+"diag5.npz")
def bands(e):
    t=torch.from_numpy(e)[None,None].float(); out=[]; prev=t
    for s in (1,2,4,8,16):
        b=D.blur(t,s); out.append(float((prev-b).std())); prev=b
    return out
print("band RMS (m) at block-res wavelengths ~2-4,4-8,8-16,16-32,32-64 blocks (120 m .. 3.8 km)")
for k in z.files: print(f"  {k:15s}", [round(x,1) for x in bands(z[k])])
fig,ax=plt.subplots(1,3,figsize=(21,7.4))
for a,k in zip(ax,["old_controls","fixed_controls","real_alps"]):
    e=z[k]; ls=matplotlib.colors.LightSource(315,40)
    a.imshow(ls.shade(e,cmap=plt.cm.terrain,vert_exag=60/60*1.0,blend_mode="overlay",vmin=0,vmax=3500)); a.set_title(f"{k.replace('_',' ')}  (p99 {np.percentile(e,99):.0f} m)",fontsize=16); a.axis("off")
plt.tight_layout(); plt.savefig(SP+"mountains_compare.png",dpi=80)
