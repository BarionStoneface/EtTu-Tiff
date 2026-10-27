# Findings, API 26

- **cold_start**: [start screen] controls with no label for screen readers: android.view.View at (890, 1690, 1027, 1816)
- **settings**: [settings] controls with no label for screen readers: android.view.View at (901, 1411, 1038, 1537); android.view.View at (901, 1674, 1038, 1800)
- **convert_keep**: The folder picker hides internal storage until 'Show internal storage' is chosen from its menu, so a new user sees no way to reach their scans (Android 8/9). The app can pass EXTRA_SHOW_ADVANCED to show it.
- **convert_keep**: [rolls] controls with no label for screen readers: android.widget.RadioButton at (65, 708, 191, 834); android.widget.CheckBox at (70, 1104, 196, 1230)
- **unzip_and_replace**: scenario stopped: picker has no roots button and no storage entry. Showing: Nothing is written until you press Unzip. Rename any folder, clear a name to drop that level (what's in it moves up), or untick a folder to leave it out. | lab.zip | 8 file(s), 20 MB · into Internal storage/Pictures | lab | Folder to make | Jpegs | 2 MB | Order 4471 | Roll 1 | 4 file(s) · 2 MB | Scan dates from: date in the zip (4) | Tiffs | 19 MB | ← | Et Tu, Tiff? | Settings | Unzip 8 file(s) · 20 MB
- **rotation**: [start screen, landscape] touch targets under 48 dp: android.view.View (52×44 dp)
- **rotation**: [start screen, landscape] controls with no label for screen readers: android.view.View at (2084, 964, 2221, 1080)
- **big_text**: [start screen, largest text] touch targets under 48 dp: android.view.View (319×44 dp); android.view.View (52×44 dp)
- **big_text**: [start screen, largest text] controls with no label for screen readers: android.view.View at (53, 2158, 890, 2274); android.view.View at (890, 2158, 1027, 2274)
- **big_text**: with the largest text size, 'Unzip a download' falls off the bottom of the start screen and can't be scrolled to

---

# Emulator walk, API 26, page size , density 2.62


## cold_start
- cold start 556 ms
- screenshot `01-cold_start-start.png`
- finished in 6 s

## settings
- screenshot `02-settings-settings-top.png`
- screenshot `03-settings-settings-bottom.png`
- finished in 32 s

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
-rw-rw---- 1 root sdcard_rw 10719442 2026-10-27 02:19 A001.tif
-rw-rw---- 1 root sdcard_rw  7918081 2019-04-12 14:32 A002.jpg
-rw-rw---- 1 root sdcard_rw 10718651 2026-10-27 02:19 A002.tif
-rw-rw---- 1 root sdcard_rw  7918220 2019-04-12 14:33 A003.jpg
-rw-rw---- 1 root sdcard_rw 10718172 2026-10-27 02:19 A003.tif

/sdcard/Pictures/Scans/Roll B:
total 144076
-rw-rw---- 1 root sdcard_rw   4456588 2026-10-27 02:19 B001.jpg
-rw-rw---- 1 root sdcard_rw   6039466 2026-10-27 02:19 B001.tif
-rw-rw---- 1 root sdcard_rw  35028032 2026-10-27 02:19 B002.jpg
-rw-rw---- 1 root sdcard_rw 102000320 2026-10-27 02:19 B002.tif
```
- 'View photos' opened: mResumedActivity: ActivityRecord{2ad5466 u0 com.google.android.apps.photos/.pager.HostPhotoPagerActivity t9}
- screenshot `15-convert_keep-view-photos.png`
- 'Open folder' opened: mResumedActivity: ActivityRecord{7948f17 u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity t5}
- screenshot `16-convert_keep-open-folder.png`
- finished in 121 s

## unzip_and_replace
- screenshot `17-unzip_and_replace-zip-picker.png`
- screenshot `18-unzip_and_replace-picker-roots.png`
- screenshot `19-unzip_and_replace-picker-Download.png`
- screenshot `20-unzip_and_replace-dest-picker.png`
- screenshot `21-unzip_and_replace-stuck.png`

## back_and_leave
- back on the start screen leaves to: mResumedActivity: ActivityRecord{7948f17 u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity t5}
- finished in 7 s

## rotation
- screenshot `22-rotation-landscape-start.png`
- screenshot `23-rotation-landscape-settings.png`
- finished in 15 s

## big_text
- screenshot `24-big_text-big-text-start.png`
- screenshot `25-big_text-big-text-settings.png`
- finished in 20 s

## dark
- screenshot `26-dark-dark-start.png`
- finished in 3 s

## process_death
- screenshot `27-process_death-picker-roots.png`
- screenshot `28-process_death-picker-Pictures.png`
- screenshot `29-process_death-picker-Scans.png`
- screenshot `30-process_death-after-process-death.png`
- finished in 62 s

## monkey
- monkey: :Dropped: keys=0 pointers=17 trackballs=0 flips=0 rotations=0 / ## Network stats: elapsed time=76047ms (0ms mobile, 0ms wifi, 76047ms not connected) / // Monkey finished
- screenshot `31-monkey-after-monkey.png`
- finished in 79 s

### A001.jpg: (2400, 1600), mode RGB, layers/progressive/dpi (3, None, (2000.0, 2000.0)), ICC no
- IFD0: {'ResolutionUnit': 2, 'ImageDescription': 'Scanned on Noritsu HS-1800.', 'Make': 'Noritsu', 'Model': 'HS-1800', 'Software': 'EZ Controller', 'Orientation': 1, 'DateTime': '2019:04:12 14:31:00', 'YResolution': 2000.0, 'Copyright': 'Copyright 2019 Pat Example. All rights reserved.', 'XResolution': 2000.0, 'Artist': 'Pat Example', 'XPComment': b'S\x00c\x00a\x00n\x00n\x00e\x00d\x00 \x00o\x00n\x00 \x00N\x00o\x00r\x00i\x00t\x00s\x00u\x00 \x00H\x00S\x00-\x001\x008\x000\x000\x00.\x00\x00\x00', 'XPAuthor': b'P\x00a\x00t\x00 \x00E\x00x\x00a\x00m\x00p\x00l\x00e\x00\x00\x00', 'XPKeywords': b'f\x00i\x00l\x00m\x00\x00\x00'}
- EXIF: {'ExifVersion': b'0232', 'CameraOwnerName': 'Pat Example', 'ExifImageWidth': 2400, 'DateTimeOriginal': '2019:04:12 14:31:00', 'DateTimeDigitized': '2019:04:12 14:31:00', 'ExifImageHeight': 1600}
- GPS: none
- XMP: yes

### B002.jpg: (5000, 3400), mode RGB, layers/progressive/dpi (3, None, (2000.0, 2000.0)), ICC no
- IFD0: {'ResolutionUnit': 2, 'ImageDescription': 'Scanned on Noritsu HS-1800.', 'Make': 'Noritsu', 'Model': 'HS-1800', 'Software': 'EZ Controller', 'Orientation': 1, 'DateTime': '2026:10:27 02:19:14', 'YResolution': 2000.0, 'Copyright': 'Copyright 2026 Pat Example. All rights reserved.', 'XResolution': 2000.0, 'Artist': 'Pat Example', 'XPComment': b'S\x00c\x00a\x00n\x00n\x00e\x00d\x00 \x00o\x00n\x00 \x00N\x00o\x00r\x00i\x00t\x00s\x00u\x00 \x00H\x00S\x00-\x001\x008\x000\x000\x00.\x00\x00\x00', 'XPAuthor': b'P\x00a\x00t\x00 \x00E\x00x\x00a\x00m\x00p\x00l\x00e\x00\x00\x00', 'XPKeywords': b'f\x00i\x00l\x00m\x00\x00\x00'}
- EXIF: {'ExifVersion': b'0232', 'CameraOwnerName': 'Pat Example', 'ExifImageWidth': 5000, 'DateTimeOriginal': '2026:10:27 02:19:14', 'DateTimeDigitized': '2026:10:27 02:19:14', 'ExifImageHeight': 3400}
- GPS: none
- XMP: yes
