"""Makes the test zips in app/src/test/resources/zip, each with a tool other than this app.

  zipcrypto.zip      Info-ZIP `zip -P`: the old password kind, check value after the data
  aes128.zip         pyzipper: WinZip AES-128, deflate
  aes256.zip         pyzipper: WinZip AES-256, one deflated and one stored file
  deflate64.zip      inflate64's Deflate64 encoder, sizes in the header, plus long.bin
                     hand-encoded to use what only Deflate64 has (a 40 KB reach, a 40,000 byte match)
  deflate64-stream.zip   the same, sizes only after the data, as a zipper writing to a pipe does
  locked-inner.zip   `zip -P` holding a zip that's locked with the same password

Password everywhere: "film soup ñ" (the ñ checks how letters become bytes).
Pip: pyzipper inflate64. Needs the `zip` command.
"""
import os
import random
import struct
import subprocess
import sys
import tempfile
import zipfile
import zlib

import inflate64
import pyzipper

out = sys.argv[1] if len(sys.argv) > 1 else "app/src/test/resources/zip"
os.makedirs(out, exist_ok=True)
PW = "film soup ñ"
rnd = random.Random(5)


def payload(n, seed):
    # Text with long repeats far apart, so Deflate64's bigger window and longer matches get used.
    r = random.Random(seed)
    block = bytes(r.randrange(256) for _ in range(40_000))
    words = b" ".join(r.choice([b"roll", b"scan", b"tiff", b"jpeg", b"film", b"soup"]) for _ in range(n // 5))
    # The block again 40 KB on: further back than ordinary deflate can reach.
    return (block + block + words)[:n]


FILES = {"Roll 1/notes.txt": payload(150_000, 1), "Roll 1/scan001.bin": payload(90_000, 2), "readme.txt": b"hello\n"}

with tempfile.TemporaryDirectory() as t:
    for name, data in FILES.items():
        os.makedirs(os.path.join(t, os.path.dirname(name)), exist_ok=True)
        open(os.path.join(t, name), "wb").write(data)
    target = os.path.abspath(f"{out}/zipcrypto.zip")
    if os.path.exists(target):
        os.remove(target)
    subprocess.run(["zip", "-q", "-r", "-P", PW, target, *FILES], cwd=t, check=True)

    inner = os.path.join(t, "Inner.zip")
    subprocess.run(["zip", "-q", "-P", PW, inner, "readme.txt", "Roll 1/notes.txt"], cwd=t, check=True)
    target = os.path.abspath(f"{out}/locked-inner.zip")
    if os.path.exists(target):
        os.remove(target)
    subprocess.run(["zip", "-q", "-0", "-P", PW, target, "Inner.zip", "readme.txt"], cwd=t, check=True)

for bits, name in ((128, "aes128.zip"), (256, "aes256.zip")):
    with pyzipper.AESZipFile(f"{out}/{name}", "w", compression=pyzipper.ZIP_DEFLATED, encryption=pyzipper.WZ_AES) as z:
        z.setpassword(PW.encode())
        z.setencryption(pyzipper.WZ_AES, nbits=bits)
        for n, d in FILES.items():
            z.writestr(n, d)
        if bits == 256:
            z.writestr("stored.txt", b"left as it is " * 50, compress_type=pyzipper.ZIP_STORED)


def deflate64(data):
    c = inflate64.Deflater()
    return c.deflate(data) + c.flush()


def hand_deflate64(block):
    """[block] as literals, then one match repeating it from 40 KB back: distance code 30 and
    length code 285 with 16 extra bits, which only Deflate64 has (inflate64's encoder never uses them)."""
    out, acc, n = bytearray(), 0, 0

    def put(value, bits):
        nonlocal acc, n
        acc |= value << n
        n += bits
        while n >= 8:
            out.append(acc & 0xFF)
            acc >>= 8
            n -= 8

    def huff(code, bits):  # Huffman codes go in highest bit first
        put(int(format(code, f"0{bits}b")[::-1], 2), bits)

    def lit(sym):
        if sym < 144: huff(0x30 + sym, 8)
        elif sym < 256: huff(0x190 + sym - 144, 9)
        elif sym < 280: huff(sym - 256, 7)
        else: huff(0xC0 + sym - 280, 8)

    put(1, 1); put(1, 2)  # last block, fixed codes
    for b in block:
        lit(b)
    lit(285); put(len(block) - 3, 16)
    huff(30, 5); put(len(block) - 32769, 14)
    lit(256)
    if n:
        out.append(acc & 0xFF)
    return bytes(out)


LONG = bytes(random.Random(9).randrange(256) for _ in range(40_000))


def write_d64(path, stream):
    """A zip of Deflate64 files, built by hand: no Python zip library writes method 9."""
    body, central = bytearray(), bytearray()
    dos_time, dos_date = (14 << 11) | (22 << 5) | 5, ((2024 - 1980) << 9) | (3 << 5) | 5
    for name, data in {**FILES, "long.bin": LONG + LONG}.items():
        comp = hand_deflate64(LONG) if name == "long.bin" else deflate64(data)
        crc = zlib.crc32(data)
        nb = name.encode()
        flags = 8 if stream else 0
        offset = len(body)
        sizes = (0, 0, 0) if stream else (crc, len(comp), len(data))
        body += struct.pack("<IHHHHHIIIHH", 0x04034B50, 21, flags, 9, dos_time, dos_date, *sizes, len(nb), 0) + nb + comp
        if stream:
            body += struct.pack("<IIII", 0x08074B50, crc, len(comp), len(data))
        central += struct.pack("<IHHHHHHIIIHHHHHII", 0x02014B50, 21, 21, flags, 9, dos_time, dos_date, crc, len(comp),
                               len(data), len(nb), 0, 0, 0, 0, 0, offset) + nb
    end = struct.pack("<IHHHHIIH", 0x06054B50, 0, 0, len(FILES) + 1, len(FILES) + 1, len(central), len(body), 0)
    open(path, "wb").write(bytes(body + central + end))


write_d64(f"{out}/deflate64.zip", False)
write_d64(f"{out}/deflate64-stream.zip", True)

for f in sorted(os.listdir(out)):
    print(f"{os.path.getsize(os.path.join(out, f)):>9,}  {f}")
