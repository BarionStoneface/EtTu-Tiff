# Findings, API 36

- **cold_start**: [start screen] controls with no label for screen readers: android.view.View at (890, 1839, 1027, 1965)
- **settings**: [settings] controls with no label for screen readers: android.view.View at (901, 1476, 1038, 1602); android.view.View at (901, 1812, 1038, 1938)
- **convert_keep**: [rolls] controls with no label for screen readers: android.widget.RadioButton at (65, 773, 191, 899); android.widget.CheckBox at (70, 1169, 196, 1295)
- **unzip_and_tag**: [unzip preview] touch targets under 48 dp: android.widget.EditText (291×9 dp)
- **unzip_and_tag**: [unzip preview] controls with no label for screen readers: android.view.View at (879, 881, 1016, 1007); android.widget.CheckBox at (33, 1071, 158, 1197); android.widget.CheckBox at (70, 1302, 195, 1428); android.widget.CheckBox at (107, 1533, 232, 1659); android.widget.EditText at (258, 2146, 1022, 2170)
- **unzip_any**: [unzip preview, locked zip] controls with no label for screen readers: android.view.View at (879, 1160, 1016, 1286); android.widget.CheckBox at (33, 1350, 158, 1476); android.view.View at (879, 1999, 1016, 2125)
- **rotation**: [start screen, landscape] controls with no label for screen readers: android.view.View at (2210, 901, 2347, 1027)
- **big_text**: [start screen, largest text] controls with no label for screen readers: android.view.View at (53, 2221, 890, 2347); android.view.View at (890, 2221, 1027, 2347)
- **big_text**: with the largest text size, 'Unzip a zip' falls off the bottom of the start screen and can't be scrolled to
- **process_death**: after Android closes the app in the background (normal when memory is short), coming back lands on the start screen and the rolls and anything typed for them are gone

---

# Emulator walk, API 36, page size 16384, density 2.62


## cold_start
- cold start 1750 ms
- screenshot `01-cold_start-start.png`
- finished in 9 s

## settings
- screenshot `02-settings-settings-top.png`
- screenshot `03-settings-settings-bottom.png`
- finished in 42 s

## convert_keep
- screenshot `04-convert_keep-picker.png`
- screenshot `05-convert_keep-picker-Pictures.png`
- screenshot `06-convert_keep-picker-Scans.png`
- screenshot `07-convert_keep-picker-scans.png`
- screenshot `08-convert_keep-rolls.png`
- screenshot `09-convert_keep-rolls-dates.png`
- screenshot `10-convert_keep-confirm.png`
- notification permission dialog shown (API 36)
- screenshot `11-convert_keep-converting.png`
- progress notification shown while in the background
- screenshot `12-convert_keep-notification.png`
- peak memory while converting about 0 MB
- screenshot `13-convert_keep-done.png`
```
/sdcard/Pictures/Scans/Roll A:
total 54640
-rw-rw---- 1 u0_a196 media_rw  7918680 2026-11-01 14:22 A001.jpg
-rw-rw---- 1 u0_a196 media_rw 10719442 2026-11-01 14:18 A001.tif
-rw-rw---- 1 u0_a196 media_rw  7918081 2026-11-01 14:22 A002.jpg
-rw-rw---- 1 u0_a196 media_rw 10718651 2026-11-01 14:18 A002.tif
-rw-rw---- 1 u0_a196 media_rw  7918220 2026-11-01 14:22 A003.jpg
-rw-rw---- 1 u0_a196 media_rw 10718172 2026-11-01 14:18 A003.tif

/sdcard/Pictures/Scans/Roll B:
total 144092
-rw-rw---- 1 u0_a196 media_rw   4456588 2026-11-01 14:22 B001.jpg
-rw-rw---- 1 u0_a196 media_rw   6039466 2026-11-01 14:18 B001.tif
-rw-rw---- 1 u0_a196 media_rw  35028032 2026-11-01 14:22 B002.jpg
-rw-rw---- 1 u0_a196 media_rw 102000320 2026-11-01 14:18 B002.tif
```
- 'View photos' opened: topResumedActivity=ActivityRecord{159990076 u0 com.google.android.apps.photos/.pager.HostPhotoPagerActivity t11}
- screenshot `14-convert_keep-view-photos.png`
- 'Open folder' opened: topResumedActivity=ActivityRecord{263961261 u0 com.google.android.documentsui/com.android.documentsui.files.FilesActivity t12}
- screenshot `15-convert_keep-open-folder.png`
- finished in 114 s

