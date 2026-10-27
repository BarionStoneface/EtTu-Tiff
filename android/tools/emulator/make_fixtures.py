"""Makes the folders and zip the emulator walk pushes to the phone.

Scans/Roll A   three 8-bit RGB TIFFs with a scan date and scanner tags
Scans/Roll B   two TIFFs with no date at all (the "enter a date" path),
               one of them a 16-bit 5000x3400 scan, about 100 MB, like a real film scan
Download/lab.zip   the way labs ship rolls: Jpegs.zip and Tiffs.zip inside one zip
Download/docs.zip  any other zip: documents and one photo, password "filmsoup"
"""
import io
import os
import sys
import zipfile

import numpy as np
import tifffile
from PIL import Image

out = sys.argv[1]
rng = np.random.default_rng(7)


def picture(w, h, bits=8):
    # A soft gradient plus grain, so JPEG sizes are realistic rather than all flat colour.
    y, x = np.mgrid[0:h, 0:w]
    base = np.stack([x / w, y / h, (x + y) / (w + h)], axis=-1)
    grain = rng.normal(0, 0.03, (h, w, 3))
    img = np.clip(base + grain, 0, 1)
    return (img * (65535 if bits == 16 else 255)).astype(np.uint16 if bits == 16 else np.uint8)


def tiff(path, w, h, bits=8, date=None):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    extra = [(271, "s", 0, "Noritsu", True), (272, "s", 0, "HS-1800", True)]
    if date:
        extra.append((306, "s", 0, date, True))
    tifffile.imwrite(path, picture(w, h, bits), photometric="rgb", compression="zlib" if bits == 8 else None,
                     resolution=(2000, 2000), software="EZ Controller", extratags=extra)


for i in range(1, 4):
    tiff(f"{out}/Pictures/Scans/Roll A/A{i:03}.tif", 2400, 1600, 8, "2019:04:12 14:3%d:00" % i)
tiff(f"{out}/Pictures/Scans/Roll B/B001.tif", 1800, 1200, 8)
tiff(f"{out}/Pictures/Scans/Roll B/B002.tif", 5000, 3400, 16)


def jpeg_bytes(w, h):
    b = io.BytesIO()
    Image.fromarray(picture(w, h)).save(b, "JPEG", quality=92)
    return b.getvalue()


def tiff_bytes(w, h):
    b = io.BytesIO()
    tifffile.imwrite(b, picture(w, h), photometric="rgb", extratags=[(306, "s", 0, "2021:08:03 10:00:00", True)])
    return b.getvalue()


def inner(files, method):
    b = io.BytesIO()
    with zipfile.ZipFile(b, "w", method) as z:
        for name, data in files:
            z.writestr(zipfile.ZipInfo(name, (2021, 8, 3, 10, 0, 0)), data, method)
    return b.getvalue()


os.makedirs(f"{out}/Download", exist_ok=True)
jpegs = inner([(f"Order 4471/Roll 1/{i:05}.jpg", jpeg_bytes(1500, 1000)) for i in range(1, 5)], zipfile.ZIP_DEFLATED)
tiffs = inner([(f"Order 4471/Roll 1/{i:05}.tif", tiff_bytes(1500, 1000)) for i in range(1, 5)], zipfile.ZIP_STORED)
with zipfile.ZipFile(f"{out}/Download/lab.zip", "w", zipfile.ZIP_STORED) as z:
    z.writestr(zipfile.ZipInfo("Jpegs.zip", (2021, 8, 3, 10, 0, 0)), jpegs)
    z.writestr(zipfile.ZipInfo("Tiffs.zip", (2021, 8, 3, 10, 0, 0)), tiffs)

# Not photos, and locked with a password (AES, as 7-Zip and WinZip make): unzipping any kind of zip.
import pyzipper
with pyzipper.AESZipFile(f"{out}/Download/docs.zip", "w", compression=pyzipper.ZIP_DEFLATED, encryption=pyzipper.WZ_AES) as z:
    z.setpassword(b"filmsoup")
    z.writestr("report.pdf", b"%PDF-1.4\n" + bytes(rng.integers(0, 256, 200_000, dtype=np.uint8)))
    z.writestr("notes.txt", b"Shot on a Pentax, developed at home.\n" * 200)
    z.writestr("data/table.csv", b"frame,shutter,aperture\n" + b"".join(b"%d,125,8\n" % i for i in range(36)))
    z.writestr("cover.jpg", jpeg_bytes(800, 600))

for root, _, files in os.walk(out):
    for f in files:
        p = os.path.join(root, f)
        print(f"{os.path.getsize(p):>12,}  {os.path.relpath(p, out)}")
