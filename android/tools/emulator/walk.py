"""Drives the installed app on an emulator through every flow a person (or Play's test robot) would,
and writes what it saw to walk-out/: a screenshot per step, a report, and the log.

Each scenario runs on its own: one that gets stuck is written up and the next one carries on.
Only the standard library is used, so it runs on any runner with adb on the path.
"""
import os
import re
import subprocess
import sys
import time
import traceback
import xml.etree.ElementTree as ET

PKG = "com.barion.filmscans"
OUT = sys.argv[1] if len(sys.argv) > 1 else "walk-out"
os.makedirs(OUT, exist_ok=True)
report = []          # markdown lines
findings = []        # (scenario, what)
shots = 0
scenario = "setup"


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {scenario}: {msg}", flush=True)


def note(what):
    """Something a reviewer should look at."""
    findings.append((scenario, what))
    log("FINDING " + what)


def adb(*args, check=False, timeout=120):
    r = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {r.stderr.strip()}")
    return r.stdout


def sh(cmd, timeout=120):
    return adb("shell", cmd, timeout=timeout)


API = int(sh("getprop ro.build.version.sdk").strip())
DENSITY = int(re.search(r"(\d+)\s*$", sh("wm density").strip().splitlines()[0]).group(1)) / 160
PAGE = sh("getconf PAGE_SIZE").strip()


# ---------------------------------------------------------------- screen reading

