# 先锋车机 CarPlay 导航 HUD 与仪表接入

适用分支：`feat/camry-navigation-hud-lab`。本文包含 2026-10-25 的实车证据与 2026-11-01 补齐的实测准备能力。原 HUD 导航接入代码位于提交 `450231b1`；后续增强的真实车辆行为仍待验收。

## 当前实现

导航数据路径：

```text
CarPlay iAP2 导航消息 0x5201 / 0x5202
  → BydNavigationOutputs
  → PioneerNavigationBridge 的专用 worker
  → BydHudRouteState 解析与转向映射
  → MeterServiceController.setNaviInfo
  → HUD 与仪表
```

仅在已识别且开启先锋适配的设备上启用，不新增设置，独立于 VPN/lwIP 数据通道。工作线程使用现有有界导航输出队列，主连接链路不等待 Meter SDK。

- 相同方向、距离和道路的输出去重。
- 当前生产引导使用 `TBT(2)`，恢复此前实车能显示的路径；`ARROWGUIDE(1)` 版本已有实车“不显示”的反馈，不能将 SDK 接受视为显示通过。
- 高德原车地图即使没有导航，也会经 TPS 定期写入 `NOTINGUIDE`，与 CarPlay 输出交替并覆盖引导。生产桥接通过 `PioneerHudMapOwnership` 在首次有效指引前检查原车来源和 TPS 实时导航状态：仅在高德明确未导航时暂停其进程；状态未知或正在导航则不接管。已停止的高德可直接取得输出；不禁用软件，用户仍可从原车入口正常打开。原车地图重新打开或来源切换时，DiPlay 本会话让出 HUD，清理与清理重试也不得覆盖新所有者。此策略不停止 TPS、音频和方向盘服务；尚未适配腾讯来源的自动接管。
- 通过原车 SDK `MeterServiceListener.OnNotifySetNaviInfo` 接收导航刷新请求。监听器按进程注册一次并保留强引用；注册失败每 5 秒重试，错误进入诊断状态。回调只合并请求，由现有 Meter worker 补发仍有效的当前帧，最小发送间隔 500ms；数据更新也可满足待处理刷新请求。停止、清理与实验页接管后不补发旧路线。此机制已接入，实际车机回调及对闪烁的影响仍待实测。
- 诊断新增 `meterListenerRegistered`、`meterListenerError`、`meterRefreshRequests`、`meterRefreshSends` 与 `meterRefreshPending`，区分监听注册、收到请求和实际补发。
- 每 500ms 检查路线解析器的过期或空状态，避免停止更新后永久显示旧指引。
- 导航结束、取消、控制器关闭或连接结束时，清空解析状态并发送停止引导状态。
- 已识别且启用先锋适配的 App 打开时，在原车输出所有权允许的条件下清屏，不依赖手机重连。停止失败最多尝试 5 次（首次立即，其余约每 500ms 一次），独立于已关闭的导航帧队列；耗尽后保留 `clearPending` 与错误，不宣称清除成功。驻车后可用 RECOVER 重试。强制杀进程仍不能保证退出回调执行。
- 实验页首次发送 Meter 时暂停生产输出，导航帧继续更新缓存。CLEAR 或页面销毁在同一 Meter worker 中清理、释放所有权，再恢复仍有效的真实指引。
- 未知转向不猜测，清除当前输出。
- 未实现环岛出口编号。原车高德的竞争按上述所有权规则处理；腾讯来源暂不接管。

关键代码：

| 文件 | 职责 |
| --- | --- |
| `shared/src/main/java/com/shilapi/xcertplay/hud/PioneerNavigationBridge.kt` | 生产导航输出、转向映射、去重和清理 |
| `shared/src/main/java/com/shilapi/xcertplay/hud/BydNavigationOutputs.kt` | 连接开始、导航消息和结束生命周期接入 |
| `common/src/main/java/com/shilapi/xcertplay/NavigationHudLabActivity.kt` | 实验页及其显示、停止和销毁清理 |
| `pioneer-adapter/src/main/java/com/shilapi/xcertplay/pioneer/PioneerDebugService.kt` | 调试版远程实验入口 |
| `pioneer-adapter/src/main/java/com/shilapi/xcertplay/pioneer/PioneerDebugRuntime.kt` | 导航状态与实验页状态 |

