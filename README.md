# Teal

Video and audio downloader for Android, built on [yt-dlp](https://github.com/yt-dlp/yt-dlp).

Paste a link, pick a quality, and Teal fetches it in the background with a
progress notification. Native Kotlin and Jetpack Compose throughout — one
activity, no fragments, no WebView.

Dark UI with teal text and teal-outlined inputs, fixed rather than dynamic so the
app always looks like itself.

## Features

- Download video or extract audio from any site yt-dlp supports
- Format picker showing real heights, frame rates, codecs and sizes, taken from
  `-J` metadata rather than guessed from a format string
- Maximum-compatibility mode: H.264 video and AAC audio muxed into `.mp4`, which
  plays on anything; or best-quality mode, which takes whatever the source offers
- Resolution cap with a fallback selector, so asking for 1080p on a 720p source
  degrades instead of failing
- Playlist handling: fetch a playlist, then download one entry or a range
- Post-processing through the bundled ffmpeg: embed metadata, embed thumbnail,
  write or embed subtitles, SponsorBlock segment removal
- aria2c as an optional multi-connection external downloader
- Foreground service with live progress, speed and ETA; survives leaving the app
- Concurrent download queue with a configurable limit
- Share-sheet and "open with" entry points: share a link to Teal from any app
- Download history in Room, with reopen, share, delete and download-again
- Reusable command templates: raw yt-dlp arguments appended last, so they can
  override anything the UI produced
- Files published to public `Downloads/Teal` via MediaStore, or kept app-private

## Install

Grab the APK for your device's ABI from the
[latest release](https://github.com/owaiskhan-ash/Video-Downloader/releases) —
`arm64-v8a` for almost any phone made since 2017, `armeabi-v7a` for older 32-bit
devices, `x86_64` for emulators. Per-ABI APKs are roughly half the size a
universal one would be, because the Python, ffmpeg and aria2c native libraries
are the bulk of it.

Since v0.4.0 these are **release** builds: `debuggable` off, application id
`com.teal.downloader` with no `.debug` suffix, signed with the key in
[`signing/`](signing/README.md). Earlier releases were debug builds under
`com.teal.downloader.debug`, so **upgrade from v0.3.0 or older means uninstalling
first** — Android treats a different application id as a different app, and the
download history does not carry over.

The signing key is committed to this public repository, which was a deliberate
trade to get signed releases without secrets infrastructure. It means anyone can
produce an APK that Android would accept as an update to Teal, so verify what you
install against the published certificate fingerprint:

```bash
apksigner verify --print-certs teal-v0.4.0-arm64-v8a.apk
# Signer #1 certificate SHA-256 digest must match signing/README.md
```

Every release also ships `SHA256SUMS.txt`. Adding a `TEAL_KEYSTORE_B64`
repository secret moves signing to a private key with no change to any existing
install — see [`signing/README.md`](signing/README.md).

Release builds are **not minified**, on purpose: R8 renames classes, and
`youtubedl-android` crosses a JNI boundary where native code resolves classes by
name, so a rename would fail at runtime rather than at build time. The keep rules
for switching it on are already in `app/proguard-rules.pro`.

## Build

CI does it on every tag:

```bash
git tag v0.4.0 && git push origin v0.4.0
```

Locally, with JDK 17 and the Android SDK:

```bash
gradle :app:testDebugUnitTest      # JVM unit tests
gradle :app:assembleRelease        # -> app/build/outputs/apk/release/*.apk
```

There is deliberately no Gradle wrapper: `gradle-wrapper.jar` is a binary blob in
git, and `gradle/actions/setup-gradle` pins the version instead.

| | |
|---|---|
| Gradle | 8.9 |
| AGP | 8.7.2 |
| Kotlin | 2.0.20 (Compose compiler as its own plugin, as Kotlin 2.0 requires) |
| Compose BOM | 2024.10.01 |
| compileSdk / targetSdk | 35 |
| minSdk | 24 (Android 7.0) |
| yt-dlp for Android | `io.github.junkfood02.youtubedl-android` 0.17.3 (library, ffmpeg, aria2c) |

## How it fits together

```
Compose UI ──> MainViewModel ──> DownloadManager ──> YtdlpManager ──> yt-dlp (JNI)
                                     │                                    │
                                     ├──> DownloadService (notification)   └──> ffmpeg / aria2c
                                     ├──> Room (history, templates)
                                     └──> FileUtil (stage → MediaStore)
```

`CommandBuilder` is the one place a yt-dlp command line is decided. It is pure —
no `Context`, no I/O — which is why the argument generation is the part covered by
unit tests rather than left to manual checking on a device.

Downloads are staged in app-private storage first, because yt-dlp is a native
process that needs a real filesystem path and cannot write into MediaStore. Once
complete, the file is published to `Downloads/Teal`. If publishing fails, the
staged copy is kept rather than deleted, so a download is never lost to a
storage hiccup.

## Status

**Verified:** JVM unit tests for command generation, progress-line parsing and
display formatting run in CI on every push; the APK builds and its contents are
listed by the workflow.

**Not verified:** nothing here has run on a device or emulator. The Compose UI,
the yt-dlp JNI calls, the foreground service and the MediaStore publish step are
written and reviewed, not executed. Treat the first launch as untested.

## Relationship to Seal

Teal's feature set is modelled on [Seal](https://github.com/junkfood02/Seal), and
it is an independent implementation: no Seal source was copied, and no Seal code
is in this repository. It does use the same published yt-dlp-for-Android library
that Seal's author maintains, which is a normal Maven Central dependency.

That library, yt-dlp, ffmpeg and aria2c are all GPL-licensed, so **Teal is
GPL-3.0** — see [LICENSE](LICENSE).

## Legal

Only download content you have the right to save: your own uploads, Creative
Commons or public-domain material, or anything the site's terms permit. Many
platforms prohibit downloading; age-restricted, private and members-only content
will be refused by the site, not by this app.
