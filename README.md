# Flow Music Player

**Flow** (**Flow Music Player**) is an ultra-lightweight, audiophile-grade multiplatform offline music player for **Android (API 24+)**, **Windows**, and **Linux**.

> **Open-Source Fork Attribution (GPLv3):** Flow Music Player is an independent multiplatform fork of [**Lune**](https://github.com/MrDemonc/Lune) originally created by **MrDemonc**, licensed under the GNU General Public License v3.0.

---

## Highlights & Audiophile Capabilities

- **ReplayGain 1.0/2.0, EBU R128 & Real-Time BS.1770 LUFS Normalization**:
  - Shared `commonMain` tag reader (`ReplayGainTagService` powered by `jaudiotagger`) supporting `ReplayGain`, `R128_TRACK_GAIN` / `R128_ALBUM_GAIN` (`Q7.8`), Apple `iTunNORM` SoundCheck, and direct `LUFS` / `INTEGRATED_LOUDNESS` tags across ID3v2, Vorbis/FLAC/Opus, MP4/M4A/ALAC, APEv2, WAV, AIFF, and DSF.
  - Real-time streaming ITU-R BS.1770-4 K-weighted LUFS analyzer (`StreamingLufsAnalyzer`) and sparse window fallback for untagged tracks, complete with adjustable preamp sliders and anti-clipping true-peak protection.
- **Sample-Accurate Gapless Playback & Harmonic Crossfade**:
  - Pre-buffers the next track in the Media3 ExoPlayer queue (`ExtendedFormatPlayer`) and Desktop `SourceDataLine` PCM pipeline for seamless zero-gap album transitions (`Xing`/`LAME`/`iTunSMPB`, FLAC, ALAC, Opus, WAV, AIFF, CAF, DSD), with Smart Album Gapless priority when Crossfade/Automix is enabled.
- **Conflict-Free Multi-Artist & Multi-Genre Engine**:
  - Configurable delimiter splitting (`;`, `,`, `/`, `\`, `|`, `&`, `ft.`, `feat.`, `featuring`, `con`, `with`, `vs.`, `x`) protected by a built-in + user-editable **Protected Artist/Genre Whitelist** (preserving names like *AC/DC*, *Tyler, The Creator*, *Earth, Wind & Fire*, *R&B*) and automatic featured-artist extraction from track titles.
- **Universal Format & Multichannel Support (Android, Windows & Linux)**:
  - Plays **24+ audio formats and containers**: `ALAC`, `AIFF/AIF/AIFC`, `APE`, `WavPack (WV)`, `DSD (DSF/DFF)`, `CAF`, `M4A`, `MP4`, `MKV/MKA`, `WebM`, `Opus` (stereo & 5.1/7.1 multichannel with ITU-R BS.775 stereo downmix), `E-AC3`, `AC-3`, `AC-4`, `DTS`, `WMA`, `FLAC`, `WAV`, `OGG`, `AAC`, and `MP3`.
  - SSE2-safe FFmpeg PCM bridge on Windows/Linux (zero AVX requirement — runs smoothly on legacy dual-core CPUs such as Intel Pentium E5800 + GeForce GT 610) plus pure-Kotlin native AIFF/CAF/DSD decoders on both Android and PC.
- **Extreme Memory, Battery & Modest-Hardware Optimization**:
  - Three hardware profiles (*Ultra-Saver*, *Light Mode*, *Full*) with automatic hardware detection, adaptive layouts from `320dp` phones and `5:4` (`1280x1024`) monitors up to ultrawide displays, `< 128–256 MB` RAM footprint on Android, and `< 256 MB` RAM footprint on Desktop (`G1GC` compact heap).
- **100% Offline Privacy**:
  - Zero `INTERNET` permission, zero trackers, zero telemetry, zero ads, and full localization across 15 languages.

---

## How to Build

### Android APK
```bash
gradle :app:assembleDebug
```

### Desktop Multiplatform (Windows & Linux)
```bash
# Run Flow Music Player on Windows or Linux
gradle -PenableDesktop=true :desktop:run

# Package native installers (.msi/.exe on Windows, .deb/.AppImage on Linux)
gradle -PenableDesktop=true :desktop:packageDistributionForCurrentOS
```

---

## Credits & License

- **Flow Music Player**: Multiplatform audiophile architecture, ReplayGain/LUFS engine, Media3 Gapless pipeline, Multi-Tag parser, native container decoders, and Desktop KMP port.
- **Upstream Base Project**: Forked from [**Lune**](https://github.com/MrDemonc/Lune) by **MrDemonc** (UX testing credits to **Desukia**).
- **License**: [GNU General Public License v3.0 (GPLv3)](LICENSE).
