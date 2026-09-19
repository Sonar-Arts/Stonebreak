"""Ice Archon reaction clips. Shared ready pose matches original attack endpoints."""
import math
from math import sin,cos,pi,sqrt,atan2,acos,degrees
NAMES=['root','torso','neck','head','arm_right','forearm_right','hand_right','leg_right','calf_right','foot_right','arm_left','forearm_left','hand_left','leg_left','calf_left','foot_left']
REST={n:(0.,0.,0.) for n in NAMES}
REST.update({'torso':(1,0,0),'arm_right':(12,-8,-8),'forearm_right':(45,0,0),'hand_right':(-55,0,-5),'arm_left':(20,12,10),'forearm_left':(35,0,0),'hand_left':(-20,0,0)})
REST['root_y']=0.
def pose(base=REST,**kw):
 p=dict(base);p.update(kw);return p
def interp(keys,t):
 if t<=keys[0][0]:return keys[0][1]
 if t>=keys[-1][0]:return keys[-1][1]
 j=next(i for i in range(len(keys)-1) if keys[i][0]<=t<=keys[i+1][0]);ds=[(keys[i+1][1]-keys[i][1])/(keys[i+1][0]-keys[i][0]) for i in range(len(keys)-1)]
 def tangent(i):
  if i==0 or i==len(keys)-1:return 0.
  a,b=ds[i-1],ds[i];return 0. if a*b<=0 else 2*a*b/(a+b)
 h=keys[j+1][0]-keys[j][0];u=(t-keys[j][0])/h
 return (2*u**3-3*u*u+1)*keys[j][1]+(u**3-2*u*u+u)*h*tangent(j)+(-2*u**3+3*u*u)*keys[j+1][1]+(u**3-u*u)*h*tangent(j+1)
S=pose(root_y=-.07,torso=(13,4,2),neck=(5,-2,0),head=(8,-3,-3),arm_right=(3,-10,-9),forearm_right=(35,0,0),hand_right=(-48,0,-5),arm_left=(9,15,14),forearm_left=(24,0,0))
D=pose(root=(-88,0,6),root_y=-.89,torso=(0,-4,0),neck=(0,0,0),head=(6,3,-3),arm_right=(-8,-5,-28),forearm_right=(20,0,0),hand_right=(33,0,-5),arm_left=(-12,8,32),forearm_left=(20,0,0),hand_left=(-8,0,0),leg_left=(-28,0,0),calf_left=(50,0,0),foot_left=(-22,0,0),leg_right=(-12,0,0),calf_right=(22,0,0),foot_right=(-10,0,0))
CLIPS={}
def add(n,d,beats=(),loop=False,start=REST,end=REST,cues=()):CLIPS[n]={'duration':d,'loop':loop,'beats':[(0,start)]+list(beats)+[(d,end)],'cues':list(cues)}
add('combat_idle',3.2,loop=True)
add('hurt',.76,[(.13,pose(root_y=-.035,torso=(-9,8,3),neck=(-4,0,0),head=(-9,-5,-2),arm_right=(5,-12,-14),forearm_right=(52,0,0),arm_left=(28,18,16))),(.28,pose(root_y=-.055,torso=(-3,5,2),head=(3,-3,0))),(.48,pose(root_y=-.025,torso=(5,-2,0),head=(3,2,0)))],cues=[.13])
add('stunned_enter',.66,[(.12,pose(root_y=-.025,torso=(-10,-6,-3),neck=(-4,0,0),head=(-10,4,2))),(.35,pose(S,root_y=-.085,torso=(17,7,3),head=(11,-5,-4)))],end=S)
add('stunned',2.8,loop=True,start=S,end=S)
add('stunned_exit',.9,[(.26,pose(S,head=(1,12,-2),neck=(0,0,0))),(.56,pose(root_y=-.025,torso=(4,-4,0),head=(-3,4,0),arm_right=(21,-8,-12),forearm_right=(53,0,0),hand_right=(-63,0,-5)))],start=S)
add('death',3.1,[(.18,pose(root_y=-.06,torso=(-12,-4,2),neck=(-7,0,0),head=(-13,3,-2),arm_left=(31,20,18))),(.64,pose(S,root_y=-.17,torso=(24,4,8),head=(17,0,7))),(.98,pose(D,root=(-19,0,3),root_y=-.22,torso=(6,-4,3))), (1.43,pose(D,root=(-62,0,5),root_y=-.66)),(1.82,D),(2.03,pose(D,torso=(2,-4,0),head=(8,3,-3))),(2.45,D)],end=D,cues=[1.82])
add('defeated',2.4,loop=True,start=D,end=D)

