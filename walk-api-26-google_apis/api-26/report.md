# Findings, API 26

- **cold_start**: [start screen] controls with no label for screen readers: android.view.View at (890, 1774, 1027, 1900)
- **settings**: [settings] controls with no label for screen readers: android.view.View at (901, 1411, 1038, 1537); android.view.View at (901, 1747, 1038, 1873)
- **convert_keep**: The folder picker hides internal storage until 'Show internal storage' is chosen from its menu, so a new user sees no way to reach their scans (Android 8/9). The app can pass EXTRA_SHOW_ADVANCED to show it.
- **convert_keep**: [rolls] controls with no label for screen readers: android.widget.RadioButton at (65, 708, 191, 834); android.widget.CheckBox at (70, 1104, 196, 1230)
- **unzip_and_tag**: [unzip preview] touch targets under 48 dp: android.widget.EditText (291×10 dp)
- **unzip_and_tag**: [unzip preview] controls with no label for screen readers: android.view.View at (879, 816, 1016, 942); android.widget.CheckBox at (33, 1006, 158, 1132); android.widget.CheckBox at (70, 1237, 195, 1363); android.widget.CheckBox at (107, 1468, 232, 1594); android.widget.EditText at (258, 2081, 1022, 2107)
- **unzip_any**: [unzip preview, locked zip] controls with no label for screen readers: android.view.View at (879, 1095, 1016, 1221); android.widget.CheckBox at (33, 1285, 158, 1411); android.view.View at (879, 1934, 1016, 2060)
- **rotation**: [start screen, landscape] touch targets under 48 dp: android.view.View (52×44 dp)
- **rotation**: [start screen, landscape] controls with no label for screen readers: android.view.View at (2084, 964, 2221, 1080)
- **big_text**: [start screen, largest text] touch targets under 48 dp: android.view.View (319×44 dp); android.view.View (52×44 dp)
- **big_text**: [start screen, largest text] controls with no label for screen readers: android.view.View at (53, 2158, 890, 2274); android.view.View at (890, 2158, 1027, 2274)
- **big_text**: with the largest text size, 'Unzip a zip' falls off the bottom of the start screen and can't be scrolled to

---

# Emulator walk, API 26, page size , density 2.62


## cold_start
- cold start 951 ms
- screenshot `01-cold_start-start.png`
- finished in 7 s

## settings
- screenshot `02-settings-settings-top.png`
- screenshot `03-settings-settings-bottom.png`
- finished in 37 s

## convert_keep
- screenshot `04-convert_keep-picker.png`
- screenshot `05-convert_keep-picker-roots.png`
- screenshot `06-convert_keep-picker-Pictures.png`
- screenshot `07-convert_keep-picker-Scans.png`
- screenshot `08-convert_keep-picker-scans.png`
- screenshot `09-convert_keep-rolls.png`
- screenshot `10-convert_keep-rolls-dates.png`
- screenshot `11-convert_keep-confirm.png`
- screenshot `12-convert_keep-converting.png`
- progress notification shown while in the background
- screenshot `13-convert_keep-notification.png`
- peak memory while converting about 0 MB
- screenshot `14-convert_keep-done.png`
```
/sdcard/Pictures/Scans/Roll A:
total 54616
-rw-rw---- 1 root sdcard_rw  7918680 2019-04-12 14:31 A001.jpg
-rw-rw---- 1 root sdcard_rw 10719442 2026-11-01 14:18 A001.tif
-rw-rw---- 1 root sdcard_rw  7918081 2019-04-12 14:32 A002.jpg
-rw-rw---- 1 root sdcard_rw 10718651 2026-11-01 14:18 A002.tif
-rw-rw---- 1 root sdcard_rw  7918220 2019-04-12 14:33 A003.jpg
-rw-rw---- 1 root sdcard_rw 10718172 2026-11-01 14:18 A003.tif

/sdcard/Pictures/Scans/Roll B:
total 144076
-rw-rw---- 1 root sdcard_rw   4456588 2026-11-01 14:18 B001.jpg
-rw-rw---- 1 root sdcard_rw   6039466 2026-11-01 14:18 B001.tif
-rw-rw---- 1 root sdcard_rw  35028032 2026-11-01 14:18 B002.jpg
-rw-rw---- 1 root sdcard_rw 102000320 2026-11-01 14:18 B002.tif
```
- 'View photos' opened: mResumedActivity: ActivityRecord{28352d0 u0 com.google.android.apps.photos/.pager.HostPhotoPagerActivity t9}
- screenshot `15-convert_keep-view-photos.png`
- 'Open folder' opened: mResumedActivity: ActivityRecord{c865d23 u0 com.barion.filmscans/.MainActivity t9}
- screenshot `16-convert_keep-open-folder.png`
- finished in 136 s

