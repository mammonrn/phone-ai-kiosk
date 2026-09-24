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
    applied on top as commits (the build reports the patched head, `ff13c11b`)
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

* Result: the release `libvlc-lgpl-3.7.6-arm64-6` of this repository holds the AAR, its
  `SHA256SUMS`, `license-report.txt` and `build-info.txt`. The app's build
  (`app/build.gradle.kts`) downloads that AAR and refuses it unless its SHA-256
  is `e7d38518cb7b88250100b286eb465adf0fcccf5da4c7a11a28b5211c000dcc30`.

## What was left out, and what it costs

In the build: 253 VLC modules, every one declaring LGPL-2.1-or-later, and the
third-party libraries listed in `license-report.txt` (FFmpeg reports itself
"LGPL version 2.1 or later"; the others are LGPL or permissive — BSD, MIT,
ISC, zlib, libpng, the FreeType licence).

Left out because they declare the GPL (26 modules) — none is used to play a
file on the phone:

| Left out | What it did | Cost here |
|---|---|---|
| lua | scripts that read web playlists (YouTube pages and similar) | none: local files and the NAS only |
| access_realrtsp, real, vod_rtsp | RealMedia streams and files, RTSP serving | none: no RealMedia in Poom's list |
| dolby_surround_decoder, headphone_channel_mixer, mono | Dolby Surround matrix decoding, headphone virtual surround, mono down-mix | small: stereo plays as stereo; VLC's LGPL mixers (simple, trivial) down-mix surround |
| rotate | a video filter that turns the picture by hand | none: the kiosk turns the screen, not the picture |
| stream_out_rtp, stream_out_cycle | streaming out to the network | none |
| hotkeys, gestures, oldrc, motion, netsync, dummy | desktop controls | none: the kiosk has its own |
| logger, file_logger, syslog, stats | logs and statistics | none: the kiosk logs nothing about files |
| audioscrobbler, export, podcast, sap, mediadirs, t140 | scrobbling, playlist export, podcasts, SAP discovery, media folders, T.140 text | none |

Also left out on purpose: **zvbi** (DVB teletext), because its own README
calls the project GPL-2+ with only some files under the LGPL. VLC's LGPL
`telx` module still decodes teletext subtitles.

Advertising clause: the FreeType library asks for this notice — Portions of
this software are copyright © The FreeType Project (www.freetype.org). All
rights reserved.
