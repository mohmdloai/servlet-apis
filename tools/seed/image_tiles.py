#!/usr/bin/env python3
"""Generate a placeholder tile per seeded listing image, into a tree that mirrors the object keys.

Usage (see tools/seed/README.md § "Image bytes for one org"):
    image_tiles.py <keys.tsv> <out-dir>
where keys.tsv is `object_key \\t sort_order \\t title` — one line per product_listing_image row.
Then `aws s3 sync` the tree into the bucket. Deliberately NOT part of run.sh: bytes never touch a
query plan, so the benchmark path must not pay for them.

Honest by construction: the tile shows the product's OWN name, so the picture can never contradict
the title beside it — the failure mode that made the landing-asset emoji mapper worthless. It is
obviously a placeholder, not a fake photograph.

SVG rather than JPEG: no image library is installed, the bytes are ~700 B instead of ~30 kB, and a
browser renders by Content-Type, not by the key's extension (the keys keep their .jpg names because
the DB rows already hold them and re-keying twice is churn). The uploader must therefore set
Content-Type: image/svg+xml explicitly.
"""
import colorsys
import html
import os
import sys

SRC = sys.argv[1]
OUT = sys.argv[2]

# Deterministic tint per listing so a gallery reads as one product, with a small lightness step per
# position so its thumbs are still distinguishable from each other.
def tint(listing_id, sort_order):
    h = (int(listing_id[:8], 16) % 360) / 360.0
    light = 0.90 - min(int(sort_order), 5) * 0.045
    r, g, b = colorsys.hls_to_rgb(h, light, 0.45)
    return "#%02X%02X%02X" % (int(r * 255), int(g * 255), int(b * 255))


def ink(listing_id):
    h = (int(listing_id[:8], 16) % 360) / 360.0
    r, g, b = colorsys.hls_to_rgb(h, 0.28, 0.55)
    return "#%02X%02X%02X" % (int(r * 255), int(g * 255), int(b * 255))


def wrap(text, width, lines):
    """Greedy wrap to `lines` lines of about `width` chars, ellipsing the overflow."""
    # Hard-break tokens longer than the line: ABO titles carry things like
    # "PlayStation-4-DualShock-4-Controller", and a greedy wrapper that only splits on spaces
    # emits one over-wide line that runs straight out of the tile.
    words = []
    for token in text.split():
        while len(token) > width:
            words.append(token[: width - 1] + "-")
            token = token[width - 1 :]
        if token:
            words.append(token)
    out, cur = [], ""
    for word in words:
        if len(cur) + len(word) + 1 <= width:
            cur = (cur + " " + word).strip()
        else:
            out.append(cur)
            cur = word
            if len(out) == lines:
                break
    if cur and len(out) < lines:
        out.append(cur)
    if not out:
        return ["Item"]
    if len(out) == lines and len(" ".join(words)) > sum(len(x) for x in out) + lines:
        out[-1] = out[-1][: width - 1] + "…"
    return out


made = 0
for raw in open(SRC, encoding="utf-8"):
    key, sort_order, title = raw.rstrip("\n").split("\t", 2)
    listing_id = key.split("/")[2]
    bg, fg = tint(listing_id, sort_order), ink(listing_id)
    lines = wrap(title, 22, 4)
    y0 = 300 - (len(lines) - 1) * 26
    spans = "".join(
        '<text x="300" y="%d" font-family="Helvetica,Arial,sans-serif" font-size="34" '
        'font-weight="600" fill="%s" text-anchor="middle">%s</text>'
        % (y0 + i * 52, fg, html.escape(line))
        for i, line in enumerate(lines)
    )
    badge = (
        '<circle cx="540" cy="60" r="26" fill="%s" opacity="0.35"/>'
        '<text x="540" y="72" font-family="Helvetica,Arial,sans-serif" font-size="28" '
        'fill="%s" text-anchor="middle">%s</text>' % (fg, fg, int(sort_order) + 1)
    )
    svg = (
        '<svg xmlns="http://www.w3.org/2000/svg" width="600" height="600" viewBox="0 0 600 600">'
        '<rect width="600" height="600" fill="%s"/>%s%s</svg>' % (bg, spans, badge)
    )
    path = os.path.join(OUT, key)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(svg)
    made += 1

print("tiles written:", made)
