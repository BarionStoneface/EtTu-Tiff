#!/usr/bin/env python3
"""TIFF -> JPEG converter for film scans.

For each folder of TIFFs:
  * converts every TIFF to a maximum-quality JPEG (same pixel dimensions,
    quality 100, no chroma subsampling, ICC colour profile and DPI kept)
  * strips all original metadata, then writes back only what you choose:
    camera body, lens, film stock, scan origin, artist, copyright, notes
  * keeps the ORIGINAL scan date (read from inside the file, not the
    download date) as "Date taken" / "Date digitized", and sets the file's
    modified date to it too
  * lets you rename in bulk (pattern or spreadsheet) while the original
    filename is stored inside each JPEG (XMP xmpMM:PreservedFileName)
  * deletes each TIFF only after its JPEG is written and checked
  * deletes .thm and other info/sidecar files
  * renames the folder from ...TIFF to ...JPEG

Usage:
    python tiff2jpeg.py "path/to/scans folder" [--dry-run]
"""

import argparse
import csv
import datetime as dt
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from xml.sax.saxutils import escape

try:
    import numpy as np
    import tifffile
    from PIL import Image
except ImportError:
    sys.exit("Missing libraries. Run:  pip install -r requirements.txt")

Image.MAX_IMAGE_PIXELS = None  # big scans are fine

TIFF_EXT = {".tif", ".tiff"}
# Info/sidecar files removed after a folder converts successfully.
SIDECAR_EXT = {".thm", ".xmp", ".info", ".nfo", ".xml", ".txt", ".db",
               ".ini", ".ds_store", ".md5", ".sfv", ".log", ".dat"}
# Subfolders of thumbnails/previews removed along with their contents.
THUMB_DIRS = re.compile(r"(?i)^[._]*(thumbs?|thumbnails?|thm|previews?)$")
PRESET_FILE = Path.home() / ".tiff2jpeg_metadata.json"
BAD_NAME_CHARS = re.compile(r'[<>:"/\\|?*\x00-\x1f]')
EXIF_FMT = "%Y:%m:%d %H:%M:%S"

META_FIELDS = [
    ("camera", "Camera body (e.g. Canon AE-1)"),
    ("lens", "Lens"),
    ("film", "Film stock"),
    ("scan_origin", "Scan origin (lab / scanner)"),
    ("artist", "Artist / author"),
    ("copyright", "Copyright"),
    ("notes", "Notes / description"),
]


# ---------------------------------------------------------------- helpers

def ask(prompt, default=""):
    suffix = f" [{default}]" if default else ""
    ans = input(f"{prompt}{suffix}: ").strip()
    return ans or default


def yes(prompt, default=True):
    d = "Y/n" if default else "y/N"
    ans = input(f"{prompt} [{d}]: ").strip().lower()
    return default if not ans else ans.startswith("y")


def parse_date(text):
    if not text:
        return None
    if isinstance(text, bytes):
        text = text.decode("ascii", "ignore")
    text = str(text).strip().strip("\x00")
    for n, fmt in ((19, EXIF_FMT), (19, "%Y-%m-%dT%H:%M:%S"), (19, "%Y-%m-%d %H:%M:%S"),
                   (16, "%Y-%m-%dT%H:%M"), (16, "%Y-%m-%d %H:%M"), (10, "%Y-%m-%d"), (10, "%Y:%m:%d")):
        try:
            return dt.datetime.strptime(text[:n], fmt)
        except ValueError:
            continue
    return None


def xmp_dates(xmp):
    """Pull creation-type dates out of an XMP packet, best first."""
    if isinstance(xmp, bytes):
        xmp = xmp.decode("utf-8", "ignore")
    found = []
    for key in ("exif:DateTimeDigitized", "xmp:CreateDate",
                "photoshop:DateCreated", "exif:DateTimeOriginal"):
        m = re.search(rf'{key}\s*=\s*"([^"]+)"', xmp) or \
            re.search(rf"<{key}>([^<]+)</{key}>", xmp)
        if m and parse_date(m.group(1)):
            found.append((parse_date(m.group(1)), f"XMP {key}"))
    return found


