# Findings, API 34

- **cold_start**: cold start took 3475 ms (Android vitals flags over 5 s; aim under 1 s)
- **cold_start**: [start screen] controls with no label for screen readers: android.view.View at (890, 1755, 1027, 1881)
- **settings**: [settings] controls with no label for screen readers: android.view.View at (901, 1476, 1038, 1602); android.view.View at (901, 1739, 1038, 1865)
- **convert_keep**: [rolls] controls with no label for screen readers: android.widget.RadioButton at (65, 773, 191, 899); android.widget.CheckBox at (70, 1169, 196, 1295)
- **unzip_and_replace**: [unzip preview] touch targets under 48 dp: Roll 1 Roll 1 (311×33 dp); android.widget.CheckBox (48×25 dp)
- **unzip_and_replace**: [unzip preview] controls with no label for screen readers: android.widget.CheckBox at (33, 908, 158, 1034); android.widget.CheckBox at (70, 1139, 195, 1265); android.widget.CheckBox at (107, 1370, 232, 1496); android.widget.CheckBox at (33, 1643, 158, 1769); android.widget.CheckBox at (70, 1874, 195, 2000); android.widget.CheckBox at (107, 2105, 232, 2170)
- **unzip_and_replace**: scenario stopped: couldn't find '^(Convert and delete|Tag in place)$' on screen. Showing: This folder has 1 TIFF roll(s) and 1 lab JPEG roll(s). Work on: | TIFFs | Lab JPEGs | Both | Roll 1 | Unzipped | ✓ lab.zip: 8 file(s) unzipped, 4 JPEG(s) given their scan date | This phone doesn't let apps set file dates, so the files show today's date. The scan date is inside each photo, which galleries use; on a PC, LabUnzip's "Fix dates" sets the file dates from it. | What happens to the originals? | Keep them | The JPEGs are added next to the TIFFs. The lab's JPEGs are copied, tagged, into a new folder beside each roll. Nothing is deleted or renamed. | Replace them: DELETE the TIFFs | Each TIFF is permanently deleted once its JPEG is saved and checked. Deleted TIFFs do not go to the Recycle bin, so keep a backup if you might want them. The lab's JPEGs get the new details and names in place. Their pictures aren't re-saved, but their old details (including any location) are replaced. | 2 roll(s) in lab. Each roll starts blank — nothing carries over. | 4 lab JPEGs to tag, pictures untouched · scanned 2021-08-03 | 00001.jpg | 00002.jpg | 00003.jpg | 00004.jpg | ← | Et Tu, Tiff? | Dry run | Settings | Convert 4 and tag 4, delete the TIFFs
- **rotation**: [start screen, landscape] controls with no label for screen readers: android.view.View at (2210, 901, 2347, 1027)
- **big_text**: [start screen, largest text] controls with no label for screen readers: android.view.View at (53, 2221, 890, 2347); android.view.View at (890, 2221, 1027, 2347)
- **big_text**: with the largest text size, 'Unzip a download' falls off the bottom of the start screen and can't be scrolled to
- **process_death**: after Android closes the app in the background (normal when memory is short), coming back lands on the start screen and the rolls and anything typed for them are gone

---

# Emulator walk, API 34, page size 4096, density 2.62


## cold_start
- cold start 3475 ms
- screenshot `01-cold_start-start.png`
- finished in 11 s

## settings
- screenshot `02-settings-settings-top.png`
- screenshot `03-settings-settings-bottom.png`
- finished in 45 s

