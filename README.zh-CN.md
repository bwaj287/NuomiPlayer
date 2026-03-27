<p align="right">
  <b>中文</b> | <a href="./README.md">English</a>
</p>

# 小老王播放器

小老王播放器是一个 Android Auto 音乐伴侣应用。它会把手机里音乐 App 暴露出来的播放状态，镜像成一个 Android Auto 可识别的本地 `MediaSession`，从而让原本不完整支持 Android Auto 的播放器，也能在车机里显示和控制。

这个 fork 基于原项目 [charlottejas/NuomiPlayer](https://github.com/charlottejas/NuomiPlayer)，但当前已经更偏向“实际可在车里测试和使用”的酷狗歌词镜像版本。

## 这个版本能做什么

- 默认优先接入酷狗音乐 `com.kugou.android`
- 保留 QQ 音乐兜底兼容
- 镜像播放 / 暂停 / 上一首 / 下一首 / 拖动进度
- 镜像封面、标题、歌手、时长，以及适合车机显示的播放状态
- 尝试从以下来源抓歌词：
  - 系统 `MediaSession` metadata / extras
  - 通知 extras
  - 酷狗前台可见界面的无障碍文本
- 把当前歌词行注入本地镜像 metadata，供 Android Auto 在播放页展示
- 手机端显示诊断信息，并提供一个简单的 `车载常亮` 暗屏测试模式

## 当前状态

这份仓库已经恢复到可从源码构建、可打包、可真机测试的状态。

本地已验证：

- `./gradlew :mobile:assembleDebug`
- `mobile/build/outputs/apk/debug/mobile-debug.apk` 可正常生成

当前表现：

- 播放控制和元数据镜像已经比较稳定
- 酷狗歌词属于“实验性支持”
- 目前最可靠的歌词来源是无障碍读取“前台可见的酷狗歌词文本”
- Android Auto 的最终排版由车机宿主决定，所以字体大小、行数和位置无法完全自定义

## 工作原理

运行链路：

1. 手机端监听当前系统里的活跃 `MediaSession`。
2. 优先绑定酷狗音乐，必要时回退到其他活跃播放器。
3. 把元数据、播放状态和歌词候选转发给本地镜像服务。
4. 本地服务暴露一个标准的 Android Auto 媒体服务。
5. 当检测到歌词时，把当前歌词行注入本地 metadata，供 Android Auto 展示。

当前尝试的歌词来源：

- 标准系统 metadata
- 通知 extras
- 酷狗前台界面的无障碍文本扫描

## 已知限制

- 酷狗在原生 Android 上没有给第三方开放稳定的后台歌词接口，所以实时歌词大多依赖“前台可见文本”。
- 一旦歌词不再可见，实时歌词更新可能会停止。
- 手机锁屏后，无障碍方案基本无法继续更新歌词。
- `车载常亮` 只在小老王播放器本身位于前台时生效。
- Android Auto 是模板化宿主界面，我们可以影响 title、subtitle、artwork、queue、custom action，但不能自己画一个完全自定义的歌词页。

## 模块

- `mobile`：手机端界面、通知监听、无障碍歌词抓取、测试辅助逻辑
- `shared`：本地镜像 `MediaBrowserServiceCompat`、播放状态映射、队列与歌词覆盖逻辑
- `automotive`：保留的车机资源模块

## 构建

环境要求：

- JDK 17
- Android SDK 34
- Android command-line tools 或 Android Studio

构建命令：

```bash
./gradlew :mobile:assembleDebug
```

产物路径：

- `mobile/build/outputs/apk/debug/mobile-debug.apk`

当前实际只需要安装 `mobile-debug.apk`。

## 首次设置

1. 在手机上安装 `mobile-debug.apk`
2. 为小老王播放器开启“通知使用权”
3. 打开 app，进入系统无障碍设置并启用 `酷狗歌词抓取`
4. 打开 Android Auto 开发者模式
5. 在 Android Auto 开发者设置里允许未知来源应用

## 推荐测试流程

1. 在酷狗音乐里开始播放
2. 尽量停留在“歌词实际可见”的酷狗前台页面
3. 打开手机上的小老王播放器，确认底部状态已经显示捕获到播放器
4. 如有需要，开启 `车载常亮`
   说明：10 秒无操作后会自动把小老王播放器当前窗口调暗，触摸后恢复亮度
5. 连接 Android Auto，在媒体应用列表中打开小老王播放器

预期结果：

- 车机里可以正常播放控制
- 封面、歌曲信息会被镜像过去
- 当歌词抓取成功时，Android Auto 会尝试显示当前歌词行

## 说明

- 本项目不包含任何音乐资源，也不提供流媒体接口
- 它只镜像源播放器已经通过 Android 系统接口暴露出来的信息，或者当前前台界面里可见的歌词文本
- GitHub 仓库名仍然是 `NuomiPlayer`，但当前安装后的应用名已经改为 `小老王播放器`

## 致谢

- 原项目：[charlottejas/NuomiPlayer](https://github.com/charlottejas/NuomiPlayer)
- 手机端界面参考/复用来源：[mardous/BoomingMusic](https://github.com/mardous/BoomingMusic)
