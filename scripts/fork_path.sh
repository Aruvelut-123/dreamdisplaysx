#!/usr/bin/env bash
# Print the fork-relative path for an upstream-relative path.
#   platform/client/common/src/main/kotlin/com/dreamdisplays/x/Y.kt
#   -> platform/client/common/src/main/kotlin/com/dreamdisplayx/x/Y.kt
#
# File basenames that embed the product name are renamed too:
#   DreamDisplaysApi.kt      -> DreamDisplaysXApi.kt
#   DreamDisplaysModule.kt   -> DreamDisplaysXModule.kt
#   DreamDisplaysRuntime.kt  -> DreamDisplaysXRuntime.kt
#   DreamDisplaysAddon.kt    -> DreamDisplaysXAddon.kt
#   Unstable.kt              -> DreamDisplaysXUnstableApi.kt
set -euo pipefail

path="$1"

path="${path//com\/dreamdisplays\//com\/dreamdisplayx\/}"
path="${path//dreamdisplays.kotlin-conventions/dreamdisplayx.kotlin-conventions}"
path="${path//dreamdisplays.serialization-conventions/dreamdisplayx.serialization-conventions}"
path="${path//dreamdisplays.stonecutter-versions/dreamdisplayx.stonecutter-versions}"

dir="$(dirname "$path")"
base="$(basename "$path")"

case "$base" in
  Unstable.kt) base="DreamDisplaysXUnstableApi.kt" ;;
  DreamDisplays*.kt) base="DreamDisplaysX${base#DreamDisplays}" ;;
esac

if [ "$dir" = "." ]; then
  printf '%s\n' "$base"
else
  printf '%s/%s\n' "$dir" "$base"
fi