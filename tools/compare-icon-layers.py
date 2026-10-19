#!/usr/bin/env python3
"""Compare the two launcher-icon layers of LuxAlarm as silhouettes.

  colour layer    app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.webp
                  432x432 artwork (1 artwork px = 0.25 viewport units)
  themed layer    app/src/main/res/drawable/ic_launcher_monochrome.xml
                  108x108 VectorDrawable, one flat colour, tinted by the system

The launcher shows both side by side, so they have to read as the same icon.
This tool measures the geometry of both layers in viewport units and reports
where they disagree:

  ring    outer/inner edge radius as a function of the angle around the clock
  sun     bounding box of the sun's ink right of the clock centre + its flat
          bottom edge
  rays    the thin components outside the ring band, with their end points
  ticks   the small components inside the face, with their centres

Exit code 0 = the two layers agree within the tolerances, 1 = they do not.
Tolerances (viewport units): --ring-tol (default 1.0), --sun-tol and
--ray-tol (default 1.5).  --tolerance overrides both at once.

Only Pillow and numpy are required.
"""

from __future__ import annotations

import argparse
import hashlib
import math
import os
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

import numpy as np
from PIL import Image

ANDROID = "{http://schemas.android.com/apk/res/android}"
SVGNS = "http://www.w3.org/2000/svg"

VIEW = 108  # the VectorDrawable viewport, in viewport units
SS = 4  # artwork pixels per viewport unit (432 / 108)
INK_WHITE = 205  # a colour pixel counts as ink when its darkest channel is below this
COV_MIN = 0.5  # coverage at which a pixel counts as solid ink
FACE_MARGIN = 0.75  # the face region stops this far inside the ring's inner edge
TICK_MAX_AREA = 14  # a component this small inside the face is a tick mark
RAY_MAX_AREA = 32  # a component this small outside the ring is a sun ray
RAY_MAX_THICK = 2.5  # ... and it has to be thin
RAY_MIN_LEN = 0.9  # ... and at least this long
RAY_RADIAL = 0.5  # a dash has to point away from the sun (cos of the angle)

OUT_DIR = "/tmp/opencode"
AVD2SVG = "/tmp/opencode/avd2svg.py"


# --------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------


def project_root() -> str:
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.dirname(here)
    if not os.path.exists(os.path.join(root, "gradlew")):
        raise SystemExit("cannot find the project root (no gradlew next to %s)" % here)
    return root


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def run(cmd) -> None:
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if proc.returncode != 0:
        raise SystemExit(
            "command failed (%d): %s\n%s"
            % (proc.returncode, " ".join(cmd), proc.stdout.decode("utf-8", "replace"))
        )


def downsample(arr: np.ndarray, factor: int = SS) -> np.ndarray:
    """Box-average an (N*factor) square array down to N x N."""
    n = arr.shape[0] // factor
    return arr[: n * factor, : n * factor].reshape(n, factor, n, factor).mean(axis=(1, 3))


def bilinear(arr: np.ndarray, xs: np.ndarray, ys: np.ndarray) -> np.ndarray:
    """Sample an array at float coordinates (clamped at the edges)."""
    h, w = arr.shape
    x0 = np.clip(np.floor(xs).astype(int), 0, w - 1)
    y0 = np.clip(np.floor(ys).astype(int), 0, h - 1)
    x1 = np.clip(x0 + 1, 0, w - 1)
    y1 = np.clip(y0 + 1, 0, h - 1)
    fx = np.clip(xs - x0, 0.0, 1.0)
    fy = np.clip(ys - y0, 0.0, 1.0)
    return (
        arr[y0, x0] * (1 - fx) * (1 - fy)
        + arr[y0, x1] * fx * (1 - fy)
        + arr[y1, x0] * (1 - fx) * fy
        + arr[y1, x1] * fx * fy
    )


def erode(mask: np.ndarray) -> np.ndarray:
    """One 3x3 erosion (4-connectivity)."""
    out = mask.copy()
    out[1:, :] &= mask[:-1, :]
    out[:-1, :] &= mask[1:, :]
    out[:, 1:] &= mask[:, :-1]
    out[:, :-1] &= mask[:, 1:]
    return out


def erode_n(mask: np.ndarray, n: int) -> np.ndarray:
    for _ in range(n):
        mask = erode(mask)
    return mask


def components(mask: np.ndarray):
    """8-connected components -> list of (area, ys, xs)."""
    lab = -np.ones(mask.shape, int)
    out = []
    ny, nx = mask.shape
    for y in range(ny):
        for x in range(nx):
            if not mask[y, x] or lab[y, x] >= 0:
                continue
            idx = len(out)
            stack = [(y, x)]
            lab[y, x] = idx
            pts = []
            while stack:
                cy, cx = stack.pop()
                pts.append((cy, cx))
                for dy in (-1, 0, 1):
                    for dx in (-1, 0, 1):
                        ny2, nx2 = cy + dy, cx + dx
                        if 0 <= ny2 < ny and 0 <= nx2 < nx and mask[ny2, nx2] and lab[ny2, nx2] < 0:
                            lab[ny2, nx2] = idx
                            stack.append((ny2, nx2))
            arr = np.array(pts)
            out.append((len(pts), arr[:, 0], arr[:, 1]))
    return out


