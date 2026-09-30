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

# Start the app once so Android creates its external files directory with the right owner.
"$adb" shell am start -W -n "$activity" >/dev/null
dest="/sdcard/Android/data/$pkg/files"

python3 - "$root/models/manifest.json" <<'PY' | while read -r file; do
import json, sys
for m in json.load(open(sys.argv[1]))["models"]:
    print(m["file"])
PY
  if [[ -f "$root/models/$file" ]]; then
    "$adb" shell mkdir -p "$dest/models" </dev/null
    "$adb" push "$root/models/$file" "$dest/models/$file" </dev/null
  fi
done

if [[ "${1:-}" == "--clips" ]]; then
  [[ -d "$root/clips" ]] || { echo "no clips/; run scripts/make-clips.sh" >&2; exit 1; }
  "$adb" shell mkdir -p "$dest/clips"
  "$adb" push "$root"/clips/*.wav "$dest/clips/"
  [[ -f "$root/clips/captions.tsv" ]] && "$adb" push "$root/clips/captions.tsv" "$dest/clips/"
fi

"$adb" shell am force-stop "$pkg"
"$adb" shell am start -n "$activity" >/dev/null
echo "pushed; the app restarted and re-verified the models"
