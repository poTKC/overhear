#!/usr/bin/env bash
# Builds the runtime bundle that the APK ships under assets/runtime/:
#   st15i-fetch            static Go download helper (linux/arm, ARMv7)
#   ld-linux-armhf.so.3 libc.so.6 libm.so.6 libpthread.so.0 librt.so.1
#   libdl.so.2             Debian trixie glibc (libc6), for Soloist
#   libatomic.so.1         Debian trixie libatomic1
#   libpulse.so.0          fakepulse (native/build.sh armhf)
#   libargkey.so           API key preload for Soloist (native/argkey.c)
#   cacert.pem             Mozilla CA bundle
#   VERSION                content hash, so the app knows when to re-extract
# Debian packages are pinned by filename and sha256; downloads are cached in
# build/cache/. Soloist itself is never bundled.
#
# Runtime contract (the app must honour this):
#   * libc.so.6 is patched to read "./resolv.conf" instead of
#     "/etc/resolv.conf" (Android has none). Soloist must therefore run with
#     its working directory set to the extracted runtime dir, which holds the
#     resolv.conf the app writes.
#   * The runtime must live on internal ext4 storage (the app's filesDir), not
#     on the sdcard: the sdcard is mounted noexec and cannot keep exec bits or
#     symlinks.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT=build/assets/runtime
CACHE=build/cache
GO_TOOLCHAIN=go1.27.3

# Pinned from https://deb.debian.org/debian/dists/trixie/main/binary-armhf/Packages.xz
# (Debian 13.7 point release, Release date 2026-09-12; both packages are in
# trixie main, neither in trixie-updates nor trixie-security at that time).
# When a newer point release drops them from the pool, the snapshot mirror
# still serves them.
DEB_MIRRORS=(
    https://deb.debian.org/debian
    https://snapshot.debian.org/archive/debian/20261001T000000Z
)
LIBC_DEB=pool/main/g/glibc/libc6_2.41-12+deb13u4_armhf.deb
LIBC_SHA256=4fc6fed8d77d01c0dacb5fda8b880a8c8a669575936de5761b693077a6f6c2ec
ATOMIC_DEB=pool/main/g/gcc-14/libatomic1_14.2.0-19_armhf.deb
ATOMIC_SHA256=e4a4dc17913ded43f2a8571f9c8c214d05534632fc5a41705d6b39acd8d5f4a4
GLIBC_LIBS=(ld-linux-armhf.so.3 libc.so.6 libm.so.6 libpthread.so.0 librt.so.1 libdl.so.2)

die() { echo "runtime/build.sh: $*" >&2; exit 1; }
for tool in go curl ar bsdtar shasum python3; do
    command -v "$tool" >/dev/null || die "$tool not found on PATH"
done

mkdir -p "$CACHE"
STAGE=$OUT.tmp
WORKDIRS=()
cleanup() {
    rm -rf "$STAGE" ${WORKDIRS[@]+"${WORKDIRS[@]}"}
}
trap cleanup EXIT
rm -rf "$STAGE"
mkdir -p "$STAGE"

sha256() { shasum -a 256 "$1" | cut -d' ' -f1; }

# 1. fetch helper (reproducible: pinned toolchain, no VCS stamp, no paths)
(cd helper && GOTOOLCHAIN=$GO_TOOLCHAIN GOOS=linux GOARCH=arm GOARM=7 CGO_ENABLED=0 \
    go build -trimpath -buildvcs=false -ldflags '-s -w' -o "../$STAGE/st15i-fetch" .)

# 2. Debian packages. fetch_deb POOL_PATH SHA256 -> prints the cached path.
#    A cached file with the right hash is used without touching the network.
fetch_deb() {
    local pool=$1 want=$2 path mirror
    path=$CACHE/$(basename "$pool")
    if [ -f "$path" ] && [ "$(sha256 "$path")" = "$want" ]; then
        echo "$path"
        return
    fi
    rm -f "$path"
    for mirror in "${DEB_MIRRORS[@]}"; do
        echo "downloading $mirror/$pool" >&2
        if curl -fsSL -o "$path.part" "$mirror/$pool"; then
            if [ "$(sha256 "$path.part")" = "$want" ]; then
                mv "$path.part" "$path"
                echo "$path"
                return
            fi
            echo "sha256 mismatch for $mirror/$pool" >&2
        fi
        rm -f "$path.part"
    done
    die "could not fetch $(basename "$pool") with sha256 $want"
}

# extract_libs DEB LIB... -> copies usr/lib/arm-linux-gnueabihf/LIB (dereferenced) into $STAGE
extract_libs() {
    local deb=$1; shift
    local work data lib src
    work=$(mktemp -d "$CACHE/deb.XXXXXX")
    WORKDIRS+=("$work")
    (cd "$work" && ar x "$OLDPWD/$deb")
    data=
    for f in "$work"/data.tar.*; do
        [ -f "$f" ] && { data=$f; break; }
    done
    [ -n "$data" ] || die "$deb has no data.tar.*"
    mkdir "$work/root"
    bsdtar -xf "$data" -C "$work/root"
    for lib in "$@"; do
        src=$work/root/usr/lib/arm-linux-gnueabihf/$lib
        [ -e "$src" ] || die "$lib missing from $deb"
        cp -L "$src" "$STAGE/$lib"
        chmod 0644 "$STAGE/$lib"
    done
}

libc_deb=$(fetch_deb "$LIBC_DEB" "$LIBC_SHA256")
atomic_deb=$(fetch_deb "$ATOMIC_DEB" "$ATOMIC_SHA256")
extract_libs "$libc_deb" "${GLIBC_LIBS[@]}"
extract_libs "$atomic_deb" libatomic.so.1
chmod 0755 "$STAGE/ld-linux-armhf.so.3"

# 3. Point libc at ./resolv.conf (same length; see the runtime contract above).
python3 -I - "$STAGE/libc.so.6" <<'PY'
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

# 4. fakepulse and argkey (always rebuilt so native changes are picked up)
native/build.sh armhf >/dev/null
cp build/native/armhf/libpulse.so.0 "$STAGE/libpulse.so.0"
cp build/native/armhf/libargkey.so "$STAGE/libargkey.so"

# 5. Mozilla CA bundle (cached; delete build/cache/cacert.pem to refresh)
if [ ! -s "$CACHE/cacert.pem" ]; then
    curl -fsSL -o "$CACHE/cacert.pem.part" https://curl.se/ca/cacert.pem
    mv "$CACHE/cacert.pem.part" "$CACHE/cacert.pem"
fi
grep -q 'BEGIN CERTIFICATE' "$CACHE/cacert.pem" || die "cacert.pem has no certificates"
cp "$CACHE/cacert.pem" "$STAGE/cacert.pem"

# 6. VERSION: sha256 of the sorted "sha256  name" manifest of every file.
version=$(cd "$STAGE" && LC_ALL=C find . -type f ! -name VERSION | LC_ALL=C sort |
    while read -r f; do shasum -a 256 "$f"; done | shasum -a 256 | cut -c1-16)
echo "$version" >"$STAGE/VERSION"

rm -rf "$OUT"
mv "$STAGE" "$OUT"
echo "built $OUT (VERSION $version)"