## 转向映射

| CarPlay 转向类别 | Meter 常量后缀 |
| --- | --- |
| 左转 / 急左转 | `LEFT` |
| 右转 / 急右转 | `RIGHT` |
| 斜左 / 斜右 | `LEFT_FRONT_AUTONAVI` / `RIGHT_FRONT_AUTONAVI` |
| 直行 | `FRONT` |
| 掉头 | `BACK` |
| 终点 | `DEST_AUTONAVI` |
| 环岛进入 | `ISLAND_IN_AUTONAVI` |
| 环岛退出 | `ISLAND_OUT_AUTONAVI` |

常量前缀均为 `METER_GUIDE_DIRECTION_`。这不是所有复杂岔路的逐项实车验收；生产映射仍须用真实 CarPlay 导航数据验证。

## 已有实车证据及边界

2026-10-25 通过实验页发送 Meter 指令，用户观察 HUD 和仪表，已确认：

| 指令 / 场景 | 实际结果 |
| --- | --- |
| 左转 300 米、右转 150 米 | 两屏显示并更新 |
| 直行 500 米、掉头 200 米 | 两屏正确显示 |
| `LEFT_FRONT_AUTONAVI`，300 米 | 两屏正确显示 |
| 右侧岔路 1500 米及长中文道路名 | 图标和距离正常，文字无乱码；未量化截断长度 |
| 距离连续更新 1000、999、500、50、0 米，随后清空道路 | 两屏正常更新，无明显闪烁，旧道路名清除；未测量端到端延迟 |
| `ISLAND_IN_3` 或 `ISLAND_IN_AUTONAVI` | 显示环岛，无出口编号 |
| 通用 `ISLAND_IN` | 本车显示直行，不能仅按 SDK 常量名判断图标 |
| `DEST_AUTONAVI` 与 `ARRIVEDEST` 同时发送 | 两屏显示终点/到达；未证明两个字段各自单独生效 |
| 仅调用 `onMapExit` | HUD 退出，仪表可能残留 |
| 单独发送完整停止状态 | 两屏均清除；退出后能够再次显示引导 |

可靠停止组合为：`NOTINGUIDE` 导航状态、`INVALID` 引导状态与方向、空道路名、0 米。当前代码已将该组合用于生产桥接清理，也用于实验页 CLEAR/销毁清理，再执行实验页原有的 `onMapExit`。

**上述实测针对实验页直接发送的指令，不代表生产 CarPlay 导航链路已经实车通过。** 接入代码完成后的新 APK 尚未安装实车；真实导航、重新规划、取消、断连/重连及修正后的实验页生命周期清理仍待验收。

## 调试状态

复用调试工具：

```sh
python3 pioneer-adapter/tools/diplay_debug.py --host <车机地址> status
```

生产输出在 `status.navigationHud`，包含最后成功的方向/距离、收帧计数和时间、解析时间、Apple 转向类型、状态原因、队列状态、SDK 成败计数、`labHold`、`clearPending`、`clearRetryExhausted`。`active` 是最后成功下发的生产指引状态，`outputMayRemain` 是保守的残留风险提示；`displayVerified` 始终为 false。错误不会抹掉最后成功状态。SDK 长时间阻塞时 worker 的状态时间不再更新，可结合收帧与解析时间定位；代码不尝试强杀 Binder 调用。

调试命令支持 `STATUS`、`OPEN`、`PROBE`、`METER`、`CLEAR`、`RECOVER`、`START_CAPTURE`、`STOP_CAPTURE`、`MARK`。`SELF_CHECK` 是客户端依次执行的驻车序列，不是服务端自动驾驶任务。实验页操作及 RECOVER 须给 CLI 显式传入 `--parked`。这些命令仅允许已识别先锋设备与 DEBUG 构建。

### ADB 实测准备

