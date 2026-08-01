# Bundled libVLC natives

Official prebuilt **libVLC 3.0.18** binaries, vendored so the desktop backend can demux containers/codecs
the platform-native player can't (e.g. Matroska with HEVC/AC3). Loaded only from here — never a
user-installed VLC. See `VlcNativeInit` for the load sequence (libvlccore preloaded by absolute path to
satisfy `@rpath`, plugins via `VLC_PLUGIN_PATH`).

## Layout

- `darwin-aarch64/lib/{libvlc,libvlccore}.dylib` — libVLC 3.0.x, macOS arm64.
- `darwin-aarch64/plugins/*.dylib` — the plugin set (65 files).
- `darwin-aarch64/plugins.index` — plain list of plugin filenames; `VlcNativeInit` reads it to extract
  each plugin from the jar at runtime (a jar has no directory listing). **The index is the authority**:
  a plugin present on disk but absent from the index is never extracted, so the index alone is enough to
  add or drop one while testing.

Only macOS arm64 is bundled. Windows (`win32-x86-64/`), Linux (`linux-x86-64/`), and macOS x86-64 are
not shipped yet (tasks 051/052 — they port the keep-list below rather than re-deriving it).

## Decisions (change here + note why)

- **Trimmed to an LGPL-clean 65-plugin set (28 MB), down from the full 339 / 78 MB** — task 104,
  ADR 0026 (`docs/adr/0026-lgpl-clean-libvlc-bundle.md` in the app repo). Okamp.tv targets the iOS App
  Store, which is incompatible with the GPL, so every GPL plugin is gone; everything else was removed
  because nothing in this app reaches it. Each removal was validated against the media matrix below —
  no group was dropped on reasoning alone, because the failure mode of a wrong trim is a *silent* black
  picture, not an error.
- **Licensing of what remains.** libvlccore is LGPL-2.1+; `avcodec` statically links FFmpeg built
  LGPL-2.1+ (`libavcodec license: LGPL version 2.1 or later`, no x264/x265 linked in). Every remaining
  plugin was checked one by one, binary by binary — the VLC module's own licence sentence *and* the
  statically linked contrib, because four plugins (`a52`, `dca`, `mad`, `libmpeg2`) declare an LGPL
  wrapper while linking a GPL library. **Adding a plugin back means redoing that check** — the filename
  is not evidence.
- **Codesign/notarization.** The dylibs run unsigned locally. Distribution on macOS needs codesign +
  hardened-runtime notarization of every vendored dylib.
- **Binary size in git:** 28 MB (was 78 MB). git-lfs is no longer worth it for one platform; revisit if
  051/052 land three more.

## What is kept, and why

| Group | Plugins | Why |
|---|---|---|
| access | `filesystem` `http` `https` `gnutls` `securetransport` `inflate` | Panel URLs are plain HTTP; `https` needs a TLS backend, and `gnutls` is the one VLC picks by default. **Not covered by the matrix** (the fixture server is HTTP-only) — kept because the app must survive an https panel. |
| stream filters | `cache_read` `cache_block` `prefetch` `noseek` `chain` | The buffering chain every network read goes through. |
| demux | `ts` `mp4` `mkv` `avi` `adaptive` | Exactly the containers the panel serves: mkv 11 173 / mp4 1 471 / avi 66 / ts 4 titles (`xtream-code-doc/XtreamCode-API-spec.md`), plus `adaptive` for HLS live and timeshift. |
| decode | `avcodec` `videotoolbox` `cvpx` | `avcodec` decodes everything (H.264, HEVC, MPEG-2, AAC, AC3, E-AC3, DTS, MP3, MP2 — all measured, see the matrix); `videotoolbox` is the macOS hardware decoder and really runs — with `avcodec` removed the picture still decodes and only the sound dies. `cvpx` converts its CoreVideo buffers. |
| packetizers | `copy` `h264` `hevc` `mpeg4audio` `mpegaudio` `mpegvideo` `a52` `dts` `mpeg4video` `vc1` | Elementary-stream framing inside TS. `a52`/`dts` are VLC's own (LGPL) packetizers — dropping them breaks AC3/DTS *in TS* even though the GPL a52/dca decoders are gone. |
| video out | `vmem` | The callback surface the whole rendering depends on (ADR 0016). Removing it is the control case: frames stop, sound keeps playing. |
| chroma/scale | `swscale` `rv32` `yuv` `yuvp` `grey_yuv` `i420_rgb` `i420_nv12` `i420_yuy2` `i420_10_p010` `i422_i420` `i422_yuy2` `yuy2_i420` `yuy2_i422` | `rv32` feeds the RV32 buffer `vmem` hands to Skia. **`swscale` is not optional**: it is the only module that converts the 10-bit HEVC/HDR channel, and without it that channel plays sound over a black picture — the exact silent shape ADR 0019 is about. |
| deinterlace | `deinterlace` | Interlaced SD channels. LGPL-2.1+, links no contrib (checked in the binary). |
| audio out | `auhal` `amem` | `auhal` is the macOS output the app uses; `amem` is the callback output the matrix measures PCM through (and what a future audio-callback feature would need). |
| audio filters | `audio_format` `simple_channel_mixer` `trivial_channel_mixer` `float_mixer` `integer_mixer` `speex_resampler` `ugly_resampler` | Format conversion, 5.1→stereo downmix and rate conversion. `samplerate` (libsamplerate, 1.5 MB) was dropped — the two remaining resamplers cover it. |
| subtitles | `subsdec` `spudec` `substx3g` `webvtt` `dvbsub` `telx` `zvbi` `cc` `freetype` `libass` `attachment` `blend` | `webvtt` is the one the matrix exercises (HLS renditions). The others are kept for content the fixtures can't produce: DVB subtitles and teletext on real TS channels (`zvbi` is LGPL-2.1+, confirmed in the binary), ASS/SSA in mkv VOD (`libass` + `attachment` for embedded fonts), closed captions. `blend`/`freetype` render them. |

