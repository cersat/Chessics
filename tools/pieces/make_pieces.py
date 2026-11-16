#!/usr/bin/env python3
# Собственные наборы фигур Chessics: силуэты в SVG (viewBox 100×100) → PNG 160×160.
import os, subprocess, sys

OUT = sys.argv[1]
SVG_DIR = "/home/claude/own_pieces_svg"
os.makedirs(SVG_DIR, exist_ok=True)

BASE = "M24 92 Q24 82 34 80 L66 80 Q76 82 76 92 Z"

SHAPES = {
	"p": BASE + " M38 80 C40 66 42 56 44 50 L56 50 C58 56 60 66 62 80 Z"
		" M50 18 A13 13 0 1 1 49.9 18 Z M38 52 L62 52 L60 46 L40 46 Z",
	"r": BASE + " M32 80 L35 44 L65 44 L68 80 Z M27 44 L73 44 L73 22 L64 22 L64 30 L56 30 L56 22 L44 22 L44 30 L36 30 L36 22 L27 22 Z",
	"b": BASE + " M36 80 C38 70 40 64 42 60 L58 60 C60 64 62 70 64 80 Z"
		" M50 16 C64 26 66 40 60 54 L40 54 C34 40 36 26 50 16 Z M36 56 L64 56 L62 62 L38 62 Z"
		" M50 4 A5 5 0 1 1 49.9 4 Z",
	"n": BASE + " M30 80 L35 60 C30 56 26 50 28 44 L44 26 L46 14 L54 22 C66 26 74 38 74 54 L72 80 Z",
	"q": BASE + " M30 80 L26 30 L39 54 L42 22 L50 50 L58 22 L61 54 L74 30 L70 80 Z"
		" M26 22 A5 5 0 1 1 25.9 22 Z M42 14 A5 5 0 1 1 41.9 14 Z M58 14 A5 5 0 1 1 57.9 14 Z M74 22 A5 5 0 1 1 73.9 22 Z",
	"k": BASE + " M30 80 C28 64 26 52 30 42 C38 36 62 36 70 42 C74 52 72 64 70 80 Z"
		" M46 6 L54 6 L54 14 L62 14 L62 22 L54 22 L54 38 L46 38 L46 22 L38 22 L38 14 L46 14 Z",
}
# мелкие детали поверх силуэта (прорезь слона, глаз коня, ободок короны)
DETAILS = {
	"b": "M44 34 L56 34",
	"n": "M50 34 L56 34",
	"q": "M32 66 L68 66",
	"k": "M32 62 L68 62",
	"r": "M34 52 L66 52",
	"p": "",
}

def svg(body):
	return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" width="100" height="100">{body}</svg>'

def style_flat(p, white):
	fill, stroke = ("#F4F1EA", "#2B2B2B") if white else ("#2B2B2B", "#F4F1EA")
	d = DETAILS[p]
	return svg(f'<path d="{SHAPES[p]}" fill="{fill}" stroke="#1A1A1A" stroke-width="3" stroke-linejoin="round" fill-rule="nonzero"/>'
		+ (f'<path d="{d}" stroke="{stroke}" stroke-width="3" stroke-linecap="round"/>' if d else ""))

def style_ghost(p, white):
	fill = "#EAF2FF" if white else "#5B3D8C"
	glow = "#FFFFFF" if white else "#B69CFF"
	eye = "#2A2A3A" if white else "#F3E9FF"
	ey = {"p": 30, "r": 58, "b": 40, "n": 42, "q": 64, "k": 54}[p]
	ex = {"n": 6}.get(p, 0)
	return svg(
		'<defs><filter id="g" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="3"/></filter></defs>'
		f'<path d="{SHAPES[p]}" fill="{glow}" opacity="0.45" filter="url(#g)"/>'
		f'<path d="{SHAPES[p]}" fill="{fill}" fill-opacity="0.55" stroke="{glow}" stroke-opacity="0.8" stroke-width="2"/>'
		f'<ellipse cx="{44 + ex}" cy="{ey}" rx="3" ry="4" fill="{eye}" opacity="0.85"/>'
		f'<ellipse cx="{56 + ex}" cy="{ey}" rx="3" ry="4" fill="{eye}" opacity="0.85"/>')

