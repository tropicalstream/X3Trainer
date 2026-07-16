#!/usr/bin/env python3
"""Render every X3Trainer exercise animation to contact-sheet PNGs.

Parses Exercises.kt directly (single source of truth) and replicates
Rig.kt's forward kinematics exactly (android.opengl.Matrix semantics:
column-major, rotateM/translateM post-multiply, RH rotations).
Pure stdlib: hand-rolled mat4 + Bresenham + zlib PNG writer.
"""
import math, re, sys, zlib, struct, os

KT = str(__import__("pathlib").Path(__file__).resolve().parent.parent / "app/src/main/java/com/x3trainer/workout/Exercises.kt")
OUT = str(__import__("pathlib").Path(__file__).resolve().parent / "poses")

# ---------------------------------------------------------------- channels
HOP,BPITCH,BYAW,BROLL,SPITCH,SYAW,SROLL,HPITCH = range(8)
HIPPL,HIPPR,HABDL,HABDR,KNEEL,KNEER,ANKL,ANKR = range(8,16)
SHPL,SHPR,SHABDL,SHABDR,ELBL,ELBR = range(16,22)
N = 22

# ---------------------------------------------------------------- parser
def parse_kt():
    src = open(KT).read()
    start = src.index("arrayOf(")
    body = src[start:]
    exercises = []
    i = 0
    while True:
        m = re.compile(r'Exercise\(\s*"([a-z_0-9]+)",\s*"([^"]+)",\s*([0-9.]+)f,\s*(-?[0-9.]+)f,\s*Db\.(\w+),\s*t\(([^)]*)\)').search(body, i)
        if not m: break
        key, name, cyc, yaw, db, times_s = m.groups()
        times = [float(x.strip().rstrip('f')) for x in times_s.split(',') if x.strip()]
        # capture to the matching close paren of Exercise(
        depth = 1; j = m.end()
        while depth > 0:
            if body[j] == '(': depth += 1
            elif body[j] == ')': depth -= 1
            j += 1
        block = body[m.end():j]
        keys = []
        for pm in re.finditer(r'pose\s*\{([^}]*)\}', block):
            keys.append(parse_pose(pm.group(1), key))
        if len(keys) != len(times):
            print(f"WARN {key}: {len(keys)} poses vs {len(times)} times")
        exercises.append(dict(key=key, name=name, cyc=float(cyc), yaw=float(yaw), db=db, times=times, keys=keys))
        i = j
    return exercises

SIGS = {
    'body':  ['pitch','yaw','roll'], 'spine': ['pitch','yaw','roll'],
    'legL':  ['hip','abd','knee','ankle'], 'legR': ['hip','abd','knee','ankle'],
    'legs':  ['hip','abd','knee','ankle'],
    'armL':  ['pitch','abd','elbow'], 'armR': ['pitch','abd','elbow'],
    'arms':  ['pitch','abd','elbow'],
    'head':  ['pitch'], 'hop': ['v'],
}
MAP = {
    ('body','pitch'):[BPITCH], ('body','yaw'):[BYAW], ('body','roll'):[BROLL],
    ('spine','pitch'):[SPITCH], ('spine','yaw'):[SYAW], ('spine','roll'):[SROLL],
    ('head','pitch'):[HPITCH], ('hop','v'):[HOP],
    ('legL','hip'):[HIPPL], ('legL','abd'):[HABDL], ('legL','knee'):[KNEEL], ('legL','ankle'):[ANKL],
    ('legR','hip'):[HIPPR], ('legR','abd'):[HABDR], ('legR','knee'):[KNEER], ('legR','ankle'):[ANKR],
    ('legs','hip'):[HIPPL,HIPPR], ('legs','abd'):[HABDL,HABDR], ('legs','knee'):[KNEEL,KNEER], ('legs','ankle'):[ANKL,ANKR],
    ('armL','pitch'):[SHPL], ('armL','abd'):[SHABDL], ('armL','elbow'):[ELBL],
    ('armR','pitch'):[SHPR], ('armR','abd'):[SHABDR], ('armR','elbow'):[ELBR],
    ('arms','pitch'):[SHPL,SHPR], ('arms','abd'):[SHABDL,SHABDR], ('arms','elbow'):[ELBL,ELBR],
}
def parse_pose(text, exkey):
    ch = [0.0]*N
    for cm in re.finditer(r'(\w+)\(([^)]*)\)', text):
        fn, args = cm.group(1), cm.group(2)
        if fn not in SIGS:
            print(f"WARN {exkey}: unknown call {fn}"); continue
        sig = SIGS[fn]
        for k, a in enumerate(x.strip() for x in args.split(',') if x.strip()):
            if '=' in a:
                pname, val = [s.strip() for s in a.split('=')]
            else:
                pname, val = sig[k], a
            for idx in MAP[(fn, pname)]:
                ch[idx] = float(val.rstrip('f'))
    return ch

