"""Prototype: the original's PickZombieWaves(), reimplemented to see what faithful tables look like."""
import random, re
GZ=[4,6,8,10,8,10,20,10,20,20, 10,20,10,20,10,10,20,10,20,20,
    10,20,20,30,20,20,30,20,30,30, 10,20,10,20,20,10,20,10,20,20]
# (name, value, startingLevel, firstAllowedWave, pickWeight)
DEFS=[("basic",1,1,1,4000),("flag",1,1,1,0),("conehead",2,3,1,4000),("pole_vaulter",2,6,5,2000),
 ("buckethead",4,8,1,3000),("newspaper",2,11,1,1000),("door",4,13,5,3500),("football",7,16,5,2000),
 ("dancing",5,18,5,1000),("backup_dancer",1,18,1,0),("ducky_tube",1,21,5,0),("snorkel",3,23,10,2000),
 ("zamboni",7,26,10,2000),("bobsled",3,26,10,2000),("dolphin_rider",3,28,10,1500),
 ("jack_in_the_box",3,31,10,1000),("balloon",2,33,10,2000),("miner",4,36,10,1000),("pogo",4,38,10,1000),
 ("yeti",4,40,1,1),("bungee",3,41,10,1000),("ladder",4,43,10,1000),("catapult",5,46,10,1500),
 ("gargantuar",10,48,15,1500),("imp",10,48,1,0),("boss",10,50,1,0)]
VALUE={n:v for n,v,_,_,_ in DEFS}
def allowed_on(level,name):
    """gZombieAllowedLevels, hard-coded for the types this prototype needs (levels 21..50)."""
    tab={
     "basic":range(1,51),"flag":range(1,51),"conehead":[l for l in range(1,51) if not (l==11)],
     "pole_vaulter":[6,7,9,10,14,15,24,29,42],
     "buckethead":[8,9,10,11,12,14,15,17,18,20,22,24,25,27,29,30,37,39,40,45,47,48,49,50],
     "newspaper":[11,12,15,17,18,19],
     "door":[13,14,17,18,20,21,22],
     "football":[16,17,20,22,25,32,44],
     "dancing":[18,19,20],
     "backup_dancer":[18,19,20],
     "ducky_tube":[],
     "snorkel":[23,27,30],
     "zamboni":[26,27,29,30],
     "bobsled":[26,27,29,30],
     "dolphin_rider":[28,29,30,34],
     "jack_in_the_box":[31,32,37,50],
     "balloon":[33,34,39,50],
     "miner":[36,37,50],
     "pogo":[38,39,50],
     "bungee":[41,42,43,44,45,46,47,48,49,50],
     "ladder":[43,44,45,46,47,48,49,50],
     "catapult":[46,47,48,49,50],
     "gargantuar":[48,49,50],
     "imp":[48,49,50],
     "yeti":[40],
    }
    if name=="bobsled": return True   # handled separately (ice rows only)
    return level in tab.get(name,[])
def can_spawn(level,name):
    d=[x for x in DEFS if x[0]==name][0]
    if name=="yeti": return False
    if level < d[2] or d[4]==0: return False
    return allowed_on(level,name)
def intro_for(level):
    if level==1: return None
    for n,v,sl,faw,w in DEFS:
        if sl==level: return n
    return None
def gen(level, seed=20240613):
    rng=random.Random(seed*1000+level)
    n=GZ[level-1]; perflag=10 if n>=10 else n
    intro=intro_for(level)
    pool=[x for x in DEFS if can_spawn(level,x[0])]
    out=[]
    for w in range(n):
        pts=w//3+1
        flag=(w%perflag==perflag-1) and not (level==1)
        final=(w==n-1)
        wave=[]
        def put(name):
            nonlocal pts
            wave.append(name); pts-=VALUE[name]
        if flag:
            plain=min(pts,8); pts=int(pts*2.5)
            for _ in range(plain): put("basic")
            put("flag")
        if intro and intro!="ducky_tube":
            if intro in ("miner","balloon"):
                if w+1==7 or final: put(intro)
            elif w==n//2 or final:
                put(intro)
        if final:
            have=set(wave)
            for name,_,_,_,_ in pool:
                if name not in have: put(name)
        guard=0
        while pts>0 and guard<200:
            guard+=1
            cand=[(x[0],x[4]) for x in pool if x[3]<=w+1 and x[1]<=pts]
            if not cand: break
            tot=sum(c[1] for c in cand); r=rng.randrange(tot)
            for nm,wt in cand:
                r-=wt
                if r<0: put(nm); break
        out.append((flag,final,wave,pts))
    return out
if __name__=="__main__":
    for lvl in (26,27,28,29,30,31,32,33,34,35,36,37,38,39,40):
        ws=gen(lvl)
        tot=sum(len(w[2]) for w in ws)
        from collections import Counter
        c=Counter(z for w in ws for z in w[2])
        print("L%d (%d-%d) waves=%d zombies=%d flags=%s" % (lvl,(lvl-1)//10+1,(lvl-1)%10+1,len(ws),tot,
              [i+1 for i,w in enumerate(ws) if w[0]]))
        print("   ", dict(c))
