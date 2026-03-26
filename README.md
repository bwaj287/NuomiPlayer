<p align="right">
  <a href="./README.zh-CN.md">中文</a> | <b>English</b>
</p>

# NuomiPlayer

NuomiPlayer is an Android Auto companion app that mirrors playback from phone music apps into a local Android Auto compatible `MediaSession`.

This fork is based on the original [charlottejas/NuomiPlayer](https://github.com/charlottejas/NuomiPlayer) project and is currently focused on:

- KuGou Music (`com.kugou.android`) as the default source
- QQ Music fallback support
- Android Auto playback mirroring
- lyric payload detection from `MediaSession` metadata and notification extras

## Current Status

This repository now builds again from source.

Verified locally:

- `./gradlew clean lintDebug assembleDebug`
- `mobile` debug APK builds successfully
- `automotive` debug APK builds successfully

Important limitation:

- KuGou playback control and metadata mirroring are implemented
- KuGou lyric display is still experimental and depends on whether KuGou exposes usable lyric payloads through system metadata or notifications
- If no lyric payload is exposed, playback control and track info still work

## Features

- Android Auto media app entry
- Play, pause, previous, next, seek
- Album art, title, artist, duration mirroring
- On-phone status panel for session and lyric detection
- Timed lyric parsing for LRC and common KRC-like formats
- Android Auto lyric overlay through mirrored local metadata

## How It Works

The project has three modules:

- `mobile`: phone app UI and notification listener
- `shared`: mirrored `MediaBrowserServiceCompat` and lyric overlay logic
- `automotive`: Android Auto app shell

Runtime flow:

1. The phone app listens for active media sessions.
2. It prefers KuGou, and falls back to another active player if needed.
3. Metadata, playback state, and lyric candidates are forwarded to the local mirror service.
4. The local service exposes a standard Android Auto media session.
5. When timed lyrics are available, the current lyric line is injected into mirrored metadata for Android Auto display.

## Build

Requirements:

- JDK 17
- Android SDK 34
- Android command-line tools or Android Studio

Build commands:

```bash
./gradlew clean lintDebug assembleDebug
```

APK outputs:

- `mobile/build/outputs/apk/debug/mobile-debug.apk`
- `automotive/build/outputs/apk/debug/automotive-debug.apk`

## Install And Test

1. Install `mobile-debug.apk` on the phone.
2. Grant Notification Access to NuomiPlayer.
3. Enable Android Auto developer mode.
4. In Android Auto developer settings, allow unknown sources.
5. Open KuGou Music and start playback.
6. Open NuomiPlayer on the phone and confirm the status text shows that a media session was detected.
7. Start Android Auto and open NuomiPlayer from the media apps list.

If KuGou exposes a lyric payload, the app will try to show synchronized lyrics in Android Auto.

## Notes

- This project does not include music resources or streaming APIs.
- It only mirrors information already exposed by the source player through Android system media surfaces.
- Historical APKs from the upstream project are still present in the repository root for reference, but they do not represent the current fork state.

## Credits

- Original project: [charlottejas/NuomiPlayer](https://github.com/charlottejas/NuomiPlayer)
- Phone UI inspiration/code reuse: [mardous/BoomingMusic](https://github.com/mardous/BoomingMusic)