def open_folder(path):
    try:
        if sys.platform.startswith("win"):
            os.startfile(path)  # noqa
        elif sys.platform == "darwin":
            subprocess.run(["open", str(path)])
        else:
            subprocess.run(["xdg-open", str(path)])
    except Exception:
        pass


# ---------------------------------------------------------------- reading

class Scan:
    def __init__(self, path):
        self.path = path
        self.new_stem = path.stem
        self.date = None
        self.date_source = ""
        self.icc = None
        self.dpi = None
        self.bits = 8
        self.orientation = 1
        self.scanner = ""
        self._read_tags()
        self._find_date()

    def _read_tags(self):
        with tifffile.TiffFile(self.path) as tif:
            page = tif.pages[0]
            tags = page.tags
            self.width, self.height = page.imagewidth, page.imagelength
            bps = tags.get("BitsPerSample")
            if bps is not None:
                v = bps.value
                self.bits = max(v) if isinstance(v, tuple) else v
            if "InterColorProfile" in tags:
                self.icc = bytes(tags["InterColorProfile"].value)
            if "XResolution" in tags:
                unit = tags["ResolutionUnit"].value if "ResolutionUnit" in tags else 2
                num, den = tags["XResolution"].value
                dpi = num / den if den else 0
                if int(unit) == 3:  # centimetres
                    dpi *= 2.54
                if dpi:
                    self.dpi = round(dpi, 3)
            if "Orientation" in tags:
                self.orientation = int(tags["Orientation"].value)
            make = str(tags["Make"].value).strip() if "Make" in tags else ""
            model = str(tags["Model"].value).strip() if "Model" in tags else ""
            self.scanner = model if make and model.startswith(make) else f"{make} {model}".strip()

            self._dates = []
            exif = tags["ExifTag"].value if "ExifTag" in tags else {}
            for key in ("DateTimeDigitized", "DateTimeOriginal"):
                d = parse_date(exif.get(key)) if isinstance(exif, dict) else None
                if d:
                    self._dates.append((d, f"EXIF {key}"))
            if "XMP" in tags:
                self._dates += xmp_dates(bytes(tags["XMP"].value))
            if "DateTime" in tags and parse_date(tags["DateTime"].value):
                self._dates.append((parse_date(tags["DateTime"].value), "TIFF DateTime"))

    def _find_date(self):
        dates = list(self._dates)
        # Sidecars written at scan time (camera/scanner .thm, .xmp)
        for side in self.path.parent.iterdir():
            if side.stem != self.path.stem or side == self.path:
                continue
            ext = side.suffix.lower()
            try:
                if ext == ".thm":
                    with Image.open(side) as im:
                        ex = im.getexif()
                        sub = ex.get_ifd(0x8769)
                        for tag, label in ((0x9004, "DateTimeDigitized"), (0x9003, "DateTimeOriginal")):
                            if parse_date(sub.get(tag)):
                                dates.append((parse_date(sub.get(tag)), f".thm {label}"))
                        if parse_date(ex.get(0x0132)):
                            dates.append((parse_date(ex.get(0x0132)), ".thm DateTime"))
                elif ext == ".xmp":
                    dates += [(d, ".xmp sidecar " + s[4:]) for d, s in xmp_dates(side.read_bytes())]
            except Exception:
                pass
        if dates:
            self.date, self.date_source = dates[0]
            return
        # Last resort: the OLDER of the file's modified/created times.
        # Unzipping usually keeps the original modified time, while the
        # created time is the download, so the older one is the better bet.
        st = self.path.stat()
        stamps = [st.st_mtime, st.st_ctime]
        if hasattr(st, "st_birthtime"):
            stamps.append(st.st_birthtime)
        self.date = dt.datetime.fromtimestamp(min(stamps)).replace(microsecond=0)
        self.date_source = "FILE DATE (no date inside file - check this)"

    def load_pixels(self):
        """Return a PIL image, 8 bits per channel, full resolution."""
        im = None
        if self.bits <= 8:
            try:
                im = Image.open(self.path)
                im.seek(0)
                im.load()
            except Exception:
                im = None
        if im is None:
            im = self._load_high_bit()
        if im.mode in ("RGBA", "P", "LA", "PA", "RGBX", "YCbCr", "LAB", "HSV"):
            im = im.convert("RGB")
        elif im.mode == "1":
            im = im.convert("L")
        elif im.mode not in ("RGB", "L", "CMYK"):
            im = self._load_high_bit()
        return _apply_orientation(im, self.orientation)

    def _load_high_bit(self):
        with tifffile.TiffFile(self.path) as tif:
            page = tif.pages[0]
            arr = page.asarray()
            photometric = int(page.photometric)
        if arr.ndim == 3 and arr.shape[0] in (3, 4) and arr.shape[-1] not in (3, 4):
            arr = np.moveaxis(arr, 0, -1)
        if arr.ndim == 3 and arr.shape[-1] > 4:
            arr = arr[..., :3]
        # scale to 8 bit with rounding (65535 -> 255 exactly)
        if arr.dtype.kind == "f":
            lo, hi = float(arr.min()), float(arr.max())
            hi = 1.0 if hi <= 1.0 and lo >= 0 else hi
            arr = np.clip(np.rint(arr / (hi or 1) * 255), 0, 255).astype(np.uint8)
        elif arr.dtype != np.uint8:
            maxval = np.iinfo(arr.dtype).max
            if self.bits and self.bits < arr.dtype.itemsize * 8:
                maxval = (1 << self.bits) - 1
            arr = np.clip(np.rint(arr.astype(np.float64) * 255.0 / maxval), 0, 255).astype(np.uint8)
        if photometric == 0:  # MinIsWhite
            arr = 255 - arr
        if arr.ndim == 2:
            return Image.fromarray(arr, "L")
        if photometric == 5 and arr.shape[-1] == 4:
            return Image.fromarray(arr, "CMYK")
        return Image.fromarray(arr[..., :3], "RGB")


