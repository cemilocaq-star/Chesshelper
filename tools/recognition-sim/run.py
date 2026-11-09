import sys, random
sys.path.insert(0, '.')
import numpy as np
from render import *
import recog as recog_module
from recog import *

BOARD = (0, 520, 1080)

def board_to_screen(grid, wb):
    return grid if wb else [row[::-1] for row in grid[::-1]]

def screen_truth(grid, wb):
    return board_to_screen(grid, wb)


def board_placement(grid):
    ranks = []
    for row in grid:
        out = []
        empty = 0
        for piece in row:
            if piece == ".":
                empty += 1
            else:
                if empty:
                    out.append(str(empty))
                    empty = 0
                out.append(piece)
        if empty:
            out.append(str(empty))
        ranks.append("".join(out))
    return "/".join(ranks)


def board_grid_from_screen(grid, white_bottom):
    return grid if white_bottom else [row[::-1] for row in grid[::-1]]

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
    positions = curated_positions() + [random_position(rng) for _ in range(n)]
    for k, board in enumerate(positions):
        wb = wb0
        g = board_to_grid(board)
        occ = [(r, c) for r in range(8) for c in range(8) if g[r][c] != "."]
        empty = [(r, c) for r in range(8) for c in range(8) if g[r][c] == "."]
        last = None
        if board.move_stack:
            move = board.peek()
            last = {
                (7 - chess.square_rank(move.from_square), chess.square_file(move.from_square)),
                (7 - chess.square_rank(move.to_square), chess.square_file(move.to_square)),
            }
        legal_empty_targets = {
            (7 - chess.square_rank(move.to_square), chess.square_file(move.to_square))
            for move in board.legal_moves if board.piece_at(move.to_square) is None
        }
        hints = rng.sample(list(legal_empty_targets), min(len(legal_empty_targets), rng.randint(0, 6)))
        cap = rng.sample(occ, min(len(occ), rng.randint(0, 2))) if rng.random() < 0.4 else []
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
        # Also assert that the complete recognized board round-trips to the exact legal FEN placement.
        actual_placement = board_placement(board_grid_from_screen(pick, wb))
        if actual_placement != board.board_fen():
            rows.append(("FEN_PLACEMENT_MISMATCH", actual_placement, board.board_fen()))
    return ok, tot, rows

if __name__ == "__main__":
    failures = []
    for th in THEMES:
        for font in ("dejavu", "freeserif"):
            result = trial(th, font, font, seed=1)
            if result is None:
                print(th, font, "CALIBRATION FAILED:", recog_module.LAST_CALIBRATION_FAILURE)
                failures.append((th, font, "calibration"))
                continue
            ok, tot, rows = result
            from collections import Counter
            accuracy = 100 * ok / tot
            print(f"{th:6s} {font:10s} {ok}/{tot} = {accuracy:.2f}%  legal_positions={len(positions)}  errors: {Counter(map(str,rows)).most_common(4)}")
            if accuracy < 95.0:
                failures.append((th, font, f"accuracy={accuracy:.2f}%"))
    if failures:
        raise SystemExit(f"Recognition simulation failed: {failures}")
