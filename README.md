<p align="right">
  <a href="./README.zh-CN.md">中文</a> | <b>English</b>
</p>

# NuomiPlayer - Android Auto Music Companion

🚗🎶 NuomiPlayer is an Android Auto companion app that extends in-car playback experience by **mirroring media metadata and controls from your phone**, providing a cleaner UI, richer info, and **time-synced lyrics**.

I built this after realizing that **QQ Music does not support Android Auto**, while many Android Auto–ready apps don’t include the songs I listen to most. Existing workarounds were either outdated or unstable (e.g., frequent crashes), so I decided to build a lightweight and reliable solution for my own daily driving.

> Note: I had little prior Android app experience. To accelerate development, part of the **phone playback UI** was adapted from the open-source project **Booming Music** (see Credits). If any usage violates the original license, please contact me and I will promptly fix or remove the relevant code.

## Disclaimer

This project is for **personal learning and research** only.  
- It does **not** include any music resources.
- It does **not** provide any music streaming API.
- Media data is obtained from system signals (e.g., **MediaSession / notifications / broadcast**), and is intended to avoid copyright infringement.

If any third-party code usage raises licensing concerns, please reach out and I will respond quickly.

## Features

- 🚘 Android Auto playback screen support
- 🎵 Reads media metadata from most playback apps (based on system MediaSession / notifications)
- ⏯️ Play / Pause / Previous / Next controls
- ⏩ Seek bar with drag-to-seek support
- 🖼️ Title / Artist / Album art display
- 📝 Time-synced lyrics view (Android Auto)

## Screenshots

### 🚘 Android Auto Playback
![Android Auto Playback](screenshot/auto.jpg)

### 📝 Android Auto Lyrics
![Android Auto Lyrics](screenshot/lyrics.jpg)

### 📱 Phone UI
<div style="display:flex; gap:10px;">
  <img src="screenshot/mobile.jpg" width="360"/>
  <img src="screenshot/mobile_1.jpg" width="360"/>
</div>

## Changelog

### 1.4.1
- Removed some permission requirements

### 1.4.0
- Upgraded from a platform-specific adaptation to a **general solution**, now compatible with **most playback apps** (music / podcast / video) via system MediaSession/notifications
- Fixed multiple edge cases and reduced crash probability

### 1.3.1
- Fixed inability to open NetEase Cloud Music

### 1.3.0
- Added NetEase Cloud Music mirroring support

### 1.2.0
- Added playback mode switching (in order / single loop / shuffle)
- Added “enable lyrics mode by default” option

### 1.1.0
- Added real-time lyrics synced with playback progress

### 1.0.0
- First stable release
- QQ Music mirroring support
- Android Auto mode
- Basic playback controls

## APK Downloads

Download prebuilt APKs:
- 📦 NuomiPlayer 1.4.1: https://github.com/charlottejas/NuomiPlayer/raw/main/糯米播放器1.4.1.apk  
  (Fewer permissions; recommended if 1.4.0 fails to install on some OEM devices)
- 📦 NuomiPlayer 1.4.0: https://github.com/charlottejas/NuomiPlayer/raw/main/糯米播放器1.4.0.apk  
  (General solution; recommended for most users)
- 📦 NuomiPlayer 1.3.1: https://github.com/charlottejas/NuomiPlayer/raw/main/糯米播放器1.3.1.apk
- 📦 NuomiPlayer 1.2.0: https://github.com/charlottejas/NuomiPlayer/raw/main/糯米播放器1.2.0.apk
- 📦 NuomiPlayer 1.1.0: https://github.com/charlottejas/NuomiPlayer/raw/main/糯米播放器1.1.0.apk
- 📦 NuomiPlayer 1.0.0: https://github.com/charlottejas/NuomiPlayer/raw/main/糯米播放器%201.0.0.apk

> Make sure Android Auto developer settings allow installing apps from unknown sources (see Run Guide).

## Run Guide

1. Enable Android Auto **Developer Mode**  
   Official guide: https://developer.android.com/training/cars/testing

2. In Android Auto developer settings, enable **Unknown sources**

3. Start Android Auto emulator or connect your car head unit

4. Start playing any track in your phone music app (e.g., QQ Music).  
   NuomiPlayer will mirror the playback info and controls in Android Auto.

> NuomiPlayer listens to system media signals. Make sure a playback app is actively playing.

## Tech Stack

- Java & Android SDK
- Android Auto (`automotive` module)
- MediaSession & PlaybackStateCompat
- BroadcastReceiver-based media signal parsing
- Custom icons & theming

## Credits

Special thanks to **Booming Music**: https://github.com/mardous/BoomingMusic  
Parts of the phone UI were adapted from this project.