以下命令在仓库根目录执行。先安装本分支的 standalone debug APK，打开 DiPlay 主页面并启用先锋适配。连接 ADB，使用 `adb devices` 确认设备；多设备时在 `--adb` 后加 `--serial <序列号>`。工具每次自动建立并移除临时端口转发，不要求车机与 Mac 在同一局域网；默认远端 TCP 端口 8721，若车机显示其他端口则传 `--port`。

```sh
python3 pioneer-adapter/tools/diplay_debug.py --adb navigation-hud --action STATUS
python3 pioneer-adapter/tools/diplay_debug.py --adb navigation-hud --action START_CAPTURE --label hud-road-test
```

采集默认关闭。开启后在车机本地保存道路文字、解析后的转向、距离、Meter 尝试与结果、会话和实验事件，含墙钟与单调时钟时间；每 5 秒保存一条状态快照。开启状态跨进程重启保留，重启留下 process_open 事件。文件为 `files/pioneer-hud/current.ndjson` 与 `previous.ndjson`，每个最多约 2 MiB；超限覆盖最旧记录。异步队列最多 512 个事件，丢失计数与磁盘错误见 `capture`。STOP_CAPTURE 停止记录，保留文件；export 会等待已排队的写入完成。ADB 断开不停止采集，车辆/App 进程退出期间无法记录。

**出发前驻车自检（可选）**：先停止 CarPlay，并确认 STATUS 中 `queueAccepting=false`、`labHold=false`。序列会先探测 Meter，再逐项发送左/右/直行/掉头和距离变化，等待每个操作的 requestId 完成标记，最后执行 CLEAR。没有图像确认能力，输出成功只代表 SDK 接受；如需核验投射，出发前固定相机录像。

```sh
python3 pioneer-adapter/tools/diplay_debug.py --adb stop
python3 pioneer-adapter/tools/diplay_debug.py --adb navigation-hud --action SELF_CHECK --parked --hold-seconds 4
```

**行驶阶段**：正常使用手机导航，不运行实验序列，不需要驾驶员操作或反馈。采集不会自动注入合成导航、切路线、断连或重连。

**到达后驻车**：先取消导航，查看清除状态；按需人工重新开始导航、断连/重连并查询 STATUS。记录结束标记、停止采集和导出：

```sh
python3 pioneer-adapter/tools/diplay_debug.py --adb navigation-hud --action MARK --label parked-finish
python3 pioneer-adapter/tools/diplay_debug.py --adb navigation-hud --action STOP_CAPTURE
python3 pioneer-adapter/tools/diplay_debug.py --adb export --output hud-road-test.zip
```

ZIP 含 `status.json` 与 `pioneer-hud/*.ndjson`。离线生成链路摘要：

```sh
python3 pioneer-adapter/tools/hud_capture_report.py hud-road-test.zip --output hud-road-test-summary.json
```

摘要列出 SDK 失败、清除重试耗尽、队列溢出、采集缺失和快照间隙；快照间隙只提示调查，不直接判定显示故障。DEBUG 包也可绕过 TCP 导出原始文件（下面是默认包名）：

```sh
adb exec-out run-as com.gtmc.app.fengyunyx.b819 cat files/pioneer-hud/current.ndjson > hud-current.ndjson
adb exec-out run-as com.gtmc.app.fengyunyx.b819 cat files/pioneer-hud/previous.ndjson > hud-previous.ndjson
```

`previous.ndjson` 仅在首次轮转后存在。只有日志时，结论限于消息接收、解析、SDK 下发和生命周期，不将实际 HUD/仪表显示标为通过。

单项实验示例（先 PROBE，STATUS 中等待“Meter 单例可用”）：

```sh
python3 pioneer-adapter/tools/diplay_debug.py --adb navigation-hud --action PROBE --parked
python3 pioneer-adapter/tools/diplay_debug.py --adb navigation-hud --action METER --parked --direction METER_GUIDE_DIRECTION_RIGHT --distance 150 --road 右转测试路
python3 pioneer-adapter/tools/diplay_debug.py --adb navigation-hud --action CLEAR --parked
python3 pioneer-adapter/tools/diplay_debug.py --adb navigation-hud --action RECOVER --parked
```

