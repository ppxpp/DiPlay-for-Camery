# 凯美瑞 HUD 普通诊断包

手机导航中，接近转向点而 HUD 未显示时，记录故障时间，继续保持连接并尽快导出普通诊断包。无需开启调试跟踪；此改动不改变显示距离策略、导航控制权、root 租约或固件白名单。

## 导出文件

- `hud/settings.json`：当前 HUD 开关、距离策略、各类距离和提前程度。
- `hud/state.json`：最新导航解析/输出状态、状态年龄、配置适配模式、导航帧数、无效帧、转向类型、数据年龄、显示阈值及不显示原因。`lastOutput` 保留 SDK 调用开始、返回值、成功/失败和限长异常链；卡住时也可看到最近正在调用的步骤。
- `hud/recent.ndjson`：当前进程最近最多 120 秒的状态记录，稳定状态最多每秒一条，关键状态变化立即保留。上限 256 条、512 KiB，繁忙情况下可能提前淘汰。头部提供实际最早/最新记录时间；进程退出后历史不保留，不应当作完整行车记录。
- `hud/firmware.json`：通过已有共享 root 只读诊断通道执行固定的三个库 `sha256sum`，记录每个库的预期/实际 SHA-256、匹配或读取失败状态、命令退出码。
- `system/logcat.txt`：额外保留 `DiPlay-Pioneer-Navi`、`DiPlay-HudFilter` 的日志，仍属于有限的系统缓冲区。

历史不主动记录道路名、坐标或原始 iAP2 数据。异常链最多保留 4096 字符，SDK 异常链截断有标记，导出按 JSON 字段脱敏，保留布尔校验结果、合法的预期/实际 SHA-256 和完整 JSON 结构；不再对机器可读证据整行截断。

## 区分校验结果

`hud/firmware.json` 的 `scope=export_read_only_not_startup_verdict` 仅证明导出时的读取结果。读取失败或命令非零退出不能判为通过。

真正启动 HUD 过滤器时的校验结果在：

```text
hud/state.json
  originalMap.filter.firmwareVerification
    scope=startup_enforced
    checkedAtMs
    passed
    modules[].path / expectedSha256 / actualSha256 / status / error
```

未启动过、未收到相应结果或适配关闭时该字段可能为空，不能解释成“已通过”。校验继续严格执行，库不匹配或不可读时仍拒绝启动。导出时检查不会启动注入器或延长租约。

## 定位顺序

1. `settings.enabled` / `hudEnabled`：开关是否关闭。
2. 导航接收/解析帧数、`routeReason`、`routeFrameValid` 和 `inputAgeMs`：是否缺输入、解析无效或过期。
3. `reason=waiting_for_approach`、`policyThresholdMeters`：是否未到显示距离。
4. `reason=oem_navigation_ownership_unconfirmed`、`originalMap.error`、`originalMap.filter.state/error`：控制权、过滤器或 root 请求是否失败。
5. 启动校验 `firmwareVerification`：是否被固件白名单阻止。
6. `lastOutput`、`sdkFailures` 和异常链：是否调用 SDK、被 SDK 拒绝或抛异常。

`result=sdk_accepted` 与 `displayVerified=false` 可以同时出现：SDK 接受不等于物理 HUD 已显示，仍需对应用户实际观察时间。

## 系统组件

普通导出不再扫描或复制系统 APK、JAR、ODEX/VDEX 和 native 库，避免大体积二进制挤占采集预算。系统版本、服务/进程、网络/防火墙文本和仪表库哈希继续保留。确需反编译时，工程排查时，可将当前手机端导出链接的 `/report` 改为同一带令牌链接下的 `/report-components`，显式下载含组件的包；两种包独立缓存且沿用相同访问保护。这不是普通用户需要开启的选项。
