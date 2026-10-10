'use strict';

/**
 * Gives every photo in a folder (and its subfolders) its scan date as its created and
 * modified date. Use it on rolls copied off the phone.
 *
 *   npm run fix-dates -- "E:\Photos\SLR\Roll 12"
 *   npm run fix-dates -- "E:\Photos\SLR" --dry-run     (shows what would change)
 */

const path = require('path');
const { fixDates, describe } = require('../src/fixdates');

const args = process.argv.slice(2);
const dryRun = args.includes('--dry-run');
const folder = args.find((a) => !a.startsWith('--'));
if (!folder) {
  console.error('Usage: npm run fix-dates -- "<folder>" [--dry-run]');
  process.exit(1);
}

fixDates(path.resolve(folder), { dryRun })
  .then((result) => {
    console.log(describe(result, path.resolve(folder)));
    process.exit(result.failed.length ? 1 : 0);
  })
  .catch((err) => { console.error(err.message); process.exit(1); });
