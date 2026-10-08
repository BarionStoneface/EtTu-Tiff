'use strict';

/**
 * Works out which timestamp a file should get.
 *
 * The whole point of this app: an extracted file's date is the date you
 * downloaded it, which is often the wrong month entirely. That date is never
 * an acceptable answer, so it is not in this list at all.
 *
 * Order of preference:
 *   1. EXIF DateTimeOriginal   - when the frame was shot, if anything set it
 *   2. EXIF CreateDate         - digitised/scanned
 *   3. EXIF ModifyDate
 *   4. XMP MetadataDate        - what the labs here actually write
 *   5. archive entry mtime     - set when the lab wrote the scan, survives download
 *
 * If none of those exist the file is reported as UNRESOLVED and left alone,
 * rather than being stamped with today's date.
 */

const EXIF_DATE_FIELDS = [
  ['DateTimeOriginal', 'exif:DateTimeOriginal'],
  ['CreateDate', 'exif:CreateDate'],
  ['DateTimeDigitized', 'exif:DateTimeDigitized'],
  ['ModifyDate', 'exif:ModifyDate'],
  ['MetadataDate', 'xmp:MetadataDate'],
  ['XMPCreateDate', 'xmp:CreateDate'],
];

/** A date is usable if it parses and is not absurd. */
function usable(value) {
  if (value === undefined || value === null) return null;
  const d = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(d.getTime())) return null;
  const year = d.getUTCFullYear();
  // Film scans are not from 1970 and not from the 22nd century. A zero or
  // epoch date usually means the field existed but was never filled in.
  if (year < 1980 || year > 2100) return null;
  return d;
}

/**
 * @param {object|null} exif   parsed tags, or null if the file has none
 * @param {Date|null} archiveDate  mtime from the zip central directory
 * @returns {{date: Date|null, source: string, sourceLabel: string}}
 */
function resolveTimestamp(exif, archiveDate) {
  if (exif) {
    for (const [field, label] of EXIF_DATE_FIELDS) {
      const d = usable(exif[field]);
      if (d) return { date: d, source: field, sourceLabel: label };
    }
  }

  const archive = usable(archiveDate);
  if (archive) return { date: archive, source: 'archive', sourceLabel: 'archive date' };

  return { date: null, source: 'none', sourceLabel: 'no usable date' };
}

/**
 * Summarises where the dates in a group of files came from, for the plan view.
 * @param {Array<{source: string}>} resolved
 */
function summariseSources(resolved) {
  const counts = new Map();
  for (const r of resolved) counts.set(r.sourceLabel, (counts.get(r.sourceLabel) || 0) + 1);
  return [...counts.entries()]
    .sort((a, b) => b[1] - a[1])
    .map(([label, count]) => ({ label, count }));
}

module.exports = { resolveTimestamp, summariseSources, usable, EXIF_DATE_FIELDS };
