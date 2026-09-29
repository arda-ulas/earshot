#!/usr/bin/env bash
# Downloads the models listed in models/manifest.json into models/ and checks each one's size and
# SHA-256 against the pinned values. URLs pin a Hugging Face commit, so a file cannot change under the
# same URL. Models are never committed.
#
#   scripts/fetch-models.sh          required models only
#   scripts/fetch-models.sh --all    also the optional ones (e.g. whisper base.en)
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
manifest="$root/models/manifest.json"
include_optional="${1:-}"

python3 - "$manifest" "$include_optional" <<'PY' | while IFS=$'\t' read -r file url size sha; do
import json, sys
manifest, flag = sys.argv[1], sys.argv[2]
for m in json.load(open(manifest))["models"]:
    if m.get("required", True) or flag == "--all":
        print("\t".join([m["file"], m["url"], str(m["sizeBytes"]), m["sha256"]]))
PY
  dest="$root/models/$file"
  if [[ -f "$dest" ]] && [[ "$(shasum -a 256 "$dest" | cut -d' ' -f1)" == "$sha" ]]; then
    echo "ok       $file (already present, hash verified)"
    continue
  fi
  echo "fetching $file"
  curl --fail --location --proto '=https' --tlsv1.2 --progress-bar -o "$dest.part" "$url"
  actual_size="$(wc -c < "$dest.part" | tr -d ' ')"
  actual_sha="$(shasum -a 256 "$dest.part" | cut -d' ' -f1)"
  if [[ "$actual_size" != "$size" || "$actual_sha" != "$sha" ]]; then
    echo "FAILED   $file: expected $size bytes / $sha, got $actual_size bytes / $actual_sha" >&2
    rm -f "$dest.part"
    exit 1
  fi
  mv "$dest.part" "$dest"
  echo "ok       $file (hash verified)"
done
