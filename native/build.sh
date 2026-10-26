#!/usr/bin/env bash
# Builds fakepulse (libpulse.so.0) with zig cc.
#   native/build.sh [host] [armhf]    (default: both)
#   host  -> build/native/host/libpulse.so.0   (macOS arm64, for native/test)
#   armhf -> build/native/armhf/libpulse.so.0  (glibc 2.38+, Cortex-A8, for the device)
set -euo pipefail
cd "$(dirname "$0")/.."

command -v zig >/dev/null || { echo "native/build.sh: zig not found on PATH" >&2; exit 1; }

CFLAGS=(-std=c11 -Wall -Wextra -Wno-unused-parameter -Werror -Inative/include)
targets=("$@")
[ ${#targets[@]} -eq 0 ] && targets=(host armhf)

for t in "${targets[@]}"; do
    out=build/native/$t
    mkdir -p "$out"
    case "$t" in
    host)
        zig cc -target aarch64-macos -shared -O2 -fvisibility=hidden "${CFLAGS[@]}" \
            -o "$out/libpulse.so.0" native/fakepulse.c
        ;;
    armhf)
        zig cc -target arm-linux-gnueabihf.2.38 -mcpu=cortex_a8 -shared -fPIC -O2 -fvisibility=hidden \
            -Wl,-soname,libpulse.so.0 -s "${CFLAGS[@]}" -o "$out/libpulse.so.0" native/fakepulse.c -lpthread -lm
        ;;
    *)
        echo "native/build.sh: unknown target $t" >&2
        exit 1
        ;;
    esac
    echo "built $out/libpulse.so.0"
done
