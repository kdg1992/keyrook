#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generates the Keyrook application icon from its geometry, using the Python 3 standard library only.

The icon is a light rook with a keyhole on a dark rounded square. The same shapes produce the SVG source and every
raster file, so the committed assets can be rebuilt byte for byte:

    python3 scripts/generate-icons.py           # write all files
    python3 scripts/generate-icons.py --check   # fail if a committed file differs from the generated one
"""
import argparse
import os
import struct
import sys
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ICONS = os.path.join(ROOT, "app", "icons")
RESOURCE = os.path.join(ROOT, "app", "src", "main", "resources", "app", "keyrook", "app", "keyrook-icon.png")

BACKGROUND = (0x1D, 0x4E, 0x6B)
FOREGROUND = (0xF2, 0xEF, 0xE6)

# Geometry in units of the icon edge (0..1). Rectangles are (left, top, right, bottom).
TILE = (0.04, 0.04, 0.96, 0.96)
TILE_RADIUS = 0.2
ROOK = [
    (0.28, 0.20, 0.72, 0.33),  # battlement band, notched below
    (0.35, 0.33, 0.65, 0.68),  # tower
    (0.29, 0.68, 0.71, 0.74),  # upper base step
    (0.24, 0.74, 0.76, 0.80),  # lower base step
]
NOTCHES = [(0.385, 0.20, 0.445, 0.26), (0.555, 0.20, 0.615, 0.26)]
KEYHOLE_CENTER = (0.5, 0.45)
KEYHOLE_RADIUS = 0.055
# Keyhole slot as a trapezoid: top y, bottom y, half width at the top, half width at the bottom.
KEYHOLE_SLOT = (0.46, 0.60, 0.022, 0.042)

PNG_SIZES = {"keyrook.png": 512}
ICO_SIZES = [16, 24, 32, 48, 64, 128, 256]
ICNS_TYPES = [(b"ic07", 128), (b"ic08", 256), (b"ic09", 512), (b"ic10", 1024)]
RESOURCE_SIZE = 256
SUPERSAMPLING = 4


def inside_rect(rect, x, y):
    return rect[0] <= x < rect[2] and rect[1] <= y < rect[3]


def inside_tile(x, y):
    left, top, right, bottom = TILE
    if not inside_rect(TILE, x, y):
        return False
    cx = min(max(x, left + TILE_RADIUS), right - TILE_RADIUS)
    cy = min(max(y, top + TILE_RADIUS), bottom - TILE_RADIUS)
    return (x - cx) ** 2 + (y - cy) ** 2 <= TILE_RADIUS ** 2


def inside_keyhole(x, y):
    cx, cy = KEYHOLE_CENTER
    if (x - cx) ** 2 + (y - cy) ** 2 <= KEYHOLE_RADIUS ** 2:
        return True
    top, bottom, top_half, bottom_half = KEYHOLE_SLOT
    if not top <= y < bottom:
        return False
    half = top_half + (bottom_half - top_half) * (y - top) / (bottom - top)
    return abs(x - cx) <= half


def classify(x, y):
    """0 = transparent, 1 = background tile, 2 = rook."""
    if not inside_tile(x, y):
        return 0
    if any(inside_rect(r, x, y) for r in ROOK) and not any(inside_rect(n, x, y) for n in NOTCHES) and not inside_keyhole(x, y):
        return 2
    return 1


COLORS = {0: (0, 0, 0, 0), 1: BACKGROUND + (255,), 2: FOREGROUND + (255,)}


def render(size):
    """Returns RGBA rows. Pixels whose corners agree take that class; edge pixels are supersampled."""
    corners = [[classify(i / size, j / size) for i in range(size + 1)] for j in range(size + 1)]
    rows = []
    n = SUPERSAMPLING
    for j in range(size):
        row = bytearray()
        for i in range(size):
            c = corners[j][i]
            if c == corners[j][i + 1] == corners[j + 1][i] == corners[j + 1][i + 1] == classify((i + 0.5) / size, (j + 0.5) / size):
                row.extend(COLORS[c])
                continue
            r = g = b = a = 0
            for sj in range(n):
                for si in range(n):
                    cr, cg, cb, ca = COLORS[classify((i + (si + 0.5) / n) / size, (j + (sj + 0.5) / n) / size)]
                    r += cr * ca
                    g += cg * ca
                    b += cb * ca
                    a += ca
            if a == 0:
                row.extend((0, 0, 0, 0))
            else:
                row.extend((round(r / a), round(g / a), round(b / a), round(a / (n * n))))
        rows.append(bytes(row))
    return rows


def png(size):
    rows = render(size)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    raw = b"".join(b"\x00" + row for row in rows)
    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


def ico(images):
    """ICO with PNG-compressed entries; a stored 0 means 256 pixels."""
    header = struct.pack("<HHH", 0, 1, len(images))
    offset = len(header) + 16 * len(images)
    entries, data = b"", b""
    for size, image in images:
        dimension = 0 if size >= 256 else size
        entries += struct.pack("<BBBBHHII", dimension, dimension, 0, 0, 1, 32, len(image), offset + len(data))
        data += image
    return header + entries + data


def icns(images):
    body = b"".join(kind + struct.pack(">I", 8 + len(image)) + image for kind, image in images)
    return b"icns" + struct.pack(">I", 8 + len(body)) + body


def svg():
    def unit(value):
        return "%g" % round(value * 1024, 3)

    def rect(r, color, radius=None):
        extra = ' rx="%s"' % unit(radius) if radius else ""
        return '  <rect x="%s" y="%s" width="%s" height="%s"%s fill="%s"/>\n' % (
            unit(r[0]), unit(r[1]), unit(r[2] - r[0]), unit(r[3] - r[1]), extra, color)

    background = "#%02X%02X%02X" % BACKGROUND
    foreground = "#%02X%02X%02X" % FOREGROUND
    top, bottom, top_half, bottom_half = KEYHOLE_SLOT
    cx, cy = KEYHOLE_CENTER
    slot = " ".join("%s,%s" % (unit(px), unit(py)) for px, py in [
        (cx - top_half, top), (cx + top_half, top), (cx + bottom_half, bottom), (cx - bottom_half, bottom)])
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        "<!--\n"
        "  SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt\n"
        "  SPDX-License-Identifier: GPL-3.0-or-later\n"
        "  Keyrook application icon: original project artwork, not third-party material.\n"
        "  Generated by scripts/generate-icons.py together with the PNG, ICO and ICNS files; edit the geometry there.\n"
        "-->\n"
        '<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">\n'
        "  <title>Keyrook</title>\n"
        + rect(TILE, background, TILE_RADIUS)
        + "".join(rect(r, foreground) for r in ROOK)
        + "".join(rect(n, background) for n in NOTCHES)
        + '  <circle cx="%s" cy="%s" r="%s" fill="%s"/>\n' % (unit(cx), unit(cy), unit(KEYHOLE_RADIUS), background)
        + '  <polygon points="%s" fill="%s"/>\n' % (slot, background)
        + "</svg>\n"
    ).encode("utf-8")


def outputs():
    cache = {}

    def image(size):
        if size not in cache:
            cache[size] = png(size)
        return cache[size]

    files = {os.path.join(ICONS, "keyrook.svg"): svg()}
    for name, size in PNG_SIZES.items():
        files[os.path.join(ICONS, name)] = image(size)
    files[os.path.join(ICONS, "keyrook.ico")] = ico([(size, image(size)) for size in ICO_SIZES])
    files[os.path.join(ICONS, "keyrook.icns")] = icns([(kind, image(size)) for kind, size in ICNS_TYPES])
    files[RESOURCE] = image(RESOURCE_SIZE)
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="compare instead of writing")
    check = parser.parse_args().check
    stale = []
    for path, data in outputs().items():
        relative = os.path.relpath(path, ROOT)
        if check:
            try:
                with open(path, "rb") as existing:
                    if existing.read() != data:
                        stale.append(relative)
            except OSError:
                stale.append(relative)
        else:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "wb") as target:
                target.write(data)
            print("wrote", relative)
    if stale:
        print("Icon files differ from their geometry: " + ", ".join(stale), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