# ---------------------------------------------------------------- mat4 (android.opengl.Matrix semantics)
def ident(): return [[1,0,0,0],[0,1,0,0],[0,0,1,0],[0,0,0,1]]
def matmul(A,B):
    return [[sum(A[r][k]*B[k][c] for k in range(4)) for c in range(4)] for r in range(4)]
def rot(deg, x, y, z):
    a = math.radians(deg); c, s = math.cos(a), math.sin(a)
    if x: return [[1,0,0,0],[0,c,-s,0],[0,s,c,0],[0,0,0,1]]
    if y: return [[c,0,s,0],[0,1,0,0],[-s,0,c,0],[0,0,0,1]]
    return [[c,-s,0,0],[s,c,0,0],[0,0,1,0],[0,0,0,1]]
def trans(x,y,z): return [[1,0,0,x],[0,1,0,y],[0,0,1,z],[0,0,0,1]]
def xform(M, x, y, z):
    return (M[0][0]*x+M[0][1]*y+M[0][2]*z+M[0][3],
            M[1][0]*x+M[1][1]*y+M[1][2]*z+M[1][3],
            M[2][0]*x+M[2][1]*y+M[2][2]*z+M[2][3])

# ---------------------------------------------------------------- rig (port of Rig.kt)
PELVIS,CHEST,NECK,HEAD = 0,1,2,3
HIP_L,KNEE_L,ANKLE_L,TOE_L = 4,5,6,7
HIP_R,KNEE_R,ANKLE_R,TOE_R = 8,9,10,11
SH_L,ELB_L,WRI_L,HAND_L = 12,13,14,15
SH_R,ELB_R,WRI_R,HAND_R = 16,17,18,19
THIGH,SHIN,SPINE_L,UARM,FARM,HEAD_R = 0.42,0.42,0.36,0.30,0.27,0.10

def solve(ch, extra_yaw):
    P = [None]*20
    root = matmul(matmul(matmul(ident(), rot(ch[BYAW]+extra_yaw,0,1,0)), rot(ch[BPITCH],1,0,0)), rot(ch[BROLL],0,0,1))
    P[PELVIS] = xform(root,0,0,0)
    def leg(hj, side, hip, abd, knee, ankle):
        mA = matmul(root, trans(side,-0.03,0))
        P[hj] = xform(mA,0,0,0)
        mA = matmul(matmul(mA, rot(abd,0,0,1)), rot(-hip,1,0,0))
        P[hj+1] = xform(mA,0,-THIGH,0)
        mA = matmul(matmul(mA, trans(0,-THIGH,0)), rot(knee,1,0,0))
        P[hj+2] = xform(mA,0,-SHIN,0)
        mA = matmul(matmul(mA, trans(0,-SHIN,0)), rot(ankle,1,0,0))
        P[hj+3] = xform(mA,0,-0.04,0.20)
    leg(HIP_L, 0.09, ch[HIPPL], ch[HABDL], ch[KNEEL], ch[ANKL])
    leg(HIP_R,-0.09, ch[HIPPR],-ch[HABDR], ch[KNEER], ch[ANKR])
    mC = matmul(matmul(matmul(root, rot(ch[SROLL],0,0,1)), rot(ch[SYAW],0,1,0)), rot(ch[SPITCH],1,0,0))
    P[CHEST] = xform(mC,0,SPINE_L,0)
    mC = matmul(mC, trans(0,SPINE_L,0))
    mH = matmul(mC, rot(ch[HPITCH],1,0,0))
    P[NECK] = xform(mH,0,0.10,0)
    P[HEAD] = xform(mH,0,0.27,0)
    axes = {}
    def arm(sj, side, pitch, abd, elbow):
        mA = matmul(mC, trans(side,0.10,0))
        P[sj] = xform(mA,0,0,0)
        mA = matmul(matmul(mA, rot(abd,0,0,1)), rot(-pitch,1,0,0))
        P[sj+1] = xform(mA,0,-UARM,0)
        mA = matmul(matmul(mA, trans(0,-UARM,0)), rot(-elbow,1,0,0))
        P[sj+2] = xform(mA,0,-FARM,0)
        axes[sj] = (mA[0][0], mA[1][0], mA[2][0])
        mA = matmul(mA, trans(0,-FARM,0))
        P[sj+3] = xform(mA,0,-0.09,0)
    arm(SH_L, 0.20, ch[SHPL], ch[SHABDL], ch[ELBL])
    arm(SH_R,-0.20, ch[SHPR],-ch[SHABDR], ch[ELBR])
    CONTACT = [0.09,0.09,0.08,HEAD_R] + [0.09,0.045,0.045,0.02]*4
    miny = min(p[1] - CONTACT[j] for j,p in enumerate(P))
    lift = -miny + ch[HOP]
    P = [(x, y+lift, z) for (x,y,z) in P]
    return P, axes

