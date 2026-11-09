import random
from PIL import Image, ImageDraw, ImageFont
import chess

THEMES = {
    "green": ((238, 238, 210), (118, 150, 86)),
    "brown": ((240, 217, 181), (181, 136, 99)),
    "blue":  ((222, 227, 230), (140, 162, 173)),
    "gray":  ((232, 232, 232), (150, 150, 150)),
    # Approximate Chess.com dark blue-gray board colors measured from the supplied screenshot.
    "chesscom": ((102, 114, 130), (37, 45, 58)),
}
FONTS = {
    "dejavu": "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "freeserif": "/usr/share/fonts/truetype/freefont/FreeSerif.ttf",
    "freesans": "/usr/share/fonts/truetype/freefont/FreeSans.ttf",
}
GLYPH = {"k": "♚", "q": "♛", "r": "♜", "b": "♝", "n": "♞", "p": "♟"}
START = ["rnbqkbnr", "pppppppp", "........", "........", "........", "........", "PPPPPPPP", "RNBQKBNR"]  # row 0 = rank 8

def blend(c, o, a):
    return tuple(int(c[i] * (1 - a) + o[i] * a) for i in range(3))

def render(grid, theme, font, white_bottom=True, last=None, hints=(), capring=(), S=135,
           origin=(0, 520), W=1080, H=2200, size_frac=0.82, bg=(32, 30, 28)):
    """grid[row][col] in *white-bottom* orientation (row 0 = rank 8). If not white_bottom the screen is rotated 180."""
    light, dark = THEMES[theme]
    img = Image.new("RGB", (W, H), bg)
    d = ImageDraw.Draw(img)
    ft = ImageFont.truetype(FONTS[font], int(S * size_frac))
    small = ImageFont.truetype(FONTS["dejavu"], int(S * 0.17))
    def scr(r, c):  # board coords -> screen coords
        return (r, c) if white_bottom else (7 - r, 7 - c)
    for r in range(8):
        for c in range(8):
            sr, sc = scr(r, c)
            base = light if (sr + sc) % 2 == 0 else dark
            x0 = origin[0] + sc * S
            y0 = origin[1] + sr * S
            col = base
            if last and (r, c) in last:
                col = blend(base, (255, 255, 0), 0.55)
            d.rectangle([x0, y0, x0 + S - 1, y0 + S - 1], fill=col)
            # coordinate labels like chess.com: rank on the left column, file on the bottom row
            if sc == 0:
                d.text((x0 + 4, y0 + 2), str(8 - r if white_bottom else r + 1), font=small,
                       fill=dark if (sr + sc) % 2 == 0 else light)
            if sr == 7:
                d.text((x0 + S - 4, y0 + S - 3), "abcdefgh"[c if white_bottom else 7 - c], font=small,
                       fill=dark if (sr + sc) % 2 == 0 else light, anchor="rd")
    ov = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    od = ImageDraw.Draw(ov)
    for (r, c) in hints:
        sr, sc = scr(r, c)
        cx = origin[0] + sc * S + S / 2
        cy = origin[1] + sr * S + S / 2
        rad = S * 0.16
        od.ellipse([cx - rad, cy - rad, cx + rad, cy + rad], fill=(0, 0, 0, 36))
    for (r, c) in capring:
        sr, sc = scr(r, c)
        x0 = origin[0] + sc * S
        y0 = origin[1] + sr * S
        od.ellipse([x0 - 2, y0 - 2, x0 + S + 1, y0 + S + 1], outline=(0, 0, 0, 36), width=int(S * 0.09))
    img = Image.alpha_composite(img.convert("RGBA"), ov).convert("RGB")
    d = ImageDraw.Draw(img)
    for r in range(8):
        for c in range(8):
            ch = grid[r][c]
            if ch == ".":
                continue
            sr, sc = scr(r, c)
            cx = origin[0] + sc * S + S / 2
            cy = origin[1] + sr * S + S / 2
            white = ch.isupper()
            black_fill = (0, 0, 0) if theme == "chesscom" else (58, 58, 58)
            black_stroke = (0, 0, 0) if theme == "chesscom" else (8, 8, 8)
            d.text((cx, cy), GLYPH[ch.lower()], font=ft, anchor="mm",
                   fill=(250, 250, 250) if white else black_fill,
                   stroke_width=2, stroke_fill=(40, 40, 40) if white else black_stroke)
    return img

def board_to_grid(board):
    """Return the board placement as rows from rank 8 to rank 1."""
    return [
        [
            board.piece_at(chess.square(file, rank)).symbol()
            if board.piece_at(chess.square(file, rank)) else "."
            for file in range(8)
        ]
        for rank in range(7, -1, -1)
    ]


CURATED_SEQUENCES = [
    # Open game followed by legal king-side castling on both sides.
    ("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6", "e1g1", "f8c5", "d2d3", "e8g8"),
    # En-passant target after Black's two-square pawn move.
    ("e2e4", "a7a6", "e4e5", "d7d5"),
    # Several genuine captures in a Queen's Gambit structure.
    ("d2d4", "d7d5", "c1f4", "g8f6", "e2e3", "e7e6", "f1d3",
     "c7c5", "c2c3", "b8c6", "g1f3", "c5d4", "e3d4"),
    # Fool's mate: a legal checkmate position.
    ("f2f3", "e7e5", "g2g4", "d8h4"),
    # Queen-side castling with the required path cleared.
    ("d2d4", "d7d5", "c1f4", "c8f5", "e2e3", "e7e6",
     "b1c3", "b8c6", "d1d2", "d8d7", "e1c1"),
]


CURATED_FENS = [
    # White pawn one step from promotion.
    "4k3/P7/8/8/8/8/8/4K3 w - - 0 1",
    # En-passant capture is available to White.
    "4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1",
    # Both sides retain castling rights.
    "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1",
    # A legal position where White is in check.
    "4k3/8/8/8/8/8/4r3/4K3 w - - 0 1",
]


def curated_positions():
    """Return verified legal positions covering special moves and game states."""
    boards = [chess.Board()]
    for sequence in CURATED_SEQUENCES:
        board = chess.Board()
        for uci in sequence:
            move = chess.Move.from_uci(uci)
            if move not in board.legal_moves:
                raise AssertionError(f"Curated sequence contains illegal move: {uci} in {board.fen()}")
            board.push(move)
        boards.append(board)
    for fen in CURATED_FENS:
        board = chess.Board(fen)
        if not board.is_valid():
            raise AssertionError(f"Curated FEN is not a legal position: {fen} (status={board.status()})")
        boards.append(board)
    return boards


def random_position(rng):
    """Generate a reachable position by playing only legal moves from the initial board."""
    board = chess.Board()
    plies = rng.randint(12, 90)
    for _ in range(plies):
        if board.is_game_over(claim_draw=True):
            break
        moves = list(board.legal_moves)
        if not moves:
            break
        board.push(rng.choice(moves))
    if not board.is_valid():
        raise AssertionError(f"Random playout produced invalid position: {board.fen()}")
    return board
