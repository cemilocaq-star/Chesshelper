"""Python mirror of BoardReader.findBoard (Kotlin) for validation."""
import sys, random
sys.path.insert(0, '.')
import numpy as np
from scipy import ndimage
from PIL import Image, ImageDraw
from render import *

def find_board(img, step=2):
    a = np.asarray(img)
    h, w = a.shape[:2]
    sw, sh = w // step, h // step
    smp = a[::step, ::step][:sh, :sw].astype(np.int32)
    R, G, B = smp[..., 0], smp[..., 1], smp[..., 2]
    key = ((R >> 3) << 10) | ((G >> 3) << 5) | (B >> 3)
    bins = np.bincount(key.ravel(), minlength=32768).reshape(32, 32, 32)
    smooth = ndimage.convolve(bins, np.ones((3, 3, 3), int), mode="constant").astype(np.int64)
    smooth = smooth.reshape(-1)
    min_count = smp.shape[0] * smp.shape[1] // 60
    peaks = []
    for _ in range(8):
        best = int(smooth.argmax()); bv = int(smooth[best])
        if bv < min_count:
            break
        pr, pg, pb = best >> 10, (best >> 5) & 31, best & 31
        sm3 = smooth.reshape(32, 32, 32)
        sm3[max(0, pr - 3):min(31, pr + 3) + 1, max(0, pg - 3):min(31, pg + 3) + 1, max(0, pb - 3):min(31, pb + 3) + 1] = 0
        cr, cg, cb = pr * 8 + 4, pg * 8 + 4, pb * 8 + 4
        m = (abs(R - cr) <= 14) & (abs(G - cg) <= 14) & (abs(B - cb) <= 14)
        if m.sum() > 0:
            peaks.append((int(R[m].sum() // m.sum()), int(G[m].sum() // m.sum()), int(B[m].sum() // m.sum())))
    if len(peaks) < 2:
        return None
    ids = np.full(R.shape, -1, np.int8)
    for k in reversed(range(len(peaks))):
        p = peaks[k]
        ids[(abs(R - p[0]) <= 14) & (abs(G - p[1]) <= 14) & (abs(B - p[2]) <= 14)] = k
    best, best_score = None, 0.75
    for ai in range(len(peaks)):
        for ci in range(ai + 1, len(peaks)):
            sel = (ids == ai) | (ids == ci)
            rows = sel.sum(axis=1); cols = sel.sum(axis=0)
            rt = max(int(rows.max() * 0.15), 6); ct = max(int(cols.max() * 0.15), 6)
            run_y = longest_run(rows >= rt); run_x = longest_run(cols >= ct)
            if run_y is None or run_x is None:
                continue
            miny, maxy = run_y; minx, maxx = run_x
            bw = (maxx - minx + 1) * step; bh = (maxy - miny + 1) * step
            for (x, y, size) in candidates(minx, maxx, miny, maxy, bw, bh, step, w, h):
                sc = checker_score(ids, step, x, y, size, ai, ci)
                if sc > best_score:
                    best_score, best = sc, (x, y, size)
    return best

def candidates(minx, maxx, miny, maxy, bw, bh, step, w, h):
    """square candidates inside the colour bounding box; slides a square when the box is not square"""
    if bw < w / 3:
        return
    if abs(bw - bh) <= bw * 0.08:
        size = min((bw + bh) // 2, w - minx * step, h - miny * step)
        yield (minx * step, miny * step, size)
        return
    for size in sorted({bw, bh}, reverse=True):
        if size < w / 3:
            continue
        xa, xb = minx * step, max(minx * step, (maxx + 1) * step - size)
        ya, yb = miny * step, max(miny * step, (maxy + 1) * step - size)
        sx = max(2, (xb - xa) // 80); sy = max(2, (yb - ya) // 80)
        for x in range(xa, xb + 1, sx):
            for y in range(ya, yb + 1, sy):
                if x + size <= w and y + size <= h:
                    yield (x, y, size)

def longest_run(flags, max_gap=2):
    """longest stretch of True values; gaps of up to max_gap False values do not break it"""
    best = None; start = None; last = None
    for i, f in enumerate(flags):
        if f:
            if start is None:
                start = i
            elif i - last > max_gap + 1:
                if best is None or last - start > best[1] - best[0]:
                    best = (start, last)
                start = i
            last = i
    if start is not None and (best is None or last - start > best[1] - best[0]):
        best = (start, last)
    return best

def checker_score(ids, step, x, y, size, a, b):
    sh, sw = ids.shape
    sq = size / 8
    m1 = m2 = n = 0
    for r in range(8):
        for c in range(8):
            even = (r + c) % 2 == 0
            x0, y0 = x + c * sq, y + r * sq
            for k in (0, 1):
                px = x0 + 0.88 * sq if k == 0 else x0 + 0.12 * sq
                py = y0 + 0.12 * sq if k == 0 else y0 + 0.88 * sq
                sx, sy = int(px / step), int(py / step)
                idv = int(ids[sy, sx]) if 0 <= sx < sw and 0 <= sy < sh else -1
                n += 1
                if idv == (a if even else b): m1 += 1
                if idv == (b if even else a): m2 += 1
    return max(m1, m2) / n

def fake_screen(theme, font, grid, wb, x, y, S, W=1080, H=2400, rng=None):
    rng = rng or random.Random(0)
    img = render(grid, theme, font, white_bottom=wb, S=S, origin=(x, y), W=W, H=H,
                 last={(6, 4), (4, 4)}, hints=[(5, 3)], capring=[(1, 3)])
    d = ImageDraw.Draw(img)
    # UI chrome: top bar, player rows, bottom buttons
    d.rectangle([0, 0, W, 180], fill=(49, 46, 43))
    d.rectangle([0, 200, W, y - 20], fill=(38, 36, 33))
    for i in range(4):
        d.rectangle([60 + i * 250, H - 260, 260 + i * 250, H - 140], fill=(129, 182, 76) if i == 0 else (60, 58, 55))
    d.ellipse([30, y - 150, 130, y - 50], fill=(rng.randint(40, 255), rng.randint(40, 255), rng.randint(40, 255)))
    d.ellipse([30, y + 8 * S + 30, 130, y + 8 * S + 130], fill=(200, 160, 90))
    d.rectangle([160, y + 8 * S, 500, y + 8 * S + 60], fill=(235, 235, 235))
    d.rectangle([160, y - 60, 500, y - 1], fill=(235, 235, 235))
    return img

if __name__ == "__main__":
    g = [list(r) for r in START]
    cases = [("green", "dejavu", 0, 520, 135, 1080, 2400), ("brown", "freeserif", 0, 480, 135, 1080, 2340),
             ("blue", "dejavu", 20, 600, 130, 1080, 2400), ("gray", "freeserif", 0, 300, 112, 900, 1600),
             ("green", "dejavu", 0, 700, 160, 1280, 2800),
             # Phone-sized board with the dark blue-gray colors from the supplied Chess.com screenshot.
             ("chesscom", "freeserif", 0, 458, 86.375, 691, 1536)]
    failures = []
    for th, fn, x, y, S, W, H in cases:
        for wb in (True, False):
            img = fake_screen(th, fn, g, wb, x, y, S, W, H)
            got = find_board(img)
            want = (x, y, 8 * S)
            ok = got is not None and all(abs(got[i] - want[i]) <= 4 for i in range(3))
            print(f"{th:6s} {W}x{H} S={S} wb={wb}: want {want} got {got} {'OK' if ok else 'FAIL'}")
            if not ok:
                failures.append((th, W, H, S, wb, want, got))
    if failures:
        raise SystemExit(f"Board detection simulation failed: {failures}")
