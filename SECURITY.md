# Security Policy — Flow Music Player

## Supported Versions

Security fixes and stability updates are provided for the latest release of **Flow Music Player** (`2.0.x`).

| Version | Supported          |
| ------- | ------------------ |
| 2.0.x   | :white_check_mark: |
| < 2.0   | :x:                |

---

## Privacy & Threat Model

Flow Music Player operates **100% offline** with **zero network permissions** (`android.permission.INTERNET` is never requested). Security auditing focuses on:

1. Safe parsing of local audio containers, tags (`jaudiotagger`), and `.lrc` lyric files.
2. Bounded memory allocation when decoding untrusted media headers (`AIFF`, `CAF`, `DSF/DFF`, `MKV`, `MP4`, `ID3v2`, `Vorbis`).
3. Scoped local storage and `MediaBrowserServiceCompat` / `AppWidgetProvider` intent validation.

---

## Reporting a Vulnerability

If you discover a security issue in Flow Music Player:

1. Avoid posting exploit payloads publicly before a fix is available.
2. Provide a concise description, reproduction steps, sample container metadata (if applicable), and target OS/Android API level.
3. For vulnerabilities inherited from the upstream base project, you may also check upstream advisories at [MrDemonc/Lune](https://github.com/MrDemonc/Lune).
