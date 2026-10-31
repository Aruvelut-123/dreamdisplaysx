#!/usr/bin/env bash
# Port the files touched by one upstream commit into the fork, translating the
# com.dreamdisplays identifiers to com.dreamdisplayx on the way.
#
#   scripts/port_upstream_commit.sh <upstream-commit> [path-filter ...]
#
# For every file in the commit (optionally limited to the given path prefixes)
# this reads the file's content AT THAT COMMIT from the upstream tree, renames
# the identifiers, and writes it to the corresponding fork path. Adds, modifies
# and deletes are all handled; deletions only remove the fork file if the same
# path existed upstream before the commit (i.e. it is a real upstream delete of
# a file this fork also tracks).
#
# It never runs git add/commit — review `git status` afterwards.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

commit="${1:?usage: port_upstream_commit.sh <commit> [path-filter ...]}"
shift || true

rename="$root/scripts/rename_fork.sh"
forkpath="$root/scripts/fork_path.sh"

# Newline-separated "STATUS<TAB>UPSTREAM_PATH" for the commit, optionally filtered.
changes="$(git show --name-status --format='' "$commit" \
  | awk -F'\t' 'NF>=2 {print $1"\t"$2}')"

if [ "$#" -gt 0 ]; then
  filtered=""
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    upstream_path="${line#*$'\t'}"
    for prefix in "$@"; do
      case "$upstream_path" in
        "$prefix"*) filtered+="$line"$'\n' ;;
      esac
    done
  done <<< "$changes"
  changes="$filtered"
fi

[ -z "$changes" ] && { echo "no files matched"; exit 0; }

added=0; modified=0; deleted=0; skipped=0

while IFS=$'\t' read -r status upstream_path; do
  [ -z "$upstream_path" ] && continue
  fork_path="$(bash "$forkpath" "$upstream_path")"

  case "$status" in
    D)
      if [ -f "$fork_path" ]; then
        rm -f "$fork_path"
        echo "DELETE  $fork_path"
        deleted=$((deleted + 1))
      else
        echo "SKIP-D  $fork_path (not in fork)"
        skipped=$((skipped + 1))
      fi
      ;;
    A|M|T|R*)
      if ! git show "$commit:$upstream_path" >/dev/null 2>&1; then
        echo "SKIP    $upstream_path (not readable at commit)"
        skipped=$((skipped + 1))
        continue
      fi

      # The fork deliberately deleted some upstream features (e.g. 13c82b92 removed
      # yt-dlp and YouTube support). Re-adding those files would resurrect dead code,
      # so refuse to create a file the fork does not already track.
      if [ ! -f "$fork_path" ]; then
        echo "REFUSE  $fork_path (absent in fork; port by hand if it is really needed)"
        skipped=$((skipped + 1))
        continue
      fi

      # A wholesale replace silently drops fork-only members (playlist state, extra
      # subtitle tracks, custom widgets). Warn loudly when the diff looks large so the
      # caller reviews the file instead of trusting the result.
      before_lines=$(wc -l < "$fork_path")
      git show "$commit:$upstream_path" | bash "$rename" > /tmp/port_candidate.$$
      after_lines=$(wc -l < /tmp/port_candidate.$$)
      lost=$((before_lines - after_lines))

      modified=$((modified + 1))
      printf 'MODIFY  %s (%d -> %d lines)\n' "$fork_path" "$before_lines" "$after_lines"
      if [ "$lost" -gt 10 ]; then
        printf '  WARNING: this replace removes %d fork lines; review it by hand.\n' "$lost"
      fi
      mv /tmp/port_candidate.$$ "$fork_path"
      ;;
    *)
      echo "SKIP    $upstream_path (status $status)"
      skipped=$((skipped + 1))
      ;;
  esac
done <<< "$changes"

echo "---"
echo "added=$added modified=$modified deleted=$deleted skipped=$skipped"