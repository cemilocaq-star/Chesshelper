"""Python mirror of the Kotlin recognition pipeline (used only to validate the algorithm)."""
import numpy as np
from scipy import ndimage

PIECES = "KQRBNPkqrbnp"
F = 16
WALL_T = 70          # |rgb - bg| sum above this = "not background"
CROP = (0.10, 0.90)
MIN_COVER = 0.15
MIN_BBOX = 0.45

def lum(a):
    return 0.299 * a[..., 0] + 0.587 * a[..., 1] + 0.114 * a[..., 2]

def l2(v):
    n = np.linalg.norm(v)
    return v / n if n > 1e-6 else v.copy()

def area_resample(a, n):
    """average a 2-D array over an n x n grid using integer bounds"""
    h, w = a.shape
    ys = np.linspace(0, h, n + 1).astype(int)
    xs = np.linspace(0, w, n + 1).astype(int)
    out = np.zeros((n, n), np.float32)
    for j in range(n):
        for i in range(n):
            blk = a[ys[j]:max(ys[j + 1], ys[j] + 1), xs[i]:max(xs[i + 1], xs[i] + 1)]
            out[j, i] = blk.mean()
    return out

class Sq:
    pass

def analyze(px):
    """px: square image HxWx3 (int). returns Sq with bg, mask, edges, fillLum, occupied, cover"""
    h, w, _ = px.shape
    s = Sq()
    # background: consensus of the 4 corner patches (capture rings never reach the corners,
    # coordinate labels cover at most 1-2 corners)
    p = max(3, int(0.10 * w))
    o = int(0.03 * w)
    patches = []
    for (py, px0) in ((o, o), (o, w - o - p), (h - o - p, o), (h - o - p, w - o - p)):
        patches.append(np.median(px[py:py + p, px0:px0 + p].reshape(-1, 3), axis=0))
    best_i, best_n = 0, -1
    for i in range(4):
        n = sum(1 for j in range(4) if np.abs(patches[i] - patches[j]).sum() < 30)
        if n > best_n:
            best_i, best_n = i, n
    agree = [patches[j] for j in range(4) if np.abs(patches[best_i] - patches[j]).sum() < 30]
    s.bg = np.mean(agree, axis=0)
    y0, y1 = int(CROP[0] * h), int(CROP[1] * h)
    x0, x1 = int(CROP[0] * w), int(CROP[1] * w)
    crop = px[y0:y1, x0:x1].astype(np.float32)
    diff = np.abs(crop - s.bg).sum(axis=2)
    wall = diff > WALL_T
    lab, n = ndimage.label(~wall)
    ext = np.zeros(n + 1, bool)
    for edge in (lab[0, :], lab[-1, :], lab[:, 0], lab[:, -1]):
        ext[np.unique(edge)] = True
    ext[0] = False
    mask = ~ext[lab]                      # everything that is not exterior background
    s.cover = float(mask.mean())
    ys, xs = np.where(mask)
    if len(ys):
        bh = (ys.max() - ys.min() + 1) / mask.shape[0]
        bw = (xs.max() - xs.min() + 1) / mask.shape[1]
    else:
        bh = bw = 0
    s.occupied = s.cover >= MIN_COVER and bh >= MIN_BBOX and bw >= MIN_BBOX
    L = lum(crop)
    vals = L[mask]
    s.fill = float(np.median(vals)) if len(vals) else 0.0
    s.maskv = l2(area_resample(mask.astype(np.float32), F).ravel())
    cell = area_resample(L, F + 2)
    gx = cell[1:-1, 2:] - cell[1:-1, :-2]
    gy = cell[2:, 1:-1] - cell[:-2, 1:-1]
    s.edges = l2((np.abs(gx) + np.abs(gy)).ravel())
    s.wm = 0.15 if s.cover > 0.70 else 0.6
    return s

def sim(q, t):
    return q.wm * float(q.maskv @ t[1]) + (1 - q.wm) * float(q.edges @ t[0])

def square(img, board, r, c):
    x, y, size = board
    sq = size / 8
    xa, ya = int(round(x + c * sq)), int(round(y + r * sq))
    xb, yb = int(round(x + (c + 1) * sq)), int(round(y + (r + 1) * sq))
    return np.asarray(img.crop((xa, ya, xb, yb)))

def scan(img, board):
    return [[analyze(square(img, board, r, c)) for c in range(8)] for r in range(8)]

def highlights(sc):
    """squares whose background differs from the median of their parity group"""
    out = set()
    for par in (0, 1):
        grp = [(r, c) for r in range(8) for c in range(8) if (r + c) % 2 == par]
        med = np.median(np.array([sc[r][c].bg for r, c in grp]), axis=0)
        for r, c in grp:
            if np.abs(sc[r][c].bg - med).sum() > 36:
                out.add((r, c))
    return out

class Templates:
    def __init__(self):
        self.s = {p: [] for p in PIECES}
        self.thr = 128.0

