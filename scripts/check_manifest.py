#!/usr/bin/env python3
"""Checks a merged Android manifest (SR-20, TH-2, TH-6).

  check_manifest.py MERGED_MANIFEST.xml [REPORT.txt]

Fails (exit 1) if:
- any permission is requested, in any declaration form (uses-permission, uses-permission-sdk-23,
  uses-permission-sdk-m), that is not on ALLOWED, INTERNET in particular;
- any activity, activity-alias, service, receiver or provider is exported, other than the launcher
  activity, or declares an intent filter without an explicit android:exported="false".
"""
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
PACKAGE = "io.github.ardaulas.earshot"
ALLOWED = {
    "android.permission.RECORD_AUDIO",
    "android.car.permission.CAR_SPEED",
    "android.car.permission.CAR_POWERTRAIN",
    "android.car.permission.CONTROL_CAR_CLIMATE",
    f"{PACKAGE}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
}
PERMISSION_TAGS = ("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m")
COMPONENT_TAGS = ("activity", "activity-alias", "service", "receiver", "provider")
LAUNCHER = "io.github.ardaulas.earshot.app.MainActivity"


def problems(path):
    root = ET.parse(path).getroot()
    out = []
    for tag in PERMISSION_TAGS:
        for e in root.iter(tag):
            name = e.get(ANDROID + "name", "")
            if name not in ALLOWED:
                out.append(f"requests {name} via <{tag}>")
    for tag in COMPONENT_TAGS:
        for e in root.iter(tag):
            name = e.get(ANDROID + "name", "")
            exported = e.get(ANDROID + "exported")
            has_filter = e.find("intent-filter") is not None
            if name == LAUNCHER:
                continue
            if exported == "true" or (has_filter and exported != "false"):
                out.append(f"exports {tag} {name}")
    return out


if __name__ == "__main__":
    found = problems(sys.argv[1])
    text = "OK" if not found else "\n".join(found)
    if len(sys.argv) > 2:
        with open(sys.argv[2], "w") as f:
            f.write(text + "\n")
    if found:
        print("Merged manifest check failed:\n" + text, file=sys.stderr)
        sys.exit(1)
    print("OK")
