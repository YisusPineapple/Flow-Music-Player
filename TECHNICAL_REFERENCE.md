# Flow Music Player — Technical Architecture Reference

**Flow Music Player** is structured as a Kotlin Multiplatform workspace sharing a pure-Kotlin zero-bloat audio DSP, metadata, and container engine (`shared/src/commonMain`) across **Android (`:app`)** and **Windows / Linux (`:desktop`)**.

> Forked from [`https://github.com/MrDemonc/Lune`](https://github.com/MrDemonc/Lune) under GPLv3; the legacy Android `namespace` (`com.demonlab.lune`) is preserved strictly for Android resource/database upgrade compatibility.

---

## 1. Shared Multiplatform Core (`shared/src/commonMain`)

| Component | Package | Responsibility |
| --- | --- | --- |
| `ReplayGainTagService` | `core.dsp` | Reads `ReplayGain 1.0/2.0`, `EBU R128` (`Q7.8`), `iTunNORM`, and direct `LUFS` tags via `jaudiotagger` with file-stat caching and computes peak-safe linear playback gain. |
| `ReplayGainEngine` & `StreamingLufsAnalyzer` | `core.dsp` | Real-time ITU-R BS.1770-4 K-weighted LUFS analyzer + sparse PCM window loudness estimator + true-peak anti-clipping limiter. |
| `MultiTagParser` | `core.metadata` | 3-stage conflict-free multi-artist & multi-genre parser with customizable Protected Whitelist and automatic `(ft. ...)` title extraction. |
| `AudioFormatRegistry` | `core.format` | Universal 24+ format/codec registry, Smart Album Gapless planner, and ITU-R BS.775 1..8ch -> Stereo 16-bit LE downmixer. |
| `NativePcmContainerDecoder` | `core.format` | Pure-Kotlin streaming decoder for `AIFF/AIFC`, Apple `CAF`, `AU/SND`, and `DSF/DFF` (`DSD64/128/256` PDM decimation) with zero JNI dependencies. |
| `CrossfadeCurveEngine` & `HarmonicAutomixScorer` | `core.dsp` / `core.ai` | Equal-Power & Hybrid Audiophile crossfade curves, tail silence offset detection, and harmonic track transition scoring. |

---

## 2. Android Application (`:app`)

| Component | File | Responsibility |
| --- | --- | --- |
| `ExtendedFormatPlayer` | `audio/ExtendedFormatPlayer.kt` | Media3 `ExoPlayer` + `MultichannelStereoBalanceProcessor` with proactive next-track playlist pre-buffering for gapless transitions, sample-domain ReplayGain ramping, and native `AudioTrack` container fallback. |
| `MusicService` | `tools/MusicService.kt` | `MediaBrowserServiceCompat` + `MediaSessionCompat` playback service, gapless queue coordinator (`syncExoGaplessQueue`), hardware DSP effects (`Equalizer`, `BassBoost`, `Virtualizer`, `LoudnessEffect`, `ReverbEffect`, `DynamicsEffect`), and foreground notification controller. |
| `ReplayGainScanner` | `audio/ReplayGainScanner.kt` | Multi-tier loudness resolver (`ReplayGainTagService` -> Room/JSON cache -> `MediaExtractor` sparse `MediaCodec` PCM sampling). |
| `LuneWidgetProvider` | `tools/LuneWidgetProvider.kt` | Binder-safe (`RGB_565` / `192px` cap) responsive home-screen widget (`1x2`, `2x2`, `2x3`, `4x2`) with broadcast-safe transport controls and software blur. |
| `FullPlayer` | `ui/player/PlayerComponents.kt` | Adaptive portrait/landscape/tablet maximized player supporting both Cinematic Cover Canvas and Classic Audiophile modes, interactive multi-artist/genre pills, and live codec/ReplayGain badges. |

---

## 3. Desktop Application (`:desktop` — Windows & Linux)

| Component | File | Responsibility |
| --- | --- | --- |
| `Main.kt` | `desktop/Main.kt` | Adaptive Compose Desktop UI supporting `5:4` (`1280x1024`), `4:3`, and widescreen monitors, multi-artist/whitelist editor, ReplayGain controls, and hardware performance profiles (`Ultra-Saver`, `Light`, `Full`). |
| `DesktopAudioEngine` | `desktop/DesktopAudioEngine.kt` | SSE2-safe `FFmpeg` + `NativePcmContainerDecoder` + `JavaSound` `SourceDataLine` audio engine with continuous 64 KB gapless PCM ring, ALSA/PipeWire/USB mixer selection, `ReplayGainTagService`, and live LUFS measurement. |