def pose_at(ex, u):
    times, keys = ex['times'], ex['keys']
    u = u % 1.0
    i = len(times)-1
    for k in range(len(times)-1):
        if times[k] <= u < times[k+1]: i = k; break
    j = (i+1) % len(times)
    t0 = times[i]; t1 = 1.0 if j==0 else times[j]
    span = t1 - t0
    s = 0.0 if span <= 1e-4 else max(0.0, min(1.0, (u-t0)/span))
    e = 0.5 - 0.5*math.cos(math.pi*s)
    return [keys[i][k] + (keys[j][k]-keys[i][k])*e for k in range(N)]

BONES = [(PELVIS,CHEST),(CHEST,NECK),(HIP_L,HIP_R),(SH_L,SH_R),
         (HIP_L,KNEE_L),(KNEE_L,ANKLE_L),(ANKLE_L,TOE_L),
         (HIP_R,KNEE_R),(KNEE_R,ANKLE_R),(ANKLE_R,TOE_R),
         (SH_L,ELB_L),(ELB_L,WRI_L),(WRI_L,HAND_L),
         (SH_R,ELB_R),(ELB_R,WRI_R),(WRI_R,HAND_R)]

# ---------------------------------------------------------------- raster
class Img:
    def __init__(s, w, h):
        s.w, s.h = w, h
        s.px = bytearray(w*h*3)
    def set(s, x, y, c):
        x, y = int(x), int(y)
        if 0 <= x < s.w and 0 <= y < s.h:
            i = (y*s.w+x)*3
            s.px[i]=c[0]; s.px[i+1]=c[1]; s.px[i+2]=c[2]
    def line(s, x0,y0,x1,y1,c):
        x0,y0,x1,y1 = int(round(x0)),int(round(y0)),int(round(x1)),int(round(y1))
        dx,dy = abs(x1-x0), -abs(y1-y0)
        sx = 1 if x0<x1 else -1; sy = 1 if y0<y1 else -1
        err = dx+dy
        while True:
            s.set(x0,y0,c)
            if x0==x1 and y0==y1: break
            e2 = 2*err
            if e2 >= dy: err += dy; x0 += sx
            if e2 <= dx: err += dx; y0 += sy
    def save(s, path):
        raw = b''.join(b'\x00' + bytes(s.px[y*s.w*3:(y+1)*s.w*3]) for y in range(s.h))
        def chunk(tag, data):
            c = tag + data
            return struct.pack('>I', len(data)) + c + struct.pack('>I', zlib.crc32(c))
        png = (b'\x89PNG\r\n\x1a\n'
               + chunk(b'IHDR', struct.pack('>IIBBBBB', s.w, s.h, 8, 2, 0, 0, 0))
               + chunk(b'IDAT', zlib.compress(raw, 6))
               + chunk(b'IEND', b''))
        open(path,'wb').write(png)

