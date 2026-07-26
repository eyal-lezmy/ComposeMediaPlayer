# Bundled libVLC natives

Official prebuilt **libVLC 3.0.18** binaries, vendored so the desktop backend can demux containers/codecs
the platform-native player can't (e.g. Matroska with HEVC/AC3). Loaded only from here — never a
user-installed VLC. See `VlcNativeInit` for the load sequence (libvlccore preloaded by absolute path to
satisfy `@rpath`, plugins via `VLC_PLUGIN_PATH`).

## Layout

- `darwin-aarch64/lib/{libvlc,libvlccore}.dylib` — libVLC 3.0.x, macOS arm64.
- `darwin-aarch64/plugins/*.dylib` — the plugin set.
- `darwin-aarch64/plugins.index` — plain list of plugin filenames; `VlcNativeInit` reads it to extract
  each plugin from the jar at runtime (a jar has no directory listing).

Only macOS arm64 is bundled. Windows (`win32-x86-64/`), Linux (`linux-x86-64/`), and macOS x86-64 are
not shipped yet.

## Decisions (change here + note why)

- **Full plugin set shipped (~78 MB, ~339 plugins), not trimmed.** Correctness over size: a wrong trim
  fails playback silently and is painful to debug. Trimming to demux(mkv/mp4/ts/avi/es) + codec(avcodec)
  + access(http/https/file) + vout(vmem) + coreaudio + packetizers/chroma is a follow-up once the needed
  set is confirmed empirically. Re-generate by copying from a VLC 3.0.x install and refreshing
  `plugins.index`.
- **Licensing.** The full set includes GPL plugins, so the shipped bundle is effectively GPL. To keep an
  application LGPL-clean, trim out the GPL plugins. libvlccore + libavcodec/matroska demux are LGPL-2.1+
  (dynamic link — fine).
- **Codesign/notarization.** The dylibs run unsigned locally. Distribution on macOS needs codesign +
  hardened-runtime notarization of every vendored dylib.
- **Binary size in git:** ~78 MB. Consider git-lfs if this becomes a problem.