def bbox(ys, xs):
    return float(xs.min()), float(ys.min()), float(xs.max()) + 1.0, float(ys.max()) + 1.0


def fit_circle(xs, ys, centre):
    """Geometric circle fit (Gauss-Newton), returns (cx, cy, r).

    Unlike an algebraic fit this stays stable when the points only cover
    part of the circle, which is exactly the case for a launcher icon ring.
    """
    cx, cy = float(centre[0]), float(centre[1])
    r = float(np.median(np.hypot(xs - cx, ys - cy)))
    for _ in range(40):
        d = np.hypot(xs - cx, ys - cy)
        d = np.where(d < 1e-6, 1e-6, d)
        res = d - r
        jac = np.stack([(cx - xs) / d, (cy - ys) / d, -np.ones_like(d)], axis=1)
        delta, *_ = np.linalg.lstsq(jac, -res, rcond=None)
        cx += delta[0]
        cy += delta[1]
        r += delta[2]
    return cx, cy, r


def thickness(mask: np.ndarray) -> int:
    """How many erosions a mask survives (a cheap stand-in for a distance map)."""
    n = 0
    cur = mask
    while cur.any():
        cur = erode(cur)
        n += 1
        if n > 12:
            break
    return n


# --------------------------------------------------------------------------
# loading the two layers
# --------------------------------------------------------------------------


def load_art(path: str, ink_white: int = INK_WHITE) -> np.ndarray:
    """Colour artwork -> 108x108 coverage map (1.0 = solid ink)."""
    img = Image.open(path).convert("RGBA")
    if img.size != (VIEW * SS, VIEW * SS):
        img = img.resize((VIEW * SS, VIEW * SS), Image.LANCZOS)
    a = np.asarray(img).astype(np.int16)
    opaque = a[:, :, 3] > 127
    ink = opaque & (a[:, :, :3].min(axis=2) < ink_white)
    return downsample(ink.astype(np.float64))


def avd_to_svg(src: str, dst: str, bg: str) -> None:
    """VectorDrawable -> SVG. Uses the shared converter when it is around."""
    if os.path.exists(AVD2SVG):
        run([sys.executable, AVD2SVG, src, dst, bg])
    else:
        _avd_to_svg_fallback(src, dst, bg)


def _avd_to_svg_fallback(src: str, dst: str, bg: str) -> None:
    """Minimal VectorDrawable -> SVG for the subset this icon uses."""
    root = ET.parse(src).getroot()
    vw = float(root.get(ANDROID + "viewportWidth", VIEW))
    vh = float(root.get(ANDROID + "viewportHeight", VIEW))
    out = [
        '<svg xmlns="%s" width="%d" height="%d" viewBox="0 0 %g %g">'
        % (SVGNS, VIEW * SS, VIEW * SS, vw, vh),
        '<rect width="%g" height="%g" fill="%s"/>' % (vw, vh, bg),
    ]

    def group_transform(g):
        parts = []
        px, py = g.get(ANDROID + "pivotX"), g.get(ANDROID + "pivotY")
        rot = g.get(ANDROID + "rotation")
        sx = g.get(ANDROID + "scaleX")
        sy = g.get(ANDROID + "scaleY")
        tx = g.get(ANDROID + "translateX")
        ty = g.get(ANDROID + "translateY")
        if tx or ty:
            parts.append("translate(%g,%g)" % (float(tx or 0), float(ty or 0)))
        if rot:
            parts.append("rotate(%g,%g,%g)" % (float(rot), float(px or 0), float(py or 0)))
        if sx or sy:
            parts.append(
                "translate(%g,%g) scale(%g,%g) translate(%g,%g)"
                % (
                    float(px or 0),
                    float(py or 0),
                    float(sx or 1),
                    float(sy or 1),
                    -float(px or 0),
                    -float(py or 0),
                )
            )
        return " ".join(parts)

    def render(node):
        for child in node:
            tag = child.tag
            if tag == "group":
                tr = group_transform(child)
                out.append('<g transform="%s">' % tr if tr else "<g>")
                render(child)
                out.append("</g>")
            elif tag == "path":
                attrs = ['d="%s"' % child.get(ANDROID + "pathData", "")]
                fill = child.get(ANDROID + "fillColor")
                if fill is None:
                    attrs.append('fill="none"')
                else:
                    attrs.append('fill="%s"' % fill)
                ftype = child.get(ANDROID + "fillType")
                if ftype:
                    attrs.append('fill-rule="%s"' % ("evenodd" if ftype == "evenOdd" else "nonzero"))
                alpha = child.get(ANDROID + "fillAlpha")
                if alpha:
                    attrs.append('fill-opacity="%s"' % alpha)
                stroke = child.get(ANDROID + "strokeColor")
                if stroke:
                    attrs.append('stroke="%s"' % stroke)
                    attrs.append('stroke-width="%s"' % child.get(ANDROID + "strokeWidth", "1"))
                    attrs.append('stroke-linecap="%s"' % child.get(ANDROID + "strokeLineCap", "butt"))
                    attrs.append('stroke-linejoin="%s"' % child.get(ANDROID + "strokeLineJoin", "miter"))
                out.append("<path " + " ".join(attrs) + "/>")

    render(root)
    out.append("</svg>")
    with open(dst, "w") as fh:
        fh.write("\n".join(out))


