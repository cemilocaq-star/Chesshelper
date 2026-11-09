import random
from PIL import Image, ImageDraw, ImageFont

THEMES = {
    "green": ((238, 238, 210), (118, 150, 86)),
    "brown": ((240, 217, 181), (181, 136, 99)),
    "blue":  ((222, 227, 230), (140, 162, 173)),
    "gray":  ((232, 232, 232), (150, 150, 150)),
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
            d.text((cx, cy), GLYPH[ch.lower()], font=ft, anchor="mm",
                   fill=(250, 250, 250) if white else (58, 58, 58),
                   stroke_width=2, stroke_fill=(40, 40, 40) if white else (8, 8, 8))
    return img

def random_position(rng, white_bottom=True):
    pieces = {"K": 1, "Q": 1, "R": 2, "B": 2, "N": 2, "P": 8}
    sq = [(r, c) for r in range(8) for c in range(8)]
    rng.shuffle(sq)
    grid = [["."] * 8 for _ in range(8)]
    n_remove = rng.randint(0, 14)
    items = []
    for side in (str.upper, str.lower):
        for p, n in pieces.items():
            for _ in range(n):
                items.append(side(p))
    keep = [it for it in items if it.lower() == "k"]
    others = [it for it in items if it.lower() != "k"]
    rng.shuffle(others)
    others = others[: len(others) - n_remove]
    for it in keep + others:
        while True:
            r, c = sq.pop()
            if it.lower() == "p" and r in (0, 7):
                sq.insert(0, (r, c))
                continue
            grid[r][c] = it
            break
    return grid