## convert_keep
- screenshot `04-convert_keep-picker.png`
- screenshot `05-convert_keep-picker-Pictures.png`
- screenshot `06-convert_keep-picker-Scans.png`
- screenshot `07-convert_keep-picker-scans.png`
- screenshot `08-convert_keep-rolls.png`
- screenshot `09-convert_keep-rolls-dates.png`
- screenshot `10-convert_keep-confirm.png`
- notification permission dialog shown (API 34)
- screenshot `11-convert_keep-converting.png`
- progress notification shown while in the background
- screenshot `12-convert_keep-notification.png`
- peak memory while converting about 0 MB
- screenshot `13-convert_keep-done.png`
```
/sdcard/Pictures/Scans/Roll A:
total 54640
-rw-rw---- 1 u0_a174 media_rw  7918680 2026-10-27 02:08 A001.jpg
-rw-rw---- 1 u0_a174 media_rw 10719442 2026-10-27 02:04 A001.tif
-rw-rw---- 1 u0_a174 media_rw  7918081 2026-10-27 02:08 A002.jpg
-rw-rw---- 1 u0_a174 media_rw 10718651 2026-10-27 02:04 A002.tif
-rw-rw---- 1 u0_a174 media_rw  7918220 2026-10-27 02:08 A003.jpg
-rw-rw---- 1 u0_a174 media_rw 10718172 2026-10-27 02:04 A003.tif

/sdcard/Pictures/Scans/Roll B:
total 144092
-rw-rw---- 1 u0_a174 media_rw   4456588 2026-10-27 02:08 B001.jpg
-rw-rw---- 1 u0_a174 media_rw   6039466 2026-10-27 02:04 B001.tif
-rw-rw---- 1 u0_a174 media_rw  35028032 2026-10-27 02:08 B002.jpg
-rw-rw---- 1 u0_a174 media_rw 102000320 2026-10-27 02:04 B002.tif
```
- 'View photos' opened: topResumedActivity=ActivityRecord{7702796 u0 com.google.android.apps.photos/.pager.HostPhotoPagerActivity t11}
- screenshot `14-convert_keep-view-photos.png`
- 'Open folder' opened: topResumedActivity=ActivityRecord{8fa7b2a u0 com.google.android.documentsui/com.android.documentsui.files.FilesActivity t12}
- screenshot `15-convert_keep-open-folder.png`
- finished in 119 s

## unzip_and_replace
- screenshot `16-unzip_and_replace-zip-picker.png`
- screenshot `17-unzip_and_replace-picker-Download.png`
- screenshot `18-unzip_and_replace-dest-picker.png`
- screenshot `19-unzip_and_replace-plan.png`
- screenshot `20-unzip_and_replace-unzipped.png`
```
/sdcard/Pictures/lab:
total 16
drwxrws--- 3 u0_a174 media_rw 4096 2026-10-27 02:10 Jpegs
drwxrws--- 3 u0_a174 media_rw 4096 2026-10-27 02:10 Tiffs

/sdcard/Pictures/lab/Jpegs:
total 8
drwxrws--- 3 u0_a174 media_rw 4096 2026-10-27 02:10 Order 4471

/sdcard/Pictures/lab/Jpegs/Order 4471:
total 8
drwxrws--- 2 u0_a174 media_rw 4096 2026-10-27 02:10 Roll 1

/sdcard/Pictures/lab/Jpegs/Order 4471/Roll 1:
total 1760
-rw-rw---- 1 u0_a174 media_rw 444649 2026-10-27 02:10 00001.jpg
-rw-rw---- 1 u0_a174 media_rw 444055 2026-10-27 02:10 00002.jpg
-rw-rw---- 1 u0_a174 media_rw 444141 2026-10-27 02:10 00003.jpg
-rw-rw---- 1 u0_a174 media_rw 444474 2026-10-27 02:10 00004.jpg

/sdcard/Pictures/lab/Tiffs:
total 8
drwxrws--- 3 u0_a174 media_rw 4096 2026-10-27 02:10 Order 4471

/sdcard/Pictures/lab/Tiffs/Order 4471:
total 8
drwxrws--- 2 u0_a174 media_rw 4096 2026-10-27 02:10 Roll 1

/sdcard/Pictures/lab/Tiffs/Order 4471/Roll 1:
total 17600
-rw-rw---- 1 u0_a174 media_rw 4500304 2026-10-27 02:10 00001.tif
-rw-rw---- 1 u0_a174 media_rw 4500304 2026-10-27 02:10 00002.tif
-rw-rw---- 1 u0_a174 media_rw 4500304 2026-10-27 02:10 00003.tif
-rw-rw---- 1 u0_a174 media_rw 4500304 2026-10-27 02:10 00004.tif
```
- screenshot `21-unzip_and_replace-replace-mode.png`
- screenshot `22-unzip_and_replace-confirm-delete.png`
- screenshot `23-unzip_and_replace-stuck.png`