def load_mono(path: str) -> np.ndarray:
    """Monochrome VectorDrawable -> 108x108 coverage map.

    The artwork inside the drawable is white (it gets tinted by the launcher),
    so it is rendered twice - once on white and once on black - and the
    coverage is recovered from the difference:  coverage = 1 - (white - black).
    That yields an exact, anti-aliased mask whatever colour the drawable uses.
    """
    tmp = tempfile.mkdtemp(prefix="iconmono-")
    try:
        renders = {}
        for bg, tag in (("#FFFFFF", "w"), ("#000000", "b")):
            svg = os.path.join(tmp, "mono_%s.svg" % tag)
            png = os.path.join(tmp, "mono_%s.png" % tag)
            avd_to_svg(path, svg, bg)
            run(["rsvg-convert", "-w", str(VIEW * SS), svg, "-o", png])
            renders[tag] = np.asarray(Image.open(png).convert("RGB")).astype(np.float64)
        # a pixel covered by the artwork with coverage c renders as
        #   white bg: c*A + (1-c)*255      black bg: c*A + (1-c)*0
        # so the difference of the two renders is 255*(1-c) whatever A is.
        cov = 1.0 - (renders["w"] - renders["b"]).mean(axis=2) / 255.0
        cov = np.clip(cov, 0.0, 1.0)
        return downsample(cov)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# --------------------------------------------------------------------------
# measuring one layer
# --------------------------------------------------------------------------


