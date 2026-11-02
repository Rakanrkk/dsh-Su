#!/usr/bin/env bash

#   tooling/build-root-apk.sh [build-apk options]

set -euo pipefail

pre=()
post=()
in_post=0
for arg in "$@"; do
  if ((in_post)); then
    post+=("$arg")
  elif [[ $arg == -- ]]; then
    in_post=1
  else
    pre+=("$arg")
  fi
done

for arg in "${pre[@]}"; do
  if [[ $arg == --beta ]]; then
    echo "build-root-apk: dsh-Su builds use this fork's release feed; do not pass --beta" >&2
    exit 64
  fi
done

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
exec "$ROOT/tooling/build-apk.sh" \
  "${pre[@]}" \
  -- \
  --android-project-arg=dsh.rootRuntime=true \
  "--dart-define=DSH_UPDATE_REPO=Rakanrkk/dsh-Su" \
  "--dart-define=DSH_UPDATE_MIRROR=" \
  "--dart-define=DSH_UPDATE_FEED=" \
  "--dart-define=DSH_UPDATE_MIRRORS=" \
  "${post[@]}"
