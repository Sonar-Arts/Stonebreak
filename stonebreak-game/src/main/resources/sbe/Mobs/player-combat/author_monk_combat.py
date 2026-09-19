"""Player Monk combat poses, authored facing -Z. Angles in degrees, distances in blocks."""
import math
from math import sin,cos,pi,sqrt,atan2,acos,degrees
NAMES=['root','torso','head','arm_left','arm_right','forearm_left','forearm_right','hand_left','hand_right','thumb_left','thumb_right','leg_left','leg_right','calf_left','calf_right','foot_left','foot_right']
NAMES += ['finger_'+f+s+'_'+side for side in ['left','right'] for f in ['index','middle','ring','little'] for s in ['', '_tip']]
G={'root_y':-.045,'left_z':-.10,'right_z':.10,'spread':.025,'grip_left':1.,'grip_right':1.,'torso':(3,-5,0),'head':(-3,5,0),'arm_left':(42,-5,10),'forearm_left':(111,0,-22),'hand_left':(-22,0,-6),'arm_right':(32,7,-12),'forearm_right':(119,0,22),'hand_right':(-22,0,6)}
C=dict(G,torso=(7,0,0),head=(-4,0,0),root_y=-.065,arm_left=(53,-12,8),arm_right=(53,12,-8),forearm_left=(112,0,-28),forearm_right=(112,0,28),hand_left=(-24,0,-12),hand_right=(-24,0,12))
M=dict(G,root_y=-.11,left_z=0.,right_z=0.,spread=.055,torso=(0,0,0),head=(8,0,0),arm_left=(32,0,0),arm_right=(32,0,0),forearm_left=(115,0,-50),forearm_right=(115,0,50),hand_left=(35,0,48),hand_right=(35,0,-48),grip_left=0.,grip_right=0.)
NEUTRAL={k:(0.,0.,0.) if isinstance(v,tuple) else 0. for k,v in G.items()}
def pose(base=G,**kw):
 d=dict(base);d.update(kw);return d

def interp(keys,t):
 if t<=keys[0][0]:return keys[0][1]
 if t>=keys[-1][0]:return keys[-1][1]
 j=next(i for i in range(len(keys)-1) if keys[i][0]<=t<=keys[i+1][0])
 ds=[(keys[i+1][1]-keys[i][1])/(keys[i+1][0]-keys[i][0]) for i in range(len(keys)-1)]
 def tangent(i):
  if i==0 or i==len(keys)-1:return 0.
  a,b=ds[i-1],ds[i];return 0. if a*b<=0 else 2*a*b/(a+b)
 h=keys[j+1][0]-keys[j][0];u=(t-keys[j][0])/h
 return (2*u**3-3*u*u+1)*keys[j][1]+(u**3-2*u*u+u)*h*tangent(j)+(-2*u**3+3*u*u)*keys[j+1][1]+(u**3-u*u)*h*tangent(j+1)
CLIPS={}
def add(name,duration,beats,impacts=(),loop=False,start=G,end=G,note=''):
 CLIPS[name]={'duration':duration,'loop':loop,'impacts':list(impacts),'beats':[(0,start)]+beats+[(duration,end)],'note':note}

def jab(side='left',twist=0):
 other='right' if side=='left' else 'left'
 twist=abs(twist)*(1 if side=='left' else -1)
 return pose(torso=(7,twist,0),head=(-5,-twist*.65,0),root_y=-.06,**{'arm_'+side:(86,0,-8 if side=='left' else 8),'forearm_'+side:(6,0,0),'hand_'+side:(-1,0,0),'arm_'+other:(36,0,12 if other=='left' else -12)})
def wind(side='left'):
 return pose(torso=(0,16 if side=='left' else -18,0),head=(0,-10 if side=='left' else 12,0),root_y=-.06,**{'arm_'+side:(25,10 if side=='left' else -10,14 if side=='left' else -14),'forearm_'+side:(123,0,0)})
def hook(side='left'):
 return pose(torso=(7,15 if side=='left' else -15,0),head=(-4,-10 if side=='left' else 10,0),root_y=-.07,**{'arm_'+side:(75,25 if side=='left' else -25,30 if side=='left' else -30),'forearm_'+side:(30,0,-65 if side=='left' else 65),'hand_'+side:(-10,0,0)})
