'use strict';

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

const { applyPattern, previewFolder, sanitiseFolderName } = require('../src/naming');
const { resolveTimestamp } = require('../src/dates');
const { scanArchive, rollFolders } = require('../src/zipscan');
const { planFrom, buildOutputPlan } = require('../src/plan');
const { runPlan } = require('../src/extract');
const { writeZip, jpegWithDate } = require('./makezip');
const { fixDates } = require('../src/fixdates');

let passed = 0;
const failures = [];

function test(name, fn) {
  try {
    const result = fn();
    if (result && typeof result.then === 'function') {
      return result.then(
        () => { passed++; },
        (err) => { failures.push([name, err]); },
      );
    }
    passed++;
  } catch (err) {
    failures.push([name, err]);
  }
  return Promise.resolve();
}

(async () => {
  // ---------------------------------------------------------------- naming

  await test('the original scanner number always survives', () => {
    assert.strictEqual(applyPattern('008030000001.jpg', { prefix: 'Lomo400' }), 'Lomo400_008030000001.jpg');
    assert.strictEqual(applyPattern('008030000001.jpg', { suffix: 'RoyalWe' }), '008030000001_RoyalWe.jpg');
    assert.strictEqual(
      applyPattern('008030000001.jpg', { prefix: 'Lomo400', suffix: 'RoyalWe' }),
      'Lomo400_008030000001_RoyalWe.jpg',
    );
  });

  await test('an empty pattern leaves the filename completely alone', () => {
    assert.strictEqual(applyPattern('008030000001.jpg', {}), '008030000001.jpg');
    assert.strictEqual(applyPattern('008030000001.jpg', { prefix: '', suffix: '' }), '008030000001.jpg');
  });

  await test('the extension is preserved and not treated as part of the stem', () => {
    assert.strictEqual(applyPattern('008030000001.tif', { prefix: 'A' }), 'A_008030000001.tif');
    assert.strictEqual(applyPattern('roll.01.frame.jpeg', { suffix: 'X' }), 'roll.01.frame_X.jpeg');
  });

  await test('a custom separator is honoured, including none at all', () => {
    assert.strictEqual(applyPattern('0001.jpg', { prefix: 'Lomo', separator: '-' }), 'Lomo-0001.jpg');
    assert.strictEqual(applyPattern('0001.jpg', { prefix: 'Lomo', separator: '' }), 'Lomo0001.jpg');
  });

  await test('characters Windows rejects are stripped from added text', () => {
    assert.strictEqual(applyPattern('0001.jpg', { prefix: 'Portra/400' }), 'Portra400_0001.jpg');
    assert.strictEqual(applyPattern('0001.jpg', { prefix: 'a:b*c?' }), 'abc_0001.jpg');
  });

  await test('folder names are made safe without mangling legal ones', () => {
    assert.strictEqual(sanitiseFolderName('00803000_PeterYoungdale_LomoColor92'), '00803000_PeterYoungdale_LomoColor92');
    assert.strictEqual(sanitiseFolderName('Lomo: 400'), 'Lomo 400', 'the colon goes, the space stays');
    assert.strictEqual(sanitiseFolderName('trailing.'), 'trailing');
    assert.strictEqual(sanitiseFolderName('CON'), 'folder');
    assert.strictEqual(sanitiseFolderName(''), 'folder');
  });

  await test('filename collisions are reported before anything is written', () => {
    const rows = previewFolder(['0001.jpg', '0002.jpg'], { prefix: 'A' });
    assert.ok(rows.every((r) => !r.collision));
    // Two different originals cannot collide, but same-name files in one folder can.
    const dupes = previewFolder(['0001.jpg', '0001.JPG'], { prefix: 'A' });
    assert.ok(dupes.every((r) => r.collision), 'Windows is case-insensitive, so these collide');
  });

  // ----------------------------------------------------------------- dates

  await test('EXIF DateTimeOriginal wins over everything else', () => {
    const r = resolveTimestamp(
      { DateTimeOriginal: '2026-03-14T10:00:00Z', CreateDate: '2026-05-01T10:00:00Z' },
      new Date('2026-09-07T19:09:00Z'),
    );
    assert.strictEqual(r.source, 'DateTimeOriginal');
    assert.strictEqual(r.date.toISOString(), '2026-03-14T10:00:00.000Z');
  });

  await test('XMP MetadataDate is used when there is no conventional EXIF date', () => {
    // This is the real case for these labs: no DateTimeOriginal anywhere.
    const r = resolveTimestamp({ MetadataDate: '2026-09-07T19:09:41-07:00' }, new Date('2027-01-01T00:00:00Z'));
    assert.strictEqual(r.source, 'MetadataDate');
  });

  await test('with no usable EXIF it falls back to the archive date', () => {
    const archive = new Date('2026-09-07T19:09:00Z');
    const r = resolveTimestamp(null, archive);
    assert.strictEqual(r.source, 'archive');
    assert.strictEqual(r.date.getTime(), archive.getTime());
  });

  await test('a nonsense EXIF date is ignored rather than trusted', () => {
    const archive = new Date('2026-09-07T19:09:00Z');
    assert.strictEqual(resolveTimestamp({ DateTimeOriginal: '1970-01-01T00:00:00Z' }, archive).source, 'archive');
    assert.strictEqual(resolveTimestamp({ DateTimeOriginal: 'not a date' }, archive).source, 'archive');
    assert.strictEqual(resolveTimestamp({ DateTimeOriginal: null }, archive).source, 'archive');
  });

  await test("the zip format's zero date (1980-01-01 00:00) is not a scan date", () => {
    assert.strictEqual(resolveTimestamp(null, new Date(1980, 0, 1, 0, 0, 0)).source, 'none');
    assert.strictEqual(resolveTimestamp(null, new Date(1980, 0, 1, 9, 30, 0)).source, 'archive');
  });

  await test('with nothing usable at all it reports no date instead of inventing one', () => {
    const r = resolveTimestamp(null, null);
    assert.strictEqual(r.source, 'none');
    assert.strictEqual(r.date, null);
  });

  // ------------------------------------------------------- scanning a zip

  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'labunzip-test-'));
  const shotDate = new Date('2026-03-14T09:30:00Z');
  const labWriteDate = new Date('2026-09-07T19:09:00Z');

  const rollA = writeZip(path.join(tmp, 'inner-a.zip'), [
    { name: 'RollA/0001.jpg', data: jpegWithDate('2026:03:14 09:30:00'), date: labWriteDate },
    { name: 'RollA/0002.jpg', data: jpegWithDate('2026:03:14 09:31:00'), date: labWriteDate },
  ]);
  const rollB = writeZip(path.join(tmp, 'inner-b.zip'), [
    { name: 'RollB/0001.tif', data: Buffer.from('not a real tiff'), date: labWriteDate },
    // Same base name as a file in RollA, with no EXIF of its own. Used to prove
    // collisions are caught when two rolls are merged into one folder.
    { name: 'RollB/0001.jpg', data: Buffer.from('no exif here'), date: labWriteDate },
    { name: 'RollB/notes.txt', data: Buffer.from('lab notes'), date: labWriteDate },
  ]);

  const outer = writeZip(path.join(tmp, 'LabName.March2026.zip'), [
    { name: 'Jpegs.zip', data: fs.readFileSync(rollA), date: labWriteDate },
    { name: 'Tiffs.zip', data: fs.readFileSync(rollB), date: labWriteDate },
    { name: 'readme.txt', data: Buffer.from('top level file'), date: labWriteDate },
  ]);

  let scan;
  await test('nested zips are unpacked recursively in one pass', async () => {
    scan = await scanArchive(outer);
    assert.strictEqual(scan.nestedCount, 2, 'both inner zips should be opened');
    assert.strictEqual(scan.fileCount, 6, "5 files inside the inner zips plus the top-level readme");
    const paths = rollFolders(scan.root).map((f) => f.path).sort();
    assert.deepStrictEqual(paths, [
      'LabName.March2026',
      'LabName.March2026/Jpegs/RollA',
      'LabName.March2026/Tiffs/RollB',
    ]);
  });

  await test('a nested zip becomes a folder named after the archive', async () => {
    const names = scan.root.folders.map((f) => f.name).sort();
    assert.deepStrictEqual(names, ['Jpegs', 'Tiffs']);
  });

  await test('EXIF dates are read out of files nested two zips deep', async () => {
    const rollAFolder = rollFolders(scan.root).find((f) => f.path.endsWith('RollA'));
    assert.strictEqual(rollAFolder.undatedCount, 0);
    for (const file of rollAFolder.files) {
      assert.strictEqual(file.dateSource, 'DateTimeOriginal');
      assert.strictEqual(new Date(file.date).getUTCFullYear(), 2026);
      assert.strictEqual(new Date(file.date).getUTCMonth(), 2, 'March, not the download month');
    }
  });

  await test('files without EXIF fall back to the date stored in the archive', async () => {
    const rollBFolder = rollFolders(scan.root).find((f) => f.path.endsWith('RollB'));
    for (const file of rollBFolder.files) {
      assert.strictEqual(file.dateSource, 'archive');
      assert.ok(file.date, 'should still get a date');
    }
  });

  await test('Mac and Windows junk in a zip is not unpacked', async () => {
    const junky = writeZip(path.join(tmp, 'junky.zip'), [
      { name: 'Roll/0001.jpg', data: Buffer.from('x'), date: labWriteDate },
      { name: '__MACOSX/Roll/._0001.jpg', data: Buffer.from('x'), date: labWriteDate },
      { name: 'Roll/._0001.jpg', data: Buffer.from('x'), date: labWriteDate },
      { name: 'Roll/.DS_Store', data: Buffer.from('x'), date: labWriteDate },
      { name: 'Roll/Thumbs.db', data: Buffer.from('x'), date: labWriteDate },
    ]);
    const s = await scanArchive(junky);
    assert.strictEqual(s.fileCount, 1);
    assert.deepStrictEqual(s.root.folders.map((f) => f.name), ['Roll']);
  });

  await test('the extraction date is never used as a timestamp', async () => {
    const today = new Date();
    const all = rollFolders(scan.root).flatMap((f) => f.files);
    for (const file of all) {
      const d = new Date(file.date);
      assert.ok(
        Math.abs(d.getTime() - today.getTime()) > 60 * 1000,
        `${file.originalName} was stamped with roughly now (${file.date}), which is the bug this app exists to fix`,
      );
    }
  });

  // -------------------------------------------------------------- the plan

  await test('the plan applies per-folder names and patterns', async () => {
    const draft = planFrom(scan);
    const rollA2 = draft.folders.find((f) => f.path.endsWith('RollA'));
    rollA2.outputName = 'Lomo 400 March';
    rollA2.pattern = { prefix: 'Lomo400', suffix: '', separator: '_' };

    const out = buildOutputPlan(scan, draft, 'D:/Photos');
    const written = out.files.filter((f) => f.sourcePath.includes('RollA'));
    assert.strictEqual(written.length, 2);
    for (const file of written) {
      assert.ok(file.targetPath.includes('Lomo 400 March'), 'folder rename applied, spaces kept');
      assert.ok(/Lomo400_000\d\.jpg$/.test(file.targetPath), 'pattern applied around the original number');
      assert.ok(file.targetPath.startsWith(path.normalize('D:/Photos')), 'written under the chosen destination');
    }
  });

  await test('the plan carries a resolved date and metadata for every file', async () => {
    const draft = planFrom(scan);
    draft.folders.find((f) => f.path.endsWith('RollA')).metadata = { Model: 'Canon AV-1' };
    const out = buildOutputPlan(scan, draft, 'D:/Photos');
    const withMeta = out.files.filter((f) => f.metadata && f.metadata.Model);
    assert.strictEqual(withMeta.length, 2);
    assert.ok(out.files.every((f) => f.date || f.dateSource === 'none'));
  });

  await test('merging two rolls into one folder surfaces the name collision', async () => {
    const draft = planFrom(scan);
    // Collapse the Jpegs/Tiffs wrappers away and send both rolls to one folder,
    // which puts RollA/0001.jpg and RollB/0001.jpg at the same path.
    for (const folder of draft.folders) {
      if (folder.sourceName === 'Jpegs' || folder.sourceName === 'Tiffs') folder.outputName = '';
      if (folder.sourceName === 'RollA' || folder.sourceName === 'RollB') folder.outputName = 'Merged';
    }
    const out = buildOutputPlan(scan, draft, 'D:/Photos');
    assert.ok(out.conflicts.length > 0, 'the duplicate 0001.jpg should be reported');
    assert.ok(out.conflicts[0].targetPath.endsWith('0001.jpg'));
  });

  await test('a plan with no collisions reports none', async () => {
    const out = buildOutputPlan(scan, planFrom(scan), 'D:/Photos');
    assert.deepStrictEqual(out.conflicts, []);
  });

  await test('collapsing a folder name lifts its files up a level', async () => {
    const draft = planFrom(scan);
    draft.folders.find((f) => f.sourceName === 'Jpegs').outputName = '';
    const out = buildOutputPlan(scan, draft, 'D:/Photos');
    const rollAFile = out.files.find((f) => f.sourcePath.includes('RollA'));
    assert.ok(!rollAFile.targetPath.includes('Jpegs'), 'the wrapper folder is gone');
    assert.ok(rollAFile.targetPath.includes('RollA'), 'the roll folder remains');
  });

  // --------------------------------------------------- extracting for real

  await test('extraction stamps files with the resolved date, not today', async () => {
    const dest = path.join(tmp, 'out-dates');
    const draft = planFrom(scan);
    const plan = buildOutputPlan(scan, draft, dest);
    const result = await runPlan({ zipPath: outer, outputPlan: plan });

    assert.strictEqual(result.errors.length, 0, JSON.stringify(result.errors));
    assert.strictEqual(result.written, plan.files.length);

    const now = Date.now();
    for (const file of plan.files) {
      const st = fs.statSync(file.targetPath);
      assert.ok(
        Math.abs(st.mtimeMs - now) > 60 * 1000,
        `${file.outputName} has today's modified date, which is the bug this app exists to fix`,
      );
      assert.strictEqual(st.mtime.toISOString(), new Date(file.date).toISOString());
    }
  });

  await test('timestamps are applied after metadata, so writing tags cannot undo them', async () => {
    const dest = path.join(tmp, 'out-order');
    const draft = planFrom(scan);
    draft.folders.find((f) => f.sourceName === 'RollA').metadata = { Model: 'Canon AV-1' };
    const plan = buildOutputPlan(scan, draft, dest);

    // Stands in for exiftool: rewrites the file, which resets its mtime to now.
    // If timestamps were applied before this, the dates would be lost.
    const touched = [];
    const fakeExiftool = {
      async write(target) {
        touched.push(target);
        fs.writeFileSync(target, fs.readFileSync(target));
      },
    };

    const result = await runPlan({ zipPath: outer, outputPlan: plan, exiftool: fakeExiftool });
    assert.strictEqual(touched.length, 2, 'both RollA files should have had metadata written');
    assert.strictEqual(result.errors.length, 0);

    const now = Date.now();
    for (const target of touched) {
      const st = fs.statSync(target);
      assert.ok(
        Math.abs(st.mtimeMs - now) > 60 * 1000,
        `${path.basename(target)} was left with the metadata-write time instead of the scan date`,
      );
    }
  });

  await test('fixing a folder gives each photo the date inside it, and leaves undated ones alone', async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'labunzip-fix-'));
    fs.mkdirSync(path.join(dir, 'Roll 1'));
    const a = path.join(dir, 'Roll 1', '0001.jpg');
    const b = path.join(dir, 'Roll 1', '0002.jpg');
    const c = path.join(dir, 'Roll 1', 'nodate.jpg');
    fs.writeFileSync(a, jpegWithDate('2026:03:14 09:30:00'));
    fs.writeFileSync(b, jpegWithDate('2025:12:01 18:05:10'));
    fs.writeFileSync(c, Buffer.from([0xff, 0xd8, 0xff, 0xd9]));
    fs.writeFileSync(path.join(dir, 'Roll 1', '._0001.jpg'), 'junk');
    const before = fs.statSync(c).mtimeMs;

    const dry = await fixDates(dir, { dryRun: true });
    assert.strictEqual(dry.dated.length, 2);
    assert.notStrictEqual(Math.round(fs.statSync(a).mtimeMs / 1000), Math.round(new Date(2026, 2, 14, 9, 30, 0).getTime() / 1000));

    const r = await fixDates(dir);
    assert.strictEqual(r.checked, 3);
    assert.strictEqual(r.dated.length, 2);
    assert.deepStrictEqual(r.undated, [c]);
    assert.strictEqual(Math.round(fs.statSync(a).mtimeMs / 1000), Math.round(new Date(2026, 2, 14, 9, 30, 0).getTime() / 1000));
    assert.strictEqual(Math.round(fs.statSync(b).mtimeMs / 1000), Math.round(new Date(2025, 11, 1, 18, 5, 10).getTime() / 1000));
    assert.strictEqual(fs.statSync(c).mtimeMs, before);
  });

  await test('only files in the approved plan are written', async () => {
    const dest = path.join(tmp, 'out-subset');
    const draft = planFrom(scan);
    const plan = buildOutputPlan(scan, draft, dest);
    plan.files = plan.files.filter((f) => f.outputName.endsWith('.jpg'));

    const result = await runPlan({ zipPath: outer, outputPlan: plan });
    assert.strictEqual(result.written, plan.files.length);

    const walk = (dir) => fs.readdirSync(dir, { withFileTypes: true })
      .flatMap((e) => (e.isDirectory() ? walk(path.join(dir, e.name)) : [e.name]));
    const onDisk = walk(dest);
    assert.ok(onDisk.every((n) => n.endsWith('.jpg')), `unexpected files written: ${onDisk.join(', ')}`);
  });

  fs.rmSync(tmp, { recursive: true, force: true });

  // ---------------------------------------------------------------- report

  console.log(`\n${passed} passed, ${failures.length} failed`);
  for (const [name, err] of failures) {
    console.log(`\n  FAIL  ${name}\n        ${err.message}`);
  }
  process.exit(failures.length ? 1 : 0);
})();