class Layer:
    def __init__(self, name: str, cov: np.ndarray):
        self.name = name
        self.cov = cov
        self.mask = cov >= COV_MIN
        self.centre = self._find_centre()
        self.ring = self._measure_ring()
        self.band = self._band_mask()
        self.outer = self.mask & ~self.band
        self.sun = self._measure_sun()
        self.rays = self._measure_rays()
        self.ticks = self._measure_ticks()

    # -- clock centre ---------------------------------------------------
    def _find_centre(self):
        """The centre of the ring.

        A Hough style search finds the busiest annulus, then a geometric
        circle fit over the ink in that annulus pins the centre down.
        """
        ys, xs = np.nonzero(self.mask)
        if len(xs) == 0:
            self.r_peak = 20.0
            return (VIEW / 2.0, VIEW / 2.0)
        pts = np.stack([xs + 0.5, ys + 0.5], axis=1)

        # coarse: the busiest annulus (0.25 unit bins)
        bins = np.arange(0, VIEW / 2.0 + 1, 0.25)
        best = None
        for cy in np.arange(ys.min() - 2, ys.max() + 3, 0.5):
            for cx in np.arange(xs.min() - 2, xs.max() + 3, 0.5):
                d = np.hypot(pts[:, 0] - cx, pts[:, 1] - cy)
                hist, _ = np.histogram(d, bins=bins)
                i = int(hist.argmax())
                if best is None or hist[i] > best[0]:
                    best = (hist[i], float(cx), float(cy), (i + 0.5) * 0.25)
        _, cx, cy, r0 = best

        # refine: circle fit over the ink in the annulus
        d = np.hypot(pts[:, 0] - cx, pts[:, 1] - cy)
        for _ in range(3):
            sel = np.abs(d - r0) < 4.5
            if sel.sum() < 20:
                break
            cx, cy, r0 = fit_circle(pts[sel, 0], pts[sel, 1], (cx, cy))
            d = np.hypot(pts[:, 0] - cx, pts[:, 1] - cy)
        sel = np.abs(d - r0) <= 1.6  # just the ring band
        if sel.sum() >= 20:
            cx, cy, r0 = fit_circle(pts[sel, 0], pts[sel, 1], (cx, cy))
        self.r_peak = r0
        return (cx, cy)

    # -- ring ------------------------------------------------------------
    def _measure_ring(self):
        """Per angle: (inner, outer) radius of the ring band, or None.

        A ray can pick up more than the ring itself - the tick marks stick
        inwards, the bells and legs outwards, the sun crosses the ring
        completely.  Those angles are reported as "not measurable" and are
        left out of the comparison (the ticks and the sun are measured on
        their own further down).
        """
        n_ang = 360
        angles = np.arange(n_ang)
        steps = int(60.0 / 0.05)
        rr = np.arange(steps) * 0.05
        ang = np.radians(angles)[:, None]
        xs = self.centre[0] + rr[None, :] * np.cos(ang)
        ys = self.centre[1] + rr[None, :] * np.sin(ang)
        vals = bilinear(self.cov, xs, ys)

        naive = {}
        for i, a in enumerate(angles):
            row = vals[i] >= COV_MIN
            lo, hi = self.r_peak - 7.0, self.r_peak + 7.0
            i0 = max(0, int(np.searchsorted(rr, lo)))
            i1 = min(steps, int(np.searchsorted(rr, hi)) + 1)
            best = None
            j = i0
            while j < i1:
                if row[j]:
                    k = j
                    while k + 1 < i1 and row[k + 1]:
                        k += 1
                    if rr[j] <= self.r_peak <= rr[k]:
                        # the run that actually contains the ring radius
                        cand = (1, -abs((rr[j] + rr[k]) / 2.0 - self.r_peak), rr[j], rr[k])
                        if best is None or cand > best:
                            best = cand
                    j = k + 1
                else:
                    j += 1
            naive[int(a)] = None if best is None else (float(best[2]), float(best[3]))

        # the ring's own radii: the median over all angles is robust against
        # the few angles where a tick or a bell sticks out
        ins = [e[0] for e in naive.values() if e]
        outs = [e[1] for e in naive.values() if e]
        if not ins:
            return {}
        self.r_in = float(np.median(ins))
        self.r_out = float(np.median(outs))
        width = self.r_out - self.r_in

        ring = {}
        for a, e in naive.items():
            if e is None:
                ring[a] = None
            elif e[1] - e[0] > width + 1.0:
                ring[a] = None  # something else is glued onto the ring here
            else:
                ring[a] = e
        return ring

    def _band_mask(self):
        """The ring's own pixels, used to peel the ring off everything else."""
        yy, xx = np.mgrid[0:VIEW, 0:VIEW]
        x = xx + 0.5
        y = yy + 0.5
        r = np.hypot(x - self.centre[0], y - self.centre[1])
        th = np.degrees(np.arctan2(y - self.centre[1], x - self.centre[0])) % 360.0
        band = np.zeros_like(self.mask)
        for a, edges in self.ring.items():
            if edges is None:
                continue
            inner, outer = edges
            sel = np.abs((th - a + 180.0) % 360.0 - 180.0) <= 1.0
            band |= sel & (r >= inner - FACE_MARGIN) & (r <= outer + FACE_MARGIN)
        return band

    def _right_half(self):
        yy, xx = np.mgrid[0:VIEW, 0:VIEW]
        return xx + 0.5 > self.centre[0]

    # -- sun --------------------------------------------------------------
    def _measure_sun(self):
        """The sun: a half disc sitting on its flat bottom edge.

        The blob of ink right of the clock centre is usually fused with the
        ring ends, the hands and the rays, so the sun is found from the
        solid part of the ink (everything that is more than four pixels
        thick).  What is left is a clean half disc.
        """
        solid = self.mask & erode_n(self.mask, 4) & self._right_half()
        comps = components(solid)
        if not comps:
            return None
        comps.sort(key=lambda c: -c[0])
        area, ys, xs = comps[0]
        # the widest row of the solid part is the disc's flat bottom; rows
        # that only hold a couple of pixels belong to the ring ends that are
        # fused to the sun and are ignored
        rows = np.bincount(ys, minlength=VIEW)
        peak = int(rows.max())
        y1 = float(max(y for y in range(VIEW) if rows[y] >= max(2, 0.5 * peak)))
        x0 = float(xs.min())
        x1 = float(xs.max()) + 1.0
        # grow the eroded half disc back to the real one
        cx = (x0 + x1) / 2.0
        cy = y1 + 4  # the flat bottom edge of the disc
        r = (x1 - x0) / 2.0 + 4
        mask = np.zeros_like(self.mask)
        mask[ys, xs] = True
        return {
            "area": area,
            "core_bbox": (x0, float(ys.min()), x1, y1),
            "bbox": (cx - r, cy - r, cx + r, cy),
            "bottom": cy,
            "centre": (cx, cy - r / 2.0),
            "disc": (cx, cy, r),
            "mask": mask,
        }

    # -- rays -------------------------------------------------------------
    def _measure_rays(self):
        """The thin dashes that fan out around the sun.

        The dashes that are fused to the sun (the colour artwork glues the
        sun onto the ring, the themed drawable has no white halo) are found
        as the thin ink that points away from the sun's disc.
        """
        # The sun is a half disc sitting on its flat bottom edge; its disc
        # model was measured from the solid part of the ink.
        if self.sun:
            disc_cx, disc_cy, disc_r = self.sun["disc"]
        else:
            disc_cx = disc_cy = disc_r = 0.0
        yy, xx = np.mgrid[0:VIEW, 0:VIEW]
        dist = np.hypot(xx + 0.5 - disc_cx, yy + 0.5 - disc_cy)
        thin = self.mask & ~erode_n(self.mask, 3)
        # cut the sun's own rim away so that the dashes which are fused to
        # the sun become separate pieces
        cut = (dist <= disc_r) & (yy + 0.5 <= disc_cy)
        # The ring band is three to four pixels wide, so it does not show up
        # in the thin ink; only its anti aliased edges could, and those are
        # rejected by the length and the radiality test.
        cand = thin & self._right_half() & ~cut
        rays = []
        for area, ys, xs in components(cand):
            if area > RAY_MAX_AREA:
                continue
            piece = np.zeros_like(self.mask)
            piece[ys, xs] = True
            if thickness(piece) > RAY_MAX_THICK:
                continue
            pts = np.stack([xs + 0.5, ys + 0.5], axis=1).astype(np.float64)
            c = pts.mean(axis=0)
            _, _, vt = np.linalg.svd(pts - c, full_matrices=False)
            axis = vt[0]
            proj = (pts - c) @ axis
            p0 = c + axis * proj.min()
            p1 = c + axis * proj.max()
            length = math.dist(p0, p1)
            if length < RAY_MIN_LEN:
                continue
            # the dash has to point away from the sun
            out = c - np.array([disc_cx, disc_cy])
            norm = np.linalg.norm(out)
            if norm < 1e-6:
                continue
            if abs(float(np.dot(axis, out / norm))) < RAY_RADIAL:
                continue
            # and it has to fan out on the upper right of the sun
            if c[0] < disc_cx - 2.0 or c[1] > disc_cy + 2.0:
                continue
            # and it has to be outside the dial, not one of the hour marks
            if math.hypot(c[0] - self.centre[0], c[1] - self.centre[1]) < self.r_in - 1.0:
                continue
            rays.append(
                {
                    "area": int(len(ys)),
                    "p0": (float(p0[0]), float(p0[1])),
                    "p1": (float(p1[0]), float(p1[1])),
                    "bbox": bbox(ys, xs),
                }
            )
        rays.sort(key=lambda r: (r["p0"][1] + r["p1"][1]) / 2.0)
        return rays

    # -- ticks ------------------------------------------------------------
    def _measure_ticks(self):
        """The small marks inside the dial.

        Only the marks that are not part of the clock hands are ticks; the
        hands are one big blob in the middle of the face.
        """
        yy, xx = np.mgrid[0:VIEW, 0:VIEW]
        r = np.hypot(xx + 0.5 - self.centre[0], yy + 0.5 - self.centre[1])
        r_in = getattr(self, "r_in", 15.0)
        face = self.mask & (r < r_in - FACE_MARGIN)
        ticks = []
        for area, ys, xs in components(face):
            x0, y0, x1, y1 = bbox(ys, xs)
            if area > TICK_MAX_AREA or (x1 - x0) > 5 or (y1 - y0) > 5:
                continue  # that is the clock hands
            ticks.append(
                {
                    "area": area,
                    "centre": (float(xs.mean() + 0.5), float(ys.mean() + 0.5)),
                    "r": float(r[ys, xs].mean()),
                    "angle": float(
                        math.degrees(
                            math.atan2(ys.mean() + 0.5 - self.centre[1], xs.mean() + 0.5 - self.centre[0])
                        )
                    )
                    % 360.0,
                    "bbox": (x0, y0, x1, y1),
                }
            )
        ticks.sort(key=lambda t: t["angle"])
        return ticks