add('combat_enter',.72,[(.25,pose(NEUTRAL,root_y=-.055)),(.52,pose(root_y=-.06))],start=NEUTRAL)
add('combat_exit',.76,[(.28,pose(root_y=-.025,grip_left=.4,grip_right=.4))],end=NEUTRAL)
add('combat_idle',3.2,[],loop=True)
add('combat_dash',.64,[],loop=True,note='In-place; stage owns world movement. Use only while moving.')
add('strike',.96,[(.18,wind()),(.29,pose(wind(),arm_left=(61,0,0),forearm_left=(55,0,-8))),(.38,jab('left',-9)),(.44,pose(jab('left',-11),arm_left=(88,0,-8))),(.66,pose(torso=(3,-2,0),arm_left=(49,0,8)))],[.38])
add('flurry',2.08,[(.18,wind()),(.43,jab('left',-10)),(.58,wind('right')),(.95,jab('right',14)),(1.13,wind()),(1.47,hook()),(1.59,pose(hook(),torso=(8,19,0))),(1.85,G)],[.43,.95,1.47],note='Three hits: lead jab, rear cross, lead hook.')
add('stunning_strike',1.38,[(.24,pose(wind('right'),grip_right=.35)),(.44,pose(wind('right'),grip_right=0.,hand_right=(-38,0,0))),(.62,pose(jab('right',12),grip_right=0.,hand_right=(85,-90,0))),(.73,pose(jab('right',15),grip_right=0.,hand_right=(85,-90,0))),(.97,pose(arm_right=(48,7,-12),grip_right=.1))],[.62],note='Open-palm strike. Hold the contact beat for optional cinematic slow motion.')
# Kick uses an explicit lifted leg; the other leg remains IK planted.
K=pose(root_y=-.06,torso=(-6,-8,0),head=(4,8,0),leg_right=(89,0,0),calf_right=(-8,0,0),foot_right=(-6,0,0),arm_left=(32,0,16),arm_right=(23,0,-22))
add('kick',1.42,[(.24,pose(root_y=-.08,leg_right=(66,0,0),calf_right=(-108,0,0),foot_right=(15,0,0))),(.46,pose(K,calf_right=(-52,0,0))),(.60,K),(.69,pose(K,leg_right=(93,0,0))),(.94,pose(root_y=-.065,leg_right=(57,0,0),calf_right=(-96,0,0),foot_right=(12,0,0))),(1.18,G)],[.60])
add('guard_enter',.32,[(.17,pose(C,root_y=-.075))],end=C)
add('guard',2.4,[],loop=True,start=C,end=C)
add('guard_exit',.38,[],start=C)
add('parry',.78,[(.08,pose(C,torso=(4,12,0))),(.20,pose(C,torso=(4,-12,0),head=(-3,8,0),arm_left=(79,-32,30),forearm_left=(57,0,0),hand_left=(-18,0,-18),grip_left=.85)),(.30,pose(C,torso=(6,-17,0),arm_left=(76,-35,33),forearm_left=(68,0,0),grip_left=.90)),(.53,C)],[.20],start=C,end=C,note='Deflect, then return to Guard. Runtime selects reaction when parry succeeds.')
add('block',.58,[(.12,pose(C,torso=(-5,0,1),head=(8,0,0),root_y=-.09)),(.25,pose(C,torso=(8,0,0),root_y=-.08))],start=C,end=C)
add('meditate_enter',.84,[(.35,pose(M,head=(3,0,0),arm_left=(26,-8,5),arm_right=(26,8,-5)))],end=M)
add('meditate_loop',3.2,[],loop=True,start=M,end=M)
add('meditate_exit',.8,[(.4,pose(root_y=-.08,grip_left=.5,grip_right=.5))],start=M)
add('meditate',3.6,[(.84,M),(1.45,pose(M,root_y=-.106,head=(6,0,0))),(2.15,pose(M,root_y=-.11)),(2.76,M)],impacts=[1.8],note='Complete standing meditation; heal cue at 1.8 s. Enter/loop/exit also supplied.')
add('swift_step',1.32,[(.18,pose(root_y=-.07,left_z=-.15,right_z=.11)),(.38,pose(root_y=-.035,left_z=-.18,left_up=.10,right_z=.10,torso=(1,-12,-3))),(.62,pose(root_y=-.055,left_z=-.10,right_z=.10)),(.81,pose(root_y=-.025,right_up=.09,right_z=.18,torso=(1,8,3))),(1.06,pose(root_y=-.065))],[.62])
add('martial_surge',1.58,[(.30,pose(root_y=-.115,torso=(8,0,0),arm_left=(18,-5,24),arm_right=(18,5,-24),forearm_left=(95,0,0),forearm_right=(95,0,0))),(.63,pose(root_y=-.115,head=(-6,0,0),arm_left=(25,-5,22),arm_right=(25,5,-22),forearm_left=(90,0,0),forearm_right=(90,0,0))),(.86,pose(root_y=-.035,torso=(-3,0,0),head=(-4,0,0),arm_left=(64,-10,28),arm_right=(64,10,-28),forearm_left=(105,0,0),forearm_right=(105,0,0))),(1.02,pose(root_y=-.045,arm_left=(56,-8,22),arm_right=(56,8,-22)))],[.86])
combo=[(.24,wind()),(.60,jab('left',-10)),(.80,wind('right')),(1.17,jab('right',15)),(1.37,wind()),(1.74,hook()),(1.95,wind('right')),(2.31,hook('right')),(2.50,pose(root_y=-.08,leg_right=(70,0,0),calf_right=(-108,0,0),foot_right=(10,0,0))),(2.88,K),(3.12,pose(root_y=-.10,leg_right=(54,0,0),calf_right=(-95,0,0))),(3.42,pose(wind('right'),root_y=-.10,grip_right=0.)),(3.80,pose(jab('right',18),grip_right=0.,hand_right=(85,-90,0))),(4.02,pose(jab('right',20),grip_right=0.,hand_right=(85,-90,0))),(4.40,pose(root_y=-.07))]
add('focus_combo',4.9,combo,[.60,1.17,1.74,2.31,2.88,3.80],note='Six contacts: jab, cross, two hooks, front kick, palm finisher. Freeze/retime before each contact for input; do not restart clip per prompt.')
add('hurt',.72,[(.13,pose(root_y=-.075,torso=(-12,5,3),head=(-13,-4,-2),arm_left=(23,0,20),arm_right=(19,0,-20))),(.27,pose(root_y=-.095,torso=(-6,3,1),head=(4,-2,0))),(.49,pose(root_y=-.065,torso=(9,0,0),head=(6,0,0)))])
S=pose(root_y=-.10,torso=(12,0,3),head=(16,0,-4),arm_left=(14,0,12),arm_right=(10,0,-12),forearm_left=(43,0,0),forearm_right=(36,0,0),grip_left=.35,grip_right=.35)
add('stunned_enter',.40,[(.13,pose(S,torso=(-7,0,-3),head=(-9,0,3)))],end=S)
add('stunned',2.4,[],loop=True,start=S,end=S)
add('stunned_exit',.68,[(.25,pose(S,head=(4,8,-2)))],start=S)
V=pose(root_y=-.018,left_z=-.05,right_z=.05,torso=(0,0,0),head=(-4,0,0),arm_left=(22,0,15),forearm_left=(62,0,0),hand_left=(0,0,0),arm_right=(154,0,-15),forearm_right=(23,0,0),hand_right=(-5,0,0),grip_left=.3)
add('victory',3.2,[(.42,pose(root_y=-.075,head=(8,0,0))),(.95,pose(M,root_y=-.04,head=(12,0,0))),(1.48,pose(M,root_y=-.025,head=(2,0,0))),(2.05,V),(2.42,pose(V,head=(-7,-8,0)))],end=V)
add('victory_loop',2.8,[],loop=True,start=V,end=V)
add('victory_exit',.9,[],start=V)
D=pose(root=(0,0,84),root_y=-.70,torso=(12,0,-7),head=(9,0,-8),arm_left=(15,0,-28),forearm_left=(48,0,0),hand_left=(0,0,0),arm_right=(35,0,-14),forearm_right=(55,0,0),hand_right=(0,0,0),leg_left=(36,0,0),calf_left=(-57,0,0),foot_left=(12,0,0),leg_right=(17,0,0),calf_right=(-42,0,0),foot_right=(12,0,0),grip_left=.12,grip_right=.10)
add('defeat',2.6,[(.16,pose(S,torso=(-10,0,4),head=(-12,0,0))),(.53,pose(S,root_y=-.18,torso=(22,0,8),head=(19,0,3))),(.92,pose(D,root=(0,0,23),root_y=-.37,torso=(24,0,12))),(1.39,pose(D,root=(0,0,67),root_y=-.64)),(1.66,D),(1.84,pose(D,torso=(14,0,-8),head=(11,0,-8))),(2.16,D)],end=D,note='Sideways collapse, settles on the floor; follow with defeated loop. No root X/Z travel.')
add('defeated',3.2,[],loop=True,start=D,end=D)

