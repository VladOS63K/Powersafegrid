#!/usr/bin/env python3
"""Generates all Powersafegrid textures (16x16 blocks + GUI) as PNG files.

Pure stdlib: writes RGBA PNGs via zlib/struct.
Run from the project root:  python3 tools/gen_textures.py
"""
import struct, zlib, os

OUT = "src/main/resources/assets/powersafegrid/textures"

def png(path, pixels, w, h):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    raw = b"".join(b"\x00" + bytes(v for px in row for v in px) for row in pixels)
    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)
    data = (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(data)

def grid(w, h, fill):
    return [[fill for _ in range(w)] for _ in range(h)]

def rect(px, x1, y1, x2, y2, color):
    for y in range(y1, y2):
        for x in range(x1, x2):
            px[y][x] = color

# 3x5 pixel font for digits (rows of bits, MSB left)
FONT = {
    "0": ["111","101","101","101","111"],
    "1": ["010","110","010","010","111"],
    "2": ["111","001","111","100","111"],
    "4": ["101","101","111","001","001"],
    "V": ["101","101","101","101","010"],
    "A": ["111","101","111","101","101"],
    "P": ["111","101","111","100","100"],
    "G": ["111","100","101","101","111"],
    "S": ["111","100","111","001","111"],
}

def draw_text(px, text, ox, oy, color):
    x = ox
    for ch in text.upper():
        if ch == " ":
            x += 2
            continue
        glyph = FONT.get(ch)
        if glyph:
            for gy, row in enumerate(glyph):
                for gx, bit in enumerate(row):
                    if bit == "1":
                        px[oy + gy][x + gx] = color
        x += 4

# Palette
METAL      = (150, 150, 155, 255)
METAL_D    = (110, 110, 115, 255)
METAL_L    = (175, 175, 180, 255)
PANEL      = (185, 185, 188, 255)
DARK       = (40, 40, 44, 255)
COPPER     = (184, 115, 51, 255)
COPPER_D   = (140, 85, 40, 255)
LED_GREEN  = (70, 220, 80, 255)
LED_RED    = (230, 60, 50, 255)
LED_AMBER  = (235, 180, 40, 255)
WHITE      = (235, 235, 235, 255)
FIN        = (125, 125, 130, 255)

def noisy(px, seed=0):
    for y in range(16):
        for x in range(16):
            r, g, b, a = px[y][x]
            n = ((x * 7 + y * 13 + seed * 31) % 5) - 2
            px[y][x] = (max(0, min(255, r + n * 4)), max(0, min(255, g + n * 4)), max(0, min(255, b + n * 4)), a)

def metal_panel(frame=METAL_D):
    px = grid(16, 16, PANEL)
    rect(px, 0, 0, 16, 1, METAL_L)
    rect(px, 0, 0, 1, 16, METAL_L)
    rect(px, 0, 15, 16, 16, frame)
    rect(px, 15, 0, 16, 16, frame)
    noisy(px)
    return px

def screws(px, positions=((2, 2), (13, 2), (2, 13), (13, 13))):
    for (x, y) in positions:
        px[y][x] = METAL_D
        px[y][x + 0] = METAL_D

B = f"{OUT}/block"

# ---- Relay side / top / bottom / back ----
px = metal_panel()
screws(px)
png(f"{B}/relay_side.png", px, 16, 16)

px = metal_panel()
rect(px, 3, 5, 13, 11, METAL_D)      # vent slot block
for x in range(4, 12, 2):
    rect(px, x, 6, x + 1, 10, DARK)   # vent slots
png(f"{B}/relay_top.png", px, 16, 16)

px = grid(16, 16, METAL_D)
noisy(px, 1)
png(f"{B}/relay_bottom.png", px, 16, 16)

px = metal_panel()
# two terminal insulators on the back
for x in (4, 11):
    rect(px, x - 1, 5, x + 2, 8, DARK)
    rect(px, x, 6, x + 1, 7, COPPER)
screws(px)
png(f"{B}/relay_back.png", px, 16, 16)

# ---- Relay front (LED on = green, off/trip = red) ----
def relay_front(led):
    px = metal_panel()
    rect(px, 3, 3, 13, 9, DARK)       # display window
    rect(px, 4, 4, 12, 8, (25, 25, 30, 255))
    # tiny dial
    rect(px, 5, 6, 11, 7, LED_AMBER if led == LED_GREEN else (60, 60, 40, 255))
    rect(px, 5, 6, 6, 7, led)
    # LED
    rect(px, 6, 11, 10, 13, METAL_D)
    rect(px, 7, 12, 9, 13, led)
    return px

png(f"{B}/relay_front_on.png", relay_front(LED_GREEN), 16, 16)
png(f"{B}/relay_front_off.png", relay_front(LED_RED), 16, 16)

# ---- Stabilizer ----
def stab_side():
    px = metal_panel()
    for y in range(2, 14, 3):
        rect(px, 1, y, 15, y + 1, FIN)
    screws(px)
    return px

png(f"{B}/stabilizer_side.png", stab_side(), 16, 16)

px = metal_panel()
for x in range(3, 13, 2):
    rect(px, x, 4, x + 1, 12, DARK)
png(f"{B}/stabilizer_top.png", px, 16, 16)

px = grid(16, 16, METAL_D)
noisy(px, 2)
png(f"{B}/stabilizer_bottom.png", px, 16, 16)

px = metal_panel()
for x in (4, 11):
    rect(px, x - 1, 6, x + 2, 9, DARK)
    rect(px, x, 7, x + 1, 8, COPPER)
screws(px)
png(f"{B}/stabilizer_back.png", px, 16, 16)

def stab_front(volt, tripped):
    px = metal_panel()
    rect(px, 2, 6, 14, 11, DARK)          # label plate
    rect(px, 2, 6, 14, 7, METAL_L)
    draw_text(px, str(volt), 3, 8, WHITE)
    draw_text(px, "V", 12, 8, (200, 170, 40, 255))
    led = LED_RED if tripped else LED_GREEN
    rect(px, 6, 2, 10, 4, METAL_D)
    rect(px, 7, 3, 9, 4, led)
    return px

for volt in (120, 240):
    png(f"{B}/stabilizer_front_{volt}.png", stab_front(volt, False), 16, 16)
    png(f"{B}/stabilizer_front_{volt}_t.png", stab_front(volt, True), 16, 16)

# ---- Terminal prong texture ----
px = grid(16, 16, COPPER)
rect(px, 0, 0, 16, 1, COPPER_D)
rect(px, 0, 15, 16, 16, COPPER_D)
png(f"{B}/terminal.png", px, 16, 16)

# ---- GUI: relay settings screen background 206x108 ----
W, H = 206, 108
px = grid(W, H, (198, 198, 200, 255))
rect(px, 0, 0, W, 1, (255, 255, 255, 255))
rect(px, 0, 0, 1, H, (255, 255, 255, 255))
rect(px, 0, H - 1, W, H, (85, 85, 85, 255))
rect(px, W - 1, 0, W, H, (85, 85, 85, 255))
# title strip
rect(px, 1, 1, W - 1, 2, (222, 222, 224, 255))
png(f"{OUT}/gui/voltage_relay.png", px, W, H)

print("textures written")
