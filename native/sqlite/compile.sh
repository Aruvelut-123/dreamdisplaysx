#!/usr/bin/env bash
#
# Rebuilds the bundled sqlite-jdbc native library with relocated JNI symbols so it can be
# relocated under com.dreamdisplayx.libs.org.sqlite (mod isolation) without breaking native
# loading. shadow excludes org/sqlite/native/** from relocation, so the built .so/.dll must stay
# at org/sqlite/native/<os>/<arch>/ and expose JNI symbols prefixed with
# Java_com_dreamdisplayx_libs_org_sqlite_core_NativeDB_.
#
# Usage:
#   compile.sh <os> <arch> [sqlite_amalgamation_version]
#     os   : Linux | Mac | Windows
#     arch : x86_64 | aarch64
#   Environment:
#     SQLITE_AMALGAMATION_DIR  cache dir holding sqlite-amalgamation-<version>
#     CC                       optional C compiler (defaults to cc / clang)
#     SQLITE_JDBC_VER          sqlite-jdbc release to source NativeDB.c from (default 3.53.2.1)
#
# Output: writes libsqlitejdbc.so / libsqlitejdbc.dylib / sqlitejdbc.dll into
#   $OUT_DIR/<os>/<arch>/  (default $PWD/out)
set -euo pipefail

OS_NAME="${1:-}"
OS_ARCH="${2:-}"
SQLITE_VER="${3:-3.53.2}"
SQLITE_JDBC_VER="${SQLITE_JDBC_VER:-3.53.2.1}"
RELOC_SLASH="com/dreamdisplayx/libs/org/sqlite"

if [[ -z "$OS_NAME" || -z "$OS_ARCH" ]]; then
  echo "usage: compile.sh <Linux|Mac|Windows> <x86_64|aarch64>" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

CC="${CC:-cc}"
OUT_DIR="${OUT_DIR:-$PWD/out}"
WORK="$TMP/work"
mkdir -p "$WORK"

# ---- 1. Source the sqlite-jdbc native sources (vendored or clone) ---------------------------
SRC_DIR="$ROOT/sqlite-jdbc-src"
if [[ ! -f "$SRC_DIR/src/main/java/org/sqlite/core/NativeDB.c" ]]; then
  git clone --depth 1 --branch "$SQLITE_JDBC_VER" \
    https://github.com/xerial/sqlite-jdbc.git "$SRC_DIR"
fi

# ---- 2. Generate the relocated JNI header from NativeDB.java --------------------------------
echo ">> Generating relocated JNI header from NativeDB.java"
# NativeDB.java pulls in org.slf4j via the util.LoggerFactory; provide it so javac -h resolves.
SLF4J_JAR="$ROOT/slf4j-api-1.7.36.jar"
if [[ ! -f "$SLF4J_JAR" ]]; then
  curl -fL --retry 3 --retry-all-errors -o "$SLF4J_JAR" \
    "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar"
fi
(
  cd "$SRC_DIR"
  mkdir -p "$WORK/jni-headers"
  javac -h "$WORK/jni-headers" \
    -d "$WORK/jni-headers" \
    -cp "$SLF4J_JAR" \
    -sourcepath src/main/java \
    src/main/java/org/sqlite/core/NativeDB.java
)
# The generated header is org_sqlite_core_NativeDB.h (or with an arch suffix on newer JDKs).
GENERATED_H="$(find "$WORK/jni-headers" -name 'org_sqlite_core_NativeDB.h' | head -n1)"
if [[ -z "$GENERATED_H" ]]; then
  echo "ERROR: could not generate NativeDB.h via javac -h" >&2
  exit 1
fi
# Rename to a neutral name and rewrite the JNI function-name prefix to the relocated package.
JNI_H="$WORK/NativeDB.h"
cp "$GENERATED_H" "$JNI_H"
# .bak suffix keeps the invocation valid on both GNU (Linux) and BSD (macOS) sed.
sed -i.bak 's/Java_org_sqlite_core_NativeDB_/Java_com_dreamdisplayx_libs_org_sqlite_core_NativeDB_/g' "$JNI_H" && rm -f "$JNI_H.bak"

# ---- 3. Prepare NativeDB.c with relocated class names ---------------------------------------
C_FILE="$WORK/NativeDB.c"
cp "$SRC_DIR/src/main/java/org/sqlite/core/NativeDB.c" "$C_FILE"
# FindClass strings use slashes; JNI function names use underscores.
sed -i.bak "s#org/sqlite#${RELOC_SLASH}#g" "$C_FILE" && rm -f "$C_FILE.bak"
sed -i.bak 's/Java_org_sqlite_core_NativeDB_/Java_com_dreamdisplayx_libs_org_sqlite_core_NativeDB_/g' "$C_FILE" && rm -f "$C_FILE.bak"