def params(name,t):
 c=CLIPS[name];bs=c['beats'];keys=set().union(*(p.keys() for _,p in bs));out={}
 for k in keys:
  v=next(p[k] for _,p in bs if k in p)
  default=G.get(k,(0.,0.,0.) if isinstance(v,tuple) else 0.)
  if isinstance(v,tuple):out[k]=tuple(interp([(tm,p.get(k,default)[i]) for tm,p in bs],t) for i in range(3))
  else:out[k]=interp([(tm,p.get(k,default)) for tm,p in bs],t)
 if c['loop']:
  a=2*pi*t/c['duration'];b=sin(a)
  if name=='combat_dash':
   out.update(root_y=-.075+.018*cos(2*a),left_z=.18*cos(a),right_z=-.18*cos(a),left_up=.13*max(0,sin(a))**2,right_up=.13*max(0,-sin(a))**2,torso=(12,5*b,2*b),head=(-8,-4*b,-b),arm_left=(42-13*b,-5,10),arm_right=(32+13*b,7,-12))
  else:
   out['root_y']+=.0025*(1-cos(a));r=out.get('torso',(0,0,0));out['torso']=(r[0]+.65*b,r[1]+.65*b,r[2]+.35*b)
   r=out.get('head',(0,0,0));out['head']=(r[0]-.6*b,r[1]+(3 if name=='stunned' else 1.1)*b,r[2]-.4*b)
 return out