## unzip_and_tag
- screenshot `17-unzip_and_tag-zip-picker.png`
- screenshot `18-unzip_and_tag-picker-roots.png`
- screenshot `19-unzip_and_tag-picker-Download.png`
- screenshot `20-unzip_and_tag-dest-picker.png`
- screenshot `21-unzip_and_tag-plan.png`
- screenshot `22-unzip_and_tag-details-typed.png`
- screenshot `23-unzip_and_tag-replace-chosen.png`
- screenshot `24-unzip_and_tag-done.png`
```
/sdcard/Pictures/lab:
total 8
drwxrwx--x 3 root sdcard_rw 4096 2026-11-01 14:24 Jpegs
drwxrwx--x 3 root sdcard_rw 4096 2026-11-01 14:24 Tiffs

/sdcard/Pictures/lab/Jpegs:
total 4
drwxrwx--x 3 root sdcard_rw 4096 2026-11-01 14:24 Order 4471

/sdcard/Pictures/lab/Jpegs/Order 4471:
total 4
drwxrwx--x 2 root sdcard_rw 4096 2026-11-01 14:24 Roll 1

/sdcard/Pictures/lab/Jpegs/Order 4471/Roll 1:
total 1756
-rw-rw---- 1 root sdcard_rw 446987 2021-08-03 10:00 00001.jpg
-rw-rw---- 1 root sdcard_rw 446393 2021-08-03 10:00 00002.jpg
-rw-rw---- 1 root sdcard_rw 446479 2021-08-03 10:00 00003.jpg
-rw-rw---- 1 root sdcard_rw 446812 2021-08-03 10:00 00004.jpg

/sdcard/Pictures/lab/Tiffs:
total 4
drwxrwx--x 3 root sdcard_rw 4096 2026-11-01 14:24 Order 4471

/sdcard/Pictures/lab/Tiffs/Order 4471:
total 4
drwxrwx--x 2 root sdcard_rw 4096 2026-11-01 14:24 Roll 1 JPEG

/sdcard/Pictures/lab/Tiffs/Order 4471/Roll 1 JPEG:
total 12128
-rw-rw---- 1 root sdcard_rw 3104612 2021-08-03 10:00 00001.jpg
-rw-rw---- 1 root sdcard_rw 3104666 2021-08-03 10:00 00002.jpg
-rw-rw---- 1 root sdcard_rw 3104065 2021-08-03 10:00 00003.jpg
-rw-rw---- 1 root sdcard_rw 3104162 2021-08-03 10:00 00004.jpg
```
- finished in 85 s

## unzip_any
- screenshot `25-unzip_any-zip-picker.png`
- screenshot `26-unzip_any-picker-roots.png`
- screenshot `27-unzip_any-picker-Download.png`
- screenshot `28-unzip_any-dest-picker.png`
- screenshot `29-unzip_any-plan.png`
- screenshot `30-unzip_any-wrong-password.png`
- screenshot `31-unzip_any-unzipped.png`
```
/sdcard/Pictures/docs:
total 352
-rw-rw---- 1 root sdcard_rw 144280 2026-11-01 14:17 cover.jpg
drwxrwx--x 2 root sdcard_rw   4096 2026-11-01 14:25 data
-rw-rw---- 1 root sdcard_rw   7400 2026-11-01 14:17 notes.txt
-rw-rw---- 1 root sdcard_rw 200009 2026-11-01 14:17 report.pdf

/sdcard/Pictures/docs/data:
total 4
-rw-rw---- 1 root sdcard_rw 337 2026-11-01 14:17 table.csv
```
- 'Open folder' after unzipping opened: mResumedActivity: ActivityRecord{1f1f460 u0 com.barion.filmscans/.MainActivity t11}
- screenshot `32-unzip_any-open-folder.png`
- finished in 101 s

## back_and_leave
- back on the start screen leaves to: mResumedActivity: ActivityRecord{e44fa21 u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity t5}
- finished in 8 s

## rotation
- screenshot `33-rotation-landscape-start.png`
- screenshot `34-rotation-landscape-settings.png`
- finished in 16 s

## big_text
- screenshot `35-big_text-big-text-start.png`
- screenshot `36-big_text-big-text-settings.png`
- finished in 22 s

## dark
- screenshot `37-dark-dark-start.png`
- finished in 4 s

## process_death
- screenshot `38-process_death-picker-roots.png`
- screenshot `39-process_death-picker-Pictures.png`
- screenshot `40-process_death-picker-Scans.png`
- screenshot `41-process_death-after-process-death.png`
- finished in 66 s

## monkey
- monkey: :Dropped: keys=0 pointers=12 trackballs=0 flips=0 rotations=0 / ## Network stats: elapsed time=79260ms (0ms mobile, 0ms wifi, 79260ms not connected) / // Monkey finished
- screenshot `42-monkey-after-monkey.png`
- finished in 83 s

