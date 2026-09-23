# MediaAnvil Android

MediaAnvil Android 是面向本地 ASMR、广播剧、有声内容和音乐文件的专用播放器。它与 Windows 版各司其职：Android 负责随时播放，只保留歌名和歌手两项轻量标签编辑；完整的标签处理、封面处理、音频剪辑和格式转换由 Windows 版完成。

## 当前功能

- 获得存储访问权限后，自动扫描设备中的 MP3、WAV、FLAC、M4A、AAC、OGG 和 Opus，并缓存媒体库以便快速启动。
- 媒体页提供歌曲、收藏、最近播放与自定义分组，支持搜索、排序和仅从播放器中移除歌曲（不会删除原文件）。
- 搜索会同时匹配歌名、歌手、专辑和文件名，多个关键词以空格分隔时逐步收窄结果，命中的文字会在列表中高亮。
- 收藏按添加顺序保存，可整列播放；“最近播放”由播放服务写入，只展示当前仍可访问的本地歌曲，最新在前。
- 分组是有序歌单：歌曲按用户添加的顺序保存并按该顺序播放，新增歌曲追加到末尾，不会打乱已有顺序。
- 使用 Media3 ExoPlayer 播放本地音频，支持播放队列、上一首/下一首、随机、列表循环和单曲循环。
- 播放页支持 0.75×–3× 变速、A-B 循环、可配置快退/快进、进度拖动、封面与同步歌词；长按封面约两秒可为当前歌曲选择持久保存的自定义图片。
- 通过歌曲右侧菜单编辑歌名和歌手（MP3、WAV、FLAC、M4A、OGG/Vorbis、Opus）。原始 AAC 在手机端保持只读。
- 自动匹配同名及双后缀 LRC、SRT、VTT 外挂歌词，支持 UTF-8、UTF-16 和 GB18030；没有本地歌词时可从 LRCLIB 搜索同步歌词。候选必须先打开完整歌词与时间轴预览，明确确认后才保存为同目录 LRC。已有歌词可重新搜索替换、选择本地文件、按 0.5 秒调整时间偏移或确认后删除。
- 相同时间戳的原文与译文会合并为双行同步显示；Android 版本身不进行语音识别或机器翻译。
- 支持后台播放、锁屏控制、系统媒体通知和可配置的耳机双击动作。
- 提供睡眠定时和后台播放电池设置入口。
- 设置页可检查 GitHub Releases 中的新版本，并可导出、恢复分组、收藏、最近播放、已移除歌曲、自定义封面关联、歌词偏移和播放器设置。
- 支持简体中文与英文以及深色/浅色主题。

音频文件不会上传。在线歌词搜索会把当前歌曲的标题和歌手发送给 LRCLIB；选择结果后只在音频旁写入一个外挂 LRC 文件。除用户主动保存的歌名和歌手外，Android 版不会改动音频数据或其他标签。

## 存储权限说明

MediaAnvil Android 仅通过 GitHub 发布。应用**不申请「所有文件访问」**：

- 本地音频通过系统媒体库（MediaStore）读取，只需音频读取权限（Android 13+ 为 `READ_MEDIA_AUDIO`，Android 12 及以下为 `READ_EXTERNAL_STORAGE`）。
- 保存外挂歌词或编辑音频标签时，应用会请你用系统文件夹选择器**选择一次音频所在文件夹**，之后长期保留该授权。这是因为歌词文件不属于媒体库，系统不允许应用直接写入任意目录。
- 未授权文件夹中的音频仍可正常播放，只是无法在该目录写入歌词或标签。

应用首次启动不会直接跳转权限页，而是在媒体库为空时说明用途，并由用户主动继续。应用不会上传音频文件，也不会删除原始音频。

## 界面结构

- **媒体**：浏览、搜索、收藏、最近播放、分组和选择歌曲。
- **播放**：独立的一级播放页面，显示封面、歌词、进度、播放模式和队列。
- **设置**：仅保留与播放器、歌词显示、声音、主题和媒体库扫描相关的选项。

## 与 Windows 版的分工

以下功能只保留在 Windows 版，不再由 Android 版提供：

- 除手机端菜单中的歌名和歌手编辑外，其他音频标签和封面写入
- 封面裁剪
- 音频剪辑与合并
- 音频、图片、歌词和字幕格式转换
- 批量重命名与智能匹配写入

## 本地构建

需要 JDK 17、Android SDK 36（Build Tools 36.0.0）和 Gradle 9.6.0（仓库已含 wrapper，无需单独安装 Gradle）：

```text
gradlew :app:testDebugUnitTest :app:assembleDebug
```

调试 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 长期签名与发布

将 `signing.properties.example` 复制为 `signing.properties`，填写长期保存的 JKS 路径、密码和别名后运行：

```text
gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

真实的 `signing.properties`、JKS 和密码均不得提交。GitHub 发布工作流使用以下仓库 Secrets 构建同一签名的 Android APK，并将 APK 与 SHA-256 一起加入 Release：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

配置一次 Secrets 后，创建并推送与 `versionName` 一致的标签即可发布，例如 V1.02 使用：

```text
git tag v1.02
git push origin v1.02
```

工作流会先运行 Release 单元测试和 Lint，验证 APK 签名及标签版本，再创建 GitHub Release。应用内更新仅在 Release 同时包含 APK 和对应的 `.sha256` 文件时允许下载安装；校验失败的文件不会交给系统安装器。

## 主要技术

- Kotlin 2.3.21
- Jetpack Compose BOM 2026.06.01
- Android Gradle Plugin 9.4.0
- Media3 1.11.1
- minSdk 26 / targetSdk 36