# --------------------------------------------------------------------------
# reporting
# --------------------------------------------------------------------------


def fmt(v, nd=2):
    if v is None:
        return "   -  "
    return "%*.2f" % (nd + 4, v)


def sector(angles):
    """Group a list of angles into readable ranges."""
    if not angles:
        return "-"
    angles = sorted(angles)
    out = []
    start = prev = angles[0]
    for a in angles[1:]:
        if a == prev + 1:
            prev = a
            continue
        out.append((start, prev))
        start = prev = a
    out.append((start, prev))
    return ", ".join("%d-%d" % s if s[0] != s[1] else "%d" % s[0] for s in out)


def pair_rays(a_rays, b_rays):
    """Match the two fans of dashes.

    The dashes are sorted from the top down, so the n-th dash of one layer
    is the n-th dash of the other one; anything left over did not exist on
    the other side.
    """
    pairs = []
    for i in range(max(len(a_rays), len(b_rays))):
        ra = a_rays[i] if i < len(a_rays) else None
        rb = b_rays[i] if i < len(b_rays) else None
        if ra and rb:
            d = min(
                math.dist(ra["p0"], rb["p0"]),
                math.dist(ra["p0"], rb["p1"]),
                math.dist(ra["p1"], rb["p0"]),
                math.dist(ra["p1"], rb["p1"]),
            )
            pairs.append((ra, rb, d))
        else:
            pairs.append((ra, rb, float("inf")))
    return pairs


