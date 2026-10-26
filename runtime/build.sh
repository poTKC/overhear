#!/usr/bin/env bash
# Builds the runtime bundle that the APK ships under assets/runtime/:
#   st15i-fetch            static Go download helper (linux/arm, ARMv7)
#   ld-linux-armhf.so.3 libc.so.6 libm.so.6 libpthread.so.0 librt.so.1
#   libdl.so.2 libatomic.so.1   Debian trixie glibc for Soloist
#                          (libc patched to read ./resolv.conf)
#   libpulse.so.0          fakepulse (native/build.sh armhf)
#   cacert.pem             Mozilla CA bundle
#   VERSION                content hash, so the app knows when to re-extract
# Downloads are cached in build/cache/. Soloist itself is never bundled.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT=build/assets/runtime
CACHE=build/cache
DEB_POOL=https://deb.debian.org/debian/pool/main
GLIBC_LIBS=(ld-linux-armhf.so.3 libc.so.6 libm.so.6 libpthread.so.0 librt.so.1 libdl.so.2)

die() { echo "runtime/build.sh: $*" >&2; exit 1; }
for tool in go curl ar bsdtar shasum python3; do
    command -v "$tool" >/dev/null || die "$tool not found on PATH"
done

mkdir -p "$CACHE"
rm -rf "$OUT"
mkdir -p "$OUT"

# 1. fetch helper
(cd helper && GOOS=linux GOARCH=arm GOARM=7 CGO_ENABLED=0 \
    go build -trimpath -ldflags '-s -w' -o "../$OUT/st15i-fetch" .)

# 2. Debian packages: newest matching .deb in the pool, cached.
#    fetch_deb POOL_SUBDIR REGEX MIN_BYTES -> prints the cached path
fetch_deb() {
    local subdir=$1 pattern=$2 min=$3 name path size
    name=$(curl -fsSL "$DEB_POOL/$subdir/" | grep -oE "$pattern" | sort -uV | tail -1) || true
    [ -n "$name" ] || die "no package matching $pattern in $DEB_POOL/$subdir/"
    path=$CACHE/$name
    if [ ! -s "$path" ]; then
        echo "downloading $name" >&2
        curl -fsSL -o "$path.part" "$DEB_POOL/$subdir/$name"
        mv "$path.part" "$path"
    fi
    size=$(wc -c <"$path" | tr -d ' ')
    [ "$size" -gt "$min" ] || { rm -f "$path"; die "$name is only $size bytes"; }
    [ "$(head -c 8 "$path")" = '!<arch>' ] || { rm -f "$path"; die "$name is not an ar archive"; }
    echo "$path"
}

# extract_libs DEB LIB... -> copies usr/lib/arm-linux-gnueabihf/LIB (dereferenced) into $OUT
extract_libs() {
    local deb=$1; shift
    local work data lib src
    work=$(mktemp -d "$CACHE/deb.XXXXXX")
    (cd "$work" && ar x "$OLDPWD/$deb")
    data=$(ls "$work"/data.tar.* 2>/dev/null | head -1)
    [ -n "$data" ] || die "$deb has no data.tar.*"
    mkdir "$work/root"
    bsdtar -xf "$data" -C "$work/root"
    for lib in "$@"; do
        src=$work/root/usr/lib/arm-linux-gnueabihf/$lib
        [ -e "$src" ] || die "$lib missing from $deb"
        cp -L "$src" "$OUT/$lib"
        chmod 0644 "$OUT/$lib"
    done
    rm -rf "$work"
}

libc_deb=$(fetch_deb g/glibc 'libc6_2\.41-[^"]*_armhf\.deb' 1000000)
atomic_deb=$(fetch_deb g/gcc-14 'libatomic1_14\.[^"]*_armhf\.deb' 4000)
extract_libs "$libc_deb" "${GLIBC_LIBS[@]}"
extract_libs "$atomic_deb" libatomic.so.1
chmod 0755 "$OUT/ld-linux-armhf.so.3"

# 3. Android has no /etc/resolv.conf: point libc at ./resolv.conf (same length).
python3 -I - "$OUT/libc.so.6" <<'PY'
import sys
path = sys.argv[1]
old, new = b"/etc/resolv.conf\0", b"./resolv.conf\0\0\0\0"
assert len(old) == len(new) == 17
data = open(path, "rb").read()
count = data.count(old)
if count != 1:
    sys.exit(f"runtime/build.sh: expected 1 occurrence of /etc/resolv.conf in libc.so.6, found {count}")
open(path, "wb").write(data.replace(old, new))
PY

# 4. fakepulse
[ -f build/native/armhf/libpulse.so.0 ] || native/build.sh armhf
cp build/native/armhf/libpulse.so.0 "$OUT/libpulse.so.0"

# 5. Mozilla CA bundle (cached; delete build/cache/cacert.pem to refresh)
if [ ! -s "$CACHE/cacert.pem" ]; then
    curl -fsSL -o "$CACHE/cacert.pem.part" https://curl.se/ca/cacert.pem
    mv "$CACHE/cacert.pem.part" "$CACHE/cacert.pem"
fi
grep -q 'BEGIN CERTIFICATE' "$CACHE/cacert.pem" || die "cacert.pem has no certificates"
cp "$CACHE/cacert.pem" "$OUT/cacert.pem"

# 6. VERSION: hash of every file's name and content, sorted by name.
version=$(cd "$OUT" && for f in $(ls | LC_ALL=C sort); do
    [ "$f" = VERSION ] && continue
    printf '%s\0' "$f"
    cat "$f"
done | shasum -a 256 | cut -c1-16)
echo "$version" >"$OUT/VERSION"

echo "built $OUT (VERSION $version)"
