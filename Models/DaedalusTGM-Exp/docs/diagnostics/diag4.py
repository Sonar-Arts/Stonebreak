import torch, math
from terrain_slm.world.generator import _fbm
dev="cuda:1"
N=3072
ci=torch.arange(-N//2,N//2,device=dev).float().view(-1,1).expand(N,N); cj=ci.T
for seed in (0,1,2):
  continent=_fbm(ci,cj,900.,seed,1,octaves=4)+0.7*torch.exp(-(ci**2+cj**2)/(2*150.**2))
  land=((continent+0.35)/0.2).clamp(0,1); land=land*land*(3-2*land)
  belts=(1-_fbm(ci,cj,220.,seed,2,octaves=3).abs())**2
  f=_fbm(ci,cj,600.,seed,3,octaves=2); hills=_fbm(ci,cj,70.,seed,8,octaves=4)
  for name,mnt in (("old",(0.5+0.5*f).clamp(0,1)**1.5),("new",((f+0.05)/0.35).clamp(0,1).pow(1.0))):
    m=mnt*mnt*(3-2*mnt) if name=="new" else mnt
    trend=-300+600*land*(0.6+0.5*continent)+land*(2400*belts*m+200*hills*(0.3+m))
    L=trend>0
    fr=lambda t:(trend[L]>t).float().mean().item()
    print(seed,name,"land %.2f"%L.float().mean().item(),"max %4.0f"%trend.max().item()," >1000 %.3f >2000 %.3f >3000 %.3f"%(fr(1000),fr(2000),fr(3000)), "mnt-zone frac %.2f"%(m[L]>0.5).float().mean().item())
