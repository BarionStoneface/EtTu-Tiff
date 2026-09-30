# Et Tu, Tiff?

*Veni, vidi, JPEG'd.*

Turns film-scan TIFFs into full-quality JPEGs, one roll at a time. It keeps
what matters from the scan, drops what doesn't, and writes your own film and
copyright details into every photo.

Built for film photographers whose lab or scanner hands them folders of huge
TIFFs, and who want JPEGs that are smaller but lose nothing visible and carry
proper metadata.

## Get it

Download the APK from [the latest release](../../releases/latest). You may
need to allow installs from your browser. It needs Android 8 or later and
asks for no permissions: you choose folders in Android's own picker, and
nothing leaves the phone.

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
  and software are kept. The scan date is read from inside the file, not
  from the download date, and becomes the photo's "date taken".
- **Drops location** and every other tag in the original.
- **Writes your copyright** into EXIF, XMP and IPTC fields and a JPEG
  comment. You choose "all rights reserved" or a Creative Commons licence.
- **Unzips lab downloads.** Pick the zip, including one that holds a zip per
  roll. Inner zips are unpacked straight into their own folders, with no
  second unzip step. File dates from the zip are used as a fallback scan date.
- **Previews the roll first.** Thumbnails are read straight from the TIFFs
  before anything is converted.
- **Renames in bulk.** You can use a pattern (`{name}`, `{nn}`, `{date}`,
  `{roll}`, `{film}`) or edit names one by one. The original filename is
  always kept inside the JPEG.
- **Replace or keep, your choice.** Before converting, you choose between
  replacing the TIFFs and keeping them with the JPEGs added beside them.
  Keeping them means nothing is deleted or renamed.
- **Cleans up safely.** When replacing, each TIFF is deleted only after its
  JPEG has been written and read back. It also removes `.thm`, `.xmp` and other info
  files, and renames `Roll 12 TIFF` to `Roll 12 JPEG`.

Film details are written to the standard EXIF fields and to the
[AnalogExif](https://analogexif.sourceforge.net/help/analogexif-xmp.php) XMP
fields, which ExifTool and film-metadata tools can read.

## Desktop version

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