def evaluate(name,t):
 p=params(name,t);out={n:{'position':(0.,1.5,0.) if n=='head' else (0.,0.,0.),'rotation':p.get(n,(0.,0.,0.)),'scale':(1.,1.,1.)} for n in NAMES}
 ry=p['root_y'];out['root']['position']=(0.,ry,0.)
 for side,sign in [('left',1),('right',-1)]:
  z=p[side+'_z'];up=p.get(side+'_up',0.)
  dy=-.825-ry+up;dz=.008+z
  l1=sqrt(.435**2+.006**2);l2=sqrt(.39**2+.002**2);dist=min(l1+l2-1e-7,sqrt(dy*dy+dz*dz))
  bend=acos(max(-1.,min(1.,(dist*dist-l1*l1-l2*l2)/(2*l1*l2))))
  a=atan2(-dz,-dy)+atan2(l2*sin(bend),l1+l2*cos(bend))
  rest1=atan2(-.006,.435);rest2=atan2(-.002,.39)
  h=degrees(a-rest1);k=degrees(-bend-(rest2-rest1))
  rest_correction=1.-min(1.,max(0.,-ry/.01));h-=0.46931695636696535*rest_correction;k-=-0.9928291355576453*rest_correction
  # Explicit attack-leg rotations blend in/out from the solved supporting pose.
  # Missing rotation in an author beat means the IK angle at that beat.
  for joint,default in [('leg',h),('calf',k),('foot',-h-k)]:
   n=joint+'_'+side
   if any(n in b for _,b in CLIPS[name]['beats']):
    keys=[]
    for tm,b in CLIPS[name]['beats']:
     yy=b.get('root_y',G['root_y']);zz=b.get(side+'_z',G[side+'_z']);uu=b.get(side+'_up',0.)
     ddy=-.825-yy+uu;ddz=.008+zz;d=min(l1+l2-1e-7,sqrt(ddy*ddy+ddz*ddz));bb=acos(max(-1.,min(1.,(d*d-l1*l1-l2*l2)/(2*l1*l2))));aa=atan2(-ddz,-ddy)+atan2(l2*sin(bb),l1+l2*cos(bb));hh=degrees(aa-rest1);kk=degrees(-bb-(rest2-rest1));ik={'leg':hh,'calf':kk,'foot':-hh-kk}[joint]
     val=b[n][0]-ik if n in b else 0.
     keys.append((tm,val))
    default+=interp(keys,t)
   out[n]['rotation']=(default,0.,0.)
  out['leg_'+side]['position']=(sign*p['spread'],0.,0.)
  grip=p['grip_'+side]
  out['thumb_'+side]['rotation']=(-48*grip,0.,-sign*32*grip)
  for i,f in enumerate(['index','middle','ring','little']):
   out['finger_'+f+'_'+side]['rotation']=(0.,0.,-sign*(66+i*2)*grip)
   out['finger_'+f+'_tip_'+side]['rotation']=(0.,0.,-sign*(80+i*3)*grip)
 return out

