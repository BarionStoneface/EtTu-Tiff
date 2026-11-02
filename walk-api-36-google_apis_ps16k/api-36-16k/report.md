# Findings, API 36

- **cold_start**: cold start took 3130 ms (Android vitals flags over 5 s; aim under 1 s)
- **cold_start**: 'Unzip a zip' can't be reached on the start screen
- **settings**: scenario stopped: couldn't find '^Settings$' on screen. Showing: System UI isn't responding | Close app | Wait
- **convert_keep**: scenario stopped: picker has no roots button and no storage entry. Showing: Turns folders of TIFF scans into full-quality JPEGs, one roll per folder, and tags the lab's own JPEGs with the same details without re-saving them. | Keeps the pixels, colour profile, DPI, scanner and original scan date. Drops location and everything else, then adds your camera, film, push/pull and copyright. JPEGs are saved in the same folder as their TIFFs; you choose whether the TIFFs are replaced or kept. | Add your name in Settings first, so the copyright gets written. | Choose scans folder | Pick a folder holding one roll, or a folder of roll folders. Zips in it can be unzipped from there. | Unzip a zip | Any zip, not only lab downloads. Zips inside it are unpacked too, and a password-protected one asks for its password. Pick the zip, then the folder it should go in, e.g. Pictures or Documents. Android doesn't let apps save into the Download folder itself. You'll see everything that's in it, and can rename or leave out folders, before anything is written. | Delete the zip after unzipping | Et Tu, Tiff? | Settings
- **unzip_and_tag**: [unzip preview] touch targets under 48 dp: android.view.View (311×19 dp)
- **unzip_and_tag**: [unzip preview] controls with no label for screen readers: android.view.View at (879, 881, 1016, 1007); android.widget.CheckBox at (33, 1071, 158, 1197); android.widget.CheckBox at (70, 1302, 195, 1428); android.widget.CheckBox at (107, 1533, 232, 1659); android.view.View at (232, 2140, 1048, 2190)
- **unzip_any**: [unzip preview, locked zip] controls with no label for screen readers: android.view.View at (879, 1160, 1016, 1286); android.widget.CheckBox at (33, 1350, 158, 1476)
- **unzip_any**: a password-protected zip didn't ask for its password
- **rotation**: [start screen, landscape] controls with no label for screen readers: android.view.View at (53, 885, 2347, 1011); android.view.View at (2210, 901, 2347, 1027)
- **rotation**: in landscape, 'Unzip a zip' can't be reached: the start screen doesn't scroll
- **big_text**: [start screen, largest text] controls with no label for screen readers: android.view.View at (53, 2221, 890, 2347); android.view.View at (890, 2221, 1027, 2347)
- **big_text**: with the largest text size, 'Unzip a zip' falls off the bottom of the start screen and can't be scrolled to
- **process_death**: after Android closes the app in the background (normal when memory is short), coming back lands on the start screen and the rolls and anything typed for them are gone

---

# Emulator walk, API 36, page size 16384, density 2.62


## cold_start
- cold start 3130 ms
- screenshot `01-cold_start-start.png`
- finished in 28 s

## settings
- screenshot `02-settings-stuck.png`

## convert_keep
- screenshot `03-convert_keep-picker.png`
- screenshot `04-convert_keep-stuck.png`

## unzip_and_tag
- screenshot `05-unzip_and_tag-zip-picker.png`
- screenshot `06-unzip_and_tag-picker-roots.png`
- screenshot `07-unzip_and_tag-picker-Download.png`
- screenshot `08-unzip_and_tag-dest-picker.png`
- screenshot `09-unzip_and_tag-plan.png`
- screenshot `10-unzip_and_tag-thumbnails.png`
- screenshot `11-unzip_and_tag-photo-from-zip.png`
- screenshot `12-unzip_and_tag-details-typed.png`
- screenshot `13-unzip_and_tag-replace-chosen.png`
- notification permission dialog shown (API 36)
- screenshot `14-unzip_and_tag-done.png`
```
/sdcard/Pictures/lab:
total 16
drwxrws--- 3 u0_a197 media_rw 4096 2026-11-02 03:03 Jpegs
drwxrws--- 3 u0_a197 media_rw 4096 2026-11-02 03:03 Tiffs

/sdcard/Pictures/lab/Jpegs:
total 8
drwxrws--- 3 u0_a197 media_rw 4096 2026-11-02 03:03 Order 4471

/sdcard/Pictures/lab/Jpegs/Order 4471:
total 8
drwxrws--- 2 u0_a197 media_rw 4096 2026-11-02 03:03 Roll 1

/sdcard/Pictures/lab/Jpegs/Order 4471/Roll 1:
total 1760
-rw-rw---- 1 u0_a197 media_rw 446132 2026-11-02 03:03 00001.jpg
-rw-rw---- 1 u0_a197 media_rw 445538 2026-11-02 03:03 00002.jpg
-rw-rw---- 1 u0_a197 media_rw 445624 2026-11-02 03:03 00003.jpg
-rw-rw---- 1 u0_a197 media_rw 445957 2026-11-02 03:03 00004.jpg

/sdcard/Pictures/lab/Tiffs:
total 8
drwxrws--- 3 u0_a197 media_rw 4096 2026-11-02 03:03 Order 4471

/sdcard/Pictures/lab/Tiffs/Order 4471:
total 8
drwxrws--- 2 u0_a197 media_rw 4096 2026-11-02 03:03 Roll 1 JPEG

/sdcard/Pictures/lab/Tiffs/Order 4471/Roll 1 JPEG:
total 12144
-rw-rw---- 1 u0_a197 media_rw 3103757 2026-11-02 03:03 00001.jpg
-rw-rw---- 1 u0_a197 media_rw 3103811 2026-11-02 03:03 00002.jpg
-rw-rw---- 1 u0_a197 media_rw 3103210 2026-11-02 03:03 00003.jpg
-rw-rw---- 1 u0_a197 media_rw 3103307 2026-11-02 03:03 00004.jpg
```
- finished in 145 s

