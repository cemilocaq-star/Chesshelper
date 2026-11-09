import sys, random
sys.path.insert(0, '.')
import numpy as np
from render import *
from recog import *

BOARD = (0, 520, 1080)

def board_to_screen(grid, wb):
    return grid if wb else [row[::-1] for row in grid[::-1]]

def screen_truth(grid, wb):
    return board_to_screen(grid, wb)

def trial(theme, calfont, testfont, seed, shift=0, repair=True, n=25):
    rng = random.Random(seed)
    start = [list(r) for r in START]
    wb0 = rng.random() < 0.5
    img = render(start, theme, calfont, white_bottom=wb0, last={(6, 4), (4, 4)} if False else None)
    sc = scan(img, BOARD)
    cal = calibrate(sc)
    if cal is None:
        return None
    t, wb_cal = cal
    assert wb_cal == wb0, "orientation wrong"
    tot = ok = 0
    rows = []
    for k in range(n):
        wb = wb0
        g = random_position(rng)
        occ = [(r, c) for r in range(8) for c in range(8) if g[r][c] != "."]
        empty = [(r, c) for r in range(8) for c in range(8) if g[r][c] == "."]
        last = set(rng.sample(occ, 1) + rng.sample(empty, 1)) if rng.random() < 0.8 else None
        hints = rng.sample(empty, rng.randint(0, 6)) if rng.random() < 0.5 else []
        cap = rng.sample(occ, rng.randint(0, 2)) if rng.random() < 0.4 else []
        img = render(g, theme, testfont, white_bottom=wb, last=last, hints=hints, capring=cap,
                     origin=(shift, 520 + shift))
        sc = scan(img, BOARD)
        res = read(sc, t, wb, repair=repair)
        truth = screen_truth(g, wb)
        if res is None:
            tot += 64
            rows.append("NULL")
            continue
        pick, wb_r, _ = res
        for r in range(8):
            for c in range(8):
                tot += 1
                if pick[r][c] == truth[r][c]:
                    ok += 1
                else:
                    rows.append((truth[r][c], pick[r][c]))
    return ok, tot, rows

if __name__ == "__main__":
    for th in THEMES:
        for font in ("dejavu", "freeserif"):
            r = trial(th, font, font, seed=1)
            if r is None:
                print(th, font, "CALIBRATION FAILED"); continue
            ok, tot, rows = r
            from collections import Counter
            print(f"{th:6s} {font:10s} {ok}/{tot} = {100*ok/tot:.2f}%  errors: {Counter(map(str,rows)).most_common(4)}")