## unzip_and_tag
- screenshot `16-unzip_and_tag-zip-picker.png`
- screenshot `17-unzip_and_tag-picker-Download.png`
- screenshot `18-unzip_and_tag-dest-picker.png`
- screenshot `19-unzip_and_tag-plan.png`
- screenshot `20-unzip_and_tag-details-typed.png`
- screenshot `21-unzip_and_tag-replace-chosen.png`
- screenshot `22-unzip_and_tag-done.png`
```
/sdcard/Pictures/lab:
total 16
drwxrws--- 3 u0_a196 media_rw 4096 2026-11-01 14:24 Jpegs
drwxrws--- 3 u0_a196 media_rw 4096 2026-11-01 14:24 Tiffs

/sdcard/Pictures/lab/Jpegs:
total 8
drwxrws--- 3 u0_a196 media_rw 4096 2026-11-01 14:24 Order 4471

/sdcard/Pictures/lab/Jpegs/Order 4471:
total 8
drwxrws--- 2 u0_a196 media_rw 4096 2026-11-01 14:24 Roll 1

/sdcard/Pictures/lab/Jpegs/Order 4471/Roll 1:
total 1772
-rw-rw---- 1 u0_a196 media_rw 446987 2026-11-01 14:24 00001.jpg
-rw-rw---- 1 u0_a196 media_rw 446393 2026-11-01 14:24 00002.jpg
-rw-rw---- 1 u0_a196 media_rw 446479 2026-11-01 14:24 00003.jpg
-rw-rw---- 1 u0_a196 media_rw 446812 2026-11-01 14:24 00004.jpg

/sdcard/Pictures/lab/Tiffs:
total 8
drwxrws--- 3 u0_a196 media_rw 4096 2026-11-01 14:24 Order 4471

/sdcard/Pictures/lab/Tiffs/Order 4471:
total 8
drwxrws--- 2 u0_a196 media_rw 4096 2026-11-01 14:24 Roll 1 JPEG

/sdcard/Pictures/lab/Tiffs/Order 4471/Roll 1 JPEG:
total 12144
-rw-rw---- 1 u0_a196 media_rw 3104612 2026-11-01 14:24 00001.jpg
-rw-rw---- 1 u0_a196 media_rw 3104666 2026-11-01 14:24 00002.jpg
-rw-rw---- 1 u0_a196 media_rw 3104065 2026-11-01 14:24 00003.jpg
-rw-rw---- 1 u0_a196 media_rw 3104162 2026-11-01 14:24 00004.jpg
```
- finished in 99 s

## unzip_any
- screenshot `23-unzip_any-zip-picker.png`
- screenshot `24-unzip_any-picker-Download.png`
- screenshot `25-unzip_any-dest-picker.png`
- screenshot `26-unzip_any-plan.png`
- screenshot `27-unzip_any-wrong-password.png`
- screenshot `28-unzip_any-unzipped.png`
```
/sdcard/Pictures/docs:
total 368
-rw-rw---- 1 u0_a196 media_rw 144280 2026-11-01 14:26 cover.jpg
drwxrws--- 2 u0_a196 media_rw   4096 2026-11-01 14:26 data
-rw-rw---- 1 u0_a196 media_rw   7400 2026-11-01 14:26 notes.txt
-rw-rw---- 1 u0_a196 media_rw 200009 2026-11-01 14:26 report.pdf

/sdcard/Pictures/docs/data:
total 8
-rw-rw---- 1 u0_a196 media_rw 337 2026-11-01 14:26 table.csv
```
- 'Open folder' after unzipping opened: topResumedActivity=ActivityRecord{15593073 u0 com.google.android.documentsui/com.android.documentsui.files.FilesActivity t15}
- screenshot `29-unzip_any-open-folder.png`
- finished in 122 s

## back_and_leave
- back on the start screen leaves to: topResumedActivity=ActivityRecord{15593073 u0 com.google.android.documentsui/com.android.documentsui.files.FilesActivity t15}
- finished in 7 s

## rotation
- screenshot `30-rotation-landscape-start.png`
- screenshot `31-rotation-landscape-settings.png`
- finished in 19 s

## big_text
- screenshot `32-big_text-big-text-start.png`
- screenshot `33-big_text-big-text-settings.png`
- finished in 25 s

## dark
- screenshot `34-dark-dark-start.png`
- finished in 4 s

## process_death
- screenshot `35-process_death-picker-Pictures.png`
- screenshot `36-process_death-picker-Scans.png`
- screenshot `37-process_death-after-process-death.png`
- finished in 59 s

## monkey
- monkey: :Dropped: keys=0 pointers=0 trackballs=0 flips=3 rotations=0 / ## Network stats: elapsed time=81157ms (0ms mobile, 0ms wifi, 81157ms not connected) / // Monkey finished
- screenshot `38-monkey-after-monkey.png`
- finished in 84 s

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