def style_neon(p, white):
	c = "#33F3FF" if white else "#FF3BD5"
	d = DETAILS[p]
	return svg(
		'<defs><filter id="g" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="2.4"/></filter></defs>'
		f'<path d="{SHAPES[p]}" fill="none" stroke="{c}" stroke-width="6" opacity="0.7" filter="url(#g)"/>'
		f'<path d="{SHAPES[p]}" fill="#000000" fill-opacity="0.35" stroke="{c}" stroke-width="2.6" stroke-linejoin="round"/>'
		+ (f'<path d="{d}" stroke="{c}" stroke-width="2.6" stroke-linecap="round"/>' if d else ""))

def style_glass(p, white):
	a, b = ("#FFFFFF", "#B9D7F0") if white else ("#4C5B70", "#10161F")
	return svg(
		f'<defs><linearGradient id="l" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{a}"/><stop offset="1" stop-color="{b}"/></linearGradient>'
		'<linearGradient id="h" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFFFFF" stop-opacity="0.75"/><stop offset="0.5" stop-color="#FFFFFF" stop-opacity="0"/></linearGradient>'
		'<clipPath id="c"><path d="' + SHAPES[p] + '"/></clipPath></defs>'
		f'<path d="{SHAPES[p]}" fill="url(#l)" fill-opacity="0.88" stroke="{"#5E7C99" if white else "#9FB3C8"}" stroke-width="2"/>'
		'<rect x="20" y="0" width="34" height="100" fill="url(#h)" clip-path="url(#c)" transform="skewX(-12)"/>')

FOUR = {"r": "#D9453B", "b": "#3D7BD9", "y": "#E0B42A", "g": "#3FA65A"}

def style_four(p, color):
	d = DETAILS[p]
	return svg(f'<path d="{SHAPES[p]}" fill="{FOUR[color]}" stroke="#1A1A1A" stroke-width="3" stroke-linejoin="round"/>'
		+ (f'<path d="{d}" stroke="#FFFFFF" stroke-width="3" stroke-linecap="round" opacity="0.9"/>' if d else ""))

def render(name, body):
	svgp = f"{SVG_DIR}/{name}.svg"
	open(svgp, "w").write(body)
	subprocess.run(["rsvg-convert", "-w", "160", "-h", "160", svgp, "-o", f"{OUT}/{name}.png"], check=True)

for p in SHAPES:
	for white in (True, False):
		c = "w" if white else "b"
		render(f"pc_flat_{c}{p}", style_flat(p, white))
		render(f"pc_ghost_{c}{p}", style_ghost(p, white))
		render(f"pc_neon_{c}{p}", style_neon(p, white))
		render(f"pc_glass_{c}{p}", style_glass(p, white))
		# пиксели: плоский силуэт → 16×16 без сглаживания → обратно 160×160
		subprocess.run(["convert", f"{OUT}/pc_flat_{c}{p}.png", "-filter", "point", "-resize", "16x16", "-channel", "A", "-threshold", "50%", "+channel",
			"-filter", "point", "-resize", "160x160", f"{OUT}/pc_pixel8_{c}{p}.png"], check=True)
	for col in FOUR:
		render(f"pc_four_{col}{p}", style_four(p, col))
print("ok")

# Пиксели (переделка): заливка силуэта в 20×20, контур в 1 пиксель, увеличение ×8 без сглаживания
def pixel(p, white):
	fill, line = ("#F4F1EA", "#1E1E1E") if white else ("#3A3A3A", "#0E0E0E")
	tmp = f"/tmp/px_{p}"
	open(tmp + ".svg", "w").write(svg(f'<path d="{SHAPES[p]}" fill="#000"/>'))
	subprocess.run(["rsvg-convert", "-w", "20", "-h", "20", tmp + ".svg", "-o", tmp + "_s.png"], check=True)
	subprocess.run(["convert", tmp + "_s.png", "-alpha", "extract", "-threshold", "45%", tmp + "_m.png"], check=True)
	subprocess.run(["convert", tmp + "_m.png", "-morphology", "Dilate", "Diamond:1", tmp + "_d.png"], check=True)
	name = f"pc_pixel8_{'w' if white else 'b'}{p}"
	subprocess.run(["convert", "-size", "20x20", "xc:none",
		"(", "-size", "20x20", f"xc:{line}", tmp + "_d.png", "-alpha", "off", "-compose", "CopyOpacity", "-composite", ")", "-compose", "Over", "-composite",
		"(", "-size", "20x20", f"xc:{fill}", tmp + "_m.png", "-alpha", "off", "-compose", "CopyOpacity", "-composite", ")", "-compose", "Over", "-composite",
		"-filter", "point", "-resize", "160x160", f"{OUT}/{name}.png"], check=True)

for p in SHAPES:
	pixel(p, True); pixel(p, False)
print("pixel ok")