FONT = {  # 3x5, rows top->bottom, 3 bits each
 'A':[2,5,7,5,5],'B':[6,5,6,5,6],'C':[3,4,4,4,3],'D':[6,5,5,5,6],'E':[7,4,6,4,7],
 'F':[7,4,6,4,4],'G':[3,4,5,5,3],'H':[5,5,7,5,5],'I':[7,2,2,2,7],'J':[1,1,1,5,2],
 'K':[5,5,6,5,5],'L':[4,4,4,4,7],'M':[5,7,7,5,5],'N':[5,7,7,7,5],'O':[2,5,5,5,2],
 'P':[6,5,6,4,4],'Q':[2,5,5,7,3],'R':[6,5,6,5,5],'S':[3,4,2,1,6],'T':[7,2,2,2,2],
 'U':[5,5,5,5,7],'V':[5,5,5,5,2],'W':[5,5,7,7,5],'X':[5,5,2,5,5],'Y':[5,5,2,2,2],
 'Z':[7,1,2,4,7],'_':[0,0,0,0,7],'0':[7,5,5,5,7],'1':[2,6,2,2,7],'2':[7,1,7,4,7],
 '3':[7,1,3,1,7],'4':[5,5,7,1,1],'5':[7,4,7,1,7],'6':[7,4,7,5,7],'7':[7,1,1,1,1],
 '8':[7,5,7,5,7],'9':[7,5,7,1,7],' ':[0,0,0,0,0],'.':[0,0,0,0,2],'/':[1,1,2,4,4],
}
def text(img, s, x, y, c):
    for ch in s.upper():
        g = FONT.get(ch, FONT[' '])
        for r in range(5):
            for b in range(3):
                if g[r] & (4>>b): img.set(x+b, y+r, c)
        x += 4

# ---------------------------------------------------------------- sheet
def render(exs, out_prefix, per_sheet=9):
    FW, FH, PHASES = 190, 168, 6
    SCALE = 66.0
    sheets = []
    for s0 in range(0, len(exs), per_sheet):
        rows = exs[s0:s0+per_sheet]
        img = Img(FW*PHASES, FH*len(rows))
        for r, ex in enumerate(rows):
            oy = r*FH
            for p in range(PHASES):
                ox = p*FW
                gy = oy + FH - 22          # ground line y
                # frame border + ground
                for x in range(FW): img.set(ox+x, oy, (40,40,55)); img.set(ox+x, oy+FH-1, (40,40,55))
                img.line(ox, gy, ox+FW-1, gy, (60,80,60))
                # mat extent (±1.15m)
                img.line(ox+FW/2-1.15*SCALE, gy+3, ox+FW/2+1.15*SCALE, gy+3, (30,90,60))
                u = p/PHASES
                ch = pose_at(ex, u)
                P, axes = solve(ch, ex['yaw'])
                def sx(pt): return ox + FW/2 + pt[0]*SCALE
                def sy(pt): return gy - pt[1]*SCALE
                for (a,b) in BONES:
                    img.line(sx(P[a]), sy(P[a]), sx(P[b]), sy(P[b]), (120,235,255))
                # head circle
                hx, hy = sx(P[HEAD]), sy(P[HEAD])
                pr = None
                for k in range(13):
                    an = k/12*2*math.pi
                    q = (hx+math.cos(an)*HEAD_R*SCALE, hy+math.sin(an)*HEAD_R*SCALE)
                    if pr: img.line(pr[0],pr[1],q[0],q[1],(120,235,255))
                    pr = q
                # dumbbells
                if ex['db'] == 'PAIR':
                    for wj, hj, aj in ((WRI_L,HAND_L,SH_L),(WRI_R,HAND_R,SH_R)):
                        cxp = (P[wj][0]*0.4+P[hj][0]*0.6, P[wj][1]*0.4+P[hj][1]*0.6, 0)
                        ax = axes[aj]
                        img.line(sx((cxp[0]-ax[0]*0.11, cxp[1]-ax[1]*0.11,0)), sy((0,cxp[1]-ax[1]*0.11,0)),
                                 sx((cxp[0]+ax[0]*0.11, cxp[1]+ax[1]*0.11,0)), sy((0,cxp[1]+ax[1]*0.11,0)), (255,80,190))
                elif ex['db'] == 'SINGLE':
                    img.line(sx(P[HAND_L]), sy(P[HAND_L]), sx(P[HAND_R]), sy(P[HAND_R]), (255,80,190))
            text(img, f"{ex['key']}  yaw{int(ex['yaw'])}", 4, oy+4, (255,220,120))
        path = f"{out_prefix}_{s0//per_sheet}.png"
        img.save(path)
        sheets.append(path)
    return sheets

if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)
    exs = parse_kt()
    print(f"parsed {len(exs)} exercises: " + " ".join(e['key'] for e in exs))
    for p in render(exs, os.path.join(OUT, "sheet")):
        print("wrote", p)