## What was removed, and why

Six groups, each validated by a full matrix re-run before the next was started:

1. **GPL, 43 plugins.** By the module's own header: `access_realrtsp` `audioscrobbler`
   `dolby_surround_decoder` `dummy` `export` `file_logger` `gestures` `headphone_channel_mixer`
   `hotkeys` `logger` `lua` `macosx` `mediadirs` `mono` `motion` `mpc` `ncurses` `netsync` `oldrc`
   `osx_notifications` `podcast` `real` `rotate` `sap` `stats` `stream_out_cycle` `stream_out_rtp`
   `syslog` `t140` `vod_rtsp` `x264` `x26410b` `x265`. Via a statically linked GPL contrib behind an
   LGPL wrapper: `a52` `dca` `mad` `libmpeg2` `postproc` `sid` `dvdread` `dvdnav` `faad` `twolame`.
   **Costs nothing**: `avcodec` decodes AC3, DTS, MP3, MPEG-2 and AAC, proven case by case.
2. **Encoding / muxing / streaming out / capture, 42.** `access_output_*`, `stream_out_*`, `mux_*`,
   `avcapture`, `avaudiocapture`, `screen`, `shm`, `demuxdump`, `record`, `demux_chromecast`.
3. **Transports and desktop services, 34.** `access_concat` `access_imem` `access_mms` `access_srt`
   `rist` `ftp` `sftp` `nfs` `satip` `live555` `rtp` `rtpvideo` `sdp` `upnp` `bonjour` `dcp` `vcd`
   `cdda` `remoteosd` `addons*` `*_keystore` `keychain` `fingerprinter` `taglib`
   `nsspeechsynthesizer` `console_logger`, and the `*dummy` fallbacks.
4. **Video/audio effects, visualizations, OSD, 71.** Everything under `video_filter`/`audio_filter`
   the app never exposes, plus `visual`/`goom`, the MIDI outputs, `scaletempo*`, the alternate audio
   outputs (`afile` `spdif` `tospdif`) and libsamplerate. Also the native macOS video outputs
   (`vout_macosx` `caopengllayer` `glconv_cvpx`): rendering goes through `vmem` only, fullscreen and
   PiP included.
5. **Decoders `avcodec` already covers, and containers/subtitle formats no XC panel serves, 76.**
   `aom` `dav1d` `vpx` `schroedinger` `theora` `vorbis` `speex` `flac` `opus` `mpg123` `g711` `lpcm`
   `araw` `adpcm` `aes3` `tta` `mod` `gme` `jpeg` `png` `mjpeg` `image` `ogg` `wav` `au` `aiff` `caf`
   `voc` `xa` `raw*` `nsv` `nuv` `pva` `ty` `vhs` `nsc` `vdr` `asf` `hds` `archive` `decomp`
   `directory_demux` `folder` `imem`; subtitle formats `aribsub` `cvdsub` `svcdsub` `stl` `textst`
   `kate` `vobsub` `subsusf` `subtitle` `subsdelay` `scte18` `scte27` `ttml` `xml`; `libbluray`
   (LGPL, but optical media); `skiptags`; the `dirac`/`av1`/`flac`/`mlp` packetizers.
6. **Leftovers, 8.** `udp` `tcp` `ps` `playlist` `scale` `es` `mpgv` `h26x`.

## The media matrix

The empirical half of the trim, and the thing 051/052 should re-run rather than re-invent:
`tools/vlc-plugin-matrix/` in the app repo (`README.md` there has the exact command line). It loads a
bundle the way `VlcNativeInit` does, plays each case through the same `vmem` callback the app renders
with plus an `amem` audio callback, and measures **frames delivered + a non-black check**, **decoded PCM
above the silence floor**, and **a real seek** — never "it launched".

Against `iptv-server` (`./gradlew :iptv-server:run`): live `.ts`, the same channel as HLS (two audio
renditions + a WebVTT subtitle rendition), the 4K/HDR HEVC 10-bit channel (`stream_id` 24083), a
timeshift window in both HLS and TS, an mp4 movie, an mkv movie, a series episode. Plus six
locally-generated files no fixture carries, which is what makes the GPL decoder removals provable:
MPEG-2 + MP2 interlaced in TS, H.264 + AC3 in TS, H.264 + MP3 in TS, HEVC + AC3 in mkv, H.264 + DTS in
mkv, H.264 + E-AC3 in mkv.

Its sensitivity was verified by removing a plugin known to be required and checking the matrix goes red:
`vmem` → 0 frames with sound intact; `ts` → nothing plays; `avcodec` → picture survives on
`videotoolbox`, sound dies; `swscale` → only the 4K/HDR case goes black.

## Regenerating `plugins.index`

```bash
cd darwin-aarch64
ls plugins | grep '\.dylib$' | LC_ALL=C sort > plugins.index   # every file present
```

To try a removal without deleting anything, drop the line from `plugins.index` and re-run the matrix —
`VlcNativeInit` extracts only what the index names. Delete the files once the trim is validated.
`VlcNativeInit` caches the extracted bundle in `$TMPDIR/composemediaplayer-vlc/`; **delete that
directory** between runs or the previous plugin set is still on disk.
