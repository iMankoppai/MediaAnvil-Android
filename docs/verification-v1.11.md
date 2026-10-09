# MediaAnvil 1.11 验证记录

日期：2026-10-09。版本：1.11，versionCode 12。

## 检查结果

- `:app:testDebugUnitTest`：150 项通过，失败、错误、跳过均为 0。
- `:app:lintDebug`、`:app:lintRelease`：通过现有 baseline，各有 4 条 warning、3 条 hint，未增加新告警。
- 调试包、测试包和签名正式包均构建成功。
- vivo V2520A（Android 16）：最终完整设备回归 75 项全部通过，无跳过。结果：`build/device-v1.11-tests.txt`。
- 新增功能专项覆盖实际 ExoPlayer 播放和 Compose 交互，界面复查结果：`build/device-v1.11-ui-tests.txt`。

## 完整播放次数

实际播放测试验证：队列自然结束、按集停止、2 倍速完整循环；拖到后段再结束、A–B 循环均不增加次数。暂停保存后重建服务和重新读取 DataStore，再从保存位置续听，完整结束后只增加一次。手动标记已听完不增加次数。

单元测试还覆盖 0.5 倍速、3 倍速、未报告的位置跳跃、前进跳过少量内容、回退后补听、未知时长以及重复结束回调去重。记录连续听过的前缀，跳过的内容没有播放时不授予完成次数；保留少量解码边界容差（起点衔接 30 ms，结束至多 50 ms），不使用原“接近结尾即已听完”的秒级阈值。

另在实际 MainActivity 媒体列表点播既有 `mediaanvil-test` 三秒 OPUS 测试音频，完整结束后界面自动出现“完整播放 1 次”的无障碍描述与右侧标记；暂停按钮恢复为播放按钮，没有继续播放。布局记录：`build/phone-v1.11-library-count-layout.json`。

## 歌词转换与界面

验证繁简双向转换、词语歧义（头发／发现）、LRC 多时间戳、offset、元数据、CRLF 换行、英文和表情保留，以及长歌词。预览从原文转换，切换回原文恢复原候选；保存回调接收的文本与选择的预览一致，转换未完成时不能保存旧结果。

界面测试检查无记录时不显示标记，有记录时在标题右侧、更多按钮前显示。首次断言读取合并后的整行无障碍节点而误判位置；改为检查未合并子节点后通过，并视觉检查测试截图：

- `build/v1.11-lyrics-preview.png`
- `build/v1.11-count-badge.png`

转换使用固定版本 OpenCC ver.1.1.9 的四份原始词典，在本机后台按最长词语优先、字符回退执行；不是完整 OpenCC 地区词汇流水线。词典、原许可与来源说明随 APK 分发，位于 `app/src/main/resources/opencc/`。转换不增加网络请求。

## 备份与发布包

新备份 schema 5 保存次数、未完成收听前缀和歌词转换偏好，按音频匹配一起重映射。设备测试验证写盘重读、音频 URI 迁移及旧备份缺少新字段时保留已有次数。本版支持 schema 1–5，旧版不支持新的 schema 5。

签名检查 `apksigner verify` 和对齐检查 `zipalign -c 4` 均通过。证书 SHA-256 与历史正式版相同：`56b7d24cb0bf29ee5797274e299c84afd90483db40cef1667d180a6bf137d3e3`。正式 APK 不包含 DebugTestActivity，也没有 debuggable 标记。

安装包：`app/build/outputs/apk/release/MediaAnvil-Android-v1.11.apk`，3,699,350 字节（约 3.53 MiB）。SHA-256：`7fd66fb0655d2a19f04fe5f711e1b91a017b01d555f8efd9f56302e53603d402`，同目录提供 `.sha256` 文件。

本轮仅安装调试包和测试包，没有安装正式包或清除正式版用户数据。历史安装包保留。以上设备测试不代表所有 Android 版本、厂商及歌词服务实时搜索结果的完整覆盖；在线候选以固定样本验证转换，未更改搜索服务。