def pair_ticks(a_ticks, b_ticks, window=15.0):
    """Match the tick marks by their position on the dial."""
    pairs = []
    used = set()
    for ta in a_ticks:
        best = None
        for i, tb in enumerate(b_ticks):
            if i in used:
                continue
            dang = abs((ta["angle"] - tb["angle"] + 180.0) % 360.0 - 180.0)
            if dang > window:
                continue
            if best is None or dang < best[0]:
                best = (dang, i)
        if best:
            used.add(best[1])
            pairs.append((ta, b_ticks[best[1]], math.dist(ta["centre"], b_ticks[best[1]]["centre"])))
        else:
            pairs.append((ta, None, float("inf")))
    for i, tb in enumerate(b_ticks):
        if i not in used:
            pairs.append((None, tb, float("inf")))
    return pairs


def report(art: Layer, mono: Layer, tol_ring, tol_sun, tol_ray):
    worst = {"ring": 0.0, "sun": 0.0, "ray": 0.0, "tick": 0.0}
    failures = []

    print("=" * 100)
    print("LAUNCHER ICON LAYER COMPARISON")
    print("=" * 100)
    print("colour layer : %s" % ART_REL)
    print("themed layer : %s" % MONO_REL)
    print(
        "clock centre : colour (%.2f, %.2f)   themed (%.2f, %.2f)   delta (%.2f, %.2f)"
        % (
            art.centre[0],
            art.centre[1],
            mono.centre[0],
            mono.centre[1],
            mono.centre[0] - art.centre[0],
            mono.centre[1] - art.centre[1],
        )
    )
    print()

    # ---------------- ring ------------------------------------------------
    print("-" * 100)
    print("RING  (radius in viewport units, measured along each ray from the clock centre)")
    print("-" * 100)
    print(
        "%5s %9s %9s %9s %9s %9s %9s"
        % ("deg", "col in", "col out", "thm in", "thm out", "d-in", "d-out")
    )
    d_ins, d_outs = [], []
    for a in range(0, 360, 5):
        e_art = art.ring.get(a)
        e_mono = mono.ring.get(a)
        if e_art and e_mono:
            di = e_mono[0] - e_art[0]
            do = e_mono[1] - e_art[1]
            d_ins.append(abs(di))
            d_outs.append(abs(do))
            flag = " " if abs(di) <= tol_ring and abs(do) <= tol_ring else "*"
            print(
                "%5d %9.2f %9.2f %9.2f %9.2f %+9.2f %+9.2f %s"
                % (a, e_art[0], e_art[1], e_mono[0], e_mono[1], di, do, flag)
            )
        else:
            print(
                "%5d %9s %9s %9s %9s %9s %9s %s"
                % (
                    a,
                    fmt(e_art[0]) if e_art else "  --  ",
                    fmt(e_art[1]) if e_art else "  --  ",
                    fmt(e_mono[0]) if e_mono else "  --  ",
                    fmt(e_mono[1]) if e_mono else "  --  ",
                    "",
                    "",
                    "(one side: sun crosses / ring missing)",
                )
            )
    if d_ins:
        worst["ring"] = max(max(d_ins), max(d_outs))
        print()
        print(
            "ring radius deviation : max %.2f  mean %.2f (inner)  max %.2f  mean %.2f (outer)  [tol %.2f]"
            % (
                max(d_ins),
                float(np.mean(d_ins)),
                max(d_outs),
                float(np.mean(d_outs)),
                tol_ring,
            )
        )
    art_missing = sorted(a for a, e in art.ring.items() if e is None)
    mono_missing = sorted(a for a, e in mono.ring.items() if e is None)
    print("ring not measurable  : colour %s" % sector(art_missing))
    print("                       themed %s" % sector(mono_missing))
    if worst["ring"] > tol_ring:
        failures.append(
            "ring radius deviates by %.2f (tolerance %.2f)" % (worst["ring"], tol_ring)
        )
    print()

    # ---------------- sun -------------------------------------------------
    print("-" * 100)
    print("SUN  (ink right of the clock centre, viewport units)")
    print("-" * 100)
    if art.sun and mono.sun:
        a, m = art.sun, mono.sun
        print("%-30s %-30s %-30s %s" % ("", "colour", "themed", "delta"))
        print(
            "%-30s %-30s %-30s %+.2f %+.2f %+.2f %+.2f"
            % (
                "sun disc bbox x0,y0,x1,y1",
                "(%.2f, %.2f, %.2f, %.2f)" % a["bbox"],
                "(%.2f, %.2f, %.2f, %.2f)" % m["bbox"],
                m["bbox"][0] - a["bbox"][0],
                m["bbox"][1] - a["bbox"][1],
                m["bbox"][2] - a["bbox"][2],
                m["bbox"][3] - a["bbox"][3],
            )
        )
        print(
            "%-30s %-30s %-30s %+.2f %+.2f"
            % (
                "sun disc centre x,r",
                "(%.2f, %.2f)" % (a["disc"][0], a["disc"][2]),
                "(%.2f, %.2f)" % (m["disc"][0], m["disc"][2]),
                m["disc"][0] - a["disc"][0],
                m["disc"][2] - a["disc"][2],
            )
        )
        print(
            "%-30s %-30s %-30s %+.2f"
            % (
                "flat bottom edge y",
                "%.2f" % a["bottom"],
                "%.2f" % m["bottom"],
                m["bottom"] - a["bottom"],
            )
        )
        dev = max(abs(m["bbox"][i] - a["bbox"][i]) for i in range(4))
        worst["sun"] = dev
        if dev > tol_sun:
            failures.append("sun bbox deviates by %.2f (tolerance %.2f)" % (dev, tol_sun))
    else:
        print("no sun found in one of the layers")
        failures.append("the sun could not be located in one of the layers")
    print()

    # ---------------- rays ------------------------------------------------
    print("-" * 100)
    print("RAYS  (thin components outside the ring band)")
    print("-" * 100)
    print("colour: %d ray(s)   themed: %d ray(s)" % (len(art.rays), len(mono.rays)))
    for ra, rb, d in pair_rays(art.rays, mono.rays):
        if ra and rb:
            print(
                "  colour %-26s themed %-26s endpoints differ by %.2f"
                % (
                    "(%.2f,%.2f)-(%.2f,%.2f)" % (ra["p0"][0], ra["p0"][1], ra["p1"][0], ra["p1"][1]),
                    "(%.2f,%.2f)-(%.2f,%.2f)" % (rb["p0"][0], rb["p0"][1], rb["p1"][0], rb["p1"][1]),
                    d,
                )
            )
            worst["ray"] = max(worst["ray"], d)
        elif ra:
            print(
                "  colour %-26s themed (missing)"
                % ("(%.2f,%.2f)-(%.2f,%.2f)" % (ra["p0"][0], ra["p0"][1], ra["p1"][0], ra["p1"][1]))
            )
        else:
            print(
                "  colour (missing)         themed %-26s"
                % ("(%.2f,%.2f)-(%.2f,%.2f)" % (rb["p0"][0], rb["p0"][1], rb["p1"][0], rb["p1"][1]))
            )
    if len(art.rays) != len(mono.rays):
        failures.append(
            "ray count differs: colour %d, themed %d" % (len(art.rays), len(mono.rays))
        )
    if worst["ray"] > tol_ray:
        failures.append("ray end points deviate by %.2f (tolerance %.2f)" % (worst["ray"], tol_ray))
    print()

    # ---------------- ticks -----------------------------------------------
    print("-" * 100)
    print("TICKS  (small components inside the face)")
    print("-" * 100)
    print("colour: %d tick(s)   themed: %d tick(s)" % (len(art.ticks), len(mono.ticks)))
    for ta, tb, d in pair_ticks(art.ticks, mono.ticks):
        if ta and tb:
            dang = abs((ta["angle"] - tb["angle"] + 180.0) % 360.0 - 180.0)
            arc = math.radians(dang) * max(ta["r"], tb["r"])
            print(
                "  colour (%5.2f,%5.2f) r=%5.2f a=%6.1f   themed (%5.2f,%5.2f) r=%5.2f a=%6.1f"
                "   centres %.2f apart, %.2f deg (%.2f units along the dial)"
                % (
                    ta["centre"][0],
                    ta["centre"][1],
                    ta["r"],
                    ta["angle"],
                    tb["centre"][0],
                    tb["centre"][1],
                    tb["r"],
                    tb["angle"],
                    d,
                    dang,
                    arc,
                )
            )
            worst["tick"] = max(worst["tick"], arc)
            if arc > tol_ring:
                failures.append(
                    "tick at %.0f deg is %.2f units off the dial position of the other layer"
                    % (ta["angle"], arc)
                )
        elif ta:
            print("  colour (%5.2f,%5.2f)  themed (missing)" % (ta["centre"][0], ta["centre"][1]))
            failures.append(
                "tick at %.0f deg only exists in the colour layer" % ta["angle"]
            )
        else:
            print("  colour (missing)        themed (%5.2f,%5.2f)" % (tb["centre"][0], tb["centre"][1]))
            failures.append(
                "tick at %.0f deg only exists in the themed layer" % tb["angle"]
            )
    print("  (the tick check uses the angular position on the dial; the radial")
    print("   extent is printed for reference - the colour layer draws the ticks")
    print("   running into the ring, the themed layer draws them free standing)")
    print()

    # ---------------- verdict ---------------------------------------------
    print("=" * 100)
    if failures:
        print("VERDICT: MISMATCH")
        for f in failures:
            print("  - %s" % f)
    else:
        print("VERDICT: MATCH - the two layers agree within tolerance")
    print(
        "  worst deviations: ring %.2f (tol %.2f)  sun %.2f (tol %.2f)  rays %.2f (tol %.2f)"
        % (worst["ring"], tol_ring, worst["sun"], tol_sun, worst["ray"], tol_ray)
    )
    print("=" * 100)
    return failures


