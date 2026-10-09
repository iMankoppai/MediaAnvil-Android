# MediaAnvil 1.0.8 验证与编号迁移

日期：2026-10-09。当前对外版本 1.0.8，Android versionCode 13，发布标签 v1.0.8。

## 当前构建

- `:app:testDebugUnitTest`：151 项通过，失败、错误、跳过均为 0。
- 新增更新检查测试：旧 1.07 与 1.0.7 等价，1.0.8 高于二者；1.0.9、1.1.0 的顺序正确，debug 后缀不改变同版本比较结果。
- `:app:lintRelease`：通过现有 baseline，4 条 warning、3 条 hint，没有增加告警。
- `:app:assembleRelease`、`apksigner verify`、`zipalign -c 4`：通过。
- APK 元数据：应用 ID `com.imankoppai.mediaanvil`，版本 1.0.8、versionCode 13、最低 API 26、目标 API 36。
- 原有功能保持本轮已完成的 75 项手机回归结果，详见 [功能验证](verification-v1.11.md)。本次编号与更新比较变更不需要再次安装手机测试包。

GitHub 模拟器首次回归的唯一失败为续听测试提前使用了控制器估算进度。测试现改为等待播放服务保存的实际进度，并在同一服务命令中暂停和保存，再检查重启续听与计数；没有修改完整播放的计数规则或跳过该用例。

正式包 `app/build/outputs/apk/release/MediaAnvil-Android-v1.0.8.apk`，3,699,358 字节。SHA-256：`b8c67a65b83a26094a70b3107c601471cd8ac597f37c9cdc0667a7e05f42102a`。签名证书 SHA-256 仍为 `56b7d24cb0bf29ee5797274e299c84afd90483db40cef1667d180a6bf137d3e3`，可以覆盖同签名临时 1.11（versionCode 12）及更早版本。

## GitHub 历史版本

仓库：`iMankoppai/MediaAnvil-Android`。

- 原 1.01–1.07 的七个 Release 统一为 1.0.1–1.0.7，保留原 Release ID、发布时间、历史 APK 内容与签名。
- 初始标签 v1.0 对应 v1.0.0；后续七个标签对应三段式编号，指向各自原提交，未重写源码历史。
- 原先带版本号的 APK 文件名改为三段式，APK asset ID、文件大小及 SHA-256 digest 保持不变。
- 为新 APK 文件名生成配套校验文件，上传后验证 GitHub digest，再替换旧文件名的校验资产。1.0.1 的历史 `app-debug.apk` 保留原文件名。
- 发布页注明原编号对照，历史安装包内部仍显示原编号；本次没有重新构建旧安装包。
- 迁移时暂时停用仅该仓库的发布工作流，完成标签与资产调整后恢复为 active；复查八个公开标签全部采用三段式。
- 本地旧标签对象保存于 `refs/mediaanvil-version-history/tags/`，保留原注释及 tag 对象，可用于恢复；不属于公开发布标签。

迁移前后 API 快照、原校验文件、执行方案与请求记录位于工作区 `build/version-migration/`，该目录不提交。历史本地临时构建的说明整理到 `docs/testing/local-builds/`；所有功能统一到 [1.0.8 更新说明](releases/v1.0.8.md)。

旧客户端的更新比较器无法自动解释新的编号规则，首次迁移需要手动覆盖安装 1.0.8；新版比较器同时兼容历史编号和三段式编号。
