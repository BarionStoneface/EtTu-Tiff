'use strict';

const fs = require('fs');
const path = require('path');

const { openZipFile, openNestedZip, forEachEntry, makeTempDir } = require('./zipsource');

const isZip = (name) => path.extname(name).toLowerCase() === '.zip';

/**
 * Looking at single files in an archive without unpacking the rest: a thumbnail,
 * or one file pulled out to open in its usual app.
 *
 * Every entry is indexed once by where it came from (the archive's own name, or
 * the name of the zip inside it, the same `origin` the scan records) and its path
 * in that zip. The zips stay open, so each look is a single read; a zip inside
 * that was deflated is spilled to temp once, as unpacking does.
 */
class Peeker {
  static async open(zipPath) {
    const peeker = new Peeker(zipPath);
    const root = await openZipFile(zipPath);
    peeker.zips.push(root);
    await peeker.index(root, path.basename(zipPath));
    return peeker;
  }

  constructor(zipPath) {
    this.zipPath = zipPath;
    this.entries = new Map();
    this.zips = [];
    this.tempFiles = [];
    this.tempDir = makeTempDir();
  }

  static key(origin, entryPath) {
    return `${origin}\u0000${entryPath}`;
  }

  async index(zip, origin) {
    const nested = [];
    await forEachEntry(zip, (entry) => {
      const name = entry.fileName;
      if (name.endsWith('/')) return;
      if (isZip(path.basename(name))) nested.push(entry);
      else this.entries.set(Peeker.key(origin, name), { zip, entry });
    });
    for (const entry of nested) {
      const opened = await openNestedZip(zip, entry, this.tempDir);
      this.zips.push(opened.zip);
      if (opened.tempPath) this.tempFiles.push(opened.tempPath);
      await this.index(opened.zip, path.basename(entry.fileName));
    }
  }

  find(origin, entryPath) {
    const found = this.entries.get(Peeker.key(origin, entryPath));
    if (!found) throw new Error(`${entryPath} is not in this archive`);
    return found;
  }

  /** The whole file in memory, for a thumbnail. Null when it's bigger than [limit]. */
  read(origin, entryPath, limit) {
    const { zip, entry } = this.find(origin, entryPath);
    if (entry.uncompressedSize > limit) return Promise.resolve(null);
    return new Promise((resolve, reject) => {
      zip.openReadStream(entry, (err, stream) => {
        if (err) return reject(err);
        const chunks = [];
        stream.on('data', (c) => chunks.push(c));
        stream.on('end', () => resolve(Buffer.concat(chunks)));
        stream.on('error', reject);
      });
    });
  }

  /**
   * Writes one file into [dir] under its own name, as name.part first so a
   * half-written file is never opened, and returns its path.
   */
  extract(origin, entryPath, dir) {
    const { zip, entry } = this.find(origin, entryPath);
    fs.mkdirSync(dir, { recursive: true });
    const name = path.basename(entryPath).replace(/[<>:"/\\|?*\x00-\x1f]/g, '_') || 'file';
    const target = path.join(dir, name);
    try {
      if (fs.statSync(target).size === entry.uncompressedSize) return Promise.resolve(target);
    } catch { /* not there yet */ }
    const part = `${target}.part`;
    return new Promise((resolve, reject) => {
      zip.openReadStream(entry, (err, stream) => {
        if (err) return reject(err);
        const out = fs.createWriteStream(part);
        stream.on('error', reject);
        out.on('error', reject);
        out.on('close', () => {
          try {
            fs.renameSync(part, target);
            resolve(target);
          } catch (e) {
            reject(e);
          }
        });
        stream.pipe(out);
      });
    });
  }

  close() {
    for (const zip of this.zips) {
      try { zip.close(); } catch { /* already closed */ }
    }
    for (const temp of this.tempFiles) {
      try { fs.unlinkSync(temp); } catch { /* best effort */ }
    }
    try { fs.rmdirSync(this.tempDir); } catch { /* not empty or gone */ }
  }
}

module.exports = { Peeker };
