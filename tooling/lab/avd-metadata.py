#!/usr/bin/env python3
"""Read-only process/disk metadata collector. JSON to stdout only.

Usage:
  avd-metadata.py --pid PID [PID ...]
  avd-metadata.py --image PATH [PATH ...]

Only explicit PIDs and image paths are read; never scans all processes.
No subprocess, no shell, no file output.
"""
import argparse
import json
import os
import sys

def fd_entry(pid, fd_num):
    fd_path = f"/proc/{pid}/fd/{fd_num}"
    try:
        target = os.readlink(fd_path)
    except OSError:
        return None
    try:
        st = os.stat(fd_path)
        dev, ino = st.st_dev, st.st_ino
    except OSError:
        dev = ino = None
    flags = ""
    try:
        with open(f"/proc/{pid}/fdinfo/{fd_num}") as fh:
            for line in fh:
                if line.startswith("flags:"):
                    flags = line.split(":", 1)[1].strip()
                    break
    except OSError:
        pass
    return {"pid": pid, "fd": fd_num, "target": target, "flags": flags,
            "dev": dev, "inode": ino}

def collect_pids(pids):
    out = []
    for pid in pids:
        fd_dir = f"/proc/{pid}/fd"
        try:
            nums = sorted(int(n) for n in os.listdir(fd_dir))
        except OSError as exc:
            out.append({"pid": pid, "error": str(exc)})
            continue
        for n in nums:
            entry = fd_entry(pid, n)
            if entry:
                out.append(entry)
    return out

def collect_images(paths):
    out = []
    for p in paths:
        try:
            st = os.stat(p)
            with open(p, "rb") as fh:
                head = fh.read(8)
        except OSError as exc:
            out.append({"path": p, "error": str(exc)})
            continue
        out.append({"path": os.path.realpath(p), "size": st.st_size,
                    "mtime": st.st_mtime, "dev": st.st_dev, "inode": st.st_ino,
                    "head_hex": head.hex()})
    return out

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pid", type=int, action="append", default=[])
    ap.add_argument("--image", action="append", default=[])
    args = ap.parse_args()
    if not args.pid and not args.image:
        ap.error("at least one of --pid or --image is required")
    result = {"pids": collect_pids(args.pid), "images": collect_images(args.image)}
    sys.stdout.write(json.dumps(result, indent=2) + "\n")

if __name__ == "__main__":
    main()