# MediaAnvil 1.09 验证记录

日期：2026-10-08。版本：1.09，versionCode 10。

## 自动检查

- `:app:testDebugUnitTest`：141 项，通过；失败、错误、跳过均为 0。
- `:app:lintDebug` 与 `:app:lintRelease`：通过现有 baseline，报告各有 4 条 warning、3 条 hint；并非全项目零告警。
- `:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:assembleRelease`：构建成功。
- 连接的 vivo V2520A（Android 16）上专项测试 8 项、完整回归测试 70 项均通过，无跳过。原始结果位于 `build/device-v1.09-targeted-tests.txt`、`build/device-v1.09-tests.txt`。

## 本轮回归范围

- 权限变化后自动加载实际 MediaStore 音频；授权前空缓存和旧版缺少权限范围标记的缓存能自动恢复。
- 区间编辑仅接受分秒，拒绝毫秒输入；旧标签只修改名称时保留原始边界。
- 主题动画中间时刻和 1 秒终点的颜色变化，快速反向切换延续当前颜色，页面状态保持；初始深色页面直接呈现深色。
- 在真实 MainActivity 设置页切换中文、英文、中文：资源语言更新、当前标签页保留、Activity 实例未重建。
- 原有播放、媒体库、标签、备份以及 1000 集作品列表批量操作回归。

另在调试版手动执行系统授权流程：撤回音频权限后重新启动，页面显示“允许访问音频”；点击后在系统弹窗选择“允许”，已有 `mediaanvil-test` 音频自动出现，未执行手动扫描。仅操作调试版权限，未清除正式版数据。

## 正式包

- 应用 ID：`com.imankoppai.mediaanvil`，保留原正式签名，可覆盖同签名旧版本。
- `apksigner verify --verbose --print-certs` 通过；证书 SHA-256 与 1.08 一致：`56b7d24cb0bf29ee5797274e299c84afd90483db40cef1667d180a6bf137d3e3`。
- `zipalign -c 4` 通过；正式包不包含 DebugTestActivity。
- 正式 Manifest 的 MainActivity 保留 `locale|layoutDirection|uiMode` 配置变化；其他配置仍按 Android 默认机制处理。
- 安装包和 SHA-256 校验文件位于 `app/build/outputs/apk/release/MediaAnvil-Android-v1.09.apk` 与同名 `.sha256` 文件。

设备测试验证了上述行为；未进行所有 Android 版本与厂商的覆盖测试，也未测量滚动帧率。系统关闭或缩短动画时，主题过渡遵循系统动画时长设置。