## unzip_any
- screenshot `15-unzip_any-zip-picker.png`
- screenshot `16-unzip_any-picker-Download.png`
- screenshot `17-unzip_any-dest-picker.png`
- screenshot `18-unzip_any-plan.png`
- screenshot `19-unzip_any-wrong-password.png`
- tapping notes.txt in the zip opened: topResumedActivity=ActivityRecord{9181151 u0 android/com.android.internal.app.ResolverActivity t15}
- screenshot `20-unzip_any-txt-from-zip.png`
- screenshot `21-unzip_any-unzipped.png`
```
/sdcard/Pictures/docs:
total 368
-rw-rw---- 1 u0_a197 media_rw 144280 2026-11-02 03:05 cover.jpg
drwxrws--- 2 u0_a197 media_rw   4096 2026-11-02 03:05 data
-rw-rw---- 1 u0_a197 media_rw   7400 2026-11-02 03:05 notes.txt
-rw-rw---- 1 u0_a197 media_rw 200009 2026-11-02 03:05 report.pdf

/sdcard/Pictures/docs/data:
total 8
-rw-rw---- 1 u0_a197 media_rw 337 2026-11-02 03:05 table.csv
```
- 'Open folder' after unzipping opened: topResumedActivity=ActivityRecord{87751800 u0 com.google.android.documentsui/com.android.documentsui.files.FilesActivity t16}
- screenshot `22-unzip_any-open-folder.png`
- finished in 134 s

## back_and_leave
- back on the start screen leaves to: topResumedActivity=ActivityRecord{87751800 u0 com.google.android.documentsui/com.android.documentsui.files.FilesActivity t16}
- finished in 8 s

## rotation
- screenshot `23-rotation-landscape-start.png`
- screenshot `24-rotation-landscape-settings.png`
- finished in 32 s

## big_text
- screenshot `25-big_text-big-text-start.png`
- screenshot `26-big_text-big-text-settings.png`
- finished in 27 s

## dark
- screenshot `27-dark-dark-start.png`
- finished in 5 s

## process_death
- screenshot `28-process_death-picker-Pictures.png`
- screenshot `29-process_death-picker-Scans.png`
- screenshot `30-process_death-after-process-death.png`
- finished in 60 s

## monkey
- monkey: :Dropped: keys=0 pointers=0 trackballs=0 flips=3 rotations=0 / ## Network stats: elapsed time=91171ms (0ms mobile, 0ms wifi, 91171ms not connected) / // Monkey finished
- screenshot `31-monkey-after-monkey.png`
- finished in 96 s

### lab-Jpegs-00001.jpg: (1500, 1000), mode RGB, layers/progressive/dpi (3, None, (72, 72)), ICC no
- IFD0: {'ImageDescription': 'Pentax K1000.', 'Make': 'Pentax', 'Model': 'Pentax K1000', 'Orientation': 1, 'DateTime': '2021:08:03 10:00:00', 'XPComment': b'P\x00e\x00n\x00t\x00a\x00x\x00 \x00K\x001\x000\x000\x000\x00.\x00\x00\x00', 'XPKeywords': b'f\x00i\x00l\x00m\x00;\x00P\x00e\x00n\x00t\x00a\x00x\x00 \x00K\x001\x000\x000\x000\x00\x00\x00'}
- EXIF: {'ExifVersion': b'0232', 'ExifImageWidth': 1500, 'DateTimeOriginal': '2021:08:03 10:00:00', 'DateTimeDigitized': '2021:08:03 10:00:00', 'ExifImageHeight': 1000}
- GPS: none
- XMP: yes

### lab-Tiffs-00001.jpg: (1500, 1000), mode RGB, layers/progressive/dpi (3, None, (72, 72)), ICC no
- IFD0: {'ImageDescription': 'Pentax K1000.', 'Make': 'Pentax', 'Model': 'Pentax K1000', 'Software': 'tifffile.py', 'Orientation': 1, 'DateTime': '2021:08:03 10:00:00', 'XPComment': b'P\x00e\x00n\x00t\x00a\x00x\x00 \x00K\x001\x000\x000\x000\x00.\x00\x00\x00', 'XPKeywords': b'f\x00i\x00l\x00m\x00;\x00P\x00e\x00n\x00t\x00a\x00x\x00 \x00K\x001\x000\x000\x000\x00\x00\x00'}
- EXIF: {'ExifVersion': b'0232', 'ExifImageWidth': 1500, 'DateTimeOriginal': '2021:08:03 10:00:00', 'DateTimeDigitized': '2021:08:03 10:00:00', 'ExifImageHeight': 1000}
- GPS: none
- XMP: yes