实验结果见 STATUS 的 `lab.entries`。`scheduled=true` 只表示已排队；操作完成有 `DONE requestId=...` 或 `ERROR requestId=...` 记录。CLEAR 会释放实验页所有权；真实导航仍有效时会恢复生产指引，因此不保证清屏后始终空白。RECOVER 重新尝试生产清除并恢复有效路线，不打开实验页。

## 真实 CarPlay 链路验收

恢复车机与调试电脑的局域网连接后，只进行一次安装和必要的验证：

1. 停止现有会话并确认空闲，安装包含 `450231b1` 的构建，建立 CarPlay，确认首帧与调试状态可读。
2. 在手机常用地图发起真实路线。检查 `navigationHud.active=true` 且无 error，人工确认两屏方向、距离和道路对应实际路线。
3. 更新或重新规划一次路线，确认状态与画面随之更新，不被旧指引覆盖。
4. 取消并重新开始导航，确认两屏清除后能够恢复。
5. 断连并重连，确认断连无残留，新的导航可继续显示。
6. 实验页发送一条引导后执行 CLEAR，确认新增停止流程同时清除两屏；另行检查退出页面后的清理。

停车时可以验证显示、路线切换、取消和重连；真实距离递减与通过路口的更新需后续行车验证，不能用静态路线变化替代。

排查顺序：从未 active 时检查消息来源和解析；出现 error 时检查 Meter 接口；状态更新而画面异常时检查映射；断连残留时分别检查解析状态、worker 清理和 SDK 实际显示行为。暂不扩展环岛出口编号、原厂导航竞争或长稳回归。

## 构建与验证记录

接入当日已执行 `:mobile:assembleStandaloneDebug`，历史构建日志记录 `BUILD SUCCESSFUL in 38s`，并完成 `git diff --check`。未扩展单测、Lint 或长稳回归；本次文档提交没有重新构建 APK，也没有新增实车测试。

本地构建留存 SHA-256：`3ae8e9e525315e9e1d1b3236f6ae46983958de4de93777593c0e80385508834a`。APK 和原始调试日志保留在本地忽略目录，不随本文提交。

### 2026-11-01 实测准备构建

本次执行 `./gradlew :mobile:assembleStandaloneDebug -Pdiplay.analytics=none --offline --console=plain`，最终 `BUILD SUCCESSFUL in 14s`。使用工作区现有签名、认证与厂商运行时材料，关闭统计 SDK。APK 签名校验、Python 语法检查、CLI TCP 收发冒烟、报告异常识别及驻车参数检查通过；未运行单测、全量 Lint 或实车测试。

本地安装包：`artifacts/DiPlay-HUD-ADB-20261101-debug.apk`（不提交）。SHA-256：`b78bf02eec91d69da721c81a9deb3bc733a9276276ede1173f93d76a35a5fffd`。

连接 ADB 并驻车后安装（多设备时加 `-s <序列号>`），再打开 DiPlay 主页面：

```sh
adb install -r artifacts/DiPlay-HUD-ADB-20261101-debug.apk
```

### 2026-11-02 连续刷新修正

首轮实车日志没有连续完全相同的报文，但同一转向几乎每秒随米数变化重发完整 Meter 引导，个别间隔约 200 ms；用户观察到 HUD 持续闪烁。当前采用 500 ms 合并窗口，按转向索引、方向和道路识别新指引；同一路口仅按距离档位推进，至少间隔 3 秒，忽略小幅距离回跳。距离档位按远近使用 500/100/50/25/10 米，Meter 保留实际下发时的米数。导航清除不受距离限频影响。

`navigationHud.inputDistanceMeters` 为最新解析距离，`distanceMeters` 为最后成功下发距离，`suppressedUpdates` 记录被合并/抑制的刷新次数。真实日志回放使用已编译的策略类，将原 64 次下发缩减为 16 次，验证同一路口间隔至少 3 秒。编译通过；软件限频不等同于实屏闪烁已消除，仍须结合现场反馈。最新包为 `artifacts/DiPlay-HUD-FlickerFix-20261102-debug.apk`，包含 PAL 直连切歌策略。