def evaluate(name,t):
 c=CLIPS[name];beats=c['beats'];p={}
 for n in NAMES:p[n]=tuple(interp([(tm,b[n][i]) for tm,b in beats],t) for i in range(3))
 ry=interp([(tm,b['root_y']) for tm,b in beats],t)
 if c['loop'] and name!='defeated':
  a=2*pi*t/c['duration'];b=sin(a)
  if name=='combat_idle':
   p['torso']=(1+1.1*b,.8*b,.35*b);p['head']=(-.6*b,2.1*b,-.3*b)
   p['arm_right']=(12+1.7*(1-cos(a)),-8,-8);p['forearm_right']=(45+1.2*(1-cos(a)),0,0);p['hand_right']=(-55-2*(1-cos(a)),0,-5)
   p['arm_left']=(20+2.8*(1-cos(a)),12,10);p['forearm_left']=(35+2*(1-cos(a)),0,0)
  else:
   ry+=.003*(1-cos(a));p['torso']=(13+1.1*b,4+1.5*b,2+.7*b);p['head']=(8+1.4*b,-3+3*b,-3-1.1*b);p['neck']=(5+.7*b,-2,0)
 if name not in ['death','defeated']:
  # Equal 0.49-block links with the original 0.015-block hip/ankle offset.
  l1=sqrt(.49*.49+.015*.015);l2=.49;dy=-.98-ry;dz=.015;dist=min(l1+l2-1e-8,sqrt(dy*dy+dz*dz));bend=acos(max(-1.,min(1.,(dist*dist-l1*l1-l2*l2)/(2*l1*l2))))
  h=degrees(atan2(-dz,-dy)+atan2(l2*sin(bend),l1+l2*cos(bend))-atan2(-.015,.49));k=degrees(-bend-atan2(.015,.49))
  # Remove the tiny alternate-IK-branch offset at the straight rest pose.
  corr=1-min(1,max(0,-ry/.01));d0=sqrt(.98*.98+.015*.015);b0=acos(max(-1,min(1,(d0*d0-l1*l1-l2*l2)/(2*l1*l2))));h0=degrees(atan2(-.015,.98)+atan2(l2*sin(b0),l1+l2*cos(b0))-atan2(-.015,.49));k0=degrees(-b0-atan2(.015,.49));h-=h0*corr;k-=k0*corr
  for side in ['left','right']:p['leg_'+side]=(h,0,0);p['calf_'+side]=(k,0,0);p['foot_'+side]=(-h-k,0,0)
 return {n:{'position':(0,ry,0) if n=='root' else (0,0,0),'rotation':p[n],'scale':(1,1,1)} for n in NAMES}

FLOOR_CORRECTION={'death': [-0.0, 0.001057987, 0.004010079, 0.008523467, 0.014265346, 0.020902909, 0.028103349, 0.03553386, 0.042861635, 0.049753868, 0.055877751, 0.060927743, 0.065550092, 0.070143463, 0.074705538, 0.079233998, 0.083726526, 0.088180803, 0.092594509, 0.096965328, 0.10129094, 0.105569028, 0.109797272, 0.113973355, 0.118094957, 0.122159761, 0.126165448, 0.1301097, 0.133990199, 0.137804625, 0.141550661, 0.145225988, 0.148828287, 0.152355241, 0.155804531, 0.159173838, 0.162460845, 0.165663232, 0.168778682, 0.171932207, 0.175623908, 0.179837014, 0.184464619, 0.189378626, 0.194440313, 0.199509806, 0.204454456, 0.2091561, 0.21351712, 0.217465272, 0.220957188, 0.22398054, 0.226554838, 0.228730899, 0.230589043, 0.232236123, 0.233801508, 0.235432162, 0.237286947, 0.239547847, 0.24286674, 0.247346328, 0.252771871, 0.258935235, 0.265930472, 0.273637563, 0.281530611, 0.28943866, 0.297203402, 0.304679917, 0.311737217, 0.318258602, 0.324141828, 0.329299077, 0.333656742, 0.337155036, 0.339747423, 0.341399908, 0.342090179, 0.341806634, 0.340547307, 0.338318721, 0.335134686, 0.331015062, 0.325984513, 0.32007127, 0.31330459, 0.305670157, 0.297176308, 0.287865021, 0.277792615, 0.267029716, 0.25566085, 0.243783698, 0.231508058, 0.218954573, 0.206253285, 0.19354206, 0.180964967, 0.16867064, 0.157421744, 0.147333983, 0.137892184, 0.129237301, 0.129490291, 0.135903874, 0.141061405, 0.144909599, 0.147389621, 0.148435029, 0.148482897, 0.148548342, 0.148652211, 0.14878338, 0.148931003, 0.149085019, 0.149236484, 0.149377748, 0.149502452, 0.149605362, 0.14968203, 0.149728288, 0.14974017, 0.149733505, 0.149717859, 0.14969385, 0.149662032, 0.149622928, 0.149577052, 0.149524928, 0.14946711, 0.149404195, 0.149336842, 0.149265774, 0.149191793, 0.14911578, 0.149038699, 0.148961599, 0.148885607, 0.148811924, 0.148741819, 0.148676615, 0.148617678, 0.148566397, 0.148524172, 0.148492385, 0.148472383, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446], 'defeated': [0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446, 0.148465446]}
raw_evaluate=evaluate
def evaluate(name,t):
 p=raw_evaluate(name,t)
 if name in FLOOR_CORRECTION:
  v=FLOOR_CORRECTION[name];a=max(0.,min(len(v)-1.,t/CLIPS[name]['duration']*(len(v)-1)));i=min(len(v)-2,int(a));u=a-i
  p['root']['position']=(0.,p['root']['position'][1]+v[i]*(1-u)+v[i+1]*u,0.)
 return p

# Run via Open Mason MCP with the Ice Archon project open.
import om
for name,spec in CLIPS.items():
 c=om.anim.clip(name,duration=spec['duration'],fps=60,loop=spec['loop'])
 c.layer(type='base',fade_in=.10,fade_out=.14)
 count=round(spec['duration']*60)
 times=sorted(set([round(spec['duration']*i/count,8) for i in range(count+1)]+[t for t,p in spec['beats']]+spec['cues']))
 for t in times:
  for part,p in evaluate(name,t).items():
   c.key(part,t,position=p['position'],rotation=p['rotation'],scale=p['scale'],easing='linear')
 c.save('/home/chaces/.local/share/OpenMason/Projects/SB_Archon/Ice_Archon_'+name+'_20260918.omanim')
 print('Saved',name,spec['duration'],'seconds;',len(times),'samples')
