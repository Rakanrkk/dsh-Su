#!/usr/bin/env python3
"""Write UTF-8 lab reports safely from stdin.

PowerShell example; replace the sample paths with the WSL paths for your lab:
$Distro = 'Ubuntu-24.04'
$ScriptPath = '/absolute/path/to/write_task_file.py'
$LabRoot = '/absolute/path/to/lab'
$TargetPath = '/absolute/path/to/lab/tasks/report.md'
@'
# Markdown data may contain `backticks` and $(...), which remain literal input.
'@ | wsl.exe -d $Distro --exec python3 $ScriptPath --root $LabRoot $TargetPath

Linux:
cat report.md | python3 tooling/lab/write_task_file.py --root /abs/lab /abs/lab/tasks/report.md
"""

import argparse
import hashlib
import json
import os
import platform
import sys
import tempfile
from pathlib import Path


def main() -> int:
    if platform.system() != "Linux":
        print("write_task_file.py requires Linux", file=sys.stderr)
        return 2

    parser = argparse.ArgumentParser(description="Atomically write a UTF-8 task report from stdin.")
    parser.add_argument("--root", required=True, help="absolute lab root directory")
    parser.add_argument("target", help="absolute report path inside --root")
    args = parser.parse_args()

    root_input = Path(args.root)
    target_input = Path(args.target)
    if not root_input.is_absolute() or not target_input.is_absolute():
        parser.error("--root and target must be absolute paths")

    root = root_input.resolve()
    target = target_input.resolve()
    try:
        target.relative_to(root)
    except ValueError:
        parser.error("target must resolve inside --root")

    text = sys.stdin.buffer.read().decode("utf-8").replace("\r\n", "\n").replace("\r", "\n")
    data = text.encode("utf-8")
    target.parent.mkdir(parents=True, exist_ok=True)

    fd, temporary_name = tempfile.mkstemp(prefix=f".{target.name}.", suffix=".tmp", dir=target.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(fd, "wb") as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, target)
    finally:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass

    written = target.stat()
    receipt = {
        "path": os.fsencode(target).decode("utf-8", errors="surrogateescape"),
        "size": written.st_size,
        "sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
        "mtime_ns": written.st_mtime_ns,
    }
    print(json.dumps(receipt, ensure_ascii=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())