# --------------------------------------------------------------------------
# side by side picture
# --------------------------------------------------------------------------


def side_by_side(art_path: str, mono_path: str, art: Layer, mono: Layer, out_path: str) -> None:
    """Colour artwork next to the themed drawable, plus a 4x zoom of the
    sun/ring junction of both."""
    art_img = Image.open(art_path).convert("RGBA")
    bg = Image.new("RGBA", art_img.size, (255, 255, 255, 255))
    bg.alpha_composite(art_img)
    art108 = bg.convert("RGB").resize((VIEW, VIEW), Image.LANCZOS)

    tmp = tempfile.mkdtemp(prefix="iconmono-")
    try:
        svg = os.path.join(tmp, "m.svg")
        png = os.path.join(tmp, "m.png")
        avd_to_svg(mono_path, svg, "#000000")
        run(["rsvg-convert", "-w", str(VIEW * SS), svg, "-o", png])
        mono432 = Image.open(png).convert("RGB")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    mono108 = mono432.resize((VIEW, VIEW), Image.LANCZOS)

    # crop the sun / ring junction out of both layers (the same window for
    # both, so the offset of the sun is visible)
    boxes = []
    for layer in (art, mono):
        if layer.sun:
            boxes.append(layer.sun["bbox"])
    if boxes:
        x0 = min(b[0] for b in boxes) - 5
        y0 = min(b[1] for b in boxes) - 7
        x1 = max(b[2] for b in boxes) + 5
        y1 = max(b[3] for b in boxes) + 5
    else:
        x0, y0, x1, y1 = 40, 30, 100, 75
    x0 = max(0, int(x0))
    y0 = max(0, int(y0))
    x1 = min(VIEW, int(x1))
    y1 = min(VIEW, int(y1))

    def zoom(img):
        return img.crop((x0, y0, x1, y1)).resize(
            ((x1 - x0) * 4, (y1 - y0) * 4), Image.NEAREST
        )

    art_panel = art108.resize((216, 216), Image.LANCZOS)
    mono_panel = mono108.resize((216, 216), Image.LANCZOS)
    art_zoom = zoom(art108)
    mono_zoom = zoom(mono108)

    pad = 10
    label_h = 16
    cell_w = max(216, art_zoom.width)
    canvas = Image.new(
        "RGB", (cell_w * 2 + pad * 3, 216 + art_zoom.height + label_h * 2 + pad * 3), (28, 28, 32)
    )
    try:
        from PIL import ImageDraw

        draw = ImageDraw.Draw(canvas)
        draw.text((pad, pad), "colour artwork (432px foreground)", fill=(230, 230, 230))
        draw.text((pad * 2 + cell_w, pad), "themed drawable (mask, tinted by the launcher)",
                  fill=(230, 230, 230))
        draw.text((pad, pad * 2 + label_h + 216),
                  "4x zoom of the sun / ring junction", fill=(230, 230, 230))
    except Exception:  # pragma: no cover - labels are cosmetic
        pass
    canvas.paste(art_panel, (pad, pad + label_h))
    canvas.paste(mono_panel, (pad * 2 + cell_w, pad + label_h))
    canvas.paste(art_zoom, (pad, pad * 2 + label_h * 2 + 216))
    canvas.paste(mono_zoom, (pad * 2 + cell_w, pad * 2 + label_h * 2 + 216))
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    canvas.save(out_path)