## back_and_leave
- back on the start screen leaves to: topResumedActivity=ActivityRecord{a61aec9 u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity t7}
- finished in 8 s

## rotation
- screenshot `24-rotation-landscape-start.png`
- screenshot `25-rotation-landscape-settings.png`
- finished in 20 s

## big_text
- screenshot `26-big_text-big-text-start.png`
- screenshot `27-big_text-big-text-settings.png`
- finished in 27 s

## dark
- screenshot `28-dark-dark-start.png`
- finished in 4 s

## process_death
- screenshot `29-process_death-picker-Pictures.png`
- screenshot `30-process_death-picker-Scans.png`
- screenshot `31-process_death-after-process-death.png`
- finished in 49 s

## monkey
- monkey: :Dropped: keys=0 pointers=0 trackballs=0 flips=5 rotations=0 / ## Network stats: elapsed time=80624ms (0ms mobile, 0ms wifi, 80624ms not connected) / // Monkey finished
- screenshot `32-monkey-after-monkey.png`
- finished in 84 s

### A001.jpg: (2400, 1600), mode RGB, layers/progressive/dpi (3, None, (2000.0, 2000.0)), ICC no
- IFD0: {'ResolutionUnit': 2, 'ImageDescription': 'Scanned on Noritsu HS-1800.', 'Make': 'Noritsu', 'Model': 'HS-1800', 'Software': 'EZ Controller', 'Orientation': 1, 'DateTime': '2019:04:12 14:31:00', 'YResolution': 2000.0, 'Copyright': 'Copyright 2019 Pat Example. All rights reserved.', 'XResolution': 2000.0, 'Artist': 'Pat Example', 'XPComment': b'S\x00c\x00a\x00n\x00n\x00e\x00d\x00 \x00o\x00n\x00 \x00N\x00o\x00r\x00i\x00t\x00s\x00u\x00 \x00H\x00S\x00-\x001\x008\x000\x000\x00.\x00\x00\x00', 'XPAuthor': b'P\x00a\x00t\x00 \x00E\x00x\x00a\x00m\x00p\x00l\x00e\x00\x00\x00', 'XPKeywords': b'f\x00i\x00l\x00m\x00\x00\x00'}
- EXIF: {'ExifVersion': b'0232', 'CameraOwnerName': 'Pat Example', 'ExifImageWidth': 2400, 'DateTimeOriginal': '2019:04:12 14:31:00', 'DateTimeDigitized': '2019:04:12 14:31:00', 'ExifImageHeight': 1600}
- GPS: none
- XMP: yes

### B002.jpg: (5000, 3400), mode RGB, layers/progressive/dpi (3, None, (2000.0, 2000.0)), ICC no
- IFD0: {'ResolutionUnit': 2, 'ImageDescription': 'Scanned on Noritsu HS-1800.', 'Make': 'Noritsu', 'Model': 'HS-1800', 'Software': 'EZ Controller', 'Orientation': 1, 'DateTime': '2026:10:27 02:04:19', 'YResolution': 2000.0, 'Copyright': 'Copyright 2026 Pat Example. All rights reserved.', 'XResolution': 2000.0, 'Artist': 'Pat Example', 'XPComment': b'S\x00c\x00a\x00n\x00n\x00e\x00d\x00 \x00o\x00n\x00 \x00N\x00o\x00r\x00i\x00t\x00s\x00u\x00 \x00H\x00S\x00-\x001\x008\x000\x000\x00.\x00\x00\x00', 'XPAuthor': b'P\x00a\x00t\x00 \x00E\x00x\x00a\x00m\x00p\x00l\x00e\x00\x00\x00', 'XPKeywords': b'f\x00i\x00l\x00m\x00\x00\x00'}
- EXIF: {'ExifVersion': b'0232', 'CameraOwnerName': 'Pat Example', 'ExifImageWidth': 5000, 'DateTimeOriginal': '2026:10:27 02:04:19', 'DateTimeDigitized': '2026:10:27 02:04:19', 'ExifImageHeight': 3400}
- GPS: none
- XMP: yes
