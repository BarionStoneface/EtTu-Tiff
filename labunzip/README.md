# LabUnzip

Unpacks the zips from film labs in one pass, including the zips nested inside
them, and fixes the dates on the way out.

Right-click a lab zip, pick **Unpack with LabUnzip**, check the plan, hit
Unpack. One step instead of three.

## What it does

- **Unpacks everything at once.** The outer zip, the `Jpegs.zip` and `Tiffs.zip`
  inside it, and anything nested deeper. No second or third round of extracting.
- **Shows the plan first.** The whole folder and file tree it is about to
  create, with the names already applied. Nothing is written to the destination
  until you press Unpack.
- **Per-roll naming.** Each subfolder is its own roll, so each gets its own
  folder name, its own text to add around the filenames, and its own metadata.
- **Keeps the lab's numbers.** Text is only ever added around the scanner's
  original filename, before it or after it. There is no way to replace it — the
  original number is always still in there.
- **Fixes the dates.** Every file gets the date the scan was actually made, on
  both *created* and *modified*. The date you downloaded it is never used.

## Three ways to open a zip

**The desktop shortcut.** `npm run shortcut` puts `LabUnzip` on the Desktop.
Double-click it and open a zip from inside, or **drag a zip onto the icon** and
it goes straight to the plan for that archive.

**Drag and drop onto the window.** Drop a zip anywhere on an open LabUnzip
window.

**The right-click menu.** `npm run register` adds **Unpack with LabUnzip** to
the right-click menu for `.zip` files.

```bash
npm install
npm run shortcut     # desktop icon
npm run register     # right-click entry
npm start            # or just run it
```

Everything is written under `HKEY_CURRENT_USER`, so none of it needs
administrator rights and none of it affects other users. `npm run unregister`
removes the menu entry.

`npm run register` sets up two separate routes, because the direct one is
unreliable for zips:

- **Right-click → Unpack with LabUnzip.** On Windows 11 this lives under
  **Show more options**.
- **Right-click → Send to → LabUnzip.** This is just a shortcut in the Send To
  folder, so it does not depend on file associations, ProgIDs or Explorer's
  menu cache at all. If the direct entry ever stops showing, this still works.

### If the right-click entry does not appear

Windows makes this harder than it should be, and the register script works
around all three causes:

- **The ProgID.** `.zip` resolves to a ProgID, normally `CompressedFolder`.
  The documented `SystemFileAssociations\.zip` branch is not reliably merged for
  zips, and Explorer often ignores a plain verb on `CompressedFolder` too,
  because it treats zips as browsable folders rather than ordinary files.
- **So the entry that actually shows** is registered under `*\shell` — which
  applies to every file — and narrowed back to zips with
  `AppliesTo = System.FileName:"*.zip"`. Same trick as "Edit with Notepad++".
- **Explorer's cache.** A newly written key may not show until Explorer
  restarts:

  ```bash
  taskkill /f /im explorer.exe & start explorer.exe
  ```

If the direct entry still refuses to show, use **Send to → LabUnzip**, or the
Desktop shortcut. All three run exactly the same command.

If you build a packaged exe (`npm run dist`), run `npm run register` and
`npm run shortcut` again afterwards and both will repoint at the exe.

**The app folder must stay where it is.** The shortcut and the menu entry point
at this directory. If you move it, re-run those two commands.

## Fixing dates on a folder

Photos copied off a phone usually arrive dated the day they were copied. Right-click
the folder (a roll, or all of `E:\Photos\SLR`) and pick **Fix dates with
LabUnzip**: every photo in it, subfolders included, gets the date inside it as its
created and modified date, by the same rules as below. A photo with no date inside
it is left alone and listed. `npm run register` adds the menu entry; from a
terminal it's

```bash
npm run fix-dates -- "E:\Photos\SLR\Roll 12"
npm run fix-dates -- "E:\Photos\SLR" --dry-run    # shows what would change
```

## Where the dates come from

This is the part that mattered most, so it is worth being precise. For each
file, in order:

