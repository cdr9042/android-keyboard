"""Redraw a VNI swipe from 'VNIDUMP' logcat lines as an SVG.

Usage: python3 -I vni_swipe_plot.py <logfile> <out.svg>
Uses the last 'VNIDUMP keys' line and the 'VNIDUMP path' lines after it.
Mirrors VniSwipe.pickedTones so the picked tones / turn points can be seen.
"""
import math
import sys

MIN_TURN = 60.0


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


def turn(p, a, b, c):
    ux, uy = p[b][0] - p[a][0], p[b][1] - p[a][1]
    vx, vy = p[c][0] - p[b][0], p[c][1] - p[b][1]
    n = math.hypot(ux, uy) * math.hypot(vx, vy)
    if n == 0:
        return 0.0
    return math.degrees(math.acos(max(-1.0, min(1.0, (ux * vx + uy * vy) / n))))


def picked(points, digits, min_step):
    out, turns = set(), []
    if not points:
        return out, turns, []
    for i in (0, len(points) - 1):
        if digits[i]:
            out.add(digits[i])
    kept = [0]
    for i in range(1, len(points)):
        j = kept[-1]
        if math.hypot(points[i][0] - points[j][0], points[i][1] - points[j][1]) >= min_step:
            kept.append(i)
    for j, idx in enumerate(kept):
        if not digits[idx]:
            continue
        best = max((turn(points, kept[j - w], idx, kept[j + w])
                    for w in (1, 2) if j - w >= 0 and j + w < len(kept)), default=0.0)
        turns.append((idx, digits[idx], best))
        if best >= MIN_TURN:
            out.add(digits[idx])
    return out, turns, kept


def main(log_path, svg_path):
    with open(log_path, encoding="utf-8", errors="replace") as f:
        w, h, keys, segs = parse(f.read().splitlines())
    tone_keys = [k for k in keys if tone_digit(k[0])]
    min_step = (tone_keys[0][3] - tone_keys[0][1]) / 3 if tone_keys else 30

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
        digits = [digit_at(x, y) for x, y, _ in pts]
        tones, turns, kept = picked(pts, digits, min_step)
        line = " ".join(f"{x},{y}" for x, y, _ in pts)
        svg.append(f'<polyline points="{line}" fill="none" stroke="#1e88e5" stroke-width="6" opacity="0.7"/>')
        for x, y, _ in [pts[i] for i in kept]:
            svg.append(f'<circle cx="{x}" cy="{y}" r="5" fill="#1e88e5"/>')
        svg.append(f'<circle cx="{pts[0][0]}" cy="{pts[0][1]}" r="14" fill="#43a047"/>')
        svg.append(f'<circle cx="{pts[-1][0]}" cy="{pts[-1][1]}" r="14" fill="#212121"/>')
        for idx, d, ang in turns:
            if ang < MIN_TURN:
                continue
            x, y, _ = pts[idx]
            svg.append(f'<circle cx="{x}" cy="{y}" r="22" fill="none" stroke="#e53935" stroke-width="5"/>')
        swept = sorted({d for d in digits if d})
        max_turn = {}
        for _, d, ang in turns:
            max_turn[d] = max(max_turn.get(d, 0.0), ang)
        report.append(f"pointer {pid}: {len(pts)} points, swept tone keys {swept}, picked {sorted(tones)}, "
                      f"max turn per tone key (threshold {MIN_TURN:.0f}): "
                      + ", ".join(f"{d}={a:.0f}" for d, a in sorted(max_turn.items())))
        on_tone = [(x, y) for (x, y, _), d in zip(pts, digits) if d]
        report.append(f"pointer {pid}: start {pts[0][:2]} end {pts[-1][:2]}, points on tone row {len(on_tone)}")
    svg.append("</svg>")
    with open(svg_path, "w", encoding="utf-8") as f:
        f.write("\n".join(svg))
    print("\n".join(report))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