def _apply_orientation(im, o):
    ops = {2: [Image.Transpose.FLIP_LEFT_RIGHT], 3: [Image.Transpose.ROTATE_180],
           4: [Image.Transpose.FLIP_TOP_BOTTOM], 5: [Image.Transpose.TRANSPOSE],
           6: [Image.Transpose.ROTATE_270], 7: [Image.Transpose.TRANSVERSE],
           8: [Image.Transpose.ROTATE_90]}
    for op in ops.get(o, []):
        im = im.transpose(op)
    return im


# ---------------------------------------------------------------- writing

def build_exif(scan, meta):
    exif = Image.Exif()
    date = scan.date.strftime(EXIF_FMT)
    exif[0x0132] = date                                   # DateTime
    if meta.get("camera"):
        exif[0x010F] = meta["camera"].split()[0]          # Make
        exif[0x0110] = meta["camera"]                     # Model
    if meta.get("artist"):
        exif[0x013B] = meta["artist"]
    if meta.get("copyright"):
        exif[0x8298] = meta["copyright"]
    desc = description(meta)
    if desc:
        exif[0x010E] = desc                               # ImageDescription
    sub = exif.get_ifd(0x8769)
    sub[0x9003] = date                                    # DateTimeOriginal ("Date taken")
    sub[0x9004] = date                                    # DateTimeDigitized
    if meta.get("lens"):
        sub[0xA434] = meta["lens"]                        # LensModel
    return exif.tobytes()


def description(meta):
    parts = []
    if meta.get("film"):
        parts.append(f"Film: {meta['film']}")
    if meta.get("scan_origin"):
        parts.append(f"Scanned by: {meta['scan_origin']}")
    if meta.get("notes"):
        parts.append(meta["notes"])
    return ". ".join(parts)


