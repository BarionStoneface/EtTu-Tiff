# Findings, API 26

- **cold_start**: [start screen] controls with no label for screen readers: android.view.View at (890, 1690, 1027, 1816)
- **settings**: [settings] controls with no label for screen readers: android.view.View at (901, 1411, 1038, 1537); android.view.View at (901, 1674, 1038, 1800)
- **convert_keep**: scenario stopped: couldn't find '^Show roots$' on screen. Showing: Turns folders of TIFF scans into full-quality JPEGs, one roll per folder, and tags the lab's own JPEGs with the same details without re-saving them. | Keeps the pixels, colour profile, DPI, scanner and original scan date. Drops location and everything else, then adds your camera, film, push/pull and copyright. JPEGs are saved in the same folder as their TIFFs; you choose whether the TIFFs are replaced or kept. | Choose scans folder | Pick a folder holding one roll, or a folder of roll folders. Zips in it can be unzipped from there. | Unzip a download (.zip) | Pick the zip (zips inside it are handled too), then the folder the rolls should go in, e.g. Pictures. Android doesn't let apps save into the Download folder itself. You'll see everything that's in it, and can rename or leave out folders, before anything is written. | Delete the zip after unzipping | Et Tu, Tiff? | Settings
- **unzip_and_replace**: scenario stopped: couldn't find '^Show roots$' on screen. Showing: Turns folders of TIFF scans into full-quality JPEGs, one roll per folder, and tags the lab's own JPEGs with the same details without re-saving them. | Keeps the pixels, colour profile, DPI, scanner and original scan date. Drops location and everything else, then adds your camera, film, push/pull and copyright. JPEGs are saved in the same folder as their TIFFs; you choose whether the TIFFs are replaced or kept. | Choose scans folder | Pick a folder holding one roll, or a folder of roll folders. Zips in it can be unzipped from there. | Unzip a download (.zip) | Pick the zip (zips inside it are handled too), then the folder the rolls should go in, e.g. Pictures. Android doesn't let apps save into the Download folder itself. You'll see everything that's in it, and can rename or leave out folders, before anything is written. | Delete the zip after unzipping | Et Tu, Tiff? | Settings
- **rotation**: [start screen, landscape] touch targets under 48 dp: android.view.View (52×44 dp)
- **rotation**: [start screen, landscape] controls with no label for screen readers: android.view.View at (2084, 964, 2221, 1080)
- **big_text**: [start screen, largest text] touch targets under 48 dp: android.view.View (319×44 dp); android.view.View (52×44 dp)
- **big_text**: [start screen, largest text] controls with no label for screen readers: android.view.View at (53, 2158, 890, 2274); android.view.View at (890, 2158, 1027, 2274)
- **big_text**: with the largest text size, 'Unzip a download' falls off the bottom of the start screen and can't be scrolled to
- **process_death**: scenario stopped: couldn't find '^Show roots$' on screen. Showing: Turns folders of TIFF scans into full-quality JPEGs, one roll per folder, and tags the lab's own JPEGs with the same details without re-saving them. | Keeps the pixels, colour profile, DPI, scanner and original scan date. Drops location and everything else, then adds your camera, film, push/pull and copyright. JPEGs are saved in the same folder as their TIFFs; you choose whether the TIFFs are replaced or kept. | Choose scans folder | Pick a folder holding one roll, or a folder of roll folders. Zips in it can be unzipped from there. | Unzip a download (.zip) | Pick the zip (zips inside it are handled too), then the folder the rolls should go in, e.g. Pictures. Android doesn't let apps save into the Download folder itself. You'll see everything that's in it, and can rename or leave out folders, before anything is written. | Delete the zip after unzipping | Et Tu, Tiff? | Settings

---

# Emulator walk, API 26, page size , density 2.62


## cold_start
- cold start 683 ms
- screenshot `01-cold_start-start.png`
- finished in 6 s

## settings
- screenshot `02-settings-settings-top.png`
- screenshot `03-settings-settings-bottom.png`
- finished in 33 s

## convert_keep
- screenshot `04-convert_keep-picker.png`
- screenshot `05-convert_keep-picker-roots.png`
- screenshot `06-convert_keep-stuck.png`

## unzip_and_replace
- screenshot `07-unzip_and_replace-zip-picker.png`
- screenshot `08-unzip_and_replace-picker-roots.png`
- screenshot `09-unzip_and_replace-stuck.png`

## back_and_leave
- back on the start screen leaves to: mResumedActivity: ActivityRecord{5c6ed50 u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity t5}
- finished in 7 s

## rotation
- screenshot `10-rotation-landscape-start.png`
- screenshot `11-rotation-landscape-settings.png`
- finished in 16 s

## big_text
- screenshot `12-big_text-big-text-start.png`
- screenshot `13-big_text-big-text-settings.png`
- finished in 21 s

## dark
- screenshot `14-dark-dark-start.png`
- finished in 3 s

## process_death
- screenshot `15-process_death-picker-roots.png`
- screenshot `16-process_death-stuck.png`

## monkey
- monkey: :Dropped: keys=0 pointers=15 trackballs=0 flips=0 rotations=0 / ## Network stats: elapsed time=76600ms (0ms mobile, 0ms wifi, 76600ms not connected) / // Monkey finished
- screenshot `17-monkey-after-monkey.png`
- finished in 80 s
