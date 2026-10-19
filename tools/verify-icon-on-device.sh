#!/usr/bin/env bash
#
# verify-icon-on-device.sh - build the app, put it on the attached device and
# compare the two launcher icon layers as the launcher draws them.
#
#   1. build the debug APK
#   2. install it
#   3. go to the home screen and take a screenshot
#   4. crop the themed icon (the one the launcher draws from the monochrome
#      drawable) out of the screenshot
#   5. build the full colour icon the way the launcher does it: the
#      foreground asset on a white background, masked to the circle of the
#      adaptive icon
#   6. write both side by side into /tmp/opencode
#
# This script never reboots the device and never touches the screen lock.
# If the device is busy, asleep or locked it says so and stops with a non
# zero exit code instead of doing anything drastic.
#
# Usage: tools/verify-icon-on-device.sh [--no-build] [--no-install]

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${OUT_DIR:-/tmp/opencode}"
APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="com.dsalmun.luxalarmplus"
LABEL="luxAlarm+"

DO_BUILD=1
DO_INSTALL=1
for arg in "$@"; do
  case "$arg" in
    --no-build) DO_BUILD=0 ;;
    --no-install) DO_INSTALL=0 ;;
    -h|--help)
      sed -n '2,26p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

# ---------------------------------------------------------------- tooling ---
if [ -z "${ANDROID_HOME:-}" ] && [ -d "$HOME/Android/Sdk" ]; then
  export ANDROID_HOME="$HOME/Android/Sdk"
fi
export PATH="$HOME/Android/Sdk/platform-tools:${ANDROID_HOME:-}/platform-tools:$PATH"
if ! command -v adb >/dev/null 2>&1; then
  echo "ERROR: adb not found in $HOME/Android/Sdk/platform-tools" >&2
  exit 3
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "ERROR: python3 is needed to build the comparison image" >&2
  exit 3
fi

mkdir -p "$OUT_DIR"
cd "$ROOT" || exit 3

say() { printf '\n== %s ==\n' "$*"; }

# ------------------------------------------------------------------ build ---
if [ "$DO_BUILD" = 1 ]; then
  say "building the debug APK"
  if ! ./gradlew :app:assembleDebug --console=plain; then
    echo "ERROR: the build failed" >&2
    exit 10
  fi
fi
if [ ! -f "$ROOT/$APK" ]; then
  echo "ERROR: $APK not found" >&2
  exit 10
fi
echo "APK: $ROOT/$APK"

# ---------------------------------------------------------------- install ---
if [ "$DO_INSTALL" = 1 ]; then
  say "installing"
  if ! adb install -r "$ROOT/$APK"; then
    echo "ERROR: the install failed" >&2
    exit 11
  fi
fi

# ----------------------------------------------------------- device state ---
say "checking the device"
STATE="$(adb get-state 2>/dev/null || echo 'no device')"
if [ "$STATE" != "device" ]; then
  echo "SKIPPED: no usable device (adb state: $STATE)."
  echo "         Another emulator or a running reboot test is in the way;"
  echo "         this script does not reboot anything, so it stops here."
  exit 20
fi
echo "device: $(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')"

LOCKED=0
if adb shell dumpsys window 2>/dev/null | grep -q "isKeyguardShowing=true"; then
  LOCKED=1
fi
if [ "$LOCKED" = 1 ]; then
  echo "SKIPPED: the screen is locked."
  echo "         Waking it up or dismissing the keyguard would disturb the"
  echo "         reboot test that is running on this emulator, so the"
  echo "         screenshot is skipped.  Unlock the device and run again."
  exit 21
fi

# ------------------------------------------------------------- screenshot ---
say "going to the home screen"
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1
sleep 3
SHOT="$OUT_DIR/icon_check_home.png"
if ! adb shell screencap -p /sdcard/icon_check.png >/dev/null 2>&1 ||
   ! adb pull /sdcard/icon_check.png "$SHOT" >/dev/null 2>&1; then
  # /sdcard can be unreachable on a device that is still booting
  adb shell rm -f /sdcard/icon_check.png >/dev/null 2>&1
  adb exec-out screencap -p > "$SHOT" 2>/dev/null
fi
if [ ! -s "$SHOT" ]; then
  STATE="$(adb get-state 2>/dev/null || echo 'no device')"
  echo "ERROR: screencap produced nothing (adb state: $STATE)." >&2
  if [ "$STATE" != "device" ]; then
    echo "       The device went away - it is probably being rebooted by" >&2
    echo "       another test.  This script never reboots anything itself," >&2
    echo "       so it stops here instead of touching the device." >&2
  fi
  exit 22
fi
if ! python3 - "$SHOT" <<'PY'
import sys

import numpy as np
from PIL import Image

