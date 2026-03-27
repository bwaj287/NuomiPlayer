<p align="right">
  <a href="./README.zh-CN.md">中文</a> | <b>English</b>
</p>

# XiaoLaoWang Player

XiaoLaoWang Player is an Android Auto companion app that mirrors playback from phone music apps into a local Android Auto-compatible `MediaSession`.

This fork is based on the original [charlottejas/NuomiPlayer](https://github.com/charlottejas/NuomiPlayer) project, but the current focus is practical KuGou Music support for day-to-day in-car use.

## What This Fork Does

- Uses KuGou Music (`com.kugou.android`) as the primary source
- Keeps QQ Music as a fallback source
- Mirrors play, pause, previous, next, seek, album art, title, artist, duration, and queue-friendly state into Android Auto
- Tries to capture lyrics from:
  - exposed `MediaSession` metadata and extras
  - notification extras
  - KuGou's visible UI through an accessibility service
- Pushes the current lyric line into the mirrored local metadata so Android Auto can display it
- Includes an on-phone diagnostics panel and a simple car keep-awake dim mode for testing

## Current Status

This repository builds successfully from source and the current debug APK is usable for real-device testing.

Verified locally:

- `./gradlew :mobile:assembleDebug`
- `mobile/build/outputs/apk/debug/mobile-debug.apk`

Current behavior:

- Playback mirroring is stable
- KuGou lyrics are supported experimentally
- The most reliable lyric path is accessibility-based capture from the visible KuGou player UI
- Android Auto layout is still controlled by the host, so text size and exact line placement cannot be fully customized

## How It Works

Runtime flow:

1. The phone app listens for active `MediaSession`s.
2. It prefers KuGou and falls back to QQ Music if needed.
3. Metadata, playback state, and lyric candidates are forwarded to a local mirror service.
4. The local `MediaBrowserServiceCompat` exposes a standard Android Auto media app.
5. The current lyric line is injected into mirrored metadata so the car host renders it in the Now Playing template.

Lyric sources currently attempted:

- Standard system metadata
- Notification extras
- Accessibility scanning of visible KuGou lyric text

## Limitations

- KuGou does not expose a stable third-party background lyric API on stock Android, so real-time lyrics mostly depend on visible on-screen text.
- If the lyric text is no longer visible, lyric updates may stop.
- Locking the phone stops accessibility-based lyric updates.
- The built-in car keep-awake dim mode only works while XiaoLaoWang Player itself is in the foreground.
- Android Auto decides the final layout. We can influence the title, subtitle, artwork, queue, and custom buttons, but not build a fully custom lyrics screen.

## Modules

- `mobile`: phone UI, notification listener, accessibility lyric capture, and test utilities
- `shared`: mirrored `MediaBrowserServiceCompat`, playback state mapping, queue-friendly metadata, and lyric overlay logic
- `automotive`: remaining car-specific resources from the original structure

## Build

Requirements:

- JDK 17
- Android SDK 34
- Android command-line tools or Android Studio

Build:

```bash
./gradlew :mobile:assembleDebug
```

APK:

- `mobile/build/outputs/apk/debug/mobile-debug.apk`

Only `mobile-debug.apk` needs to be installed for the current workflow.

## Setup

1. Install `mobile-debug.apk` on the phone.
2. Grant Notification Access to XiaoLaoWang Player.
3. Open the app and enable the accessibility service entry labeled `酷狗歌词抓取`.
4. Enable Android Auto developer mode.
5. In Android Auto developer settings, allow unknown sources.

## Recommended Test Flow

1. Start playback in KuGou Music.
2. Keep KuGou on a page where lyric text is visibly rendered.
3. Open XiaoLaoWang Player and confirm the bottom diagnostics show that a source was detected.
4. Optionally enable `车载常亮` if you want the phone screen to stay awake and dim after 10 seconds of inactivity while the app is foregrounded.
5. Connect to Android Auto and open XiaoLaoWang Player from the media app list.

Expected results:

- playback controls work in the car
- album art and track metadata are mirrored
- when lyric capture succeeds, Android Auto shows the current lyric line in the Now Playing template

## Notes

- This project does not include music resources or streaming APIs.
- It only mirrors information already exposed by the source player or visible on the source player's UI.
- The GitHub repository name is still `NuomiPlayer`, but the installed app name is now `小老王播放器`.

## Credits

- Original project: [charlottejas/NuomiPlayer](https://github.com/charlottejas/NuomiPlayer)
- Phone UI inspiration/code reuse: [mardous/BoomingMusic](https://github.com/mardous/BoomingMusic)
