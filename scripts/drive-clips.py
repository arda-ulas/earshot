#!/usr/bin/env python3
"""Drives the debug build's clip player over adb + uiautomator and prints each turn from the app's trace.

  scripts/drive-clips.py CLIP [CLIP>>ANSWER ...]

  CLIP            play one clip (file name in the app's clips folder), through the debug build's
                  launch-intent hook, so it also works while the platform restricts the UI
  CLIP>>ANSWER    play a clip that asks for confirmation, then the answer clip
Works on the phone emulator (user 0) and the Android Automotive emulator (secondary user, detected).
Results are synthetic-clip input ("clip"), never live microphone.
"""
import json, os, re, subprocess, sys, time

ADB = os.environ.get("ADB", os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))
PKG = "io.github.ardaulas.earshot"


def sh(*a):
    return subprocess.run([ADB, *a], capture_output=True, text=True).stdout


USER = sh("shell", "am", "get-current-user").strip() or "0"
CLIPDIR = f"/sdcard/Android/data/{PKG}/files/clips" if USER == "0" else f"/data/media/{USER}/Android/data/{PKG}/files/clips"


def dump():
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return sh("shell", "cat", "/sdcard/ui.xml")


NODE = re.compile(r'<node [^>]*?text="([^"]*)"[^>]*?enabled="(true|false)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')


def nodes(xml):
    for m in NODE.finditer(xml):
        t, en, x1, y1, x2, y2 = m.groups()
        yield t, en == "true", (int(x1) + int(x2)) // 2, (int(y1) + int(y2)) // 2


def find(label, xml):
    return next(((x, y, en) for t, en, x, y in nodes(xml) if t == label), None)


def tap(x, y):
    sh("shell", "input", "tap", str(x), str(y))


def swipe_up():
    sh("shell", "input", "swipe", "500", "600", "500", "150", "150")
    time.sleep(0.3)


def last_trace():
    out = sh("shell", f"run-as {PKG} --user {USER} sh -c 'cat files/traces/*.jsonl 2>/dev/null' | tail -1")
    return json.loads(out) if out.strip() else None


def clips():
    return sorted(x for x in sh("shell", "ls", CLIPDIR).split() if x.endswith(".wav"))


def controls():
    sh("shell", "am", "start", "--user", USER, "-n", f"{PKG}/{PKG}.app.MainActivity")
    time.sleep(0.8)
    for _ in range(6):
        xml = dump()
        if find("Play clip", xml) and find("▶", xml):
            return xml
        swipe_up()
    raise SystemExit("clip controls not found (debug build with clips pushed?)")


NUMBERED = re.compile(r"^clip (\d+) of (\d+)$")


def current(xml, names):
    """Index of the selected clip: its name, or "clip N of M" under the platform's UX restrictions."""
    for t, en, x, y in nodes(xml):
        if t.endswith(".wav"):
            return names.index(t)
        m = NUMBERED.match(t)
        if m:
            return int(m.group(1)) - 1
    raise SystemExit("no clip selector on screen")


def shows(clip, xml):
    names = clips()
    return find(clip, xml) is not None or find(f"clip {names.index(clip) + 1} of {len(names)}", xml) is not None


def select(clip, xml):
    names = clips()
    cur = current(xml, names)
    nxt = find("▶", xml)
    for _ in range((names.index(clip) - cur) % len(names)):
        tap(nxt[0], nxt[1])
        time.sleep(0.12)


def wait_enabled(label, timeout=60):
    t0 = time.time()
    while time.time() - t0 < timeout:
        xml = dump()
        p = find(label, xml)
        if p and p[2]:
            return xml, p
        time.sleep(0.3)
    raise SystemExit(f"{label} not enabled")


def wait_new(before, timeout=90):
    t0 = time.time()
    while time.time() - t0 < timeout:
        time.sleep(0.5)
        t = last_trace()
        if t and (not before or t["turnId"] != before["turnId"]):
            return t
    return None


def report(clip, t):
    stages = " ".join(f"{s['stage']}={s['durationMs']:.0f}" for s in t["stages"])
    print(f"{clip:32s} heard={t['transcript']!r} conf={t['asrConfidence']} cmd={t['command']} src={t['commandSource']} "
          f"lm={t.get('lmOutcome')} verdict={t['verdict']} -> {t['outcome']} | said={t['spoken']!r} | {t['drivingState']} | "
          f"{stages} | input={t['inputSource']} host={t['host']['device']}", flush=True)


def idle(timeout=60):
    """Waits until the push-to-talk button says "Hold to talk", i.e. the app takes a new turn."""
    t0 = time.time()
    while time.time() - t0 < timeout:
        if find("Hold to talk", dump()):
            return
        time.sleep(0.2)
    raise SystemExit("app not idle")


def request(clip):
    """Debug builds only: asks the running activity to play a clip (no on-screen controls needed)."""
    sh("shell", "am", "start", "--user", USER, "-f", "0x20000000", "-n", f"{PKG}/{PKG}.app.MainActivity",
       "--es", "earshot.debug.clip", clip)


def play(clip, answer=None):
    names = clips()
    for c in (clip, answer):
        if c and c not in names:
            raise SystemExit(f"no clip {c}")
    idle()
    before = last_trace()
    request(clip)
    t = wait_new(before)
    report(clip, t)
    asked = time.time()
    if answer:
        # The answer window is 10 s from the end of the spoken question.
        idle()
        before = last_trace()
        request(answer)
        print(f"  (answer requested {time.time() - asked:.1f} s after the question's trace)", flush=True)
        report(answer, wait_new(before))
    time.sleep(1)


if __name__ == "__main__":
    for step in sys.argv[1:]:
        clip, _, answer = step.partition(">>")
        play(clip, answer or None)
