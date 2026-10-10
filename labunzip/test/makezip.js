'use strict';

/**
 * Minimal zip writer, used only by the tests.
 *
 * Everything is written with compression method 0 (stored), which is also how
 * real zip tools handle an already-compressed file like a nested .zip - so this
 * exercises the in-place nested reader that the app relies on.
 */
const fs = require('fs');
const zlib = require('zlib');

function dosDateTime(date) {
  const time = ((date.getHours() & 0x1f) << 11) | ((date.getMinutes() & 0x3f) << 5) | ((date.getSeconds() / 2) & 0x1f);
  const day = (((date.getFullYear() - 1980) & 0x7f) << 9) | (((date.getMonth() + 1) & 0x0f) << 5) | (date.getDate() & 0x1f);
  return { time, day };
}

/**
 * @param {Array<{name: string, data: Buffer, date?: Date}>} entries
 * @returns {Buffer}
 */
function buildZip(entries) {
  const locals = [];
  const central = [];
  let offset = 0;

  for (const entry of entries) {
    const nameBuf = Buffer.from(entry.name, 'utf8');
    const data = entry.data;
    const crc = zlib.crc32 ? zlib.crc32(data) : crc32(data);
    const { time, day } = dosDateTime(entry.date || new Date());

    const local = Buffer.alloc(30 + nameBuf.length);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);          // version needed
    local.writeUInt16LE(0, 6);           // flags
    local.writeUInt16LE(0, 8);           // method: stored
    local.writeUInt16LE(time, 10);
    local.writeUInt16LE(day, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    local.writeUInt16LE(0, 28);
    nameBuf.copy(local, 30);

    locals.push(local, data);

    const cd = Buffer.alloc(46 + nameBuf.length);
    cd.writeUInt32LE(0x02014b50, 0);
    cd.writeUInt16LE(20, 4);
    cd.writeUInt16LE(20, 6);
    cd.writeUInt16LE(0, 8);
    cd.writeUInt16LE(0, 10);
    cd.writeUInt16LE(time, 12);
    cd.writeUInt16LE(day, 14);
    cd.writeUInt32LE(crc, 16);
    cd.writeUInt32LE(data.length, 20);
    cd.writeUInt32LE(data.length, 24);
    cd.writeUInt16LE(nameBuf.length, 28);
    cd.writeUInt16LE(0, 30);             // extra
    cd.writeUInt16LE(0, 32);             // comment
    cd.writeUInt16LE(0, 34);             // disk
    cd.writeUInt16LE(0, 36);             // internal attrs
    cd.writeUInt32LE(0, 38);             // external attrs
    cd.writeUInt32LE(offset, 42);
    nameBuf.copy(cd, 46);
    central.push(cd);

    offset += local.length + data.length;
  }

  const centralBuf = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(0, 4);
  end.writeUInt16LE(0, 6);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(centralBuf.length, 12);
  end.writeUInt32LE(offset, 16);
  end.writeUInt16LE(0, 20);

  return Buffer.concat([...locals, centralBuf, end]);
}

// Fallback for Node versions without zlib.crc32.
let table = null;
function crc32(buf) {
  if (!table) {
    table = new Int32Array(256);
    for (let i = 0; i < 256; i++) {
      let c = i;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      table[i] = c;
    }
  }
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = (c >>> 8) ^ table[(c ^ buf[i]) & 0xff];
  return (c ^ -1) >>> 0;
}

function writeZip(filePath, entries) {
  fs.writeFileSync(filePath, buildZip(entries));
  return filePath;
}

/** A tiny JPEG carrying a real EXIF DateTimeOriginal, for date-resolution tests. */
function jpegWithDate(dateString) {
  const ascii = (s) => Buffer.from(s + '\0', 'ascii');
  const dateBuf = ascii(dateString); // "YYYY:MM:DD HH:MM:SS"

  // One IFD0 entry pointing at an EXIF sub-IFD, which holds DateTimeOriginal.
  const tiff = Buffer.alloc(8 + 2 + 12 + 4 + 2 + 12 + 4 + dateBuf.length);
  let p = 0;
  tiff.write('II', p); p += 2;
  tiff.writeUInt16LE(42, p); p += 2;
  tiff.writeUInt32LE(8, p); p += 4;          // IFD0 at offset 8

  tiff.writeUInt16LE(1, p); p += 2;          // one entry
  tiff.writeUInt16LE(0x8769, p); p += 2;     // ExifIFDPointer
  tiff.writeUInt16LE(4, p); p += 2;          // LONG
  tiff.writeUInt32LE(1, p); p += 4;
  const exifIfdOffset = 8 + 2 + 12 + 4;
  tiff.writeUInt32LE(exifIfdOffset, p); p += 4;
  tiff.writeUInt32LE(0, p); p += 4;          // no next IFD

  tiff.writeUInt16LE(1, p); p += 2;          // one entry in EXIF IFD
  tiff.writeUInt16LE(0x9003, p); p += 2;     // DateTimeOriginal
  tiff.writeUInt16LE(2, p); p += 2;          // ASCII
  tiff.writeUInt32LE(dateBuf.length, p); p += 4;
  const dataOffset = exifIfdOffset + 2 + 12 + 4;
  tiff.writeUInt32LE(dataOffset, p); p += 4;
  tiff.writeUInt32LE(0, p); p += 4;
  dateBuf.copy(tiff, p);

  const app1 = Buffer.concat([Buffer.from('Exif\0\0', 'ascii'), tiff]);
  const app1Header = Buffer.alloc(4);
  app1Header.writeUInt16BE(0xffe1, 0);
  app1Header.writeUInt16BE(app1.length + 2, 2);

  return Buffer.concat([
    Buffer.from([0xff, 0xd8]),   // SOI
    app1Header, app1,
    Buffer.from([0xff, 0xd9]),   // EOI
  ]);
}

module.exports = { buildZip, writeZip, jpegWithDate };
