# Et Tu, Tiff?

*Veni, vidi, JPEG'd.*

Turns film-scan TIFFs into full-quality JPEGs, and tags the lab's own JPEGs,
one roll at a time. It keeps what matters from the scan, drops what doesn't,
and writes your own film and copyright details into every photo.

Built for film photographers whose lab or scanner hands them folders of huge
TIFFs, and who want JPEGs that are smaller but lose nothing visible and carry
proper metadata.

## Get it

Download the APK from [the latest release](../../releases/latest). You may
need to allow installs from your browser. It needs Android 8 or later. It
has no access to your storage beyond the folders you choose in Android's own
picker, and nothing leaves the phone. The one thing it asks, the first time
a long job starts, is to show notifications, so you can see an unzip or a
conversion carry on while you're in other apps. It works the same if you say
no.

## What it does

- **Converts at full quality.** It uses JPEG quality 100 with colour kept at
  full resolution (4:4:4), where Android's built-in encoder would halve it.
  Pixel dimensions, colour profile and DPI are unchanged. 16-bit scans are
  rounded to 8 bits, because that's all a JPEG can hold.
- **Works one roll per folder.** Each roll gets its own camera body, lens,
  film stock (about 60 in the list, or type your own), box ISO, push/pull,
  and tags such as expired, redscale, cross-processed or film soup, plus
  notes and lab. Nothing carries over from the previous roll. Past cameras
  and lenses are only offered as suggestions.
- **Keeps the scanner and the original scan date.** The scanner make, model
  and software are kept. The scan date becomes the photo's "date taken", and
  is never the download date. It comes from, in order: EXIF date taken, EXIF
  date digitized, the same dates in XMP (plus XMP CreateDate), EXIF modify
  date, XMP MetadataDate (the only date some labs write), a `.thm` or `.xmp`
  file beside the scan, then the file's date inside the zip it came from.
  Blank "zero" dates such as 1970-01-01 are skipped. If none of those exist,
  the app asks: type the date, or choose to use the files' dates on the
  phone, which may just be when they were downloaded. Each roll shows where
  its dates came from.
- **Drops location** and every other tag in the original.
- **Writes your copyright** into EXIF, XMP and IPTC fields and a JPEG
  comment. You choose "all rights reserved" or a Creative Commons licence.
- **Unzips lab downloads, with a preview first.** Pick the zip, including one
  that holds `Jpegs.zip` and `Tiffs.zip`, or a zip per roll. Before anything
  is written you see every folder it will make, with where each roll's scan
  dates come from. Rename a folder, clear a name to drop that level, or untick
  a folder (say, the lab's JPEGs) to leave it out. Two files that would land
  on the same name stop it. Inner zips are read where they sit inside the
  outer one, so even a 13 GB `Tiffs.zip` is listed straight away, and
  everything is unpacked in one pass.
- **Unzipping can stop and carry on.** Each file only gets its real name once
  it's complete. Unzipping the same zip again skips what's already there.
- **Dates the lab's JPEGs.** A phone can't set a file's date, so a lab JPEG with
  no date taken gets its scan date written in (from its own XMP, or its date in
  the zip). Galleries then sort it by when it was scanned, not when it was
  unzipped. Nothing else in the file changes, and it can be turned off. The
  zip's dates are also remembered for the TIFFs after the app closes.
- **Tags the lab's JPEGs too, without re-saving them.** Most labs send JPEGs
  as standard and TIFFs only as an extra. A folder of the lab's JPEGs gets the
  same roll details, copyright, dates and names as a converted scan, but the
  picture data is copied byte for byte, so nothing about the image changes.
  Their old details, location included, are replaced; the colour profile is
  kept. Keeping the originals puts tagged copies in a new folder beside the
  roll; replacing them tags them in place.
- **Previews the roll first.** Thumbnails are read straight from the TIFFs
  before anything is converted.
- **Keeps the lab's file names.** Text is added before or after the lab's name,
  never instead of it: `008030000001.tif` with `LomoColor92` before and
  `RoyalWe` after becomes `LomoColor92_008030000001_RoyalWe.jpg`. The added
  text can use `{nn}`, `{nnn}`, `{date}`, `{roll}` and `{film}`. Names can
  still be edited one by one, and the original name is always kept inside the
  JPEG. A JPEG already in the folder is only replaced if you tick to replace it.
- **Copies details between rolls when asked.** Nothing carries over by itself,
  but "Copy details from another roll" fills in the camera, film and the rest.
- **Replace or keep, your choice.** Before converting, you choose between
  replacing the TIFFs and keeping them with the JPEGs added beside them.
  Keeping them means nothing is deleted or renamed.
- **Cleans up safely.** When replacing, each TIFF is deleted only after its
  JPEG has been written and read back, and `Roll 12 TIFF` is renamed to
  `Roll 12 JPEG`. The `.thm`, `.xmp` and other info files next to the TIFFs
  are only deleted if you choose to, per roll; Settings sets where that
  choice starts (off).

Film details are written to the standard EXIF fields and to the
[AnalogExif](https://analogexif.sourceforge.net/help/analogexif-xmp.php) XMP
fields, which ExifTool and film-metadata tools can read.

## Desktop versions

`labunzip/` is LabUnzip, a Windows app that unpacks lab downloads (nested
zips included) and gives every file its scan date as both its created and
modified date. It adds a right-click "Unpack with LabUnzip" for zips. See
[labunzip/README.md](labunzip/README.md). The Android app's unzipping, date
rules and naming come from it.


`desktop/tiff2jpeg.py` is an earlier command-line version for Windows, Mac
and Linux (`pip install -r desktop/requirements.txt`). On Windows you can
drag a folder onto `tiff2jpeg.bat`.

## Building

The app is Kotlin with Jetpack Compose, in `android/`. GitHub Actions runs
the tests, builds the app and publishes the APK on every push to `main`. The
TIFF decoder, JPEG encoder and metadata writer are plain Kotlin in
`android/app/src/main/java/com/barion/filmscans/core/`.

The signing key in `android/keystore/` is committed on purpose, so each
sideloaded build installs over the last one. Only install APKs from this
repo's releases.
