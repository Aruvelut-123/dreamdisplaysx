#!/usr/bin/env python3
"""Translate an upstream commit's diff into the fork's names and apply it.

Unlike ``port_upstream_commit.sh`` (which wholesale-replaces a file with the
upstream version and therefore drops every fork-only member), this keeps the
patch semantics: only the hunks the upstream commit actually touched are
changed, so fork-only code inside the same file survives.

Usage:
    scripts/port_diff.py <upstream-commit> [--apply] [--exclude p1,p2,...]

Without ``--apply`` it only writes the translated patch to /tmp and reports
how many paths/hunks it produced.
"""
from __future__ import annotations

import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Path prefixes the fork deliberately does not carry (yt-dlp / YouTube support
# was removed in 13c82b92). Porting them would resurrect dead code.
DEFAULT_EXCLUDE = (
    "yt-dlp",
    "YouTube",
    "MediaChapter",
    "DisplayChapters",
)

BASENAME_RENAMES = {
    "Unstable.kt": "DreamDisplaysXUnstableApi.kt",
}


def fork_path(path: str) -> str:
    """Mirror scripts/fork_path.sh."""
    path = path.replace("com/dreamdisplays/", "com/dreamdisplayx/")
    # Resource trees use the mod id as the assets/data namespace, not a package.
    path = path.replace("assets/dreamdisplays/", "assets/dreamdisplayx/")
    path = path.replace("data/dreamdisplays/", "data/dreamdisplayx/")
    path = path.replace("dreamdisplays.kotlin-conventions", "dreamdisplayx.kotlin-conventions")
    path = path.replace("dreamdisplays.serialization-conventions", "dreamdisplayx.serialization-conventions")
    path = path.replace("dreamdisplays.stonecutter-versions", "dreamdisplayx.stonecutter-versions")
    directory, _, base = path.rpartition("/")
    base = BASENAME_RENAMES.get(base, base)
    if base.startswith("DreamDisplays") and base.endswith(".kt") and not base.startswith("DreamDisplaysX"):
        base = "DreamDisplaysX" + base[len("DreamDisplays"):]
    return f"{directory}/{base}" if directory else base


KEEP = "\x00KEEP\x00"


def rename_idents(line: str) -> str:
    """Mirror scripts/rename_fork.sh."""
    line = line.replace("DreamDisplaysX", KEEP)
    line = line.replace("DreamDisplays", "DreamDisplaysX")
    line = line.replace(KEEP, "DreamDisplaysX")
    line = line.replace("dreamdisplays", "dreamdisplayx")
    line = re.sub(r"\bUnstable\b", "DreamDisplaysXUnstableApi", line)
    return line


def git(*args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=ROOT, check=True, capture_output=True, text=True
    ).stdout


def translate(commit: str, excludes: tuple[str, ...]) -> tuple[str, list[str]]:
    raw = git("show", "--format=", "--find-renames", "-U3", commit, "--")
    out: list[str] = []
    touched: list[str] = []
    skip_file = False

    for line in raw.splitlines():
        if line.startswith("diff --git "):
            m = re.match(r"diff --git a/(.*) b/(.*)$", line)
            if not m:
                skip_file = True
                out.append(line)
                continue
            old, new = m.group(1), m.group(2)
            skip_file = any(token in old or token in new for token in excludes)
            if skip_file:
                out.append(line)
                continue
            out.append(f"diff --git a/{fork_path(old)} b/{fork_path(new)}")
            touched.append(fork_path(new))
            continue

        if skip_file:
            continue

        if line.startswith("--- "):
            path = line[4:].strip()
            out.append("--- /dev/null" if path == "/dev/null" else f"--- a/{fork_path(path[2:] if path.startswith('a/') else path)}")
            continue
        if line.startswith("+++ "):
            path = line[4:].strip()
            out.append("+++ /dev/null" if path == "/dev/null" else f"+++ b/{fork_path(path[2:] if path.startswith('b/') else path)}")
            continue
        if line.startswith(("index ", "new file mode", "deleted file mode", "similarity index",
                            "rename from", "rename to", "old mode", "new mode", "Binary files")):
            if line.startswith(("rename from ", "rename to ")):
                prefix, _, path = line.partition(" ")
                out.append(f"{prefix} {fork_path(path)}")
            else:
                out.append(line)
            continue

        out.append(rename_idents(line))

    return "\n".join(out) + "\n", touched


def main() -> int:
    argv = sys.argv[1:]
    if not argv:
        print(__doc__)
        return 2
    commit = argv[0]
    apply = "--apply" in argv
    excludes = DEFAULT_EXCLUDE
    for arg in argv:
        if arg.startswith("--exclude="):
            excludes = tuple(x for x in arg.split("=", 1)[1].split(",") if x)

    patch, touched = translate(commit, excludes)
    target = f"/tmp/port_{commit[:8]}.patch"
    with open(target, "w", encoding="utf-8") as handle:
        handle.write(patch)
    print(f"patch: {target} ({len(patch.splitlines())} lines, {len(touched)} files)")
    for path in touched:
        print(f"  {path}")

    if not apply:
        print("dry run; re-run with --apply")
        return 0

    result = subprocess.run(
        ["git", "apply", "--3way", "--whitespace=nowarn", target],
        cwd=ROOT, capture_output=True, text=True,
    )
    print(result.stdout[-4000:])
    print(result.stderr[-8000:])
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
