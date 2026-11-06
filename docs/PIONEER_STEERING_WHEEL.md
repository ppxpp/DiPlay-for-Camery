# 先锋方向盘切歌输入

适用：已识别并开启先锋适配的凯美瑞车机。

方向盘前后曲只采用 DiPlay 进程自己的 PAL 监听：

```text
PAL cabin input (key 8 / 9, action 1)
  → PalSteeringWheelInputAdapter
  → VehicleMediaRuntime.dispatchCommand
  → CarPlayController / iPhone media command
```

PAL SDK 在后台加载、在主线程请求连接，使用共享监听注册前后曲。长按与音量仍交由系统。去重只针对本进程 PAL 回调的事件签名，不读取爱趣听的事件签名，因此桥接消息先到也不会吞掉直连事件。无活动 CarPlay 会话时不向手机发送命令。

之前同时接受爱趣听隔离保护进程通过 Binder code 2 转发的方控事件，使用共同签名去重，导致两条路径中先到者执行。2026-11-02 起 code 2 只确认接收并计数，不调用手机切歌；确认接收用于避免旧版爱趣听回落为原生播放操作。code 1 是爱趣听界面的显式播放控制，继续保留。播放保护和媒体信息同步仍使用爱趣听服务，与方向盘输入路径独立。

通过 `diplay_debug.py --adb status` 查看 `steeringWheel`：

- `policy=PAL_DIRECT`：物理按键策略。
- `registered` / `accepting`：SDK 监听注册状态与是否接受事件。
- `directEvents`：本进程有效 PAL 切歌事件数。
- `relayedWheelEventsIgnored`：已确认但未执行的爱趣听方控事件数。
- `lastKey` / `lastAction`：最近 PAL 回调，8 为上一曲，9 为下一曲，action 1 为松开。

适配日志用 `source=PAL_DIRECT` 标识直接输入，`CarPlay command=... queued=true` 仅证明发送链路接受，不证明手机实际切歌；仍应结合 iPhone NowPlaying 变化核验。没有真实按键事件时，监听注册成功不算完成物理按键验收。PAL 不可用时报告故障，不恢复爱趣听方控中转。