### A001.jpg: (2400, 1600), mode RGB, layers/progressive/dpi (3, None, (2000.0, 2000.0)), ICC no
- IFD0: {'ResolutionUnit': 2, 'ImageDescription': 'Scanned on Noritsu HS-1800.', 'Make': 'Noritsu', 'Model': 'HS-1800', 'Software': 'EZ Controller', 'Orientation': 1, 'DateTime': '2019:04:12 14:31:00', 'YResolution': 2000.0, 'Copyright': 'Copyright 2019 Pat Example. All rights reserved.', 'XResolution': 2000.0, 'Artist': 'Pat Example', 'XPComment': b'S\x00c\x00a\x00n\x00n\x00e\x00d\x00 \x00o\x00n\x00 \x00N\x00o\x00r\x00i\x00t\x00s\x00u\x00 \x00H\x00S\x00-\x001\x008\x000\x000\x00.\x00\x00\x00', 'XPAuthor': b'P\x00a\x00t\x00 \x00E\x00x\x00a\x00m\x00p\x00l\x00e\x00\x00\x00', 'XPKeywords': b'f\x00i\x00l\x00m\x00\x00\x00'}
- EXIF: {'ExifVersion': b'0232', 'CameraOwnerName': 'Pat Example', 'ExifImageWidth': 2400, 'DateTimeOriginal': '2019:04:12 14:31:00', 'DateTimeDigitized': '2019:04:12 14:31:00', 'ExifImageHeight': 1600}
- GPS: none
- XMP: yes

### B002.jpg: (5000, 3400), mode RGB, layers/progressive/dpi (3, None, (2000.0, 2000.0)), ICC no
- IFD0: {'ResolutionUnit': 2, 'ImageDescription': 'Scanned on Noritsu HS-1800.', 'Make': 'Noritsu', 'Model': 'HS-1800', 'Software': 'EZ Controller', 'Orientation': 1, 'DateTime': '2026:11:01 14:18:04', 'YResolution': 2000.0, 'Copyright': 'Copyright 2026 Pat Example. All rights reserved.', 'XResolution': 2000.0, 'Artist': 'Pat Example', 'XPComment': b'S\x00c\x00a\x00n\x00n\x00e\x00d\x00 \x00o\x00n\x00 \x00N\x00o\x00r\x00i\x00t\x00s\x00u\x00 \x00H\x00S\x00-\x001\x008\x000\x000\x00.\x00\x00\x00', 'XPAuthor': b'P\x00a\x00t\x00 \x00E\x00x\x00a\x00m\x00p\x00l\x00e\x00\x00\x00', 'XPKeywords': b'f\x00i\x00l\x00m\x00\x00\x00'}
- EXIF: {'ExifVersion': b'0232', 'CameraOwnerName': 'Pat Example', 'ExifImageWidth': 5000, 'DateTimeOriginal': '2026:11:01 14:18:04', 'DateTimeDigitized': '2026:11:01 14:18:04', 'ExifImageHeight': 3400}
- GPS: none
- XMP: yes

### lab-Jpegs-00001.jpg: (1500, 1000), mode RGB, layers/progressive/dpi (3, None, (72, 72)), ICC no
- IFD0: {'ImageDescription': 'Pentax K1000.', 'Make': 'Pentax', 'Model': 'Pentax K1000', 'Orientation': 1, 'DateTime': '2021:08:03 10:00:00', 'Copyright': 'Copyright 2021 Pat Example. All rights reserved.', 'Artist': 'Pat Example', 'XPComment': b'P\x00e\x00n\x00t\x00a\x00x\x00 \x00K\x001\x000\x000\x000\x00.\x00\x00\x00', 'XPAuthor': b'P\x00a\x00t\x00 \x00E\x00x\x00a\x00m\x00p\x00l\x00e\x00\x00\x00', 'XPKeywords': b'f\x00i\x00l\x00m\x00;\x00P\x00e\x00n\x00t\x00a\x00x\x00 \x00K\x001\x000\x000\x000\x00\x00\x00'}
- EXIF: {'ExifVersion': b'0232', 'CameraOwnerName': 'Pat Example', 'ExifImageWidth': 1500, 'DateTimeOriginal': '2021:08:03 10:00:00', 'DateTimeDigitized': '2021:08:03 10:00:00', 'ExifImageHeight': 1000}
- GPS: none
- XMP: yes

### lab-Tiffs-00001.jpg: (1500, 1000), mode RGB, layers/progressive/dpi (3, None, (72, 72)), ICC no
- IFD0: {'ImageDescription': 'Pentax K1000.', 'Make': 'Pentax', 'Model': 'Pentax K1000', 'Software': 'tifffile.py', 'Orientation': 1, 'DateTime': '2021:08:03 10:00:00', 'Copyright': 'Copyright 2021 Pat Example. All rights reserved.', 'Artist': 'Pat Example', 'XPComment': b'P\x00e\x00n\x00t\x00a\x00x\x00 \x00K\x001\x000\x000\x000\x00.\x00\x00\x00', 'XPAuthor': b'P\x00a\x00t\x00 \x00E\x00x\x00a\x00m\x00p\x00l\x00e\x00\x00\x00', 'XPKeywords': b'f\x00i\x00l\x00m\x00;\x00P\x00e\x00n\x00t\x00a\x00x\x00 \x00K\x001\x000\x000\x000\x00\x00\x00'}
- EXIF: {'ExifVersion': b'0232', 'CameraOwnerName': 'Pat Example', 'ExifImageWidth': 1500, 'DateTimeOriginal': '2021:08:03 10:00:00', 'DateTimeDigitized': '2021:08:03 10:00:00', 'ExifImageHeight': 1000}
- GPS: none
- XMP: yes
