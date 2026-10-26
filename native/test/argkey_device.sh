#!/usr/bin/env bash
# Runs libargkey.so for real on the attached ARMv7 device (adb) under the
# bundled glibc, with a fake key: argv injection, environ scrub, auxv intact,
# and pass-through when the variable is unset. Needs build/assets/runtime
# (runtime/build.sh). Uses and removes /data/local/tmp/argkey-probe.
set -euo pipefail
cd "$(dirname "$0")/../.."

RT=build/assets/runtime
[ -f "$RT/ld-linux-armhf.so.3" ] || { echo "argkey_device.sh: run runtime/build.sh first" >&2; exit 1; }
native/build.sh armhf >/dev/null
out=build/native/test
mkdir -p "$out"
zig cc -target arm-linux-gnueabihf.2.38 -mcpu=cortex_a8 -O1 -std=c11 -Wall -Wextra -Werror \
    -o "$out/argkey_probe" native/test/argkey_probe.c

D=/data/local/tmp/argkey-probe
trap 'adb shell rm -rf $D >/dev/null 2>&1 || true' EXIT
adb shell "rm -rf $D && mkdir -p $D"
adb push "$RT"/ld-linux-armhf.so.3 "$RT"/libc.so.6 "$RT"/libdl.so.2 build/native/armhf/libargkey.so \
    "$out/argkey_probe" "$D/" >/dev/null
adb shell chmod 755 "$D/ld-linux-armhf.so.3" "$D/argkey_probe"

KEY=fakekey-0123456789abcdef0123456789
run() { adb shell "cd $D && $1 ./ld-linux-armhf.so.3 --preload $D/libargkey.so --library-path $D ./argkey_probe -n ST15i" | tr -d '\r'; }

fail=0
expect() { # output line...
    local got=$1; shift
    for want in "$@"; do
        if ! grep -qxF -- "$want" <<<"$got"; then
            echo "FAIL: missing '$want'"; fail=1
        fi
    done
}

with=$(run "HOME=/h PROBE_EXPECT_KEY=$KEY ST15I_SOLOIST_KEY=$KEY")
echo "$with" | sed "s/$KEY/<key>/g"
expect "$with" "argc=5" "argv[0]=./argkey_probe" "argv[1]=-k" "argv[2]=$KEY" "argv[3]=-n" "argv[4]=ST15i" \
    "argv[argc]=null" "environ_is_after_argv=1" "getenv_key_var=unset" "getenv_home=/h" \
    "cmdline_key=0" "environ_var=0"
# PROBE_EXPECT_KEY itself holds the key in environ (1 match); the scrubbed var adds none.
expect "$with" "environ_key=1"
grep -qE '^pagesz=4096 sysconf=4096$' <<<"$with" || { echo "FAIL: auxv page size"; fail=1; }

without=$(run "HOME=/h")
echo "$without"
expect "$without" "argc=3" "argv[0]=./argkey_probe" "argv[1]=-n" "argv[2]=ST15i" "environ_is_after_argv=1" \
    "getenv_home=/h" "environ_var=0"

[ $fail = 0 ] && echo "argkey device test: PASS" || { echo "argkey device test: FAIL"; exit 1; }