def build_xmp(scan, meta):
    iso = scan.date.strftime("%Y-%m-%dT%H:%M:%S")
    e = lambda s: escape(s, {'"': "&quot;"})
    attrs = [
        f'xmpMM:PreservedFileName="{e(scan.path.name)}"',
        f'xmp:CreateDate="{iso}"',
        f'exif:DateTimeDigitized="{iso}"',
        f'exif:DateTimeOriginal="{iso}"',
        f'photoshop:DateCreated="{iso}"',
    ]
    if meta.get("scan_origin"):
        attrs.append(f'photoshop:Source="{e(meta["scan_origin"])}"')
    if meta.get("camera"):
        attrs.append(f'tiff:Model="{e(meta["camera"])}"')
    if meta.get("lens"):
        attrs.append(f'exifEX:LensModel="{e(meta["lens"])}"')
    body = ""
    desc = description(meta)
    if desc:
        body += (f'<dc:description><rdf:Alt><rdf:li xml:lang="x-default">{e(desc)}'
                 f"</rdf:li></rdf:Alt></dc:description>")
    if meta.get("artist"):
        body += f"<dc:creator><rdf:Seq><rdf:li>{e(meta['artist'])}</rdf:li></rdf:Seq></dc:creator>"
    if meta.get("copyright"):
        body += (f'<dc:rights><rdf:Alt><rdf:li xml:lang="x-default">{e(meta["copyright"])}'
                 f"</rdf:li></rdf:Alt></dc:rights>")
    xml = (
        '<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>'
        '<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF '
        'xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">'
        '<rdf:Description rdf:about="" '
        'xmlns:xmp="http://ns.adobe.com/xap/1.0/" '
        'xmlns:xmpMM="http://ns.adobe.com/xap/1.0/mm/" '
        'xmlns:exif="http://ns.adobe.com/exif/1.0/" '
        'xmlns:exifEX="http://cipa.jp/exif/1.0/" '
        'xmlns:tiff="http://ns.adobe.com/tiff/1.0/" '
        'xmlns:photoshop="http://ns.adobe.com/photoshop/1.0/" '
        'xmlns:dc="http://purl.org/dc/elements/1.1/" '
        + " ".join(attrs) + ">" + body +
        "</rdf:Description></rdf:RDF></x:xmpmeta>"
        '<?xpacket end="w"?>'
    )
    return xml.encode("utf-8")


def convert(scan, meta, out_path):
    im = scan.load_pixels()
    expected = (scan.width, scan.height) if scan.orientation < 5 else (scan.height, scan.width)
    if im.size != expected:
        raise RuntimeError(f"pixel size changed {expected} -> {im.size}")
    # No optimize=True: it saves only a few % and fails on grainy scans at q100
    opts = dict(quality=100, subsampling=0,
                exif=build_exif(scan, meta), xmp=build_xmp(scan, meta))
    if scan.icc:
        opts["icc_profile"] = scan.icc
    if scan.dpi:
        opts["dpi"] = (scan.dpi, scan.dpi)
    tmp = out_path.with_name(out_path.name + ".part")
    im.save(tmp, "JPEG", **opts)
    with Image.open(tmp) as check:  # verify before anything is deleted
        check.load()
        if check.size != im.size:
            raise RuntimeError("written JPEG has wrong size")
    os.replace(tmp, out_path)
    ts = scan.date.timestamp()
    os.utime(out_path, (ts, ts))


# ---------------------------------------------------------------- planning

def find_folders(root):
    folders = []
    for d, _, files in os.walk(root):
        if any(Path(f).suffix.lower() in TIFF_EXT and not f.startswith("._") for f in files):
            folders.append(Path(d))
    return sorted(folders)


def jpeg_folder_name(name):
    new = re.sub(r"(?i)\btiffs?\b|\btifs?\b", "JPEG", name)
    return new if new != name else f"{name} JPEG"


def clean_name(s):
    return BAD_NAME_CHARS.sub("_", s).strip().rstrip(".")


def apply_pattern(pattern, scans_by_folder):
    for folder, scans in scans_by_folder.items():
        for i, s in enumerate(scans, 1):
            s.new_stem = clean_name(pattern.format(
                name=s.path.stem, n=i, date=s.date.strftime("%Y-%m-%d"),
                folder=jpeg_folder_name(folder.name)))


