#!/usr/bin/env python3
"""Update the app version name and regenerate the bundled guide.

Usage: python3 tooling/set_version.py 1.2.0-dev.1
"""

import argparse
import re
import subprocess
import sys
from pathlib import Path

VERSION_RE = re.compile(r"(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)(?:-dev\.[1-9]\d*|-rc\.[1-9]\d*)?", re.ASCII)
PUBSPEC_RE = re.compile(r"^version:[ \t]*(\S+)[ \t]*$", re.M)
GUIDE_RE = re.compile(r"^> dsh-go[ \t]+\S+[ \t]*$", re.M)


def update(root: Path, version: str) -> None:
    """Set the version name in app/pubspec.yaml and docs/guide/README.md, then run guide_sync."""
    if VERSION_RE.fullmatch(version) is None:
        raise ValueError(f"invalid version name: {version}")

    pubspec_path = root / "app/pubspec.yaml"
    guide_path = root / "docs/guide/README.md"

    pubspec = pubspec_path.read_text(encoding="utf-8")
    guide = guide_path.read_text(encoding="utf-8")

    pubspec_matches = list(PUBSPEC_RE.finditer(pubspec))
    guide_matches = list(GUIDE_RE.finditer(guide))
    if len(pubspec_matches) != 1:
        raise ValueError(f"{pubspec_path}: expected one version line, found {len(pubspec_matches)}")
    if len(guide_matches) != 1:
        raise ValueError(f"{guide_path}: expected one `> dsh-go` line, found {len(guide_matches)}")

    old_token = pubspec_matches[0].group(1)
    build_suffix = "+" + old_token.split("+", 1)[1] if "+" in old_token else ""
    pubspec_new = PUBSPEC_RE.sub(f"version: {version}{build_suffix}", pubspec, count=1)
    guide_new = GUIDE_RE.sub(f"> dsh-go {version}", guide, count=1)
    pubspec_path.write_text(pubspec_new, encoding="utf-8")
    guide_path.write_text(guide_new, encoding="utf-8")

    guide_sync = root / "app/tool/guide_sync.py"
    result = subprocess.run(
        [sys.executable, str(guide_sync), "--repo", str(root)],
        cwd=root,
    )
    if result.returncode != 0:
        raise SystemExit(result.returncode)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("version", help="version name, e.g. 1.2.0-dev.1")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    try:
        update(root, args.version)
    except (OSError, ValueError) as exc:
        raise SystemExit(f"error: {exc}") from exc


if __name__ == "__main__":
    main()