def calibrate(sc):
    occ = [[sc[r][c].occupied for c in range(8)] for r in range(8)]
    emptyHome = sum(1 for r in range(8) for c in range(8) if (r <= 1 or r >= 6) and not occ[r][c])
    midOcc = sum(1 for r in range(8) for c in range(8) if (2 <= r <= 5) and occ[r][c])
    if emptyHome > 2 or midOcc > 2 or emptyHome != midOcc:
        return None
    def mean(rows):
        v = [sc[r][c].fill for r in rows for c in range(8) if occ[r][c]]
        return np.mean(v) if v else None
    top, bot = mean([0, 1]), mean([6, 7])
    if top is None or bot is None or abs(top - bot) < 40:
        return None
    wb = bot > top
    layout = {0: "rnbqkbnr", 1: "pppppppp", 6: "PPPPPPPP", 7: "RNBQKBNR"} if wb else \
             {0: "RNBKQBNR", 1: "PPPPPPPP", 6: "pppppppp", 7: "rnbkqbnr"}
    t = Templates()
    wl, bl = [], []
    for r, line in layout.items():
        for c in range(8):
            if not occ[r][c]:
                continue
            ch = line[c]
            t.s[ch].append((sc[r][c].edges, sc[r][c].maskv))
            (wl if ch.isupper() else bl).append(sc[r][c].fill)
    # Match the Android implementation: a piece may already have moved from
    # its home square or one glyph may be missed by occupancy detection. Reuse
    # the opposite-colour template for the same silhouette before rejecting the
    # calibration; colour is classified independently using fill luminance.
    for p in PIECES:
        if not t.s[p]:
            counterpart = p.lower() if p.isupper() else p.upper()
            if t.s[counterpart]:
                t.s[p] = list(t.s[counterpart])

    if not wl or not bl or any(len(t.s[p]) == 0 for p in PIECES):
        return None
    t.thr = (np.mean(wl) + np.mean(bl)) / 2
    t.gap = np.mean(wl) - np.mean(bl)
    return t, wb

def class_scores(q, t):
    """score for each of the 12 classes (colour-gated; ambiguous colours allow both with penalty)"""
    amb = abs(q.fill - t.thr) < 0.15 * t.gap
    white = q.fill > t.thr
    out = np.full(len(PIECES), -1.0)
    for i, p in enumerate(PIECES):
        sides_ok = (p.isupper() == white)
        if not sides_ok and not amb:
            continue
        best = max(sim(q, s) for s in t.s[p])
        out[i] = best if sides_ok else best - 0.08
    return out

def read(sc, t, prev_wb=True, repair=True, detect_orientation=False):
    scores = [[None] * 8 for _ in range(8)]
    pick = [["."] * 8 for _ in range(8)]
    for r in range(8):
        for c in range(8):
            if not sc[r][c].occupied:
                continue
            s = class_scores(sc[r][c], t)
            scores[r][c] = s
            pick[r][c] = PIECES[int(np.argmax(s))]
    wr = [r for r in range(8) for c in range(8) if pick[r][c].isupper()]
    br = [r for r in range(8) for c in range(8) if pick[r][c].islower()]
    if not wr or not br:
        return None
    delta = np.mean(wr) - np.mean(br)
    inferred_wb = True if delta > 0.6 else False if delta < -0.6 else prev_wb
    # Match Kotlin: infer orientation at initial calibration only, then lock it for the session.
    wb = inferred_wb if detect_orientation else prev_wb

    def rank_of(r):
        return 7 - r if wb else r

    def reassign(r, c, banned):
        s = scores[r][c].copy()
        for i, p in enumerate(PIECES):
            if p in banned:
                s[i] = -9
        pick[r][c] = PIECES[int(np.argmax(s))]

    if repair:
        for _ in range(3):
            for r in range(8):
                for c in range(8):
                    if pick[r][c] in "Pp" and rank_of(r) in (0, 7):
                        reassign(r, c, "Pp")
            for K in "Kk":
                pos = [(r, c) for r in range(8) for c in range(8) if pick[r][c] == K]
                idx = PIECES.index(K)
                if len(pos) > 1:
                    keep = max(pos, key=lambda p: scores[p[0]][p[1]][idx])
                    for p in pos:
                        if p != keep:
                            reassign(p[0], p[1], K)
                elif len(pos) == 0:
                    side = [(r, c) for r in range(8) for c in range(8)
                            if pick[r][c] != "." and pick[r][c].isupper() == K.isupper()]
                    if side:
                        r, c = max(side, key=lambda p: scores[p[0]][p[1]][idx])
                        pick[r][c] = K
            for P in "Pp":
                pos = [(r, c) for r in range(8) for c in range(8) if pick[r][c] == P]
                idx = PIECES.index(P)
                if len(pos) > 8:
                    pos.sort(key=lambda p: -scores[p[0]][p[1]][idx])
                    for p in pos[8:]:
                        reassign(p[0], p[1], P)
    return pick, wb, scores
