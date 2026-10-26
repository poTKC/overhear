# Third-party notices

ST15i Player ships the third-party components below inside its APK
(`assets/runtime/`, and the icons in `res/`). This file is also packaged as
`assets/licenses/THIRD_PARTY_NOTICES.txt` and shown in the app
(設定画面の「ライセンス」). In that packaged copy, `runtime/build.sh` appends the
full licence texts: LGPL-2.1-or-later, GPL-3.0-or-later, GCC-exception-3.1,
MPL-2.0 and Apache-2.0 from SPDX license-list-data v3.27.0 (pinned by sha256), and Debian's
copyright file for libc6. The BSD-3-Clause text for Go is reproduced below.
In this repository copy, each entry gives the SPDX id and links to the text.

Spotify's Soloist is **not** part of this app. The app downloads it on the
device from Spotify's CDN, under Spotify's terms. It is never committed to this
repository or bundled in the APK.

The project's own code (the Android app, fakepulse, libargkey, st15i-fetch's
own sources and the build scripts) has no licence chosen yet.

---

## GNU C Library (glibc) 2.41

- Files: `ld-linux-armhf.so.3`, `libc.so.6`, `libm.so.6`, `libpthread.so.0`,
  `librt.so.1`, `libdl.so.2`
- From the Debian binary package `libc6` 2.41-12+deb13u4 (armhf)
- Licence: LGPL-2.1-or-later (SPDX `LGPL-2.1-or-later`). Some files carry other
  permissive notices; Debian's copyright file lists them all and is appended to
  the in-app copy.
  <https://spdx.org/licenses/LGPL-2.1-or-later.html>
- **Modification:** `libc.so.6` is binary-patched. The one 17-byte sequence
  `/etc/resolv.conf\0` is replaced with `./resolv.conf\0\0\0\0` (same length,
  NUL-padded), because Android has no `/etc/resolv.conf`. Nothing else is
  changed. `runtime/build.sh` applies the patch and checks it was the only
  occurrence.
- Corresponding source: the Debian source package `glibc` 2.41-12+deb13u4.
  - <https://deb.debian.org/debian/pool/main/g/glibc/>
  - <https://snapshot.debian.org/package/glibc/2.41-12%2Bdeb13u4/>
  - Upstream: <https://sourceware.org/glibc/>
- Soloist loads the libraries dynamically, through the bundled
  `ld-linux-armhf.so.3`. To use a modified glibc, change `runtime/build.sh` to
  take your build, then rerun it (plain `./build.sh` does, but not
  `SKIP_RUNTIME=1 ./build.sh`). The bundle's VERSION hash then changes, and the
  app re-extracts the runtime on its next start.

## libatomic 14.2.0

- File: `libatomic.so.1`
- From the Debian binary package `libatomic1` 14.2.0-19 (armhf), built from the
  Debian source package `gcc-14` 14.2.0-19
- Licence: GPL-3.0-or-later with the GCC Runtime Library Exception 3.1 (SPDX
  `GPL-3.0-or-later WITH GCC-exception-3.1`)
  - <https://spdx.org/licenses/GPL-3.0-or-later.html>
  - <https://spdx.org/licenses/GCC-exception-3.1.html>
- Source:
  - <https://deb.debian.org/debian/pool/main/g/gcc-14/>
  - <https://snapshot.debian.org/package/gcc-14/14.2.0-19/>

## Mozilla CA certificate bundle

- File: `cacert.pem`, as published by the curl project at
  <https://curl.se/ca/cacert.pem> (extracted from Mozilla's NSS `certdata.txt`)
- Licence: MPL-2.0 (SPDX `MPL-2.0`). <https://spdx.org/licenses/MPL-2.0.html>
- Source: <https://hg.mozilla.org/projects/nss/> (`lib/ckfw/builtins/certdata.txt`)

## Go standard library (in `st15i-fetch`)

- File: `st15i-fetch`, a static Go program built with Go 1.27.3. It links parts
  of the Go standard library.
- Licence: BSD-3-Clause (SPDX `BSD-3-Clause`). Go's patent grant is at
  <https://go.dev/PATENTS>.
- Source: <https://go.dev/dl/>, <https://go.googlesource.com/go>

```
Copyright 2009 The Go Authors.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

   * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
   * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
   * Neither the name of Google LLC nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## PulseAudio client headers (used to build fakepulse)

- `libpulse.so.0` in the APK is fakepulse, this project's own replacement
  library. It is compiled against the PulseAudio client headers vendored in
  `native/include/pulse/`. Those come unchanged from the Debian package
  `libpulse-dev` 17.0+dfsg1-3. The headers supply API types and prototypes
  only; no PulseAudio code is linked.
- Licence of the headers: LGPL-2.1-or-later (SPDX `LGPL-2.1-or-later`), as
  stated at the top of each file.
- Source:
  - <https://snapshot.debian.org/package/pulseaudio/17.0%2Bdfsg1-3/>
  - Upstream: <https://www.freedesktop.org/wiki/Software/PulseAudio/>

## Remix Icon (icons)

- The player, notification and status bar icons are rasterised (by
  `tools/make_icons.py`) from Remix Icon 4.5.0 (npm package
  `remixicon@4.5.0`): shuffle-line, repeat-2-line, repeat-one-line,
  skip-back-fill, skip-forward-fill, play-fill, pause-fill, play-circle-fill,
  pause-circle-fill, play-list-2-line,
  settings-3-line, close-line, music-2-line and music-2-fill. Recoloured,
  resized and composed (the toggle dot, the artwork placeholder); otherwise
  unmodified.
- Licence: Apache License 2.0 (SPDX `Apache-2.0`). The package's `License`
  file is the unmodified Apache-2.0 text, and its `package.json` says
  `Apache-2.0`. Its README adds a request (not a licence condition) that the
  icons themselves not be sold, and welcomes a mention of "Remix Icon".
  Remix Icon is by Remix Design.
- Source: <https://remixicon.com>, <https://github.com/Remix-Design/remixicon>,
  <https://unpkg.com/remixicon@4.5.0/> (each SVG is checked by sha256 in
  `tools/make_icons.py`).
- Licence text: <https://spdx.org/licenses/Apache-2.0.html>

## Zig toolchain runtime

`libpulse.so.0` and `libargkey.so` are built with `zig cc`. The toolchain may
link small parts of its compiler runtime (LLVM compiler-rt). Those are licensed
Apache-2.0 WITH LLVM-exception (SPDX), and the exception waives the notice
requirement for compiled binaries. They are listed here for completeness.
<https://spdx.org/licenses/LLVM-exception.html>
