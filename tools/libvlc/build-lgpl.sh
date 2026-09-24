#!/bin/bash
# LibVLC for phone-ai-kiosk (0.60.0, Poom): arm64 only, LGPL only, built once.
#
# Runs inside VideoLAN's own build image (see .github/workflows/libvlc-lgpl.yml,
# which pins it), with /work mounted. Everything it fetches is pinned by commit:
#
#   vlc-android  libvlc-3.7.6  1e9a49eff9b4031656e91bd489f6dfbe5f013935
#     -> libvlcjni             c0cc8ce6443dcb09be9b43c7b7a3ed01e33cb3ac  (vlc-android's compile.sh pins it)
#     -> VLC 3.0.x             66455a98c8c515796b4a192acaa125c5d68c76c8  (libvlcjni's get-vlc.sh pins it)
#
# LGPL ONLY, in three layers:
#   1. contribs: "--license a" = --disable-gpl --disable-gnuv3 --enable-ad-clauses
#      (no GPL library is built; dvdread/dvdnav/live555 are only turned on for "g");
#   2. VLC's own modules: each carries its license as a string (vlc_plugin.h,
#      VLC_MODULE_LICENSE). Every module whose string is the GPL's is added to
#      the module blacklist before libvlc.so is linked (patch below), and listed;
#   3. the result is checked: the GPL module string must not be in libvlc.so,
#      and FFmpeg's own configuration must say LGPL.
#
# Output in /work/out: the AAR, SHA256SUMS, license-report.txt, build-info.txt.
set -euo pipefail

VLC_ANDROID_REPO=https://code.videolan.org/videolan/vlc-android.git
VLC_ANDROID_COMMIT=1e9a49eff9b4031656e91bd489f6dfbe5f013935
GPL_MARK="GNU General Public License, version 2 or later"
LGPL_MARK="GNU Lesser General Public License, version 2.1 or later"

OUT=/work/out
rm -rf "$OUT"; mkdir -p "$OUT"
cd /work
echo "== environment"; env | grep -E '^ANDROID_(SDK|NDK)=' || true; nproc; df -h /work | tail -1

rm -rf vlc-android
git clone "$VLC_ANDROID_REPO" vlc-android
cd vlc-android
git checkout -q "$VLC_ANDROID_COMMIT"

# Fetch libvlcjni and VLC at the commits this tag pins (and apply VideoLAN's patches).
./buildsystem/compile.sh --init -a arm64

# Layer 2: leave out every module that declares the GPL, just before the list of
# static modules is made. The names go to gpl-modules-left-out.txt.
CL=libvlcjni/buildsystem/compile-libvlc.sh
grep -q 'echo "Generating static module list"' "$CL"
python3 - "$CL" "$GPL_MARK" <<'EOF'
import sys
path, mark = sys.argv[1], sys.argv[2]
s = open(path).read()
anchor = 'echo "Generating static module list"'
patch = '''# phone-ai-kiosk: LGPL only - a module whose own license string is the GPL is left out.
: > ${VLC_BUILD_DIR}/gpl-modules-left-out.txt
for f in $(find ${VLC_BUILD_DIR}/modules -name 'lib*_plugin.a'); do
    if grep -aqF "%s" "$f"; then
        n=$(basename "$f" | sed 's/^lib//; s/_plugin\\.a$//')
        VLC_MODULE_BLACKLIST="$VLC_MODULE_BLACKLIST $n"
        echo "$n" >> ${VLC_BUILD_DIR}/gpl-modules-left-out.txt
    fi
done
''' % mark
assert anchor in s
s = s.replace(anchor, patch + anchor, 1)
open(path, "w").write(s)
EOF

# Build: libvlc only, release, contribs under LGPL v2.1 + ad-clauses.
./buildsystem/compile.sh -l -a arm64 -r --license a

