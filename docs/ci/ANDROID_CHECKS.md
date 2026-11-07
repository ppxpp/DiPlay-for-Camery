# Android CI 与手动出包

## PR 验证

`Android checks (diplay)` 保留 `check` job，避免改变现有分支保护需要的检查名称。同一个 PR 新提交会取消旧提交的检查。

`scripts/ci_android_tasks.py` 选择 Gradle 任务：

- 文档、网站、更新服务、安装器目录的变更不启动 Android 工具链，仍执行仓库凭据检查和轻量脚本检查。
- 普通 Android 代码：运行改动模块的 lint 和受影响的单元测试，并编译主应用，默认不打 APK。先锋模块变更包含 shared/common 测试，shared 变更包含 common 测试。
- 资源、Manifest、assets 变更：增加主应用 APK 构建；示例变更只增加相应示例的测试、lint、APK 构建；示例资源变更还会执行 common 中的翻译约束测试。
- Gradle、JNI/C++、CI 脚本或其他未分类路径：执行原有完整任务集，包含三个 APK。
- main 推送始终执行完整任务集。手动运行默认开启 `full_validation`；关闭后只验证最近一次提交的改动范围。

源码验证使用 `--build-cache` 复用可缓存任务输出。首次运行、缓存未命中或不可缓存任务仍需正常执行。正式发布不启用该选项，避免把含私有认证资源的构建输出放进可复用任务缓存。

修改路径规则时运行：

```sh
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
```

## 出包与发布

所有 tag 推送都不会自动触发 Actions。`Publish DiPlay update` 仅通过 Actions 页面手动运行，选择 stable/beta 渠道：

- 默认只构建签名 Release，上传并验证发布快照。
- `full_validation` 默认关闭，开启后额外执行 Android 单测和 Debug lint。
- `deploy_update_site` 默认关闭。网站改动优先运行独立的 `Deploy DiPlay update site`；需要同时部署时再开启此选项。
- 签名、认证文件、版本号、更新渠道、校验和与上传结果的检查继续执行。手动选择 tag 出包时仍会校验 tag 与 APK 版本的一致性。
- `Build DiPlay Android TV source APK (diplay)` 默认只打源码验证包；需要重复测试和 lint 时开启 `full_validation`。此包不包含 CarPlay 私有认证资源。

原生编译和 APK 的支持架构保持不变。没有触发实际 GitHub runner 前，不预估或宣称具体提速比例。