# ---- 4. Obtain sqlite3 amalgamation ----------------------------------------------------------
AMALG="sqlite-amalgamation-$SQLITE_VER"
AMALG_DIR="${SQLITE_AMALGAMATION_DIR:-$ROOT/amalgamation}/$AMALG"
if [[ ! -f "$AMALG_DIR/sqlite3.c" ]]; then
  mkdir -p "$AMALG_DIR"
  # sqlite.org names the file with the version digits concatenated. For 3.X.Y the pattern is
  # MAJOR MINOR(2) PATCH(2) 00, e.g. 3.53.2 -> 3530200.
  AMALG_NUMERIC="$(printf '%s' "$SQLITE_VER" | awk -F. '{ printf "%s%02d%02d00", $1, $2, $3 }')"
  echo ">> Downloading sqlite-amalgamation-${AMALG_NUMERIC}"
  ZIP="$TMP/$AMALG.zip"
  YEAR_OK=0
  for year in 2026 2025 2024 2023 2022; do
    if curl -fL --retry 3 --retry-all-errors -o "$ZIP" "https://www.sqlite.org/$year/sqlite-amalgamation-$AMALG_NUMERIC.zip"; then
      YEAR_OK=1
      break
    fi
  done
  if [[ "$YEAR_OK" != "1" ]]; then
    echo "ERROR: could not download sqlite3 amalgamation $AMALG (digits $AMALG_NUMERIC)" >&2
    exit 1
  fi
  # The zip contains a top-level sqlite-amalgamation-<digits>/ dir; move its sqlite3*.c/h up.
  if command -v unzip >/dev/null 2>&1; then
    (cd "$AMALG_DIR" && unzip -oq "$ZIP")
  else
    # Windows runners have no unzip; python is always present.
    (cd "$AMALG_DIR" && python3 -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall('.')" "$ZIP" \
      || python -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall('.')" "$ZIP")
  fi
  for f in sqlite3.c sqlite3.h sqlite3ext.h; do
    FOUND="$(find "$AMALG_DIR" -name "$f" | head -n1)"
    if [[ -n "$FOUND" && "$FOUND" != "$AMALG_DIR/$f" ]]; then cp "$FOUND" "$AMALG_DIR/$f"; fi
  done
fi
SQLITE3_C="$AMALG_DIR/sqlite3.c"

# ---- 5. Compile ------------------------------------------------------------------------------
JAVA_HOME="${JAVA_HOME:-}"
if [[ -z "$JAVA_HOME" ]]; then
  JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)" 2>/dev/null || echo javac)")")"
fi
JNI_INCLUDES=()
if [[ -d "$JAVA_HOME/include" ]]; then
  JNI_INCLUDES=(-I"$JAVA_HOME/include")
  case "$OS_NAME" in
    Linux)   [[ -d "$JAVA_HOME/include/linux" ]]   && JNI_INCLUDES+=(-I"$JAVA_HOME/include/linux") ;;
    Mac)     [[ -d "$JAVA_HOME/include/darwin" ]]  && JNI_INCLUDES+=(-I"$JAVA_HOME/include/darwin") ;;
    Windows) [[ -d "$JAVA_HOME/include/win32" ]]   && JNI_INCLUDES+=(-I"$JAVA_HOME/include/win32") ;;
  esac
fi

COMMON_FLAGS=(
  -O2 -fPIC -fvisibility=hidden -fno-omit-frame-pointer
  -I"$WORK" -I"$AMALG_DIR"
  "${JNI_INCLUDES[@]}"
  -DSQLITE_ENABLE_LOAD_EXTENSION=1
  -DSQLITE_HAVE_ISNAN -DHAVE_USLEEP=1
  -DSQLITE_ENABLE_COLUMN_METADATA -DSQLITE_CORE
  -DSQLITE_ENABLE_FTS3 -DSQLITE_ENABLE_FTS3_PARENTHESIS -DSQLITE_ENABLE_FTS5
  -DSQLITE_ENABLE_RTREE -DSQLITE_ENABLE_PERCENTILE -DSQLITE_ENABLE_STAT4
  -DSQLITE_ENABLE_DBSTAT_VTAB -DSQLITE_ENABLE_MATH_FUNCTIONS
  -DSQLITE_ENABLE_UPDATE_DELETE_LIMIT
  -DSQLITE_THREADSAFE=1 -DSQLITE_DEFAULT_MEMSTATUS=0
  -DSQLITE_DEFAULT_FILE_PERMISSIONS=0666
  -DSQLITE_MAX_VARIABLE_NUMBER=250000 -DSQLITE_MAX_MMAP_SIZE=1099511627776
  -DSQLITE_MAX_LENGTH=2147483647 -DSQLITE_MAX_COLUMN=32767
  -DSQLITE_MAX_SQL_LENGTH=1073741824 -DSQLITE_MAX_FUNCTION_ARG=127
  -DSQLITE_MAX_ATTACHED=125 -DSQLITE_MAX_PAGE_COUNT=4294967294
  -DSQLITE_DISABLE_PAGECACHE_OVERFLOW_STATS
)

OUT_LIBDIR="$OUT_DIR/$OS_NAME/$OS_ARCH"
mkdir -p "$OUT_LIBDIR"

case "$OS_NAME" in
  Linux)
    LIBNAME="libsqlitejdbc.so"
    LINK_FLAGS=(-shared -static-libgcc -pthread -lm)
    ;;
  Mac)
    LIBNAME="libsqlitejdbc.dylib"
    LINK_FLAGS=(-dynamiclib -pthread -lm)
    ;;
  Windows)
    LIBNAME="sqlitejdbc.dll"
    # MinGW gcc drives Windows linking; -static-libgcc avoids a runtime libgcc dependency.
    LINK_FLAGS=(-shared -static-libgcc -lws2_32)
    ;;
  *)
    echo "ERROR: unsupported os $OS_NAME" >&2
    exit 2
    ;;
esac

echo ">> Compiling sqlite3.o and NativeDB.o -> $LIBNAME ($OS_NAME/$OS_ARCH)"
"$CC" "${COMMON_FLAGS[@]}" -c -o "$WORK/sqlite3.o" "$SQLITE3_C"
"$CC" "${COMMON_FLAGS[@]}" -c -o "$WORK/NativeDB.o" "$C_FILE"
"$CC" "${LINK_FLAGS[@]}" -o "$OUT_LIBDIR/$LIBNAME" "$WORK/NativeDB.o" "$WORK/sqlite3.o"

echo ">> Wrote $OUT_LIBDIR/$LIBNAME"
ls -la "$OUT_LIBDIR"
