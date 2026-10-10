'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');
const yauzl = require('yauzl');
const { PassThrough } = require('stream');

const OPEN_OPTS = { lazyEntries: true, autoClose: false, decodeStrings: true };

/**
 * Opening a zip that lives inside another zip.
 *
 * A zip's directory is at the end of the file, so it needs random access - you
 * cannot parse one from a forward-only stream. The inner archives here can be
 * 13 GB, so buffering them is out of the question.
 *
 * Zip tools almost always store an already-compressed file (like a .zip) rather
 * than deflating it again. When that is the case the inner archive is sitting
 * in the outer file verbatim, and we can read it in place through a reader that
 * just offsets into the parent. Nothing is copied and nothing hits the disk.
 *
 * Only when an inner zip really was deflated do we fall back to spilling it to
 * a temp file, because then there is no byte range to point at.
 */
class NestedReader extends yauzl.RandomAccessReader {
  constructor(parentZip, entry) {
    super();
    this.parentZip = parentZip;
    this.entry = entry;
  }

  _readStreamForRange(start, end) {
    // yauzl needs a stream back synchronously, but openReadStream is
    // callback-based, so hand back a passthrough and feed it once it opens.
    const out = new PassThrough();
    // The entry is stored, so the bytes in the parent are already the inner
    // archive verbatim. yauzl rejects a `decompress` option on stored entries,
    // so the range is all we pass.
    this.parentZip.openReadStream(this.entry, { start, end }, (err, stream) => {
      if (err) return out.destroy(err);
      stream.on('error', (e) => out.destroy(e));
      stream.pipe(out);
    });
    return out;
  }
}

function openZipFile(filePath) {
  return new Promise((resolve, reject) => {
    yauzl.open(filePath, OPEN_OPTS, (err, zip) => (err ? reject(err) : resolve(zip)));
  });
}

function openFromReader(reader, size) {
  return new Promise((resolve, reject) => {
    yauzl.fromRandomAccessReader(reader, size, OPEN_OPTS, (err, zip) => (err ? reject(err) : resolve(zip)));
  });
}

/** Writes a deflated inner zip out to temp so it can be opened with seeking. */
async function spillToTemp(parentZip, entry, tempDir) {
  const target = path.join(tempDir, `nested-${Date.now()}-${Math.random().toString(36).slice(2)}.zip`);
  await new Promise((resolve, reject) => {
    parentZip.openReadStream(entry, (err, readStream) => {
      if (err) return reject(err);
      const out = fs.createWriteStream(target);
      readStream.on('error', reject);
      out.on('error', reject);
      out.on('close', resolve);
      readStream.pipe(out);
    });
  });
  return target;
}

/**
 * @returns {{zip: object, tempPath: string|null, inPlace: boolean}}
 */
async function openNestedZip(parentZip, entry, tempDir) {
  const STORED = 0;
  if (entry.compressionMethod === STORED) {
    const reader = new NestedReader(parentZip, entry);
    const zip = await openFromReader(reader, entry.uncompressedSize);
    return { zip, tempPath: null, inPlace: true };
  }
  const tempPath = await spillToTemp(parentZip, entry, tempDir);
  const zip = await openZipFile(tempPath);
  return { zip, tempPath, inPlace: false };
}

/** Walks every entry in an opened zip. */
function forEachEntry(zip, onEntry) {
  return new Promise((resolve, reject) => {
    zip.readEntry();
    zip.on('entry', (entry) => {
      Promise.resolve(onEntry(entry))
        .then(() => zip.readEntry())
        .catch(reject);
    });
    zip.on('end', resolve);
    zip.on('error', reject);
  });
}

/**
 * Pulls the first `limit` decompressed bytes of an entry and stops.
 * Used to read EXIF without inflating a 145 MB TIFF in full.
 */
function readHead(zip, entry, limit) {
  return new Promise((resolve, reject) => {
    zip.openReadStream(entry, (err, stream) => {
      if (err) return reject(err);
      const chunks = [];
      let total = 0;
      let done = false;
      const finish = () => {
        if (done) return;
        done = true;
        stream.destroy();
        resolve(Buffer.concat(chunks, Math.min(total, limit)));
      };
      stream.on('data', (chunk) => {
        chunks.push(chunk);
        total += chunk.length;
        if (total >= limit) finish();
      });
      stream.on('end', finish);
      stream.on('error', (e) => (done ? undefined : (done = true, reject(e))));
    });
  });
}

function makeTempDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'labunzip-'));
}

module.exports = { openZipFile, openNestedZip, forEachEntry, readHead, makeTempDir };