class Node:
    def __init__(self, e):
        a = e.attrib
        self.text = a.get("text", "")
        self.desc = a.get("content-desc", "")
        self.pkg = a.get("package", "")
        self.cls = a.get("class", "")
        self.clickable = a.get("clickable") == "true"
        self.scrollable = a.get("scrollable") == "true"
        self.enabled = a.get("enabled") == "true"
        self.checked = a.get("checked") == "true"
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", a.get("bounds", "[0,0][0,0]")))
        self.box = (x1, y1, x2, y2)
        self.center = ((x1 + x2) // 2, (y1 + y2) // 2)
        self.el = e

    @property
    def label(self):
        return self.text or self.desc

    def subtree_label(self):
        return " ".join(filter(None, (n.attrib.get("text", "") or n.attrib.get("content-desc", "") for n in self.el.iter())))


def dump():
    for _ in range(4):
        out = adb("exec-out", "uiautomator dump /dev/tty", timeout=60)
        m = re.search(r"<\?xml.*</hierarchy>", out, re.S)
        if m:
            try:
                root = ET.fromstring(m.group(0))
                return [Node(e) for e in root.iter("node")]
            except ET.ParseError:
                pass
        time.sleep(1)
    return []


def find(pattern, timeout=15, pkg=None, clickable=None):
    rx = re.compile(pattern, re.I)
    end = time.time() + timeout
    while True:
        for n in dump():
            if (rx.search(n.text) or rx.search(n.desc)) and (pkg is None or n.pkg == pkg) \
                    and (clickable is None or n.clickable == clickable):
                return n
        if time.time() > end:
            return None
        time.sleep(1)


def tap(n):
    adb("shell", "input", "tap", str(n.center[0]), str(n.center[1]))
    time.sleep(1.2)


def describe():
    """What's on screen, for the report when something can't be found."""
    seen = []
    for n in dump():
        lab = n.label.strip()
        if lab and lab not in seen:
            seen.append(lab + ("" if n.enabled else " (disabled)"))
    return " | ".join(seen)[:1500]


def tap_text(pattern, timeout=15, **kw):
    n = find(pattern, timeout, **kw)
    if n is None:
        raise Stuck(f"couldn't find '{pattern}' on screen. Showing: {describe()}")
    tap(n)
    return n


def screen_size():
    m = re.search(r"(\d+)x(\d+)", sh("wm size"))
    return int(m.group(1)), int(m.group(2))


def swipe_up(fraction=0.5):
    w, h = screen_size()
    adb("shell", "input", "swipe", str(w // 2), str(int(h * 0.75)), str(w // 2), str(int(h * (0.75 - fraction))), "400")
    time.sleep(1)


def scroll_to(pattern, tries=12, **kw):
    for _ in range(tries):
        n = find(pattern, timeout=1, **kw)
        if n:
            return n
        swipe_up()
    return None


def key(k):
    adb("shell", "input", "keyevent", k)
    time.sleep(1)


def type_text(s):
    adb("shell", "input", "text", s.replace(" ", "%s"))
    time.sleep(0.5)


def shot(name):
    global shots
    shots += 1
    fn = f"{shots:02}-{scenario}-{name}.png".replace(" ", "_")
    with open(os.path.join(OUT, fn), "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout)
    report.append(f"- screenshot `{fn}`")
    return fn


def foreground():
    out = sh("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity|ResumedActivity' | head -1")
    return out.strip()


def audit(name):
    """Accessibility checks like the pre-launch report's: touch targets and unlabelled controls."""
    nodes = [n for n in dump() if n.pkg == PKG]
    small, unlabelled = [], []
    for n in nodes:
        if not n.clickable or not n.enabled:
            continue
        x1, y1, x2, y2 = n.box
        wdp, hdp = (x2 - x1) / DENSITY, (y2 - y1) / DENSITY
        lab = n.subtree_label()
        if (wdp < 47.5 or hdp < 47.5) and wdp > 0:
            small.append(f"{lab[:40] or n.cls} ({wdp:.0f}×{hdp:.0f} dp)")
        if not lab.strip():
            unlabelled.append(f"{n.cls} at {n.box}")
    if small:
        note(f"[{name}] touch targets under 48 dp: " + "; ".join(sorted(set(small))[:12]))
    if unlabelled:
        note(f"[{name}] controls with no label for screen readers: " + "; ".join(unlabelled[:8]))


class Stuck(Exception):
    pass


# ---------------------------------------------------------------- crash watching

def crashes_since_clear():
    out = adb("logcat", "-d", "-b", "crash", timeout=60)
    main = adb("logcat", "-d", "-s", "AndroidRuntime:E", "ActivityManager:E", timeout=60)
    found = []
    # Any app's crash counts: the phone's own apps can crash on what this app hands them.
    if "FATAL EXCEPTION" in out or PKG in out:
        found.append(out[-6000:])
    for m in re.finditer(r"ANR in " + re.escape(PKG) + r".*", main):
        found.append(m.group(0))
    return found


def check_crashes():
    for c in crashes_since_clear():
        note("CRASH or ANR:\n```\n" + c.strip()[:5000] + "\n```")
    adb("logcat", "-b", "crash", "-c")


def launch(cold=True):
    if cold:
        sh(f"am force-stop {PKG}")
    out = sh(f"am start -W -n {PKG}/.MainActivity")
    m = re.search(r"TotalTime: (\d+)", out)
    time.sleep(2)
    return int(m.group(1)) if m else None


# ---------------------------------------------------------------- the system file picker

def allow_permission_dialogs():
    """Notification permission (Android 13+) appears the first time a long job starts."""
    n = find(r"^(Allow|ALLOW)$", timeout=2, pkg=None)
    if n and n.pkg != PKG and "permissioncontroller" in n.pkg:
        report.append(f"- notification permission dialog shown (API {API})")
        tap(n)
        return True
    return False


def picker_go_to_storage():
    """Gets the picker to the top of internal storage, whatever version of the picker it is."""
    device = r"^(sdk_gphone|Android SDK built|Internal storage|Phone|SDK)"
    n = find(device, timeout=4)
    if n and n.pkg != PKG:
        tap(n)
        return
    roots = find(r"^Show roots$", timeout=4)
    if roots:
        tap(roots)
        shot("picker-roots")
        n = find(device, timeout=4)
        if n is None:
            # Android 8 and 9 hide internal storage unless it's switched on in the picker's menu.
            w, h = screen_size()
            adb("shell", "input", "tap", str(int(w * 0.92)), str(h // 2))
            time.sleep(1)
            more = find(r"^More options$", timeout=4)
            if more:
                tap(more)
                show = find(r"Show internal storage", timeout=4)
                if show:
                    note("The folder picker hides internal storage until 'Show internal storage' is chosen from its "
                         "menu, so a new user sees no way to reach their scans (Android 8/9). The app can pass "
                         "EXTRA_SHOW_ADVANCED to show it.")
                    tap(show)
                else:
                    raise Stuck(f"no 'Show internal storage' in the picker menu. Showing: {describe()}")
            tap_text(r"^Show roots$", timeout=4)
            n = find(device, timeout=4)
        if n is None:
            raise Stuck(f"couldn't find internal storage in the picker. Showing: {describe()}")
        tap(n)
        return
    raise Stuck(f"picker has no roots button and no storage entry. Showing: {describe()}")


def picker_open_path(*folders):
    for f in folders:
        n = scroll_to("^" + re.escape(f) + "$")
        if n is None:
            raise Stuck(f"picker: folder {f} not found. Showing: {describe()}")
        for _ in range(3):
            tap(n)
            time.sleep(1.5)
            if find("^" + re.escape(f) + "$", timeout=2) is None or find(r"Can.t use this folder|^FILES ON|^Files on", timeout=1) is None:
                break
            n = find("^" + re.escape(f) + "$", timeout=2) or n
        else:
            # Android 11's picker sometimes ignores taps in its grid: switch to the list and try again.
            lv = find(r"^List view$", timeout=2)
            if lv:
                tap(lv)
                n = find("^" + re.escape(f) + "$", timeout=3)
                if n:
                    tap(n)
                    time.sleep(1.5)
        shot("picker-" + f)


def picker_use_folder():
    tap_text(r"^(USE THIS FOLDER|Use this folder|SELECT|Select)$", timeout=10)
    n = find(r"^(ALLOW|Allow)$", timeout=6)
    if n:
        tap(n)


# ---------------------------------------------------------------- scenarios

def wait_for(pattern, minutes, step="", on_wait=None):
    end = time.time() + minutes * 60
    while time.time() < end:
        allow_permission_dialogs()
        n = find(pattern, timeout=1)
        if n:
            return n
        if on_wait:
            on_wait()
        time.sleep(4)
    raise Stuck(f"waited {minutes} min for '{pattern}' {step}")


def s_cold_start():
    t = launch()
    report.append(f"- cold start {t} ms")
    if t and t > 3000:
        note(f"cold start took {t} ms (Android vitals flags over 5 s; aim under 1 s)")
    shot("start")
    audit("start screen")
    # Can every control on the first screen be reached?
    if not scroll_to(r"^Unzip a zip$", tries=4):
        note("'Unzip a zip' can't be reached on the start screen")


def s_settings():
    launch()
    tap_text(r"^Settings$")
    shot("settings-top")
    audit("settings")
    n = find(r"Your name", timeout=5)
    if n:
        tap(n)
        type_text("Pat Example")
        key("KEYCODE_BACK")  # hide the keyboard
    for i in range(5):
        swipe_up(0.6)
    shot("settings-bottom")
    tap_text(r"^Done$")
    launch()
    tap_text(r"^Settings$")
    if not find(r"Pat Example", timeout=4):
        note("name typed in Settings wasn't kept after the app was restarted")
    tap_text(r"^Done$")


def s_convert_keep():
    launch()
    tap_text(r"^Choose scans folder$")
    time.sleep(2)
    shot("picker")
    picker_go_to_storage()
    picker_open_path("Pictures", "Scans")
    shot("picker-scans")
    picker_use_folder()
    if not wait_for(r"roll\(s\) in Scans|What happens to the", 3, "after picking the folder"):
        return
    shot("rolls")
    audit("rolls")
    # Roll B has no dates: use the files' dates.
    n = scroll_to(r"^Use their file dates")
    if n is None:
        note("Roll B has no scan dates, but the 'use their file dates' choice didn't show")
    else:
        tap(n)
        shot("rolls-dates")
    btn = find(r"keep the originals$", timeout=5)
    if btn is None:
        raise Stuck("convert button not found")
    if not btn.enabled:
        shot("blocked")
        problems = [x.text for x in dump() if x.pkg == PKG and x.text and "issue" not in x.text.lower()]
        raise Stuck("convert button disabled: " + " | ".join(problems)[:600])
    tap(btn)
    shot("confirm")
    tap_text(r"^Convert$")
    allow_permission_dialogs()
    time.sleep(3)
    shot("converting")
    # Go to another app while it works, like a person would.
    key("KEYCODE_HOME")
    time.sleep(3)
    notif = sh("dumpsys notification --noredact | grep -A3 'pkg=" + PKG + "' | head -20")
    if PKG not in notif:
        note("no progress notification while converting in the background")
    else:
        report.append("- progress notification shown while in the background")
    sh("cmd statusbar expand-notifications")
    time.sleep(2)
    shot("notification")
    sh("cmd statusbar collapse")
    peak = [0]

    def mem():
        m = re.search(r"TOTAL PSS:\s+(\d+)|TOTAL\s+(\d+)", sh(f"dumpsys meminfo {PKG}"))
        if m:
            peak[0] = max(peak[0], int(m.group(1) or m.group(2)))
    launch(cold=False)
    wait_for(r"^Back to home$", 15, "for the conversion to finish", on_wait=mem)
    report.append(f"- peak memory while converting about {peak[0] // 1024} MB")
    shot("done")
    audit("done")
    n = find(r"didn't convert|✗", timeout=2)
    if n:
        note("some files didn't convert: " + n.text)
    listing = sh("ls -l '/sdcard/Pictures/Scans/Roll A' '/sdcard/Pictures/Scans/Roll B'")
    report.append("```\n" + listing.strip() + "\n```")
    jpgs = re.findall(r"\S+\.jpg", listing)
    if len(jpgs) < 5:
        note(f"expected 5 JPEGs after converting, found {len(jpgs)}")
    gallery = sh("content query --uri content://media/external/images/media --projection _display_name")
    if "A001" not in gallery:
        note("the new JPEGs aren't in the phone's media index, so the gallery won't show them")
    adb("pull", "/sdcard/Pictures/Scans/Roll A/A001.jpg", os.path.join(OUT, "A001.jpg"))
    adb("pull", "/sdcard/Pictures/Scans/Roll B/B002.jpg", os.path.join(OUT, "B002.jpg"))
    tap_text(r"^View photos$", timeout=5)
    time.sleep(3)
    report.append(f"- 'View photos' opened: {foreground()}")
    shot("view-photos")
    key("KEYCODE_BACK")
    launch(cold=False)
    n = find(r"^Open folder$", timeout=5)
    if n:
        tap(n)
        time.sleep(3)
        report.append(f"- 'Open folder' opened: {foreground()}")
        shot("open-folder")
        key("KEYCODE_BACK")


def pick_zip(name):
    """From the start screen to the unzip preview of Download/[name], unzipping into Pictures."""
    launch()
    if find(r"^Back to home$", timeout=2):
        tap_text(r"^Back to home$")
    tap_text(r"^Unzip a zip$")
    time.sleep(2)
    shot("zip-picker")
    picker_go_to_storage()
    picker_open_path("Download")
    tap_text("^" + re.escape(name) + "$")
    # Some pickers need a confirm for multiple selection.
    n = find(r"^(SELECT|Select|OPEN|Open)$", timeout=3)
    if n and n.pkg != PKG:
        tap(n)
    time.sleep(2)
    shot("dest-picker")
    if find(r"Nothing is written until you press Unzip", timeout=3) is None:
        if find(r"^(USE THIS FOLDER|Use this folder|SELECT|Select)$", timeout=4) is None:
            picker_go_to_storage()
            picker_open_path("Pictures")
        picker_use_folder()
    wait_for(r"Nothing is written until you press Unzip", 3, "for the unzip preview")


def unzip_button():
    for _ in range(3):
        btn = find(r"^Unzip (\d+ file|\()|^Checking the password|^Fix the issues", timeout=3)
        if btn:
            return btn
        key("KEYCODE_BACK")  # keyboard may be up
    raise Stuck(f"Unzip button not found on the preview. Showing: {describe()}")


def s_unzip_and_replace():
    pick_zip("lab.zip")
    shot("plan")
    audit("unzip preview")
    btn = None
    for _ in range(3):
        btn = find(r"^Unzip (\d+ file|\()", timeout=3)
        if btn:
            break
        key("KEYCODE_BACK")  # keyboard may be up
    if btn is None:
        raise Stuck("Unzip button not found on the preview")
    if not btn.enabled:
        shot("plan-blocked")
        raise Stuck("Unzip button disabled: " + btn.text)
    tap(btn)
    allow_permission_dialogs()
    wait_for(r"^Unzipped|What happens to the", 10, "for the unzip to finish")
    shot("unzipped")
    report.append("```\n" + sh("ls -lR /sdcard/Pictures/lab").strip()[:3000] + "\n```")
    # Folder holds lab JPEGs and TIFFs: work on both, replace the originals.
    n = find(r"^Both$", timeout=3)
    if n:
        tap(n)
    tap_text(r"^Replace them")
    shot("replace-mode")
    btn = find(r"^(Convert|Tag) .*(delete the TIFFs|in place)$", timeout=5)
    if btn is None or not btn.enabled:
        shot("replace-blocked")
        raise Stuck("replace button missing or disabled")
    tap(btn)
    time.sleep(1)
    shot("confirm-delete")
    tap_text(r"^(Convert and delete|Tag in place)$")
    wait_for(r"^Back to home$", 10, "for convert and delete to finish")
    shot("replaced")
    after = sh("ls -lR /sdcard/Pictures/lab")
    report.append("```\n" + after.strip()[:3000] + "\n```")
    if re.search(r"\.tif\b", after):
        note("TIFFs still there after 'Convert and delete'")
    tap_text(r"^Back to home$")


def s_unzip_any():
    """A zip that isn't photos, locked with a password: a wrong one first, then the right one."""
    pick_zip("docs.zip")
    shot("plan")
    audit("unzip preview, locked zip")
    if find(r"type its password", timeout=3) is None:
        note("a password-protected zip didn't ask for its password")
    field = find(r"^Password$", timeout=5)
    if field is None:
        raise Stuck(f"no password field. Showing: {describe()}")
    tap(field)
    type_text("wrongpass")
    key("KEYCODE_BACK")
    tap(unzip_button())
    if find(r"doesn.t open it", timeout=10) is None:
        note("a wrong password wasn't said on the preview")
    shot("wrong-password")
    tap(find(r"^Password$|^wrongpass$", timeout=5) or field)
    key("KEYCODE_MOVE_END")
    for _ in range(12):
        key("KEYCODE_DEL")
    type_text("filmsoup")
    key("KEYCODE_BACK")
    btn = unzip_button()
    if not btn.enabled:
        shot("blocked")
        raise Stuck("Unzip button disabled with the right password: " + btn.text)
    tap(btn)
    allow_permission_dialogs()
    wait_for(r"^Unzipped — ", 5, "for the Unzipped screen")
    shot("unzipped")
    audit("unzipped")
    listing = sh("ls -lR /sdcard/Pictures/docs")
    report.append("```\n" + listing.strip()[:2000] + "\n```")
    for f in ("report.pdf", "notes.txt", "table.csv", "cover.jpg"):
        if f not in listing:
            note(f"{f} missing after unzipping docs.zip")
    if "No TIFFs" in describe():
        note("unzipping a zip of other files ended on 'No TIFFs, JPEGs or zips'")
    n = find(r"^Open folder$", timeout=3)
    if n:
        tap(n)
        time.sleep(3)
        report.append(f"- 'Open folder' after unzipping opened: {foreground()}")
        shot("open-folder")
        key("KEYCODE_BACK")
    launch(cold=False)
    if find(r"^Back to home$", timeout=3):
        tap_text(r"^Back to home$")


def s_back_and_leave():
    launch()
    key("KEYCODE_BACK")
    time.sleep(1)
    report.append(f"- back on the start screen leaves to: {foreground()}")
    launch()


def s_rotation():
    launch()
    sh("settings put system accelerometer_rotation 0")
    sh("settings put system user_rotation 1")
    time.sleep(3)
    shot("landscape-start")
    audit("start screen, landscape")
    if not scroll_to(r"^Unzip a zip$", tries=4):
        note("in landscape, 'Unzip a zip' can't be reached: the start screen doesn't scroll")
    tap_text(r"^Settings$")
    shot("landscape-settings")
    tap_text(r"^Done$")
    sh("settings put system user_rotation 0")
    time.sleep(2)


def s_big_text():
    sh("settings put system font_scale 2.0")
    launch()
    shot("big-text-start")
    audit("start screen, largest text")
    if not scroll_to(r"^Unzip a zip$", tries=4):
        note("with the largest text size, 'Unzip a zip' falls off the bottom of the start screen and "
             "can't be scrolled to")
    tap_text(r"^Settings$")
    shot("big-text-settings")
    tap_text(r"^Done$")
    sh("settings put system font_scale 1.0")


def s_dark():
    sh("cmd uimode night yes")
    launch()
    shot("dark-start")
    sh("cmd uimode night no")


def s_process_death():
    launch()
    tap_text(r"^Choose scans folder$")
    picker_go_to_storage()
    picker_open_path("Pictures", "Scans")
    picker_use_folder()
    wait_for(r"roll\(s\) in Scans|What happens to the", 3)
    n = scroll_to(r"Box ISO")
    if n:
        tap(n)
        type_text("400")
        key("KEYCODE_BACK")
    key("KEYCODE_HOME")
    time.sleep(2)
    # What Android does to a backgrounded app when it needs the memory.
    sh(f"am kill {PKG}")
    time.sleep(2)
    sh(f"am start -n {PKG}/.MainActivity")
    time.sleep(3)
    shot("after-process-death")
    if find(r"^Choose scans folder$", timeout=3):
        note("after Android closes the app in the background (normal when memory is short), coming back "
             "lands on the start screen and the rolls and anything typed for them are gone")


def s_monkey():
    launch()
    out = sh(f"monkey -p {PKG} -s 1234 --throttle 120 --pct-syskeys 0 --pct-appswitch 0 "
             f"--ignore-security-exceptions -v 2500", timeout=900)
    tail = out.strip().splitlines()[-3:]
    report.append("- monkey: " + " / ".join(tail))
    if "CRASH" in out or "NOT RESPONDING" in out:
        note("random tapping crashed or froze the app:\n```\n" + out[-4000:] + "\n```")
    shot("after-monkey")


SCENARIOS = [s_cold_start, s_settings, s_convert_keep, s_unzip_and_replace, s_unzip_any, s_back_and_leave,
             s_rotation, s_big_text, s_dark, s_process_death, s_monkey]


def main():
    global scenario
    adb("logcat", "-b", "all", "-c")
    sh("settings put global window_animation_scale 0; settings put global transition_animation_scale 0; "
       "settings put global animator_duration_scale 0")
    report.append(f"# Emulator walk, API {API}, page size {PAGE}, density {DENSITY:.2f}\n")
    for s in SCENARIOS:
        scenario = s.__name__[2:]
        report.append(f"\n## {scenario}")
        t0 = time.time()
        try:
            s()
            report.append(f"- finished in {time.time() - t0:.0f} s")
        except Exception as e:
            shot("stuck")
            note(f"scenario stopped: {e}")
            traceback.print_exc()
            sh(f"am force-stop {PKG}; am force-stop com.android.documentsui; am force-stop com.google.android.documentsui")
            key("KEYCODE_HOME")
        check_crashes()
    head = [f"# Findings, API {API}\n"] + [f"- **{s}**: {w}" for s, w in findings] if findings else [f"# API {API}: no findings\n"]
    with open(os.path.join(OUT, "report.md"), "w") as f:
        f.write("\n".join(head + ["\n---\n"] + report) + "\n")
    print("\n".join(head))


if __name__ == "__main__":
    main()
