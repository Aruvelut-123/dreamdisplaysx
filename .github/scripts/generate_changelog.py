#!/usr/bin/env python3
"""Generate release notes, using the previous preview as the baseline for preview builds."""

import json
import os
import re
import sys
import urllib.request


def current_block(content: str, version: str) -> str:
    pattern = rf"^# {re.escape(version)}(?![\d.])\s+(?:[^\n]*)\n(.*?)(?=\n# \S|\Z)"
    match = re.search(pattern, content, re.DOTALL | re.MULTILINE)
    return match.group(1).strip() if match else ""


def normalize(block: str) -> str:
    sections = re.split(r"^## ", block, flags=re.MULTILINE)
    lines = []
    for section in sections:
        if not section.strip():
            continue
        header, _, body = section.partition("\n")
        lines.append(f"## {header.strip()}")
        for line in body.splitlines():
            line = line.strip()
            if line:
                lines.append(line)
        lines.append("")
    return "\n".join(lines).strip() + "\n" if lines else ""


def previous_preview(repo: str, token: str, current_tag: str) -> str:
    url = f"https://api.github.com/repos/{repo}/releases?per_page=100"
    request = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json"})
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            releases = json.load(response)
    except Exception as error:
        print(f"warning: unable to query previous releases: {error}", file=sys.stderr)
        return ""

    candidates = [release for release in releases
                  if release.get("prerelease")
                  and release.get("tag_name", "") != current_tag
                  and "-preview" in release.get("tag_name", "")]
    candidates.sort(key=lambda release: release.get("published_at") or release.get("created_at") or "", reverse=True)
    return (candidates[0].get("body") or "") if candidates else ""


def preview_delta(current: str, previous: str) -> str:
    if not previous:
        return current
    previous_lines = {line.strip() for line in previous.splitlines() if line.strip() and not line.startswith("## ")}
    output = []
    for section in re.split(r"^## ", current, flags=re.MULTILINE):
        if not section.strip():
            continue
        header, _, body = section.partition("\n")
        additions = [line.strip() for line in body.splitlines()
                     if line.strip() and line.strip() not in previous_lines]
        if additions:
            output.append(f"## {header.strip()}\n" + "\n".join(additions))
    return "\n\n".join(output).strip() + "\n" if output else "No changelog changes since the previous preview.\n"


def main() -> int:
    version = os.environ["CHANGELOG_VERSION"].split("-")[0]
    is_preview = os.environ.get("CHANGELOG_IS_PREVIEW", "false").lower() == "true"
    repo = os.environ.get("GITHUB_REPOSITORY", "")
    token = os.environ.get("GITHUB_TOKEN", "")
    current = current_block(open("CHANGELOG.md", encoding="utf-8").read(), version)
    if not current:
        if is_preview:
            open("changelog.md", "w", encoding="utf-8").write("")
            return 0
        print(f"Version {version} not found in CHANGELOG.md", file=sys.stderr)
        return 1
    result = preview_delta(normalize(current), previous_preview(repo, token, os.environ.get("CHANGELOG_TAG", ""))) if is_preview else normalize(current)
    open("changelog.md", "w", encoding="utf-8").write(result)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