def edit_in_csv(root, scans_by_folder):
    plan = root / "_rename_plan.csv"
    with open(plan, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["folder", "original_file", "new_name", "scan_date", "date_source"])
        for folder, scans in scans_by_folder.items():
            for s in scans:
                w.writerow([str(folder.relative_to(root)) or ".", s.path.name, s.new_stem,
                            s.date.strftime("%Y-%m-%d %H:%M:%S"), s.date_source])
    print(f"\nOpened {plan.name}. Edit the new_name column (no extension) and, if"
          "\nneeded, scan_date (YYYY-MM-DD HH:MM:SS). Save it as CSV, close it,")
    open_folder(plan)
    input("then press Enter here... ")
    lookup = {(s.path.parent, s.path.name): s for ss in scans_by_folder.values() for s in ss}
    with open(plan, newline="", encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            s = lookup.get(((root / row["folder"]).resolve(), row["original_file"]))
            if not s:
                continue
            s.new_stem = clean_name(row["new_name"]) or s.path.stem
            d = parse_date(row["scan_date"])
            if d and d != s.date:
                s.date, s.date_source = d, "set by you"
    plan.unlink()


def validate(scans_by_folder):
    problems = []
    for folder, scans in scans_by_folder.items():
        names = [s.new_stem.lower() for s in scans]
        for n in {n for n in names if names.count(n) > 1}:
            problems.append(f"{folder.name}: duplicate name '{n}'")
        tiff_names = {s.path.name.lower() for s in scans}
        for s in scans:
            target = folder / (s.new_stem + ".jpg")
            if target.exists() and target.name.lower() not in tiff_names:
                problems.append(f"{folder.name}: {target.name} already exists")
    return problems


def ask_metadata():
    last = {}
    if PRESET_FILE.exists():
        try:
            last = json.loads(PRESET_FILE.read_text(encoding="utf-8"))
        except Exception:
            last = {}
    print("\nMetadata to write into every JPEG (Enter = keep shown value, '-' = leave empty).")
    meta = {}
    for key, label in META_FIELDS:
        v = ask(f"  {label}", last.get(key, ""))
        meta[key] = "" if v == "-" else v
    try:
        PRESET_FILE.write_text(json.dumps(meta, indent=2), encoding="utf-8")
    except Exception:
        pass
    return meta


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("folder", nargs="?", help="folder of TIFF scans (subfolders included)")
    ap.add_argument("--dry-run", action="store_true", help="show the plan, change nothing")
    args = ap.parse_args()

    root = Path(args.folder or ask("Folder with the TIFF scans").strip('"')).expanduser().resolve()
    if not root.is_dir():
        sys.exit(f"Not a folder: {root}")

    folders = find_folders(root)
    if not folders:
        sys.exit("No TIFF files found.")
    print(f"\nReading {len(folders)} folder(s)...")
    scans_by_folder = {}
    for folder in folders:
        paths = sorted(p for p in folder.iterdir() if p.suffix.lower() in TIFF_EXT
                       and p.is_file() and not p.name.startswith("._"))
        scans = []
        for p in paths:
            try:
                scans.append(Scan(p))
            except Exception as ex:
                print(f"  ! cannot read {p.name}: {ex}")
        scans_by_folder[folder] = scans

    for folder, scans in scans_by_folder.items():
        print(f"\n{folder.relative_to(root.parent)}  ({len(scans)} TIFFs)")
        for s in scans:
            print(f"  {s.path.name:40} {s.width}x{s.height} {s.bits}-bit  "
                  f"scanned {s.date:%Y-%m-%d %H:%M}  <- {s.date_source}")
    if any(s.date_source.startswith("FILE DATE") for ss in scans_by_folder.values() for s in ss):
        print("\nNote: some files carry no date inside them; the file date shown may not be the"
              "\nscan date. You can correct dates in the spreadsheet option below.")

    scanners = {s.scanner for ss in scans_by_folder.values() for s in ss if s.scanner}
    if scanners:
        print(f"\nScanner recorded in the TIFFs: {', '.join(sorted(scanners))}"
              " (removed with the rest unless you enter it as scan origin)")

    # --- names
    while True:
        print("\nFile names:\n  1) keep original names\n  2) pattern"
              "\n  3) edit in a spreadsheet (CSV)")
        choice = ask("Choose", "1")
        if choice == "2":
            print("  Tokens: {name} original name, {n} number (use {n:03d} for 001),"
                  "\n          {date} scan date, {folder} folder name")
            pattern = ask("  Pattern", "{date}_{n:03d}")
            try:
                apply_pattern(pattern, scans_by_folder)
            except (KeyError, ValueError, IndexError) as ex:
                print(f"  bad pattern: {ex}")
                continue
            sample = [s for ss in scans_by_folder.values() for s in ss][:5]
            for s in sample:
                print(f"    {s.path.name} -> {s.new_stem}.jpg")
            if yes("  Fine-tune any of them in the spreadsheet?", False):
                edit_in_csv(root, scans_by_folder)
        elif choice == "3":
            edit_in_csv(root, scans_by_folder)
        problems = validate(scans_by_folder)
        if not problems:
            break
        print("\nFix these first:\n  " + "\n  ".join(problems))

    meta = ask_metadata()

    # --- plan
    sidecars = {}
    for folder, scans in scans_by_folder.items():
        found = []
        for d, _, files in os.walk(folder):
            if Path(d) != folder and Path(d) in scans_by_folder:
                continue
            in_thumbs = any(THUMB_DIRS.match(part) for part in Path(d).relative_to(folder).parts)
            for f in files:
                p = Path(d) / f
                if (in_thumbs or p.suffix.lower() in SIDECAR_EXT or f.startswith("._")
                        or f.lower() == ".ds_store"):
                    found.append(p)
        sidecars[folder] = found
    renames = {f: f.with_name(jpeg_folder_name(f.name)) for f in scans_by_folder}

    total = sum(len(s) for s in scans_by_folder.values())
    print(f"\nPlan: convert {total} TIFFs to JPEG (quality 100, 4:4:4, same pixels),"
          " then delete the TIFFs.")
    for folder in scans_by_folder:
        if sidecars[folder]:
            print(f"  delete in {folder.name}: " + ", ".join(
                str(p.relative_to(folder)) for p in sidecars[folder]))
        print(f"  folder: {folder.name} -> {renames[folder].name}")
    if args.dry_run:
        print("\n(dry run - nothing changed)")
        return
    if not yes("\nGo ahead? TIFFs are deleted after each JPEG is verified", False):
        return
    if any(renames[f].exists() and renames[f] != f for f in renames):
        if not yes("Some target folder names already exist; skip renaming those?", True):
            return

    # --- run
    for folder, scans in scans_by_folder.items():
        ok = True
        for s in scans:
            out = folder / (s.new_stem + ".jpg")
            try:
                convert(s, meta, out)
                s.path.unlink()
                print(f"  ok  {s.path.name} -> {out.name}")
            except Exception as ex:
                ok = False
                print(f"  FAILED {s.path.name}: {ex}  (TIFF kept)")
        if not ok:
            print(f"  {folder.name}: some files failed, so its info files and name were left alone.")
            continue
        for p in sidecars[folder]:
            try:
                p.unlink()
            except OSError:
                pass
        for d, _, _ in sorted(os.walk(folder), key=lambda t: -len(t[0])):
            if Path(d) != folder and not any(Path(d).iterdir()):
                Path(d).rmdir()
        scans_by_folder[folder] = "done"

    for folder in sorted(renames, key=lambda p: -len(p.parts)):
        target = renames[folder]
        if scans_by_folder[folder] == "done" and target != folder and not target.exists():
            folder.rename(target)
            print(f"  renamed folder -> {target.name}")
    print("\nDone.")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nStopped.")