FLOOR_CORRECTION={'defeat': [-0.0, -0.0, -0.0, -0.0, -0.0, -0.0, -0.0, -0.0, -0.0, -0.0, -0.0, -0.0, -0.0, -0.0, -0.0, -0.0, -0.0, -0.0, -0.0, -0.0, -0.0, -0.0, -0.0, -0.0, -0.0, -0.0, -0.0, -0.0, -0.0, -0.0, -0.0, -0.0, 6.686e-05, 0.00236694, 0.00779932, 0.01612915, 0.02708191, 0.04035218, 0.05561151, 0.07251561, 0.09071137, 0.10984358, 0.12956173, 0.14952646, 0.16941585, 0.1889425, 0.20783291, 0.22584587, 0.24277875, 0.25846545, 0.2727763, 0.2856163, 0.29692166, 0.3066548, 0.31479766, 0.32138899, 0.32671942, 0.33136553, 0.33529719, 0.33846937, 0.34088068, 0.3425241, 0.34331213, 0.34322424, 0.34224721, 0.34047422, 0.33781919, 0.33427911, 0.32986856, 0.32465283, 0.31867602, 0.31190778, 0.30438614, 0.29615434, 0.28728813, 0.2778724, 0.26790057, 0.2574346, 0.24654101, 0.23529069, 0.22375869, 0.21202373, 0.20021819, 0.18837033, 0.17632699, 0.16324519, 0.14921818, 0.13445878, 0.11919338, 0.10366254, 0.08818765, 0.07299465, 0.05832783, 0.04472159, 0.03224001, 0.02110308, 0.01171608, 0.01299719, 0.01769772, 0.0202535, 0.02066061, 0.02041095, 0.01993481, 0.01928847, 0.01852826, 0.01771065, 0.01689229, 0.01613007, 0.015481, 0.01500226, 0.01475104, 0.0147442, 0.01484464, 0.01502611, 0.01527844, 0.01559145, 0.01595496, 0.01635879, 0.01679278, 0.01724677, 0.01771065, 0.01817428, 0.01862759, 0.01906049, 0.01946294, 0.01982489, 0.02013633, 0.02038724, 0.02056761, 0.02066741, 0.02068444, 0.02068444, 0.02068444, 0.02068444, 0.02068444, 0.02068444, 0.02068444, 0.02068444, 0.02068444, 0.02068444, 0.02068444, 0.02068444, 0.02068444, 0.02068444, 0.02068444, 0.02068444, 0.02068444, 0.02068444, 0.02068444, 0.02068444, 0.02068444, 0.02068444, 0.02068444, 0.02068444, 0.02068444, 0.02068444, 0.02068444], 'defeated': [0.02068444, 0.02089264, 0.02109791, 0.02130001, 0.02149873, 0.02169387, 0.02188521, 0.02207255, 0.02225568, 0.02243442, 0.02260857, 0.02277795, 0.02294237, 0.02310167, 0.02325567, 0.02340421, 0.02354713, 0.02368429, 0.02381553, 0.02394072, 0.02405973, 0.02417242, 0.02427869, 0.02437841, 0.02447149, 0.02455783, 0.02463733, 0.02470992, 0.02477552, 0.02483405, 0.02488547, 0.02492971, 0.02496673, 0.02499649, 0.02501897, 0.02503413, 0.02504197, 0.02504248, 0.02503566, 0.02502152, 0.02500007, 0.02497134, 0.02493535, 0.02489216, 0.02484181, 0.02478434, 0.02471983, 0.02464834, 0.02456996, 0.02448475, 0.02439282, 0.02429426, 0.02418918, 0.02407769, 0.0239599, 0.02383595, 0.02370597, 0.02357009, 0.02342846, 0.02328123, 0.02312855, 0.0229706, 0.02280752, 0.02263951, 0.02246673, 0.02228937, 0.02210762, 0.02192166, 0.02173171, 0.02153796, 0.02134061, 0.02113987, 0.02093596, 0.02072909, 0.02051949, 0.02030737, 0.02009296, 0.01987649, 0.01965818, 0.01943828, 0.01921701, 0.0189946, 0.01877131, 0.01854735, 0.01832297, 0.01809842, 0.01787392, 0.01764972, 0.01742605, 0.01720316, 0.01698128, 0.01676065, 0.01654151, 0.01632409, 0.01610861, 0.01589532, 0.01568444, 0.01547619, 0.01527081, 0.0150685, 0.01486949, 0.01467399, 0.01448221, 0.01429435, 0.01411062, 0.01393122, 0.01375634, 0.01358616, 0.01342087, 0.01326066, 0.01310568, 0.01295612, 0.01281212, 0.01267386, 0.01254147, 0.0124151, 0.01229489, 0.01218181, 0.01207546, 0.01197561, 0.01188236, 0.01179582, 0.01171608, 0.01164323, 0.01157735, 0.01151851, 0.01146679, 0.01142222, 0.01138487, 0.01135478, 0.01133197, 0.01131648, 0.01130832, 0.01130751, 0.01131405, 0.01132792, 0.01134912, 0.01137763, 0.01141341, 0.01145643, 0.01150664, 0.01156399, 0.01162842, 0.01169986, 0.01177823, 0.01186346, 0.01195544, 0.01205408, 0.01215927, 0.01227091, 0.01238886, 0.01251301, 0.01264322, 0.01277934, 0.01292124, 0.01306876, 0.01322174, 0.01338001, 0.01354341, 0.01371176, 0.01388487, 0.01406256, 0.01424464, 0.0144309, 0.01462116, 0.0148152, 0.01501281, 0.01521378, 0.01541789, 0.01562493, 0.01583466, 0.01604686, 0.01626166, 0.01647933, 0.01669882, 0.01691987, 0.01714225, 0.01736572, 0.01759005, 0.01781498, 0.01804027, 0.01826569, 0.01849099, 0.01871592, 0.01894025, 0.01916374, 0.01938613, 0.01960721, 0.01982671, 0.02004442, 0.0202601, 0.02047352, 0.02068444]}
raw_evaluate=evaluate
def evaluate(name,t):
 p=raw_evaluate(name,t)
 if name in FLOOR_CORRECTION:
  v=FLOOR_CORRECTION[name];at=max(0.,min(len(v)-1.,t/CLIPS[name]['duration']*(len(v)-1)));i=min(len(v)-2,int(at));u=at-i;dy=v[i]*(1-u)+v[i+1]*u
  p['root']['position']=(0.,p['root']['position'][1]+dy,0.)
 return p

# Run in the Player project using Open Mason run_python_script.
import om
SELECTED=list(CLIPS)

for name in SELECTED:
 spec=CLIPS[name]
 clip=om.anim.clip(name,duration=spec['duration'],fps=60,loop=spec['loop'])
 clip.layer(type='base',fade_in=.12,fade_out=.14)
 count=round(spec['duration']*60)
 # Include authored impact and pose beats exactly, in addition to the 60-Hz samples.
 times=sorted(set([round(spec['duration']*i/count,8) for i in range(count+1)]+[t for t,p in spec['beats']]+spec['impacts']))
 for t in times:
  values=evaluate(name,t)
  for part in NAMES:
   clip.key(part,t,position=values[part]['position'],rotation=values[part]['rotation'],scale=(1,1,1),easing='linear')
 suffix='-20260918-v2.omanim' if name in ['combat_enter','combat_exit'] else '-20260918.omanim'
 clip.save('/home/chaces/.local/share/OpenMason/Projects/Player/Animations/Monk-'+name+suffix)
 print('Saved',name,spec['duration'],'s',len(times),'samples',len(NAMES),'tracks')
