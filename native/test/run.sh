#!/usr/bin/env bash
# Runs the harness against the host fakepulse from native/build.sh, then again
# with both built under AddressSanitizer (Apple clang: zig has no ASan runtime
# for macOS).
set -euo pipefail
cd "$(dirname "$0")/../.."

native/build.sh host
out=build/native/test
mkdir -p "$out"
WARN=(-std=c11 -D_DEFAULT_SOURCE -Wall -Wextra -Wno-unused-parameter -Werror -Inative/include)

echo "== host build"
zig cc -target aarch64-macos -O1 "${WARN[@]}" -o "$out/harness" native/test/harness.c
"$out/harness" build/native/host/libpulse.so.0 native/test/symbols.txt "$out/out.raw"

echo "== AddressSanitizer build"
ASAN=(-fsanitize=address -fno-omit-frame-pointer -g -O1)
clang "${ASAN[@]}" "${WARN[@]}" -shared -fvisibility=hidden -o "$out/libpulse-asan.so.0" native/fakepulse.c
clang "${ASAN[@]}" "${WARN[@]}" -o "$out/harness-asan" native/test/harness.c
ASAN_OPTIONS=abort_on_error=1 "$out/harness-asan" "$out/libpulse-asan.so.0" native/test/symbols.txt "$out/out.raw"

echo "== argkey (host build, ASan)"
clang "${ASAN[@]}" "${WARN[@]}" -Inative -o "$out/argkey-test" native/test/argkey_test.c native/argkey.c
ASAN_OPTIONS=abort_on_error=1 "$out/argkey-test"
