"""Inject the commit hash into Dream DisplaysX lang files and commit.txt.

Usage:
    python scripts/inject_commit_hash.py <commit_hash>
    python scripts/inject_commit_hash.py unknown   # placeholder for local dev

Replaces 'dreamdisplayx.debug.commit' value in all 12 lang JSON files
and the content of commit.txt with the given hash.
"""
import json
import os
import sys

LANG_DIR = "platform/resources/src/main/resources/assets/dreamdisplayx/lang/client"
COMMIT_TXT = "platform/resources/src/main/resources/assets/dreamdisplayx/commit.txt"


def inject_lang(commit_hash: str) -> None:
    """Update dreamdisplayx.debug.commit in all lang JSON files."""
    for fname in sorted(os.listdir(LANG_DIR)):
        if not fname.endswith(".json"):
            continue
        fpath = os.path.join(LANG_DIR, fname)
        with open(fpath, "r", encoding="utf-8") as f:
            data = json.load(f)
        data["dreamdisplayx.debug.commit"] = commit_hash
        with open(fpath, "w", encoding="utf-8", newline="\n") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
            f.write("\n")
        # verify
        with open(fpath, "r", encoding="utf-8") as f:
            restored = json.load(f)
        assert restored["dreamdisplayx.debug.commit"] == commit_hash, \
            f"Verification failed for {fname}"
        print(f"  OK: {fname} -> {commit_hash}")


def inject_commit_txt(commit_hash: str) -> None:
    """Replace the content of commit.txt with the given hash."""
    with open(COMMIT_TXT, "r", encoding="utf-8") as f:
        old = f.read().strip()
    with open(COMMIT_TXT, "w", encoding="utf-8", newline="\n") as f:
        f.write(commit_hash + "\n")
    print(f"  commit.txt: {old!r} -> {commit_hash!r}")


def main() -> None:
    if len(sys.argv) < 2:
        print("Usage: python scripts/inject_commit_hash.py <commit_hash>")
        sys.exit(1)
    commit_hash = sys.argv[1]
    print(f"Injecting commit hash: {commit_hash}")
    print("Lang files:")
    inject_lang(commit_hash)
    print("commit.txt:")
    inject_commit_txt(commit_hash)
    print("Done.")


if __name__ == "__main__":
    main()