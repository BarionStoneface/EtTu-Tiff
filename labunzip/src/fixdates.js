'use strict';

const fs = require('fs');
const path = require('path');
const exifr = require('exifr');

const { resolveTimestamp, summariseSources } = require('./dates');
const { setModified, setCreatedBatch } = require('./timestamps');

/**
 * Gives every photo in a folder its scan date as its created and modified date, read from
 * inside the photo by the same rules as unpacking. For photos copied off a phone (which can't
 * set file dates), or anything else that arrived with the wrong date.
 *
 * Only the date inside the file counts here: there is no zip to fall back on, and the file's
 * own date is the thing being fixed, so a photo with no date inside it is left alone.
 */

const IMAGE_EXT = new Set(['.jpg', '.jpeg', '.tif', '.tiff', '.png', '.webp', '.dng', '.heic']);
const JUNK = /^(\._|\.DS_Store$|Thumbs\.db$|desktop\.ini$)/i;
const EXIF_OPTS = { tiff: true, exif: true, ifd0: true, xmp: true, iptc: false, jfif: false };

function imagesIn(folder) {
  const out = [];
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      if (JUNK.test(entry.name) || entry.name === '__MACOSX') continue;
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (IMAGE_EXT.has(path.extname(entry.name).toLowerCase())) out.push(full);
    }
  };
  walk(folder);
  return out.sort();
}

/**
 * @param {string} folder
 * @param {{dryRun?: boolean, onProgress?: (done: number, total: number, file: string) => void}} [options]
 */
async function fixDates(folder, { dryRun = false, onProgress = () => {} } = {}) {
  const files = imagesIn(folder);
  const dated = [];
  const undated = [];
  const failed = [];
  const resolved = [];

  for (let i = 0; i < files.length; i++) {
    const file = files[i];
    onProgress(i, files.length, file);
    let exif = null;
    try { exif = await exifr.parse(file, EXIF_OPTS); } catch { exif = null; }
    const r = resolveTimestamp(exif, null);
    resolved.push(r);
    if (!r.date) { undated.push(file); continue; }
    if (!dryRun) {
      try { setModified(file, r.date); } catch (err) { failed.push({ path: file, error: err.message }); continue; }
    }
    dated.push({ path: file, date: r.date, source: r.sourceLabel });
  }

  let createdSet = 0;
  if (!dryRun && dated.length) {
    const created = await setCreatedBatch(dated.map((d) => ({ path: d.path, date: d.date })));
    createdSet = created.skipped ? 0 : created.updated;
    for (const f of created.failed || []) failed.push(f);
  }
  onProgress(files.length, files.length, '');

  return {
    checked: files.length,
    dated,
    undated,
    failed,
    createdSet,
    dryRun,
    sources: summariseSources(resolved.filter((r) => r.date)),
  };
}

/** Plain-English summary for a message box or the terminal. */
function describe(result, folder) {
  const lines = [];
  const verb = result.dryRun ? 'would get' : 'now have';
  lines.push(`${result.dated.length} of ${result.checked} photo(s) in ${folder} ${verb} their scan date.`);
  if (result.sources.length) lines.push('Dates from: ' + result.sources.map((s) => `${s.label} (${s.count})`).join(', '));
  if (result.undated.length) {
    lines.push(`${result.undated.length} have no date inside them and were left alone:`);
    lines.push(...result.undated.slice(0, 10).map((p) => `  ${path.relative(folder, p)}`));
    if (result.undated.length > 10) lines.push(`  ...and ${result.undated.length - 10} more`);
  }
  if (result.failed.length) {
    lines.push(`${result.failed.length} couldn't be changed:`);
    lines.push(...result.failed.slice(0, 10).map((f) => `  ${path.relative(folder, f.path)}: ${f.error}`));
  }
  return lines.join('\n');
}

module.exports = { fixDates, describe, imagesIn };