1. EXIF `DateTimeOriginal`
2. EXIF `CreateDate` / `DateTimeDigitized`
3. EXIF `ModifyDate`
4. XMP `MetadataDate` or XMP `CreateDate`
5. the file's modified date **as stored inside the zip**, which the lab set when
   it wrote the scan and which survives the download

If none of those give a sensible date the file is left alone and reported as
undated, rather than being stamped with today. **The extraction date is never
used** — that is the bug this app exists to fix.

Worth knowing about your current lab: their scans carry **no**
`DateTimeOriginal` and no camera tags at all. The only date in the file is XMP
`MetadataDate`, which is when the scanner wrote the file — and it matches the
archive date to the second. So in practice rule 4 is what fires, rule 5 agrees
with it, and both give the right month. The plan screen tells you which rule was
used for each folder, so you can see it rather than trust it.

Timestamps are applied **after** metadata is written. Writing a tag rewrites the
file and resets its modified date, so doing it the other way round would undo
the whole point on the last step. There is a test for this.

## The plan screen

Each folder gets a row. For folders that hold photos you can set:

| Field | What it does |
| --- | --- |
| Folder name | Renames it. Leave it empty to drop that level and move its files up. |
| Add before the number | Text in front of the lab's filename. |
| Join with | What separates added text from the original. Defaults to `_`. |
| Add after the number | Text after the lab's filename. |
| Metadata | Any tag and value, written into each photo. `Model` for the camera body; add as many others as you like. |

So `008030000001.jpg` with prefix `LomoColor92` and suffix `RoyalWe` becomes
`LomoColor92_008030000001_RoyalWe.jpg`.

Because the same roll usually appears under both `Jpegs` and `Tiffs`, there is a
**Copy these settings to the other folder named …** link that applies a roll's
settings to its twin.

The right-hand column previews the real output names as you type, and the plan
refuses to run if two files would end up at the same path.

## Development

```bash
npm test     # 25 tests, no network, no Electron needed
npm start    # run the app
```

`npm test` covers the naming rules, the date fallback chain, recursive nested
scanning, the plan builder, and a real extract-and-check-the-timestamps run
against zips built in the test itself.

Set `LABUNZIP_DEBUG=1` to forward the page's console to the terminal, which is
otherwise invisible when the app is launched from the right-click menu.

### How it is put together

| File | Role |
| --- | --- |
| `main.js` | Electron main process, IPC, dialogs |
| `preload.js` | The only bridge the page gets |
| `src/zipsource.js` | Opening zips, including ones nested inside other zips |
| `src/zipscan.js` | Reads the tree and the dates without writing anything |
| `src/dates.js` | The date fallback chain |
| `src/naming.js` | Filename patterns and Windows-safe names |
| `src/plan.js` | Turns a scan plus your edits into the exact list of files to write |
| `src/extract.js` | Carries the plan out |
| `src/timestamps.js` | Sets modified, and created via PowerShell |
| `renderer/` | The plan screen |

Two things in there are less obvious than they look:

**Nested zips are read in place.** A zip's directory is at the end of the file,
so it needs random access and cannot be parsed from a stream. Since zip tools
store an already-compressed file like a `.zip` rather than deflating it again,
the inner archive sits in the outer file verbatim and is read through a reader
that offsets into the parent. Nothing is copied and nothing hits the disk. A
13 GB `Tiffs.zip` scans in about a tenth of a second. Only if an inner zip was
genuinely deflated does it fall back to spilling to a temp file.

**Only the first 64 KB of each image is read during a scan.** That is enough to
reach the EXIF and XMP in both JPEG and TIFF from these labs, measured against
the real scans. Inflating a 145 MB TIFF just to read a date would make scanning
unusable.

## Limits

- Windows only. The created-date step uses PowerShell; everything else is
  portable, so a later Android or macOS phase would need that one piece redone.
- Metadata writing uses ExifTool, which ships with the app (about 40 MB).
- Zip files are read, never modified. Your original download is left alone.
