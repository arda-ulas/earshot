#!/usr/bin/env bash
# Copies the downloaded models (and, with --clips, the synthetic test clips) to the app's
# external files directory on a connected emulator or device, then restarts the app so it
# re-verifies them. Install the app first (./gradlew :app:installDebug).
#
#   scripts/push-models.sh            models only
#   scripts/push-models.sh --clips    models and clips/ (debug builds only use clips)
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
pkg="io.github.ardaulas.earshot"
activity="$pkg/io.github.ardaulas.earshot.app.MainActivity"

adb="${ADB:-}"
if [[ -z "$adb" ]]; then
  if command -v adb >/dev/null; then adb="adb"
  elif [[ -x "${ANDROID_HOME:-}/platform-tools/adb" ]]; then adb="$ANDROID_HOME/platform-tools/adb"
  elif [[ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]]; then adb="$HOME/Library/Android/sdk/platform-tools/adb"
  else echo "adb not found; set ADB or ANDROID_HOME" >&2; exit 1
  fi
fi

"$adb" shell pm path "$pkg" >/dev/null || { echo "$pkg is not installed; run ./gradlew :app:installDebug" >&2; exit 1; }

# Android Automotive runs apps as a secondary user (usually 10); the phone uses user 0.
user="$("$adb" shell am get-current-user </dev/null | tr -d '\r')"
user="${user:-0}"

# Start the app once so Android creates its external files directory with the right owner.
"$adb" shell am start --user "$user" -W -n "$activity" >/dev/null </dev/null

if [[ "$user" == "0" ]]; then
  dest="/sdcard/Android/data/$pkg/files"
  put() { "$adb" shell mkdir -p "$(dirname "$2")" </dev/null; "$adb" push "$1" "$2" </dev/null; }
else
  # A secondary user's app storage is not reachable as /sdcard from adb; this needs a debuggable
  # image that allows adb root (the Android Automotive emulator image does). Files are handed to the
  # app's uid so the app can read them.
  "$adb" root </dev/null >/dev/null && sleep 2
  dest="/data/media/$user/Android/data/$pkg/files"
  owner="$("$adb" shell stat -c %u "/data/media/$user/Android/data/$pkg" </dev/null | tr -d '\r')"
  put() {
    "$adb" push "$1" "/data/local/tmp/$(basename "$1")" </dev/null
    "$adb" shell "mkdir -p '$(dirname "$2")' && mv '/data/local/tmp/$(basename "$1")' '$2' && chown -R $owner:ext_data_rw '$(dirname "$2")' && chmod 660 '$2'" </dev/null
  }
fi

python3 - "$root/models/manifest.json" <<'PY' | while read -r file; do
import json, sys
for m in json.load(open(sys.argv[1]))["models"]:
    print(m["file"])
PY
  if [[ -f "$root/models/$file" ]]; then
    put "$root/models/$file" "$dest/models/$file"
  fi
done

if [[ "${1:-}" == "--clips" ]]; then
  [[ -d "$root/clips" ]] || { echo "no clips/; run scripts/make-clips.sh" >&2; exit 1; }
  for f in "$root"/clips/*.wav "$root"/clips/captions.tsv; do
    [[ -f "$f" ]] && put "$f" "$dest/clips/$(basename "$f")" >/dev/null
  done
  echo "clips pushed"
fi

"$adb" shell am force-stop --user "$user" "$pkg" </dev/null
"$adb" shell am start --user "$user" -n "$activity" >/dev/null </dev/null
echo "pushed; the app restarted and re-verified the models"
