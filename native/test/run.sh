#!/usr/bin/env bash
# Builds the host fakepulse and runs the harness against it.
set -euo pipefail
cd "$(dirname "$0")/../.."

native/build.sh host
out=build/native/test
mkdir -p "$out"
zig cc -target aarch64-macos -std=c11 -D_DEFAULT_SOURCE -Wall -Wextra -Wno-unused-parameter -Werror -O1 \
    -Inative/include -o "$out/harness" native/test/harness.c
"$out/harness" build/native/host/libpulse.so.0 native/test/symbols.txt "$out/out.raw"
