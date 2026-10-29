#!/usr/bin/env bash
# Rename an upstream (com.dreamdisplays) source tree to this fork's identifiers
# (com.dreamdisplayx). Used when porting upstream commits by hand: check out the
# upstream file, pipe it through this, and write the result into the fork path.
#
# Usage:
#   scripts/rename_fork.sh < upstream_file > fork_file
#
# The substitutions are ordered so that DreamDisplays -> DreamDisplaysX happens
# once (upper camel), then the lowercase package/identifier forms.
set -euo pipefail

sed \
  -e 's/DreamDisplaysX/DreamDisplaysX__KEEP__/g' \
  -e 's/DreamDisplays/DreamDisplaysX/g' \
  -e 's/DreamDisplaysX__KEEP__/DreamDisplaysX/g' \
  -e 's/dreamdisplays/dreamdisplayx/g' \
  -e 's/\bUnstable\b/DreamDisplaysXUnstableApi/g'
