'use strict';

const fs = require('fs');
const path = require('path');

const { openZipFile, openNestedZip, forEachEntry, makeTempDir } = require('./zipsource');
const { setModified, setCreatedBatch } = require('./timestamps');

/**
 * Carries out an approved plan.
 *
 * Order matters here. Writing metadata rewrites the file and resets its
 * modified time, so timestamps are stamped last - otherwise the whole point of
 * the app would be undone on the final step.
 */

const isZip = (name) => path.extname(name).toLowerCase() === '.zip';

function writeEntry(zip, entry, targetPath) {
  return new Promise((resolve, reject) => {
    zip.openReadStream(entry, (err, readStream) => {
      if (err) return reject(err);
      fs.mkdirSync(path.dirname(targetPath), { recursive: true });
      const out = fs.createWriteStream(targetPath);
      readStream.on('error', reject);
      out.on('error', reject);
      out.on('close', resolve);
      readStream.pipe(out);
    });
  });
}

/**
 * @param {object} options
 * @param {string} options.zipPath          the archive being unpacked
 * @param {object} options.outputPlan       result of buildOutputPlan
 * @param {(p: object) => void} [options.onProgress]
 * @param {() => boolean} [options.isCancelled]
 * @param {object} [options.exiftool]       an open exiftool-vendored instance
 */
async function runPlan({ zipPath, outputPlan, onProgress = () => {}, isCancelled = () => false, exiftool = null }) {
  // entryPath is unique per file across the whole nested structure.
  const byEntryPath = new Map(outputPlan.files.map((f) => [f.sourcePath, f]));

  const tempDir = makeTempDir();
  const openZips = [];
  const tempFiles = [];
  const written = [];
  const errors = [];
  let done = 0;
  let bytesDone = 0;

  for (const folder of outputPlan.folders) fs.mkdirSync(folder, { recursive: true });

  async function walk(zip) {
    const nested = [];

    await forEachEntry(zip, async (entry) => {
      if (isCancelled()) return;
      const name = entry.fileName;
      if (name.endsWith('/')) return;

      if (isZip(path.basename(name))) {
        nested.push(entry);
        return;
      }

      const planned = byEntryPath.get(name);
      if (!planned) return; // not in the approved plan, so not written

      try {
        await writeEntry(zip, entry, planned.targetPath);
        written.push(planned);
        bytesDone += planned.size;
      } catch (err) {
        errors.push({ file: planned.targetPath, stage: 'extract', error: err.message });
      }

      done += 1;
      onProgress({
        phase: 'extracting',
        done,
        total: outputPlan.files.length,
        bytesDone,
        totalBytes: outputPlan.totalBytes,
        detail: planned.outputName,
      });
    });

    for (const entry of nested) {
      if (isCancelled()) return;
      const opened = await openNestedZip(zip, entry, tempDir);
      openZips.push(opened.zip);
      if (opened.tempPath) tempFiles.push(opened.tempPath);
      await walk(opened.zip);
    }
  }

  try {
    const rootZip = await openZipFile(zipPath);
    openZips.push(rootZip);
    await walk(rootZip);

    // --- metadata, before timestamps ---
    const withMetadata = written.filter((f) => f.metadata && Object.keys(f.metadata).length);
    if (exiftool && withMetadata.length && !isCancelled()) {
      let metaDone = 0;
      for (const file of withMetadata) {
        if (isCancelled()) break;
        try {
          await exiftool.write(file.targetPath, file.metadata, { writeArgs: ['-overwrite_original'] });
        } catch (err) {
          errors.push({ file: file.targetPath, stage: 'metadata', error: err.message });
        }
        metaDone += 1;
        onProgress({ phase: 'metadata', done: metaDone, total: withMetadata.length, detail: file.outputName });
      }
    }

    // --- timestamps, last, so nothing can overwrite them ---
    const datable = written.filter((f) => f.date);
    onProgress({ phase: 'timestamps', done: 0, total: datable.length, detail: '' });

    for (const file of datable) {
      try {
        setModified(file.targetPath, new Date(file.date));
      } catch (err) {
        errors.push({ file: file.targetPath, stage: 'timestamp', error: err.message });
      }
    }

    const created = await setCreatedBatch(datable.map((f) => ({ path: f.targetPath, date: new Date(f.date) })));
    for (const failure of created.failed || []) {
      errors.push({ file: failure.path, stage: 'created-date', error: failure.error });
    }

    onProgress({ phase: 'timestamps', done: datable.length, total: datable.length, detail: '' });

    return {
      written: written.length,
      skippedUndated: written.length - datable.length,
      createdDatesSet: created.skipped ? 0 : created.updated,
      errors,
      cancelled: isCancelled(),
    };
  } finally {
    for (const zip of openZips) {
      try { zip.close(); } catch { /* already closed */ }
    }
    for (const temp of tempFiles) {
      try { fs.unlinkSync(temp); } catch { /* best effort */ }
    }
    try { fs.rmdirSync(tempDir); } catch { /* not empty or gone */ }
  }
}

module.exports = { runPlan };
