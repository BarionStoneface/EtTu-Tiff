# Findings, API 30

- **cold_start**: [start screen] controls with no label for screen readers: android.view.View at (890, 1690, 1027, 1816)
- **settings**: [settings] controls with no label for screen readers: android.view.View at (901, 1411, 1038, 1537); android.view.View at (901, 1674, 1038, 1800)
- **convert_keep**: scenario stopped: picker: folder Scans not found. Showing: sdk_gphone_x86_64 | FILES ON SDK_GPHONE_X86_64 | List view | New folder | Sort by... | More options | Can’t use this folder | To protect your privacy, choose another folder | CREATE NEW FOLDER | Alarms | Android | Audiobooks | DCIM | Documents | Download | Movies | Music | Notifications | Pictures | Podcasts | Ringtones | USE THIS FOLDER (disabled)
- **unzip_and_replace**: scenario stopped: couldn't find '^lab\.zip$' on screen. Showing: sdk_gphone_x86_64 | Large files | This week | FILES ON SDK_GPHONE_X86_64 | List view | Show roots | Search | More options | Alarms | Android | Audiobooks | DCIM | Documents | Download | Movies | Music | Notifications | Pictures | Podcasts | Ringtones
- **rotation**: [start screen, landscape] touch targets under 48 dp: android.view.View (52×44 dp)
- **rotation**: [start screen, landscape] controls with no label for screen readers: android.view.View at (2084, 964, 2221, 1080)
- **big_text**: [start screen, largest text] touch targets under 48 dp: android.view.View (319×44 dp); android.view.View (52×44 dp)
- **big_text**: [start screen, largest text] controls with no label for screen readers: android.view.View at (53, 2158, 890, 2274); android.view.View at (890, 2158, 1027, 2274)
- **big_text**: with the largest text size, 'Unzip a download' falls off the bottom of the start screen and can't be scrolled to
- **process_death**: scenario stopped: picker: folder Scans not found. Showing: sdk_gphone_x86_64 | FILES ON SDK_GPHONE_X86_64 | List view | New folder | Sort by... | More options | Can’t use this folder | To protect your privacy, choose another folder | CREATE NEW FOLDER | Alarms | Android | Audiobooks | DCIM | Documents | Download | Movies | Music | Notifications | Pictures | Podcasts | Ringtones | USE THIS FOLDER (disabled)

---

# Emulator walk, API 30, page size 4096, density 2.62


## cold_start
- cold start 617 ms
- screenshot `01-cold_start-start.png`
- finished in 7 s

## settings
- screenshot `02-settings-settings-top.png`
- screenshot `03-settings-settings-bottom.png`
- finished in 40 s

## convert_keep
- screenshot `04-convert_keep-picker.png`
- screenshot `05-convert_keep-picker-Pictures.png`
- screenshot `06-convert_keep-stuck.png`

## unzip_and_replace
- screenshot `07-unzip_and_replace-zip-picker.png`
- screenshot `08-unzip_and_replace-picker-roots.png`
- screenshot `09-unzip_and_replace-picker-Download.png`
- screenshot `10-unzip_and_replace-stuck.png`

## back_and_leave
- back on the start screen leaves to: mResumedActivity: ActivityRecord{d497d03 u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity t8}
- finished in 8 s

## rotation
- screenshot `11-rotation-landscape-start.png`
- screenshot `12-rotation-landscape-settings.png`
- finished in 18 s

## big_text
- screenshot `13-big_text-big-text-start.png`
- screenshot `14-big_text-big-text-settings.png`
- finished in 26 s

## dark
- screenshot `15-dark-dark-start.png`
- finished in 4 s

## process_death
- screenshot `16-process_death-picker-Pictures.png`
- screenshot `17-process_death-stuck.png`

## monkey
- monkey: :Sending rotation degree=0, persist=false / :Dropped: keys=0 pointers=1 trackballs=0 flips=2 rotations=0 / ## Network stats: elapsed time=24155ms (0ms mobile, 0ms wifi, 24155ms not connected)
- screenshot `18-monkey-after-monkey.png`
- finished in 28 s