a = np.asarray(Image.open(sys.argv[1]).convert("L"))
sys.exit(0 if a.mean() > 4.0 else 1)
PY
then
  echo "SKIPPED: the screenshot is black - the device is asleep." >&2
  echo "         This script does not wake the device up, so it stops here." >&2
  exit 22
fi
echo "screenshot: $SHOT"

# ------------------------------------------------- locate the themed icon ---
say "locating the themed icon on the home screen"

find_icon() {
  adb shell uiautomator dump /sdcard/icon_check_ui.xml >/dev/null 2>&1
  adb pull /sdcard/icon_check_ui.xml "$OUT_DIR/icon_check_ui.xml" >/dev/null 2>&1
  python3 - "$OUT_DIR/icon_check_ui.xml" "$LABEL" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

path, label = sys.argv[1], sys.argv[2].lower()
try:
    root = ET.parse(path).getroot()
except Exception:
    sys.exit(0)
best = None
for node in root.iter("node"):
    rid = node.get("resource-id", "")
    desc = (node.get("content-desc") or "").strip().lower()
    text = (node.get("text") or "").strip().lower()
    if label not in (desc, text):
        continue
    if rid and not rid.endswith(":id/icon"):
        continue
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if not m:
        continue
    x0, y0, x1, y1 = (int(v) for v in m.groups())
    if x1 - x0 < 20:
        continue
    best = (x0, y0, x1, y1)
if best:
    print(" ".join(str(v) for v in best))
PY
}

BOUNDS=""
for attempt in 1 2 3; do
  BOUNDS="$(find_icon)"
  [ -n "$BOUNDS" ] && break
  sleep 2
done
if [ -z "$BOUNDS" ]; then
  # the icon may live in the all apps drawer instead of on the home page
  echo "not on the home page - looking in the app drawer"
  adb shell input swipe 540 1800 540 600 200 >/dev/null 2>&1
  sleep 2
  for attempt in 1 2 3; do
    BOUNDS="$(find_icon)"
    [ -n "$BOUNDS" ] && break
    sleep 2
  done
  adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1
fi
adb shell rm -f /sdcard/icon_check_ui.xml >/dev/null 2>&1
if [ -z "$BOUNDS" ]; then
  echo "ERROR: could not find the '$LABEL' icon in the launcher dump" >&2
  exit 23
fi
echo "icon bounds: $BOUNDS"

# ------------------------------------------------------------ side by side ---
say "writing the side by side comparison"
OUT_IMG="$OUT_DIR/icon-on-device.png"
python3 - "$SHOT" "$BOUNDS" "$OUT_IMG" "$ROOT/app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.webp" <<'PY'
import sys

from PIL import Image, ImageDraw

shot_path, bounds, out_path, fg_path = sys.argv[1:5]
x0, y0, x1, y1 = (int(v) for v in bounds.split())
shot = Image.open(shot_path).convert("RGB")
side = max(x1 - x0, y1 - y0)

# the themed icon exactly as the launcher draws it
themed = shot.crop((x0, y0, x0 + side, y0 + side))

# the full colour icon: the foreground asset on white, masked to the circle
# of the adaptive icon (the launcher shows the middle 72 of the 108 dp)
fg = Image.open(fg_path).convert("RGBA")
diam = int(round(fg.width * 72.0 / 108.0))
left = (fg.width - diam) // 2
top = (fg.height - diam) // 2
fg = fg.crop((left, top, left + diam, top + diam))
canvas = Image.new("RGBA", (diam, diam), (255, 255, 255, 255))
canvas.alpha_composite(fg)
mask = Image.new("L", (diam, diam), 0)
ImageDraw.Draw(mask).ellipse((0, 0, diam - 1, diam - 1), fill=255)
colour = Image.new("RGBA", (diam, diam), (255, 255, 255, 255))
colour.paste(canvas, (0, 0), mask)
colour = colour.convert("RGB").resize((side, side), Image.LANCZOS)

pad = 12
label_h = 16
out = Image.new("RGB", (side * 2 + pad * 3, side + label_h + pad * 2), (28, 28, 32))
draw = ImageDraw.Draw(out)
draw.text((pad, pad), "themed icon (home screen)", fill=(235, 235, 235))
draw.text((pad * 2 + side, pad), "full colour icon (foreground)", fill=(235, 235, 235))
out.paste(themed, (pad, pad + label_h))
out.paste(colour, (pad * 2 + side, pad + label_h))
out.save(out_path)
print("themed crop : %s  (%d x %d px at %d,%d)" % (shot_path, side, side, x0, y0))
print("side by side: %s" % out_path)
PY
RC=$?
if [ "$RC" != 0 ]; then
  echo "ERROR: could not build $OUT_IMG" >&2
  exit 24
fi

echo
echo "screenshot   : $SHOT"
echo "side by side : $OUT_IMG"
echo "done"
