"""Redraw a VNI swipe from 'VNIDUMP' logcat lines as an SVG.

Usage: python3 -I vni_swipe_plot.py <logfile> <out.svg>
Uses the last 'VNIDUMP keys' line and the 'VNIDUMP path' lines after it.
Mirrors VniSwipe.splitTones: tones are picked where the finger slows down on them, and each trip
to the tone row is cut between the letters the finger slowed down on. Points sent to the model are
blue, cut points grey, slow points on tone keys circled red.
"""
import math
import sys

SLOW_KEY_WIDTHS_PER_MS = 0.005
SPEED_WINDOW_MS = 30
REST_MS = 60


def parse(lines):
    keys_idx = max(i for i, l in enumerate(lines) if "VNIDUMP keys" in l)
    head = lines[keys_idx].split("VNIDUMP keys ", 1)[1].strip()
    size, _, keys_txt = head.partition(" ")
    w, h = (int(v) for v in size.split("x"))
    keys = []
    for k in keys_txt.split(";"):
        code, l, t, r, b = (int(v) for v in k.split(","))
        keys.append((code, l, t, r, b))
    segs = {}
    for l in lines[keys_idx + 1:]:
        if "VNIDUMP path" not in l:
            continue
        pid, _part, pts = l.split("VNIDUMP path ", 1)[1].strip().split(" ", 2)
        for p in pts.split(";"):
            x, y, t = (int(v) for v in p.split(","))
            segs.setdefault(pid, []).append((x, y, t))
    return w, h, keys, segs


def tone_digit(code):
    return code - 0xFF10 if 0xFF11 <= code <= 0xFF19 else 0


def speeds(points):
    """px/ms around each point, like VniSwipe.slowPoints."""
    n, out, a, b = len(points), [], 0, 0
    for i in range(n):
        t = points[i][2]
        while t - points[a][2] > SPEED_WINDOW_MS:
            a += 1
        b = max(b, i)
        while b + 1 < n and points[b + 1][2] - t <= SPEED_WINDOW_MS:
            b += 1
        lo, hi = min(a, max(i - 1, 0)), max(b, min(i + 1, n - 1))
        dist = math.hypot(points[hi][0] - points[lo][0], points[hi][1] - points[lo][1])
        dt = points[hi][2] - points[lo][2]
        out.append(dist / dt if dt > 0 else (0.0 if dist == 0 else math.inf))
    return out


def split(points, digits, key_width):
    """(picked tones, keep flags, slow flags), like VniSwipe.splitTones."""
    n = len(points)
    keep = [d == 0 for d in digits]
    if not n:
        return set(), keep, []
    slow = [v < key_width * SLOW_KEY_WIDTHS_PER_MS for v in speeds(points)]
    # Resting: slow for at least REST_MS in a row, like VniSwipe.restingPoints
    first = 0
    rest = [False] * n
    while first < n:
        if not slow[first]:
            first += 1
            continue
        last = first
        while last + 1 < n and slow[last + 1]:
            last += 1
        if points[last][2] - points[first][2] >= REST_MS:
            for i in range(first, last + 1):
                rest[i] = True
        first = last + 1
    slow = rest
    out = {digits[i] for i in (0, n - 1) if digits[i]}
    out |= {digits[i] for i in range(n) if slow[i] and digits[i]}
    rests = [slow[i] and not digits[i] for i in range(n)]
    start = 0
    while start < n:
        if not digits[start]:
            start += 1
            continue
        end = start
        while end + 1 < n and digits[end + 1]:
            end += 1
        frm = next((i for i in range(start - 1, -1, -1) if rests[i]), start - 1)
        to = next((i for i in range(end + 1, n) if rests[i]), end + 1)
        for i in range(frm + 1, to):
            keep[i] = False
        start = end + 1
    return out, keep, slow


def main(log_path, svg_path):
    with open(log_path, encoding="utf-8", errors="replace") as f:
        w, h, keys, segs = parse(f.read().splitlines())
    tone_keys = [k for k in keys if tone_digit(k[0])]
    key_width = (tone_keys[0][3] - tone_keys[0][1]) if tone_keys else 100

    def key_at(x, y):
        for code, l, t, r, b in keys:
            if l <= x < r and t <= y < b:
                return str(tone_digit(code)) if tone_digit(code) else chr(code)
        return "?"

    def digit_at(x, y):
        for code, l, t, r, b in tone_keys:
            if l <= x < r and t <= y < b:
                return tone_digit(code)
        return 0

    svg = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" height="{h}">',
           f'<rect width="{w}" height="{h}" fill="#fff"/>']
    for code, l, t, r, b in keys:
        d = tone_digit(code)
        fill = "#ffe0b2" if d else "#eceff1"
        label = str(d) if d else (chr(code) if 32 < code < 0x110000 else str(code))
        svg.append(f'<rect x="{l}" y="{t}" width="{r - l}" height="{b - t}" fill="{fill}" stroke="#90a4ae"/>')
        svg.append(f'<text x="{(l + r) / 2}" y="{(t + b) / 2}" font-size="{(b - t) * 0.35}" '
                   f'text-anchor="middle" dominant-baseline="middle" fill="#455a64">{label}</text>')

    report = []
    for pid, pts in segs.items():
        pts.sort(key=lambda p: p[2])
        digits = [digit_at(x, y) for x, y, _ in pts]
        tones, keep, slow = split(pts, digits, key_width)
        line = " ".join(f"{x},{y}" for x, y, _ in pts)
        svg.append(f'<polyline points="{line}" fill="none" stroke="#b0bec5" stroke-width="6" opacity="0.7"/>')
        for (x, y, _), k in zip(pts, keep):
            svg.append(f'<circle cx="{x}" cy="{y}" r="5" fill="{"#1e88e5" if k else "#90a4ae"}"/>')
        svg.append(f'<circle cx="{pts[0][0]}" cy="{pts[0][1]}" r="14" fill="#43a047"/>')
        svg.append(f'<circle cx="{pts[-1][0]}" cy="{pts[-1][1]}" r="14" fill="#212121"/>')
        for (x, y, _), d, s_ in zip(pts, digits, slow):
            if d and s_:
                svg.append(f'<circle cx="{x}" cy="{y}" r="22" fill="none" stroke="#e53935" stroke-width="5"/>')
        swept = sorted({d for d in digits if d})
        model_keys = []
        for (x, y, _), k in zip(pts, keep):
            name = key_at(x, y)
            if k and (not model_keys or model_keys[-1] != name):
                model_keys.append(name)
        report.append(f"pointer {pid}: {len(pts)} points, swept tone keys {swept}, picked {sorted(tones)}")
        report.append(f"pointer {pid}: model sees keys {''.join(model_keys)} "
                      f"({sum(keep)}/{len(pts)} points kept), slow threshold "
                      f"{key_width * SLOW_KEY_WIDTHS_PER_MS:.2f} px/ms")
    svg.append("</svg>")
    with open(svg_path, "w", encoding="utf-8") as f:
        f.write("\n".join(svg))
    print("\n".join(report))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
