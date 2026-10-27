#!/usr/bin/env bash
# Runs inside the emulator job: installs the release APK, pushes the test scans, walks the app.
set -u
OUT="walk-out/api-$API"
mkdir -p "$OUT"
adb wait-for-device
adb install -r app-release.apk
adb push fixtures/Pictures/. /sdcard/Pictures/ >/dev/null
adb push fixtures/Download/. /sdcard/Download/ >/dev/null
adb logcat -v time > "$OUT/logcat.txt" 2>&1 &
python3 android/tools/emulator/walk.py "$OUT" || true
kill %1 2>/dev/null || true

python3 - "$OUT" <<'PY' >> "$OUT/report.md"
# What the converted JPEGs carry, checked with Pillow on the runner.
import pathlib, sys
from PIL import Image, ExifTags
for p in sorted(pathlib.Path(sys.argv[1]).glob("*.jpg")):
    im = Image.open(p)
    ex = im.getexif()
    tags = {ExifTags.TAGS.get(k, k): v for k, v in ex.items()}
    sub = {ExifTags.TAGS.get(k, k): v for k, v in ex.get_ifd(0x8769).items()}
    gps = ex.get_ifd(0x8825)
    sampling = getattr(im, "layers", None), im.info.get("progression"), im.info.get("dpi")
    print(f"\n### {p.name}: {im.size}, mode {im.mode}, layers/progressive/dpi {sampling}, ICC {'yes' if im.info.get('icc_profile') else 'no'}")
    print(f"- IFD0: { {k: v for k, v in tags.items() if k not in ('ExifOffset',)} }")
    print(f"- EXIF: {sub}")
    print(f"- GPS: {dict(gps) or 'none'}")
    print(f"- XMP: {'yes' if b'http://ns.adobe.com/xap' in p.read_bytes() else 'no'}")
PY
cat "$OUT/report.md"
cat "$OUT/report.md" >> "$GITHUB_STEP_SUMMARY"
