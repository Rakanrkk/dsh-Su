#!/usr/bin/env bash

set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
APP="$ROOT/app"

root_sources=$(mktemp)
trap 'rm -f "$root_sources"' EXIT

(
  cd "$APP"
  source "$ROOT/tooling/env.sh"
  export DSH_TEST_FONT_DIR=/usr/share/fonts/opentype/noto
  flutter test test/update/root_update_config_sources_test.dart \
    --dart-define DSH_UPDATE_REPO=Rakanrkk/dsh-Su
) >"$root_sources"

grep -Fq 'every GitHub API, release and asset URL uses the fork' "$root_sources"
grep -Fq 'All tests passed!' "$root_sources"

wrapper=$("$ROOT/tooling/build-root-apk.sh" --dry-run -f arm64 -n 10261100 --build-name 1.0.0)
grep -Fq -- '--android-project-arg=dsh.rootRuntime=true' <<<"$wrapper"
grep -Fq -- '--dart-define=DSH_UPDATE_REPO=Rakanrkk/dsh-Su' <<<"$wrapper"
grep -Fq -- '--dart-define=DSH_UPDATE_MIRROR=' <<<"$wrapper"
grep -Fq -- '--dart-define=DSH_UPDATE_FEED=' <<<"$wrapper"
! grep -Fq -- 'DSH_BETA_CHANNEL_TOKEN' <<<"$wrapper"

echo 'root build identity checks passed'
