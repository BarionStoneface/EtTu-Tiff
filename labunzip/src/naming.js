'use strict';

const path = require('path');

/**
 * Builds output filenames.
 *
 * Hard rule: the scanner's original number is never replaced. Whatever the lab
 * called the file stays in the name in full; the pattern only adds text around
 * it. There is deliberately no way to express "replace the name".
 *
 *   008030000001.jpg  + prefix "Lomo400"   -> Lomo400_008030000001.jpg
 *   008030000001.jpg  + suffix "RoyalWe"   -> 008030000001_RoyalWe.jpg
 *   008030000001.jpg  + both               -> Lomo400_008030000001_RoyalWe.jpg
 */

// Punctuation Windows rejects outright. Spaces and hyphens are legal and are
// deliberately left alone -- "Lomo 400 March" is a name someone may well want.
const ILLEGAL_PUNCTUATION = '<>:"/\\|?*';
// Names Windows reserves regardless of extension.
const RESERVED = /^(con|prn|aux|nul|com[1-9]|lpt[1-9])$/i;

function isIllegal(char) {
  return ILLEGAL_PUNCTUATION.includes(char) || char.charCodeAt(0) < 32;
}

/** Strips characters Windows rejects, without touching anything legal. */
function sanitisePart(text) {
  const input = String(text == null ? '' : text);
  let out = '';
  for (const char of input) if (!isIllegal(char)) out += char;
  return out.trim();
}

/** Makes a folder name safe while leaving an already-legal name untouched. */
function sanitiseFolderName(name, fallback = 'folder') {
  // Windows silently drops trailing dots and spaces, so trim them ourselves
  // rather than let a folder appear under a name we did not plan.
  let out = sanitisePart(name).replace(/[. ]+$/, '');
  if (!out || RESERVED.test(out)) out = sanitisePart(fallback) || 'folder';
  return out;
}

/**
 * @param {string} originalName  the lab's filename, e.g. "008030000001.jpg"
 * @param {object} pattern
 * @param {string} [pattern.prefix]     text to put before the original
 * @param {string} [pattern.suffix]     text to put after the original
 * @param {string} [pattern.separator]  joins added text to the original
 * @returns {string}
 */
function applyPattern(originalName, pattern = {}) {
  const ext = path.extname(originalName);
  const stem = path.basename(originalName, ext); // untouched, always

  const sep = pattern.separator === undefined ? '_' : sanitisePart(pattern.separator);
  const prefix = sanitisePart(pattern.prefix);
  const suffix = sanitisePart(pattern.suffix);

  let name = stem;
  if (prefix) name = prefix + sep + name;
  if (suffix) name = name + sep + suffix;

  const finalName = name + ext;
  // A pattern can never shorten or alter the original stem. If something has
  // gone wrong, fall back to the untouched original rather than mangle it.
  return finalName.includes(stem) ? finalName : originalName;
}

/**
 * Applies a pattern across a folder's files and flags collisions, so the plan
 * can show them before anything is written.
 * @param {string[]} originalNames
 * @param {object} pattern
 */
function previewFolder(originalNames, pattern) {
  const seen = new Map();
  const rows = originalNames.map((original) => {
    const output = applyPattern(original, pattern);
    const key = output.toLowerCase(); // Windows compares case-insensitively
    seen.set(key, (seen.get(key) || 0) + 1);
    return { original, output };
  });
  for (const row of rows) row.collision = seen.get(row.output.toLowerCase()) > 1;
  return rows;
}

module.exports = { applyPattern, previewFolder, sanitiseFolderName, sanitisePart };
