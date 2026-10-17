# DiPlay 上游 Release 覆盖与补齐提交清单（2026-10-18 07:51）

## 结论

当前分支完整覆盖上游 **v0.2.20**，并在此基础上合入了部分后续代码；尚未完整覆盖 **v0.2.21**。
可以理解成“v0.2.20 + 部分通向 v0.2.21 的修改 + 我们自己的车机适配”。
应用界面显示的版本号不能代替这个判断，本次是按 Git 实际提交关系检查的。

上游最新发布为 [v0.2.21](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.21)，
北京时间 **2026-10-18 02:07** 发布，GitHub 标为 public preview / Pre-release。
本次抓取到的 main 比该标签多一个发布合并提交，但两者文件树相同。

| 目标 | 当前覆盖情况 | 还缺多少上游提交 |
| --- | --- | --- |
| v0.2.18 / v0.2.19 | 已完整包含 | 0 |
| v0.2.20 | 已完整包含，并已超出 | 0 |
| v0.2.21 | 仅包含其中一部分 | 80：54 个非合并提交 + 26 个合并提交 |
| 本次抓取的 main | 尚未完整包含 | 81；多出的 1 个是发布合并记录，不增加文件差异 |

## 已经超出 v0.2.20 多少

当前本地 HEAD：`c04f5c14fae3e335aaa72f1bc5349f01a05d3674`。
最近完整合入的上游位置为 [`1b1ac5f2`](https://github.com/shihabal3amri/DiPlay/commit/1b1ac5f27986ce83b6d17fdd8c4d1be852fbbb70)，
提交时间是北京时间 2026-10-15 18:45。

从 v0.2.20 到这个位置共有 **38 个上游提交**，其中 **20 个非合并提交、18 个合并记录**。
沿上游主线看是 14 个节点；其中包含把 v0.2.20 发布分支合回 main 的记录，因此不要把节点数直接当成功能数量。

已经包含的后续内容包括音乐积压/切歌延迟修复（#814）、临时焦点丢失后的恢复（#804）、
无线失败/权限提示、临时系统栏、方控播放暂停键、Siri 双击等。
因此，v0.2.21 发布说明里提到的部分改进，我们其实已经合入；不能把整份发布说明都当成待补功能。

本地还有先锋、WeCarFlow、签名等定制修改。“上游提交已包含”不等于对应行为完全没有被我们的适配调整。

## 如果刚好对齐下一个 Release

建议以 **v0.2.21 标签 `49bf3bc2`** 为边界，而非继续追 main。
一次 Git 合并可以吸收缺失的全部历史，不需要手工执行 80 次合并。
合并后仍应保留我们的包名、签名、先锋 lwIP、WeCarFlow 桥接、已验证音频及昼夜默认策略。

换一种计数方式：上游主线上待补的是 **23 次 PR 合并 + 1 次发布准备**，共 24 个节点。
80 是完整提交图中缺失的提交数；54 是其中实际非合并提交数，包含修正、撤销、翻译及发布文档，不等于 54 个独立功能。
`git cherry` 比较这 54 个提交，未发现与本地完全相同的补丁；独立实现的部分相近功能不在这个统计承诺内。

## 对这台车机值得先关注的改动

| 提交 | 改动含义 | 与我们当前问题的关系 |
| --- | --- | --- |
| `ab324016` | iPhone 不响应切换请求时不再无限等待；调整导航音频降低增益的处理 | 与连接卡住、导航声音处理相关 |
| `6270e992` | 发出开始请求后，30 秒仍没有 CarPlay 会话就结束本轮并重试 | 与停在连接中相关；合入需接入我们的有线/lwIP 分支与生命周期 |
| `b6c2db84` + `fd004ac5` | 连接已恢复时不被旧重试误拆；恢复后再次掉线仍能正常重试 | 与快速重连、旧任务干扰新连接相关 |
| `4791f5d0` | 软件 Opus 编码器检查失败时丢一帧，避免整个应用退出 | 与旧车机麦克风上行稳定性相关；不等于已解决通话对端听不见 |

其他待补包括 Android 7.0 安装支持、蓝牙诊断、设置保存、热点配置、意大利语、BYD 分屏/仪表/灯光功能等。
BYD 专属功能对凯美瑞未必有直接收益；完整对齐 release 时仍要审查默认值和设备识别范围。

## 合并难度预览

执行了不更改当前工作区的 `git merge-tree` 预演，发现 **19 个文件有文本冲突**。
主要集中在启动页面、设置页、音频、连接控制、NCM USB 与构建配置，不能直接点“全部接受上游”。
自动合并没有冲突的文件也仍需构建与回归检查，文本无冲突不代表行为无冲突。

- `README.md`
- `README.zh-CN.md`
- `README.zh-TW.md`
- `common/build.gradle.kts`
- `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt`
- `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`
- `common/src/main/res/values-ja/strings.xml`
- `common/src/main/res/values-zh-rCN/strings.xml`
- `common/src/main/res/values-zh-rTW/strings.xml`
- `common/src/main/res/values/strings.xml`
- `common/src/test/java/com/shilapi/xcertplay/CarPlayHostSettingsTest.kt`
- `docs/COMPATIBILITY.md`
- `mobile/build.gradle.kts`
- `shared/build.gradle`
- `shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt`
- `shared/src/main/java/com/shilapi/xcertplay/network/ManualHotspotInterfaces.kt`
- `shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt`
- `shared/src/main/java/com/shilapi/xcertplay/transport/NcmUsbBridge.kt`
- `shared/src/main/jni/Application.mk`

## 完整缺失清单：54 个非合并提交

按拓扑反向日志顺序列出。此清单用于追溯，不是建议逐条 cherry-pick 的实施顺序。

| 提交 | 上游标题 |
| --- | --- |
| [4aee9fa7](https://github.com/shihabal3amri/DiPlay/commit/4aee9fa705b20c0947a5da1e5c132328a6878f58) | Fix Android 8 Bluetooth adapter acquisition and add diagnostics (#750) |
| [68a36098](https://github.com/shihabal3amri/DiPlay/commit/68a360982ad9da41bdd66ba0d2fd2a16624ce823) | Fix Android 8 Bluetooth adapter acquisition and add diagnostics (#750) |
| [78b4b71b](https://github.com/shihabal3amri/DiPlay/commit/78b4b71be9ced7d456cd2987901ff56e897d3d3b) | Ambient lighting: the car's own music rhythm follows CarPlay music |
| [55c4649d](https://github.com/shihabal3amri/DiPlay/commit/55c4649d2c7bf32767e518e4a6ab0e47d6c2a453) | Car music rhythm: start the helper again after a failure while the music plays |
| [b243d780](https://github.com/shihabal3amri/DiPlay/commit/b243d780f6f393124ce226bfcf5e44af184e0df1) | Car music rhythm: DiPlay makes the call itself, the ADB helper only where that fails |
| [b274c86f](https://github.com/shihabal3amri/DiPlay/commit/b274c86f0ed1e4faccae9b477f69f5c1e7110bf2) | Refine Bluetooth radio state handling and diagnostic report (#750) |
| [ab15f847](https://github.com/shihabal3amri/DiPlay/commit/ab15f847596896db7e6dcea832078f86a6a1655d) | Wait for Bluetooth That Is Turning On and Report Adapter State (#822) |
| [7b2484c2](https://github.com/shihabal3amri/DiPlay/commit/7b2484c2083b2a191175c81ec2d26345dfe98625) | Connect the car hotspot without typing anything |
| [e9e514f0](https://github.com/shihabal3amri/DiPlay/commit/e9e514f04f15ddeeb099314be9872cbc97fa7c15) | Toast when the hotspot write takes effect |
| [2195d445](https://github.com/shihabal3amri/DiPlay/commit/2195d44531873078a2d4f1ebb576278d2ca3df27) | Write the car hotspot config from a root adb on Android 8-10 |
| [9273c5ec](https://github.com/shihabal3amri/DiPlay/commit/9273c5ecec33e58b3fbea538360498232ad71904) | Allow the WRITE_SETTINGS appop before writing the hotspot config |
| [36031978](https://github.com/shihabal3amri/DiPlay/commit/3603197876d1508fd7192b76261f3a487fb679f9) | Revert "Allow the WRITE_SETTINGS appop before writing the hotspot config" |
| [981f3029](https://github.com/shihabal3amri/DiPlay/commit/981f30296bd053e975587d3676a14c0720d292bc) | Revert "Write the car hotspot config from a root adb on Android 8-10" |
| [08386dbc](https://github.com/shihabal3amri/DiPlay/commit/08386dbcaf4908b1ad08fc2bb51bcb71d1ef47a3) | feat(wireless): read the car hotspot configuration before overwriting it |
| [8afba49d](https://github.com/shihabal3amri/DiPlay/commit/8afba49de097f4684fef5b44c763f10fdc58a057) | fix(wireless): rewrite only the car hotspot passphrase |
| [fd72889a](https://github.com/shihabal3amri/DiPlay/commit/fd72889aa1ae486378f1f63fb8cdd6b3784935a7) | Confirm the car hotspot write and keep the car's SSID and password |
| [6e17806c](https://github.com/shihabal3amri/DiPlay/commit/6e17806c29f2b18d15b9b9c6b68c42c7c5b1f76e) | Render when the save-time overwrite dialog is cancelled |
| [4189948a](https://github.com/shihabal3amri/DiPlay/commit/4189948aed85a4038d9e43f67203380c00b63be5) | Reject open writes when the car's security type is unknown |
| [f97b5866](https://github.com/shihabal3amri/DiPlay/commit/f97b586604057a20d555a3933c96669d9178f203) | Say that importing is unsupported, not writing |
| [32233f18](https://github.com/shihabal3amri/DiPlay/commit/32233f182f00ac605254f53721cee2cc1687adf0) | Create the car hotspot details when the car has none |
| [1304fab8](https://github.com/shihabal3amri/DiPlay/commit/1304fab8e878f676c0f507a79ff2702440fa8076) | Describe saving details in the save dialog |
| [ab324016](https://github.com/shihabal3amri/DiPlay/commit/ab324016a6187b8ec5d1b7ff43a6776279d26ab5) | Stop waiting forever for an iPhone that ignores the mode request; lower guidance in the PCM |
| [cea429af](https://github.com/shihabal3amri/DiPlay/commit/cea429afb4d8c46983ca39e00a2be3c0ff071d84) | docs: clarify CarPlay call, Siri and media key controls |
| [14b779bb](https://github.com/shihabal3amri/DiPlay/commit/14b779bb7794d30894fcff4689998b92e5997ea7) | feat(launcher): a home-screen bubble that comes back to CarPlay |
| [484d9623](https://github.com/shihabal3amri/DiPlay/commit/484d96230c0217a4a76a4b7531af4cd451a25a9f) | fix: support BYD call popup suppression on Android 10+ |
| [71391aeb](https://github.com/shihabal3amri/DiPlay/commit/71391aeb74a1f6e64aa3e4bd8cf4c5bc046d60ae) | Install on Android 7.0 (API 24) |
| [672dc7c0](https://github.com/shihabal3amri/DiPlay/commit/672dc7c06cd47e56e1dcb00e3b1b5b655ff4b40d) | Mention only the password in the save dialog |
| [1d3cd00f](https://github.com/shihabal3amri/DiPlay/commit/1d3cd00f85ace81f1c795cd79d63d74d76537fe9) | Save CarPlay menu settings when an unused hotspot is not set up |
| [cacac6d6](https://github.com/shihabal3amri/DiPlay/commit/cacac6d68804ebbfdba444925d1b9088d02b4fff) | Add Italian translation |
| [89810087](https://github.com/shihabal3amri/DiPlay/commit/89810087ebaaec49467b8875568451c3edd29f91) | Report once a minute what location and wheel speed reached the iPhone |
| [e18f20f8](https://github.com/shihabal3amri/DiPlay/commit/e18f20f8030c2ddc899519a9121f2acd1cc9b4ad) | Say under screen turning that an iPhone screenshot shows the whole square |
| [940c362a](https://github.com/shihabal3amri/DiPlay/commit/940c362a0ee7196a785727a653cd1bffa3dfef1b) | Check the hotspot again when the menu switches to wireless |
| [28388bbf](https://github.com/shihabal3amri/DiPlay/commit/28388bbf179d78ae46b35103403c57e1c44aa330) | Fix Italian resource names and add Italian experimental suffix |
| [4dca57f7](https://github.com/shihabal3amri/DiPlay/commit/4dca57f78537460ab5860fe564544af4e529a4fd) | fix: show call popup toggle only on BYD head units |
| [aefc702d](https://github.com/shihabal3amri/DiPlay/commit/aefc702d55ddffd0d935af92279a23e501bd173d) | docs: remove call-key implementation history from user guide |
| [4791f5d0](https://github.com/shihabal3amri/DiPlay/commit/4791f5d09c5cf6876a129ecb6de038f97b608769) | Let a failed check in the software Opus encoder cost one frame, not DiPlay |
| [447bddc4](https://github.com/shihabal3amri/DiPlay/commit/447bddc4835810c25249b28b8d2d959e1886d4f7) | add new italian strings |
| [c68add89](https://github.com/shihabal3amri/DiPlay/commit/c68add89a34f8b9955e7369b4021264f9bbe18aa) | feat: add DiLink split config |
| [393e9f2d](https://github.com/shihabal3amri/DiPlay/commit/393e9f2d0946f41e8eb2c798de97076567b505a6) | feat: add multiple split mode |
| [b6c2db84](https://github.com/shihabal3amri/DiPlay/commit/b6c2db846cdcd81f33e34e9b6af34aaf279c9267) | Leave a wired attempt alone when it recovers before its retry |
| [8567ea3e](https://github.com/shihabal3amri/DiPlay/commit/8567ea3ed13c1eeaa00a08392271eeb2ac7db8cb) | Release notes for #847, #769, #844 and #695 |
| [2a4d9f61](https://github.com/shihabal3amri/DiPlay/commit/2a4d9f6193f257d1c91acd3e59b8fb89fc81dfa9) | docs: add comment |
| [c9c9c77f](https://github.com/shihabal3amri/DiPlay/commit/c9c9c77fd09d0750845817cde07ec4e50f49096c) | Accept a car hotspot Android does not report as a Wi-Fi AP (#573) |
| [8880fcf3](https://github.com/shihabal3amri/DiPlay/commit/8880fcf3b448716e85f385972500e0367c0561a8) | Add new translations |
| [fd004ac5](https://github.com/shihabal3amri/DiPlay/commit/fd004ac51eafbede846660d3bc290601b9170978) | Restart a recovered attempt that is lost again before its retry |
| [5745c1b9](https://github.com/shihabal3amri/DiPlay/commit/5745c1b91c86723d4e2688f6cacab784e9245c86) | Stop asking again when Wi-Fi Direct is refused beside the car hotspot |
| [b9ed71d4](https://github.com/shihabal3amri/DiPlay/commit/b9ed71d4f45bfda7e43cde8d3a4c4a6879db0e3a) | Release notes for the 2026-10-17 merges |
| [6270e992](https://github.com/shihabal3amri/DiPlay/commit/6270e992b8c3a33243da39647b01b7dd9dbd1201) | Retry wired CarPlay when the iPhone never opens its session |
| [fca447c3](https://github.com/shihabal3amri/DiPlay/commit/fca447c3a746ccd079ec9a08fc801d553731fb28) | Send the played music's spectrum to the car's own rhythm on head units that make none |
| [3c5a9c14](https://github.com/shihabal3amri/DiPlay/commit/3c5a9c14d18233c5f439e10a837db13221c92346) | Hand the BYD cluster back to the car when CarPlay's map stops |
| [0794a457](https://github.com/shihabal3amri/DiPlay/commit/0794a45737fd0856b9cd59a8dfe5c93a2b220c3e) | feat: remove useless metadata |
| [f4f73aa3](https://github.com/shihabal3amri/DiPlay/commit/f4f73aa34c2566814218a4b6ad3d9732e863fe3c) | Release notes for #907 and #909 |
| [f83fbda5](https://github.com/shihabal3amri/DiPlay/commit/f83fbda534bd8f6ef748053d5c2bdaed3754fee6) | Release notes for #875 and #908 |
| [49bf3bc2](https://github.com/shihabal3amri/DiPlay/commit/49bf3bc2b011cb5ee7c7f4da9b99b18cbd3a23a2) | Prepare DiPlay 0.2.21 public preview release |

## 上游主线的 24 个待补节点

| 提交 | 合入记录 |
| --- | --- |
| [ac2f4b21](https://github.com/shihabal3amri/DiPlay/commit/ac2f4b214c740ca830a7379d06de7d6ddcb873f2) | Merge pull request #847 from Boring206/android-7-0-install |
| [94f6a072](https://github.com/shihabal3amri/DiPlay/commit/94f6a072138aac5e85e364397a1ccbbbcf69bc03) | Merge pull request #769 from band-wa/feat/car-hotspot-config-write |
| [7b7aa167](https://github.com/shihabal3amri/DiPlay/commit/7b7aa167645e9c61fb47964f85574c250c0267fc) | Merge pull request #844 from macheteHot/feat/home-screen-bubble |
| [b309eae9](https://github.com/shihabal3amri/DiPlay/commit/b309eae90974921118d4d835413e7928b3a6193e) | Merge pull request #695 from romanchukg-cloud/feat/byd-car-music-rhythm |
| [f8e51ba7](https://github.com/shihabal3amri/DiPlay/commit/f8e51ba7b9388b238cdaed3f67ef6a868bf63544) | Merge pull request #841 from shihabal3amri/fix/reports-20261015b |
| [1cd4640a](https://github.com/shihabal3amri/DiPlay/commit/1cd4640a3931029be661b8d03625476ae9b786d5) | Merge pull request #862 from shihabal3amri/fix/wheel-speed-report |
| [4a485bb4](https://github.com/shihabal3amri/DiPlay/commit/4a485bb4ecc227d0544ed7649fcade6b6a06cf87) | Merge pull request #864 from shihabal3amri/docs/rotation-screenshot-note |
| [e15395e4](https://github.com/shihabal3amri/DiPlay/commit/e15395e46e081c7dca596955e0bacd0eece1e93f) | Merge pull request #866 from shihabal3amri/fix/opus-encoder-assertion |
| [2ff88600](https://github.com/shihabal3amri/DiPlay/commit/2ff8860043711fcc46937dee91b4023d8746118f) | Merge pull request #822 from faizannazir/fix/android8-bluetooth-diagnostics-issue-750-1172537892055024361 |
| [98135cc6](https://github.com/shihabal3amri/DiPlay/commit/98135cc63781cc4d88b221d623ac9a15b6fe3a47) | Merge pull request #853 from Boring206/settings-save-unused-hotspot |
| [16f92773](https://github.com/shihabal3amri/DiPlay/commit/16f92773143ddaab76d22539d075caf9b6365c9d) | Merge pull request #876 from Boring206/retry-after-recovered-attempt |
| [db0ed90f](https://github.com/shihabal3amri/DiPlay/commit/db0ed90f0b6f80dff65e0aafe3476c96dfcfba0c) | Merge pull request #893 from Boring206/wifi-direct-beside-hotspot |
| [0d429d41](https://github.com/shihabal3amri/DiPlay/commit/0d429d41914ba23abd423b3600c2aee15e091bbc) | Merge pull request #845 from lpcheng1208/fix/byd-call-popup-android10 |
| [94b4bb04](https://github.com/shihabal3amri/DiPlay/commit/94b4bb04636267a6fd602ced3ef2aa08c9e1b66d) | Merge pull request #842 from lpcheng1208/docs/carplay-call-key-description |
| [c537fed8](https://github.com/shihabal3amri/DiPlay/commit/c537fed8a07f16c84c307a19eb2c5669b2873d4b) | Merge pull request #885 from svipdami/feature/car-hotspot-vlan-interface |
| [6a623fc0](https://github.com/shihabal3amri/DiPlay/commit/6a623fc025e77ebc9a9c2f5f12d3fbfbf1a3515f) | Merge pull request #863 from gabrielerandazzo/add-italian-translation |
| [8e74e11c](https://github.com/shihabal3amri/DiPlay/commit/8e74e11cb6b95e9743f0f0c1a082d1d33a8fd942) | Merge pull request #881 from shihabal3amri/docs/release-notes-20261017 |
| [cbc19f64](https://github.com/shihabal3amri/DiPlay/commit/cbc19f64e4b39e253c9b8481efcc7282ee784beb) | Merge pull request #907 from shihabal3amri/fix/wired-start-timeout |
| [7fc60734](https://github.com/shihabal3amri/DiPlay/commit/7fc607345323510e1b6fc0a66903fd25bf425d8c) | Merge pull request #909 from shihabal3amri/fix/cluster-handback |
| [b1a56099](https://github.com/shihabal3amri/DiPlay/commit/b1a56099acac5d0e52669d193bc2dac587b32903) | Merge pull request #910 from shihabal3amri/docs/release-notes-20261017b |
| [98a58537](https://github.com/shihabal3amri/DiPlay/commit/98a58537a8e3560d5b405f2bc97e576073b0ba43) | Merge pull request #875 from crazyurus/main |
| [09951e3f](https://github.com/shihabal3amri/DiPlay/commit/09951e3f8d4dc19735fb470be61ed39a7ee17d1a) | Merge pull request #908 from sa3eedo12/feat/car-rhythm-lighting |
| [9509960e](https://github.com/shihabal3amri/DiPlay/commit/9509960e96ade3b9184e2f41d3aae3a123a73920) | Merge pull request #911 from shihabal3amri/docs/release-notes-20261017c |
| [49bf3bc2](https://github.com/shihabal3amri/DiPlay/commit/49bf3bc2b011cb5ee7c7f4da9b99b18cbd3a23a2) | Prepare DiPlay 0.2.21 public preview release |

## 检查方式与边界

拉取上游 main 和标签到隔离的 review 引用，避免覆盖本地已有标签。通过
`merge-base --is-ancestor`、`rev-list --count`、`git cherry` 核对历史及补丁等价情况，
通过 `merge-tree --write-tree` 检查合并冲突。
没有实际执行 merge、没有改业务代码、没有构建、没有安装，也没有提交或推送这轮检查结果。
当前 DiPlay 工作区仍干净。该结论对应本次抓取的上游快照。
