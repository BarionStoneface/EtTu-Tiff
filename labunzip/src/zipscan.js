'use strict';

const path = require('path');
const fs = require('fs');
const exifr = require('exifr');

const { openZipFile, openNestedZip, forEachEntry, readHead, makeTempDir } = require('./zipsource');
const { resolveTimestamp, summariseSources } = require('./dates');

// 64 KB is enough to reach the EXIF/XMP block in both JPEG and TIFF from these
// labs - measured against the real scans, not assumed.
const HEAD_BYTES = 64 * 1024;

const IMAGE_EXT = new Set(['.jpg', '.jpeg', '.tif', '.tiff', '.png', '.webp', '.dng', '.heic']);
const isZip = (name) => path.extname(name).toLowerCase() === '.zip';
// Junk that zips made on a Mac or Windows carry along; never part of a roll.
const JUNK = /(^|\/)(__MACOSX\/|\._)|(^|\/)(\.DS_Store|Thumbs\.db|desktop\.ini)$/i;
const isImage = (name) => IMAGE_EXT.has(path.extname(name).toLowerCase());

const EXIF_OPTS = { tiff: true, exif: true, ifd0: true, xmp: true, iptc: false, jfif: false };

async function readExifDate(buffer) {
  try {
    return await exifr.parse(buffer, EXIF_OPTS);
  } catch {
    return null; // a file with no parseable metadata is normal, not an error
  }
}

/** Splits a zip entry path into its folder segments and file name. */
function splitEntryPath(entryName) {
  const parts = entryName.split('/').filter((p) => p && p !== '.' && p !== '..');
  return { dirs: parts.slice(0, -1), name: parts[parts.length - 1] };
}

/**
 * Reads a zip and every zip nested inside it, without writing anything to the
 * destination. Returns the tree that extraction *would* produce, so it can be
 * reviewed and edited first.
 *
 * @param {string} zipPath
 * @param {(progress: {phase: string, detail: string, files: number}) => void} [onProgress]
 */
async function scanArchive(zipPath, onProgress = () => {}) {
  const tempDir = makeTempDir();
  const openZips = [];
  const tempFiles = [];
  let fileCount = 0;
  let nestedCount = 0;

  const root = { name: path.basename(zipPath, path.extname(zipPath)), dirs: new Map(), files: [] };

  /** Finds or creates a folder node for a list of path segments. */
  function folderAt(node, segments) {
    let current = node;
    for (const segment of segments) {
      if (!current.dirs.has(segment)) current.dirs.set(segment, { name: segment, dirs: new Map(), files: [] });
      current = current.dirs.get(segment);
    }
    return current;
  }

  async function walk(zip, baseNode, originLabel) {
    const nestedJobs = [];

    await forEachEntry(zip, async (entry) => {
      const name = entry.fileName;
      if (name.endsWith('/')) return; // directory marker; folders come from file paths
      if (JUNK.test(name)) return;

      const { dirs, name: fileName } = splitEntryPath(name);
      if (!fileName) return;

      if (isZip(fileName)) {
        // Defer: we cannot open a nested zip while the parent is mid-iteration.
        nestedJobs.push({ entry, dirs, fileName });
        return;
      }

      const parent = folderAt(baseNode, dirs);
      const archiveDate = entry.getLastModDate();
      let exif = null;

      if (isImage(fileName)) {
        try {
          exif = await readExifDate(await readHead(zip, entry, HEAD_BYTES));
        } catch {
          exif = null;
        }
      }

      const resolved = resolveTimestamp(exif, archiveDate);
      parent.files.push({
        originalName: fileName,
        size: entry.uncompressedSize,
        archiveDate: archiveDate ? archiveDate.toISOString() : null,
        date: resolved.date ? resolved.date.toISOString() : null,
        dateSource: resolved.source,
        dateSourceLabel: resolved.sourceLabel,
        origin: originLabel,
        entryPath: name,
      });

      fileCount += 1;
      if (fileCount % 25 === 0) onProgress({ phase: 'scanning', detail: fileName, files: fileCount });
    });

    for (const job of nestedJobs) {
      nestedCount += 1;
      // A nested Jpegs.zip becomes a Jpegs/ folder, which is what unpacking it
      // by hand would have produced.
      const nestedFolderName = path.basename(job.fileName, path.extname(job.fileName));
      const parent = folderAt(baseNode, [...job.dirs, nestedFolderName]);
      onProgress({ phase: 'opening', detail: job.fileName, files: fileCount });

      const opened = await openNestedZip(zip, job.entry, tempDir);
      openZips.push(opened.zip);
      if (opened.tempPath) tempFiles.push(opened.tempPath);
      await walk(opened.zip, parent, job.fileName);
    }
  }

  const rootZip = await openZipFile(zipPath);
  openZips.push(rootZip);

  try {
    await walk(rootZip, root, path.basename(zipPath));
  } finally {
    for (const zip of openZips) {
      try { zip.close(); } catch { /* already closed */ }
    }
    for (const temp of tempFiles) {
      try { fs.unlinkSync(temp); } catch { /* best effort */ }
    }
    try { fs.rmdirSync(tempDir); } catch { /* not empty or already gone */ }
  }

  return { root: serialise(root, ''), fileCount, nestedCount, sourceZip: zipPath };
}

/** Converts the Map-based tree into plain JSON for the UI, with per-folder summaries. */
function serialise(node, parentPath) {
  const nodePath = parentPath ? `${parentPath}/${node.name}` : node.name;
  const children = [...node.dirs.values()]
    .sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }))
    .map((child) => serialise(child, nodePath));

  const files = node.files.slice().sort((a, b) => a.originalName.localeCompare(b.originalName, undefined, { numeric: true }));

  return {
    id: nodePath,
    name: node.name,
    path: nodePath,
    folders: children,
    files,
    fileCount: files.length,
    totalFileCount: files.length + children.reduce((sum, c) => sum + c.totalFileCount, 0),
    totalBytes: files.reduce((s, f) => s + f.size, 0) + children.reduce((s, c) => s + c.totalBytes, 0),
    dateSources: summariseSources(files.map((f) => ({ sourceLabel: f.dateSourceLabel }))),
    undatedCount: files.filter((f) => !f.date).length,
  };
}

/** Flattens the tree to the folders that actually hold files - one per roll. */
function rollFolders(node, out = []) {
  if (node.fileCount > 0) out.push(node);
  for (const child of node.folders) rollFolders(child, out);
  return out;
}

module.exports = { scanArchive, rollFolders, HEAD_BYTES };
