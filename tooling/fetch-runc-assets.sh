#!/usr/bin/env bash
set -euo pipefail

SOURCE_COMMIT=bb14dabeb7185bb72c8c86735d090dcb20f36587
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
VERSION=1.4.3
CACHE=${DSH_RUNC_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/dsh-runc-$VERSION}
printf 'runc version=%s source_commit=%s\n' "$VERSION" "$SOURCE_COMMIT"

declare -A SHA=(
  [amd64]=f6ae8efc0fa40079e1475e97cbe9d1bd3f106a28d6af78a11d9f1bd565515e60
  [arm64]=633301e2e32f8a5ad54031aab4901eb00308bec677dd15faa2751e8f9dab5ca4
)
declare -A ABI_DIR=(
  [amd64]=x86_64
  [arm64]=arm64-v8a
)
declare -A CACHE_FILE=(
  [amd64]=runc.amd64
  [arm64]=runc.arm64
)

for arch in amd64 arm64; do
  abi=${ABI_DIR[$arch]}
  dest="$ROOT/runtime/out/$abi/assets/runtime/root-runtime/runc"
  mkdir -p "$(dirname "$dest")"

  if [[ -f $dest ]] && [[ $(sha256sum "$dest" | awk '{print $1}') == "${SHA[$arch]}" ]]; then
    echo "runc $arch already present: $dest"
    continue
  fi

  tmp=$(mktemp "${dest}.tmp.XXXXXX")
  trap 'rm -f "$tmp"' EXIT
  cache_file="$CACHE/${CACHE_FILE[$arch]}"
  if [[ -f $cache_file ]] && [[ $(sha256sum "$cache_file" | awk '{print $1}') == "${SHA[$arch]}" ]]; then
    cp "$cache_file" "$tmp"
  else
    url="https://github.com/opencontainers/runc/releases/download/v$VERSION/runc.$arch"
    curl -fsSL "$url" -o "$tmp"
  fi

  actual=$(sha256sum "$tmp" | awk '{print $1}')
  [[ $actual == "${SHA[$arch]}" ]] || { echo "checksum mismatch for runc $arch: $actual" >&2; exit 65; }
  chmod 0755 "$tmp"
  mv -f "$tmp" "$dest"
  trap - EXIT
  echo "installed runc $arch -> $dest"
done
