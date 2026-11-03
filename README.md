# GrabAll

Multi-site video and audio downloader for Android (personal project).

Download videos and music from many sites — not just YouTube — straight to
your phone. Paste a link, pick a format, done. No server involved: everything
runs on-device, and your Google login (when added) stays on your phone only.

> Status: project skeleton. The app builds and CI produces APKs. The download
> engine (yt-dlp), Google login, queue and multi-site support land next.

## Features (planned)

- Paste a link, download as MP4 video or MP3 audio
- Quality picker (resolution / audio bitrate)
- Playlists and batch downloads, with pause and resume
- Google login kept on-device, so sessions stay trusted (no manual cookies)
- Multi-site support, added one site at a time (YouTube first)

## Distribution

This app is **not** on Google Play (YouTube downloaders violate Play policy).
Releases are published here on GitHub: download the APK from
[Releases](../../releases) and sideload it (allow "install from unknown
sources" for your browser/file manager).

## Building

CI builds automatically (see `.github/workflows/android.yml`):

- Push to `main` → debug APK uploaded as a workflow artifact.
- Push a tag like `v0.1.0` → release APK built and published to
  [Releases](../../releases).

Local build (Gradle 8.9, JDK 17, Android SDK with platform 34):

```sh
gradle :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Release signing

Release APKs are signed only when these repository secrets exist:

- `KEYSTORE_BASE64` — base64 of the release keystore file
- `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

Without them, `assembleRelease` still succeeds but produces an unsigned APK.
**Never commit a keystore or password to the repo** — `.gitignore` blocks
`*.jks` / `*.keystore`. Back up the keystore somewhere safe: losing it means
future updates require a full reinstall.

## Requirements

- Android 5.0+ (API 21), including 32-bit ARMv7 devices
- No account, no server, no tracking
