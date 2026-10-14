# Et Tu, Tiff?

*Veni, vidi, JPEG'd.*

An Android app for film photographers whose lab hands them folders of TIFF
scans. It turns each roll's TIFFs into full quality JPEGs, tags the lab's own
JPEGs without re-encoding them, and unzips lab downloads with a preview first.
Every photo gets the roll's film and camera details and your copyright; the
scan date and scanner are kept, location and everything else is dropped.

## Get it

The APK is on [the latest release](../../releases/latest). Android 8 or later.
Nothing leaves the phone: there is no network code. Folders are opened through
Android's own picker, so no storage permission is asked for. The one permission
it asks, the first time a long job starts, is to show notifications, so an
unzip or conversion can carry on in the background with progress shown. It
works the same if you say no.

## What it does

**Converting TIFFs.** JPEG quality 100 (adjustable down to 85 in Settings),
colour kept at full resolution (4:4:4), where Android's own encoder would halve
it. Pixel dimensions, colour profile and DPI are unchanged. 16 bit scans are
rounded to 8 bits. The encoder and decoder are the app's own; the scan is
streamed a few rows at a time, so a 500 MB TIFF converts in about 15 MB of
memory.

**Reading TIFFs.** Classic and BigTIFF. 1 to 16 bit integer samples (packed
2, 4 and 12 bit included), 32 bit integer, and 16, 32 and 64 bit floating
point. Gray, RGB, CMYK, palette, and YCbCr when JPEG compressed. Strips or
tiles, chunky or planar. No compression, LZW, Deflate, PackBits and JPEG, with
the horizontal or floating point predictor. Each kind is checked in the tests
against tifffile's output.

**One roll per folder.** Each roll gets its own camera, lens, film (about 60
in the list, or typed), box ISO, push or pull, tags such as expired or
cross processed, notes and lab. Nothing carries over from the previous roll
unless you ask with "Copy details from another roll". Past cameras, lenses and
labs are offered as suggestions.

**Scan date.** Becomes the photo's "date taken". Taken from, in order: EXIF
date taken, EXIF date digitized, the same in XMP plus XMP CreateDate, EXIF
modify date, XMP MetadataDate, a `.thm` or `.xmp` file beside the scan, then
the file's date inside the zip it came from. Zero dates (1970, 1980) are
skipped. If none exist the app asks you to type one, or to use the files' own
dates on the phone, which may just be when they were downloaded. The app never
uses the download date on its own.

**Metadata written.** EXIF, XMP and IPTC, plus a JPEG comment with the
copyright notice. Film details go to the standard EXIF fields and to the
[AnalogExif](https://analogexif.sourceforge.net/help/analogexif-xmp.php) XMP
fields. Copyright is "all rights reserved" or a Creative Commons licence, set
once in Settings. The original file name is kept inside the JPEG.

**Lab JPEGs.** A folder holding only JPEGs is tagged rather than converted:
the image data is copied byte for byte, only the metadata is replaced. Keep the
originals (tagged copies go in a new folder beside the roll) or replace them in
place. A JPEG with no date taken gets its scan date written in when unzipped,
so galleries sort it by scan date; this can be turned off.

**Unzipping.** Pick a zip, including one holding `Jpegs.zip` and `Tiffs.zip`,
or a zip per roll, up to four levels deep. Inner zips that were stored rather
than compressed are read in place, so a 13 GB inner zip lists as fast as a
small one. Before anything is written you see every folder, with where its
scan dates come from; rename a folder, clear a name to drop that level, or
untick a folder. Two files landing on the same name stop the plan. Each file
is written under a temporary name and renamed once complete, and checked
against the length and checksum in the zip. Unzipping the same zip again skips
what's already there. Mac and Windows junk (`__MACOSX`, `.DS_Store`,
`Thumbs.db`) is skipped.

**Names.** Text is added before or after the lab's file name, never instead
of it: `008030000001.tif` with `LomoColor92` before and `RoyalWe` after becomes
`LomoColor92_008030000001_RoyalWe.jpg`. `{nn}`, `{nnn}`, `{date}`, `{roll}`
and `{film}` are filled in. Names can be edited one by one. Characters Windows
can't take are replaced, and reserved names (`con`, `nul`) are prefixed.

**Replace or keep.** Keeping the TIFFs adds the JPEGs beside them and deletes
nothing. Replacing deletes each TIFF only after its JPEG is written, its size
on disk matches what was written, and the phone's own decoder reads it back at
the right size. `Roll 12 TIFF` is then renamed to `Roll 12 JPEG`. Info files
(`.thm`, `.xmp`, `.txt`...) are deleted only if chosen, per roll.

**Dry run.** Lists what would happen to every file (new name, scan date and
its source, what gets deleted, renamed or replaced) without changing anything.

**File dates.** Each JPEG's and unzipped file's own date is set to the scan
date where Android allows it, which varies by phone and folder. When refused,
the app says so once; the scan date is still inside each photo.

## Limits

- Images up to 1,048,576 pixels a side. A compressed strip or tile has to be
  decoded whole, so one can't be over 512 MB; uncompressed files have no such
  limit.
- Not read: old style JPEG in TIFF (compression 6), Zstandard, WebP, LERC,
  JPEG 2000. Not read at all: RAW, HEIC, PNG.
- Zips using a compression other than Deflate or stored, or with passwords,
  are listed but skipped.
- Android doesn't let apps save into the Download folder itself; pick Pictures
  or a folder of your own.
- English only.

## Damaged and hostile files

The readers are fuzz tested (`RobustnessTest`): thousands of random mutations
of real TIFFs, JPEGs and zips, plus headers claiming absurd sizes. A damaged
file fails with a message naming it, in milliseconds, without taking the rest
of the folder with it. Zip entries that inflate to more than they claim (zip
bombs) or less (cut off downloads), or whose checksum doesn't match, are
refused. Paths that would escape the destination (`../`, `C:\`, leading `/`)
are dropped.

## Signing key

`android/keystore/sideload.keystore` (password `android`) is committed on
purpose, so every build installs over the last one when sideloaded. The
consequence: anyone can build an APK that installs as an update over this one
and reads its settings. Only install APKs from this repo's releases. A Play
Store build would use a separate upload key.

## Building

Kotlin with Jetpack Compose, in `android/`. The TIFF decoder, JPEG encoder,
zip index and metadata writer are plain Kotlin in
`android/app/src/main/java/com/barion/filmscans/core/`, with no Android
dependencies, and are what the unit tests cover. GitHub Actions runs the tests,
lint and the release build on every push; pushes to `main` publish the APK to
the `latest` release.

```
cd android && ./gradlew :app:testDebugUnitTest :app:assembleRelease
```

## Desktop versions

`labunzip/` is LabUnzip, a Windows app (Electron) that unpacks lab downloads,
nested zips included, and sets each file's created and modified date to its
scan date. Its right click "Fix dates" does the same for files already
unpacked. See [labunzip/README.md](labunzip/README.md). The Android app's
unzipping, date rules and naming come from it.

`desktop/tiff2jpeg.py` is an earlier command line converter for Windows, Mac
and Linux (`pip install -r desktop/requirements.txt`). On Windows, drag a
folder onto `tiff2jpeg.bat`.