# --------------------------------------------------------------------------


ART_REL = "app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.webp"
MONO_REL = "app/src/main/res/drawable/ic_launcher_monochrome.xml"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--art", default=None, help="colour artwork (webp/png)")
    ap.add_argument("--mono", default=None, help="monochrome VectorDrawable")
    ap.add_argument("--ring-tol", type=float, default=1.0, help="ring radius tolerance")
    ap.add_argument("--sun-tol", type=float, default=1.5, help="sun bbox tolerance")
    ap.add_argument("--ray-tol", type=float, default=1.5, help="ray end point tolerance")
    ap.add_argument(
        "--tolerance", type=float, default=None, help="override ring/sun/ray tolerances at once"
    )
    ap.add_argument(
        "--ink-white",
        type=int,
        default=INK_WHITE,
        help="a colour pixel counts as ink when its darkest channel is below this",
    )
    ap.add_argument("--out", default=os.path.join(OUT_DIR, "icon-layers-side-by-side.png"))
    args = ap.parse_args(argv)

    if args.tolerance is not None:
        args.ring_tol = args.sun_tol = args.ray_tol = args.tolerance

    root = project_root()
    art_path = args.art or os.path.join(root, ART_REL)
    mono_path = args.mono or os.path.join(root, MONO_REL)
    for p in (art_path, mono_path):
        if not os.path.exists(p):
            raise SystemExit("missing input: %s" % p)

    print("colour artwork : %s" % art_path)
    print("  sha256 %s" % sha256(art_path))
    print("themed drawable: %s" % mono_path)
    print("  sha256 %s" % sha256(mono_path))
    print("rendering ...")

    art_cov = load_art(art_path, args.ink_white)
    mono_cov = load_mono(mono_path)
    art = Layer("colour", art_cov)
    mono = Layer("themed", mono_cov)

    failures = report(art, mono, args.ring_tol, args.sun_tol, args.ray_tol)

    side_by_side(art_path, mono_path, art, mono, args.out)
    print("side by side image: %s" % args.out)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