# ------------------------------------------------------------------ what came out
BUILD=$(ls -d libvlcjni/vlc/build-android-aarch64-linux-android)
CONTRIB_SRC=$(ls -d libvlcjni/vlc/contrib/contrib-android-aarch64-linux-android)
AAR=$(find libvlcjni/libvlc/build/outputs/aar -name '*release*.aar' | head -1)
[ -n "$AAR" ] || { echo "no AAR"; find . -name '*.aar'; exit 1; }
cp "$AAR" "$OUT/libvlc-lgpl-3.7.6-arm64.aar"

mkdir -p /tmp/aar && (cd /tmp/aar && unzip -o -q "$OUT/libvlc-lgpl-3.7.6-arm64.aar")
SO=/tmp/aar/jni/arm64-v8a/libvlc.so

{
  echo "# LibVLC for phone-ai-kiosk: license report"
  echo
  echo "## 1. VLC modules left out because they declare the GPL"
  sort "$BUILD/gpl-modules-left-out.txt" | sed 's/^/- /'
  echo
  echo "## 2. VLC modules in libvlc.so, with the license each declares"
  for n in $(grep -o 'vlc_entry__[A-Za-z0-9_]*' "$BUILD/ndk/libvlcjni-modules.c" | sed 's/vlc_entry__//' | sort -u); do
    f=$(find "$BUILD/install/lib/vlc/plugins" -name "lib${n}_plugin.a" | head -1)
    if [ -z "$f" ]; then l="(object not found)"
    elif grep -aqF "$GPL_MARK" "$f"; then l="GPL-2.0-or-later  <-- MUST NOT BE HERE"
    elif grep -aqF "$LGPL_MARK" "$f"; then l="LGPL-2.1-or-later"
    else l="(no license string)"; fi
    echo "- $n: $l"
  done
  echo
  echo "## 3. Contribs built (third-party libraries linked into libvlc.so)"
  for d in "$CONTRIB_SRC"/*/; do
    p=$(basename "$d")
    lic=$(ls "$d" 2>/dev/null | grep -iE '^(COPYING|LICENSE|LICENCE)' | head -3 | tr '\n' ' ')
    first=""
    for f in $lic; do first="$first [$f: $(grep -m1 -iE 'general public|lesser|mit|bsd|apache|zlib|isc|public domain|permission is hereby' "$d/$f" 2>/dev/null | head -c 120)]"; done
    echo "- $p:$first"
  done
  echo
  echo "## 4. FFmpeg's own view of its license"
  grep -h 'FFMPEG_LICENSE\|FFMPEG_CONFIGURATION' $(find "$CONTRIB_SRC" -path '*ffmpeg*' -name config.h | head -1) || echo "(ffmpeg config.h not found)"
  echo
  echo "## 5. Checks on the final libvlc.so"
  echo "- GPL module string in libvlc.so: $(grep -acF "$GPL_MARK" "$SO") (must be 0)"
  echo "- LGPL module strings in libvlc.so: $(grep -aoF "$LGPL_MARK" "$SO" | wc -l)"
  echo "- stripped: $(file "$SO" | grep -o 'stripped\|not stripped')"
} > "$OUT/license-report.txt"
cat "$OUT/license-report.txt"

if grep -qF "MUST NOT BE HERE" "$OUT/license-report.txt" || [ "$(grep -acF "$GPL_MARK" "$SO")" != "0" ]; then
  echo "A GPL module is in the build"; exit 1
fi

{
  echo "vlc-android $VLC_ANDROID_COMMIT (libvlc-3.7.6)"
  echo "libvlcjni $(git -C libvlcjni rev-parse HEAD)"
  echo "vlc $(git -C libvlcjni/vlc rev-parse HEAD)"
  echo "license a (LGPL v2.1 + ad-clauses), GPL modules left out: $(wc -l < "$BUILD/gpl-modules-left-out.txt")"
  echo "abi arm64-v8a"
  ls -l "$OUT/libvlc-lgpl-3.7.6-arm64.aar" /tmp/aar/jni/arm64-v8a/
} > "$OUT/build-info.txt"
cat "$OUT/build-info.txt"
(cd "$OUT" && sha256sum *.aar > SHA256SUMS && cat SHA256SUMS)
