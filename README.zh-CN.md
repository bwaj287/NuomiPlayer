<p align="right">
  <b>中文</b> | <a href="./README.md">English</a>
</p>

# 小老王播放器

小老王播放器是一个 Android Auto 音乐伴侣应用。它会把手机里音乐 App 暴露出来的播放状态，镜像成一个 Android Auto 可识别的本地 `MediaSession`，从而让原本不完整支持 Android Auto 的播放器，也能在车机里显示和控制。

这个 fork 基于原项目 [charlottejas/NuomiPlayer](https://github.com/charlottejas/NuomiPlayer)，当前重点是：

- 默认优先接入酷狗音乐 `com.kugou.android`
- 保留 QQ 音乐兜底兼容
- 支持 Android Auto 播放控制镜像
- 自动探测 `MediaSession` metadata 和通知 extras 里的歌词负载

## 当前状态

这份仓库现在已经恢复到可从源码构建的状态。

本地已验证通过：

- `./gradlew clean lintDebug assembleDebug`
- `mobile` 模块 debug APK 可正常构建
- `automotive` 模块 debug APK 可正常构建

需要提前说明的一点：

- 酷狗的播放控制和元数据镜像已经接好
- 酷狗歌词显示目前属于“实验性支持”
- 它是否真的能像 QQ 音乐那样稳定显示歌词，取决于酷狗有没有通过系统 `MediaSession` 或通知把可用歌词字段暴露出来
- 如果没有暴露歌词字段，播放控制、封面、标题、歌手、进度仍然可以工作

## 功能

- Android Auto 媒体应用入口
- 播放 / 暂停 / 上一首 / 下一首 / 拖动进度
- 镜像歌曲标题、歌手、时长、封面
- 手机端显示当前抓取状态和歌词探测状态
- 支持解析 LRC 与常见 KRC 风格时间轴歌词
- 在 Android Auto 中通过本地 metadata 覆盖显示当前歌词行

## 工作原理

项目分成三个模块：

- `mobile`：手机端界面与通知监听服务
- `shared`：本地镜像 `MediaBrowserServiceCompat` 和歌词逻辑

运行链路如下：

1. 手机端监听当前系统里的活跃 `MediaSession`。
2. 优先绑定酷狗音乐，必要时回退到其他活跃播放器。
3. 把元数据、播放状态和歌词候选转发给本地镜像服务。
4. 本地服务暴露一个标准的 Android Auto 媒体服务。
5. 当检测到带时间轴歌词时，把当前歌词行注入本地 metadata，供 Android Auto 展示。

## 构建

环境要求：

- JDK 17
- Android SDK 34
- Android command-line tools 或 Android Studio

构建命令：

```bash
./gradlew clean lintDebug assembleDebug
```

产物路径：

- `mobile/build/outputs/apk/debug/mobile-debug.apk`

实际只需要安装 `mobile-debug.apk`。Android Auto 所需的 service 和 metadata 已经合并进这个 APK。

## 安装与测试

1. 在手机上安装 `mobile-debug.apk`
2. 为小老王播放器开启“通知使用权”
3. 打开 Android Auto 开发者模式
4. 在 Android Auto 开发者设置里允许未知来源应用
5. 打开酷狗音乐并开始播放
6. 打开手机上的小老王播放器，确认底部状态文字已经显示捕获到播放器
7. 启动 Android Auto，在媒体应用列表中打开小老王播放器

如果酷狗确实把歌词负载暴露给系统，这个版本会尝试在 Android Auto 中显示同步歌词。

## 说明

- 本项目不包含任何音乐资源，也不提供流媒体接口
- 它只镜像源播放器已经通过 Android 系统媒体接口暴露出来的信息
- 仓库根目录里保留的历史 APK 主要是上游项目的旧构建产物，仅作参考，不代表当前 fork 的最新状态

## 致谢

- 原项目：[charlottejas/NuomiPlayer](https://github.com/charlottejas/NuomiPlayer)
- 手机端界面参考/复用来源：[mardous/BoomingMusic](https://github.com/mardous/BoomingMusic)
