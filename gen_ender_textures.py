#!/usr/bin/env python3
"""Generate ender-pearl styled HDMonitor block textures (16x16)."""
import random
from PIL import Image, ImageDraw

OUT_DIR = "/tmp/znatokos-hdmon/src/main/resources/assets/hdmon/textures/block"

# Palette
RIM_DARK = (15, 71, 68, 255)        # #0F4744
RIM_DARK2 = (28, 107, 102, 255)     # #1C6B66
MID1 = (46, 139, 128, 255)          # #2E8B80
MID2 = (77, 179, 166, 255)          # #4DB3A6
HI1 = (127, 224, 210, 255)          # #7FE0D2
HI2 = (184, 255, 242, 255)          # #B8FFF2
SHADOW = (5, 24, 24, 255)           # #051818
PURPLE = (107, 47, 142, 255)        # #6B2F8E
BLACK = (0, 0, 0, 255)


def swirl_base(w=16, h=16, base=MID1, alt=MID2, dark=RIM_DARK2, seed=42):
    """Build a base image with subtle ender-mist swirl pattern."""
    random.seed(seed)
    img = Image.new("RGBA", (w, h), base)
    px = img.load()
    # Diagonal swirl bands
    for y in range(h):
        for x in range(w):
            # pseudo-swirl: blend based on sin-like pattern using ints
            v = (x * 3 + y * 5 + (x ^ y)) % 11
            if v < 2:
                px[x, y] = dark
            elif v < 5:
                px[x, y] = alt
            elif v < 7:
                px[x, y] = base
    # Light noise
    for _ in range(8):
        x, y = random.randint(1, w - 2), random.randint(1, h - 2)
        px[x, y] = HI1
    for _ in range(3):
        x, y = random.randint(2, w - 3), random.randint(2, h - 3)
        px[x, y] = HI2
    # Occasional dark fleck
    for _ in range(4):
        x, y = random.randint(1, w - 2), random.randint(1, h - 2)
        px[x, y] = SHADOW
    return img


def draw_frame(img, color):
    d = ImageDraw.Draw(img)
    w, h = img.size
    d.rectangle([0, 0, w - 1, h - 1], outline=color)


def add_screws(img):
    px = img.load()
    # corners (inset 2px)
    for cx, cy in [(2, 2), (13, 13)]:
        px[cx, cy] = SHADOW
        px[cx + 1, cy] = RIM_DARK
        px[cx, cy + 1] = RIM_DARK
        px[cx + 1, cy + 1] = HI1


def gen_side():
    img = swirl_base(seed=42)
    draw_frame(img, RIM_DARK)
    add_screws(img)
    # one purple hint
    img.load()[8, 4] = PURPLE
    return img


def gen_back():
    # darker base
    img = swirl_base(base=RIM_DARK2, alt=RIM_DARK, dark=SHADOW, seed=7)
    draw_frame(img, SHADOW)
    px = img.load()
    # Ventilation slats: horizontal dark lines at y=4, 7, 10
    d = ImageDraw.Draw(img)
    for y in (4, 7, 10):
        d.line([(3, y), (12, y)], fill=SHADOW)
        # tiny highlight pixel above slat
        px[3, y - 1] = MID2
        px[12, y - 1] = MID2
    add_screws(img)
    return img


def gen_front():
    # mostly black with teal bezel ring (1px)
    img = Image.new("RGBA", (16, 16), BLACK)
    px = img.load()
    random.seed(17)
    # bezel ring
    for i in range(16):
        px[i, 0] = MID1
        px[i, 15] = MID1
        px[0, i] = MID1
        px[15, i] = MID1
    # corner glow / noise on bezel
    glow_pixels = [(0, 0), (15, 0), (0, 15), (15, 15)]
    for x, y in glow_pixels:
        px[x, y] = HI1
    # subtle noise on bezel
    for _ in range(8):
        side = random.choice(["t", "b", "l", "r"])
        if side == "t":
            px[random.randint(1, 14), 0] = random.choice([RIM_DARK2, MID2])
        elif side == "b":
            px[random.randint(1, 14), 15] = random.choice([RIM_DARK2, MID2])
        elif side == "l":
            px[0, random.randint(1, 14)] = random.choice([RIM_DARK2, MID2])
        else:
            px[15, random.randint(1, 14)] = random.choice([RIM_DARK2, MID2])
    return img


def main():
    import os
    os.makedirs(OUT_DIR, exist_ok=True)
    for name, gen in (("hd_monitor_side", gen_side),
                      ("hd_monitor_back", gen_back),
                      ("hd_monitor_front", gen_front)):
        img = gen()
        assert img.size == (16, 16)
        assert img.mode == "RGBA"
        path = f"{OUT_DIR}/{name}.png"
        img.save(path, "PNG")
        # verify reopen
        chk = Image.open(path)
        assert chk.size == (16, 16), f"bad size {chk.size}"
        print(f"wrote {path}  {chk.size}  {chk.mode}")


if __name__ == "__main__":
    main()
