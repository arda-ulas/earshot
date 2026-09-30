#!/usr/bin/env python3
"""Drives the debug build's clip player over adb + uiautomator and prints each turn from the app's trace.

  scripts/drive-clips.py CLIP [CLIP>>ANSWER ...]

  CLIP            play one clip (file name in the app's clips folder)
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


def select(clip, xml):
    names = clips()
    cur = next(t for t, en, x, y in nodes(xml) if t.endswith(".wav"))
    nxt = find("▶", xml)
    for _ in range((names.index(clip) - names.index(cur)) % len(names)):
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


def play(clip, answer=None):
    xml = controls()
    select(clip, xml)
    xml, btn = wait_enabled("Play clip")
    if not find(clip, xml):
        raise SystemExit(f"could not select {clip}")
    before = last_trace()
    tap(btn[0], btn[1])
    t = wait_new(before)
    report(clip, t)
    if answer:
        xml = dump()
        select(answer, xml)
        xml, btn = wait_enabled("Play clip")
        before = last_trace()
        tap(btn[0], btn[1])
        report(answer, wait_new(before))
    time.sleep(3)


if __name__ == "__main__":
    for step in sys.argv[1:]:
        clip, _, answer = step.partition(">>")
        play(clip, answer or None)