防连续刷新版本已于 2026-11-02 覆盖安装实车并恢复无线 CarPlay。首轮现场采集中，新策略抑制 245 次细微刷新；抽取本次进程记录，14 次有效输出的同一路口最小间隔为 3025 ms，SDK 失败与队列溢出均为 0。表明软件节流已生效；HUD 实屏闪烁是否完全消失尚未再次确认。采集与 Mac 防休眠、防黑屏继续保持。

### 2026-11-02 小米高德插桩取证与模式修正

在小米 13 安装独立检测包 `com.autonavi.amapauto.hudprobe`（原有高德 9.5 保留），对用户提供的 7.5 工程的标准广播发送点和 JSON 分发点插入只读日志。通过 KEY_TYPE=10076 发起模拟导航，采集到完整开始、经过路口、到达以及显式停止事件，最终 KEY_TYPE=10010 停止模拟。

实测 JSON 30200：模拟开始 autoStatus=33，结束=35；路口场景存在 autoStatus=43→44，前几次间隔 1510、1512、1422 ms。与本地原车 ROM 的 AMapAutoJsonProxy 对照，43 明确映射 setGuideState(2/TBT)，44 映射 setGuideState(1/ARROWGUIDE)。手机抓包共保留 414 条标准引导广播；存在亚秒级和秒级连续更新，因此不能把高频更新本身当作根因。

本次修正：CarPlay 普通转向数据使用 ARROWGUIDE，保留同一份 MeterNaviInfo 状态，进入引导时初始化，后续更新方向、道路和距离；不模拟 CarPlay 未提供的高德 TBT 场景状态。撤销距离分档与 3 秒限制，仅保留完全重复去重和 500 ms 帧合并。clear 继续使用此前实车验证的完整停止组合，未擅自更换停止语义。

`assembleStandaloneDebug -Pdiplay.analytics=none --offline` 构建通过（21s）；新包 `artifacts/DiPlay-HUD-ArrowGuide-20261102-debug.apk`。小米只能证实高德输出和模式时序，不能证明车机 HUD 持久显示已修复；仍需实车验证。系统 TPS 状态覆盖假设尚未排除。

原始日志、状态提取和截图位于本地忽略目录 `agent_docs/temporary/amap-hud-probe-20261102/`。高德检测源单独保留在 `车载软件/amap/build/hud-probe-source`，用 `amap/scripts/build_hud_probe.sh` 构建；其包名与签名固定，后续用 `adb install -r` 覆盖检测版。生产高德工程的 manifest 和被插桩的 smali 已恢复。

多路线覆盖与剩余边界见 [高德模拟覆盖记录](PIONEER_HUD_COVERAGE.md)。环岛出口映射补正后的最新包为 `artifacts/DiPlay-HUD-ArrowGuide-Coverage-20261102-debug.apk`，构建通过（16s），尚未实车验证。

### 2026-11-02 车机连续验证

最新 ArrowGuide-Coverage 包已覆盖安装车机并恢复无线 CarPlay。只读附加 DiPlay 实际 Meter 调用，90 秒内 26 次写入均为 source=1、naviState=1、guideState=1，全部返回成功，无 onMapExit。临时跟踪程序、端口转发和设备端可执行文件已移除。系统进程附加受权限限制，不能声称穷尽排除了其他进程覆盖；同期系统日志未观察到 Meter 缓存重发记录。

连续观察 304 秒、60 次状态采样，新增路线消息 399 条、SDK 成功下发 90 次，涵盖左前与直行指引；SDK 失败、解析错误、队列溢出与监测异常均为 0。原始 capture 检查在导航开始后无额外清屏。结果说明本轮软件链路正常，仍不能替代对物理 HUD 持续显示的独立视觉确认。

证据：`agent_docs/temporary/hud-stability-20261102/`（本地忽略目录），包含更新前后 ZIP、实际调用跟踪、状态采样和汇总。本地 HUD 采集继续开启；Mac 防休眠、防黑屏继续保持。
