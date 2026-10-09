# MediaAnvil 1.10 验证记录

日期：2026-10-08。版本：1.10，versionCode 11。

## 检查结果

- `:app:testDebugUnitTest`：141 项通过，失败、错误、跳过均为 0。
- `:app:lintDebug`、`:app:lintRelease`：通过现有 baseline，各有 4 条 warning、3 条 hint。
- 调试版、测试包和签名正式版均构建成功。
- vivo V2520A、Android 16：2 项主题专项测试和 70 项完整设备回归测试全部通过，无跳过。
- 原始测试结果：`build/device-v1.10-theme-tests.txt`、`build/device-v1.10-tests.txt`。

## 主题验证

主题测试从实际绘制画面取样，检查淡入淡出中间帧和结束帧，同时确认动画期间页面调色板保持最终值，没有逐帧重组页面；快速反向切换从当时显示画面衔接，页面实例保持。初始深色主题立即显示最终颜色。

在实际 MainActivity 设置页交替点击深色和浅色各 5 次，记录 `dumpsys gfxinfo framestats`。调试版本次样本为 352 帧，系统报告 9 帧 jank（2.56%），第 50/90/95 百分位耗时为 7/9/9 ms，第 99 百分位为 121 ms。记录位于 `build/device-v1.10-theme-framestats.txt`。这是一次调试版样本，包含切换瞬间与点按反馈，无旧版同条件基线，不能据此声称零卡顿或量化改善幅度。测试后调试版恢复为原先的跟随系统主题。

动画时长采用 300 ms，遵循系统动画时长设置；旧画面仅暂存于内存，不写入磁盘。截图不可用时仍能应用新主题。实际眼睛舒适度由用户体验判断。

## 正式包

- 应用 ID `com.imankoppai.mediaanvil`；版本 1.10，versionCode 11。
- `apksigner verify` 与 `zipalign -c 4` 通过，保留既有正式签名。
- 签名证书 SHA-256：`56b7d24cb0bf29ee5797274e299c84afd90483db40cef1667d180a6bf137d3e3`。
- 正式包不包含 DebugTestActivity；语言配置变化处理保持。
- 安装包：`app/build/outputs/apk/release/MediaAnvil-Android-v1.10.apk`；同名 `.sha256` 为校验文件。

本轮未向正式版安装或清除用户数据，保留历史 1.08、1.09 安装包。
