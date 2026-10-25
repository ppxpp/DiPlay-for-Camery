# 先锋车机 CarPlay 导航 HUD 与仪表接入

适用分支：`feat/camry-navigation-hud-lab`。本文记录截至 2026-10-25 的实现、已验证能力与实车验收步骤。HUD 导航接入代码位于提交 `450231b1`。

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
- 每秒检查路线解析器的过期或空状态，避免停止更新后永久显示旧指引。
- 导航结束、取消、控制器关闭或连接结束时，清空解析状态并发送停止引导状态。
- 初始化时主动发送停止状态，尝试清除上一个进程遗留的显示。强制杀进程时不能保证退出回调执行，仍需验证下次启动后的恢复。
- 未知转向不猜测，清除当前输出。
- 未实现环岛出口编号，也未处理与原厂导航同时输出的竞争策略。

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
| 环岛 | `ISLAND_IN_AUTONAVI` |

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

生产输出在 `status.navigationHud`，包含 `active`、`direction`、`distanceMeters`、`updatedAtMs`，失败时包含 `error`，不回传实际道路文字。

实验页使用调试版命令 `navigation-hud`，action 为 `STATUS`、`OPEN`、`PROBE`、`METER`、`CLEAR`。目前没有对应 CLI 子命令，可通过工具模块的 `request(args, "navigation-hud", action=...)` 调用。仅允许已识别先锋设备和 DEBUG 构建，通过内部 Intent 操作非导出的实验页。

- PROBE 后等待状态出现“Meter 单例可用”，再发 METER。
- METER 示例：`direction=METER_GUIDE_DIRECTION_RIGHT`、`distance=150`、`road=右转测试路`。
- 正常停止：`direction=METER_GUIDE_DIRECTION_INVALID`、`guideState=METER_GUIDE_STATE_INVALID`、`naviState=METER_NAVI_STATE_NOTINGUIDE`、`distance=0`、`road=""`。
- 实验结果从 STATUS 的 `lab.entries` 读取。
- `scheduled=true` 仅表示操作排队，SDK 返回成功也不替代实屏确认。

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
