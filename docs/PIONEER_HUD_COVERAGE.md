# 高德模拟导航覆盖记录（2026-11-02）

仅验证高德协议输出；不等同于 CarPlay 转向映射或车机 HUD 显示验收。

设备：小米 13；应用：com.autonavi.amapauto.hudprobe。原有高德保留。模拟速度由高德自身控制。

| 路线 | 起点 GCJ-02（纬度，经度） | 终点 GCJ-02 | 引导包 | 图标码 | 自然结束 |
|---|---|---|---:|---|---|
| city_forward | (22.5431, 114.0579) | (22.5526, 114.0648) | 65 | 2, 3, 4, 5, 8, 15 | 是 |
| city_reverse | (22.5526, 114.0648) | (22.5431, 114.0579) | 49 | 2, 3, 5, 15, 65 | 是 |
| short_reverse_candidate | (22.5431, 114.0579) | (22.5424, 114.058) | 18 | 3, 8, 15 | 是 |
| highway_reverse | (22.5431, 114.0579) | (22.5778, 113.9308) | 257 | 2, 3, 5, 14, 15, 20, 65, 66 | 是 |
| roundabout_candidate | (30.5078, 114.392) | (30.503, 114.408) | 71 | 2, 3, 11, 12, 15 | 是 |

合计 460 条标准引导广播，观察到 12 种 NEW_ICON。

| 图标码 | 原车 SDK 常量 / 分类 |
|---|---|
| 2 | METER_GUIDE_DIRECTION_LEFT_AUTONAVI |
| 3 | METER_GUIDE_DIRECTION_RIGHT_AUTONAVI |
| 4 | METER_GUIDE_DIRECTION_LEFT_FRONT_AUTONAVI |
| 5 | METER_GUIDE_DIRECTION_RIGHT_FRONT_AUTONAVI |
| 8 | METER_GUIDE_DIRECTION_LEFT_UTURN_AUTONAVI |
| 11 | METER_GUIDE_DIRECTION_ISLAND_IN_AUTONAVI |
| 12 | METER_GUIDE_DIRECTION_ISLAND_OUT_AUTONAVI |
| 14 | METER_GUIDE_DIRECTION_TOLLGATE_AUTONAVI |
| 15 | METER_GUIDE_DIRECTION_DEST_AUTONAVI |
| 20 | METER_GUIDE_DIRECTION_DIRECT_AUTONAVI |
| 65 | 无对应常量；扩展含义待确认 |
| 66 | 无对应常量；扩展含义待确认 |

生命周期：5 条路线均观察到模拟开始 33 和结束 35；路口场景出现 TBT 43 与 ARROWGUIDE 44。主动路线替换后观察到新目的地的引导；主动取消观察到 35，等待 1 秒后继续观察 4 秒，引导包数为 0。

代码缺口：此前 PioneerNavigationBridge 将环岛出口与入口都输出 ISLAND_IN_AUTONAVI，本次将明确的 ROUNDABOUT_EXIT 单独映射为 ISLAND_OUT_AUTONAVI。复杂环岛出口编号仍未实现，不以本次入口/出口覆盖代替编号验收。

仍未证实：扩展图标 65/66 的语义、途经点专用引导、偏航自动重算、左侧通行/反向环岛、CarPlay 各原始转向类型以及硬件显示持续时间。高德图标码与 DiPlay 内部 BYD 码不是同一枚举，不能直接套用。

原始日志和汇总：agent_docs/temporary/amap-hud-coverage-20261102/（本地忽略目录）。复现工具：pioneer-adapter/tools/amap_hud_coverage.py，必须传 --serial 和 --output；--sdk-source 可选。默认每条路线最多 180 秒，完成或超时后停止模拟。
