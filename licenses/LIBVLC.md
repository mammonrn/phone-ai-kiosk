# LibVLC in phone-ai-kiosk

Since 0.60.0 the music and video players hand the files that Media3 cannot play
(VCD `.dat`, `.mpg`, `.mpeg`, `.vob`, `.ts`, `.wmv`, `.wma`, `.flv`, and ALAC
inside `.m4a`) to **LibVLC 3.7.6** from VideoLAN. The choice is made in one
place, `app/src/main/java/com/mammonrn/phoneaikiosk/media/MediaKinds.kt`
(`PlayerChoice`).

## License

LibVLC, and every library built into it here, is under the **GNU Lesser
General Public License, version 2.1 or later** (full text: `LGPL-2.1.txt` in
this folder, and a copy inside the APK at `assets/licenses/LGPL-2.1.txt`).
Some of the libraries it bundles carry their own permissive licences (BSD,
MIT, zlib and similar); the full list, module by module, is in the license
report published with the build (see below).

It is built by us **with no GPL component**:

1. VLC's third-party libraries ("contribs") are built with
   `--disable-gpl --disable-gnuv3 --enable-ad-clauses` (vlc-android's
   `--license a`). No GPL library is built; libdvdread/libdvdnav are not
   enabled.
2. Every VLC module declares its own license. Each module whose declaration is
   the GPL is left out of `libvlc.so` before it is linked, and listed.
3. The finished `libvlc.so` is checked: the GPL module declaration must not be
   in it, and FFmpeg's own configuration must say LGPL. The build fails
   otherwise.

LibVLC is loaded as its own shared libraries (`libvlc.so`, `libvlcjni.so`,
`libc++_shared.so`); it can be replaced by another build of the same API.

## Where it comes from, and how to build it again

* Source, pinned by commit:
  * vlc-android `libvlc-3.7.6` = `1e9a49eff9b4031656e91bd489f6dfbe5f013935`
    (https://code.videolan.org/videolan/vlc-android)
  * libvlcjni `c0cc8ce6443dcb09be9b43c7b7a3ed01e33cb3ac`
    (https://code.videolan.org/videolan/libvlcjni), pinned by vlc-android's
    `buildsystem/compile.sh`
  * VLC 3.0.x `66455a98c8c515796b4a192acaa125c5d68c76c8`
    (https://code.videolan.org/videolan/vlc), pinned by libvlcjni's
    `buildsystem/get-vlc.sh`, with the patches in libvlcjni's `libvlc/patches`
  * each contrib's source tarball as VLC's `contrib/src` pins it (URL and
    SHA-512)
* Build: `tools/libvlc/build-lgpl.sh` in this repository, run inside
  VideoLAN's own image `registry.videolan.org/vlc-debian-android:20260610055743`
  by `.github/workflows/libvlc-lgpl.yml` (run it from the Actions tab). By hand:

  ```
  mkdir -p work/home && cp tools/libvlc/build-lgpl.sh work/
  docker run --rm -v "$PWD/work:/work" -u "$(id -u):$(id -g)" -e HOME=/work/home \
    registry.videolan.org/vlc-debian-android:20260610055743 bash /work/build-lgpl.sh
  ```

* Result: the release `LIBVLC_RELEASE` of this repository holds the AAR, its
  `SHA256SUMS`, `license-report.txt` and `build-info.txt`. The app's build
  (`app/build.gradle.kts`) downloads that AAR and refuses it unless its SHA-256
  is `LIBVLC_SHA256`.

## What was left out, and what it costs

LIBVLC_LEFT_OUT
