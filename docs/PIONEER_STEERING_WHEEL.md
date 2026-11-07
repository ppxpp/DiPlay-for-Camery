# 先锋方向盘切歌输入

适用：已识别并开启先锋适配的凯美瑞车机，需要安装兼容的爱趣听隔离保护服务。

0.5.1 恢复 0.4.0 的爱趣听方控中转处理，DiPlay 不再注册自己的 PAL 方向盘监听：

```text
方向盘 → 爱趣听 PAL 监听 → 隔离保护服务 Binder code 2（命令 + 事件签名）
  → QQMusicPlaybackGuardClient → VehicleMediaRuntime 会话校验
  → CarPlayController → iPhone media command
```

Binder code 2 的命令值 4 为下一曲，5 为上一曲。沿用 0.4.0 的事件签名去重：2 秒内相同签名只执行一次，最多保留 64 条。重复事件仍返回已处理，避免爱趣听回退为原生播放操作。无活动 CarPlay 会话、旧会话或已关闭的客户端不发送命令。

Binder code 1 继续处理爱趣听界面的显式播放控制。播放保护、仪表媒体信息同步和播放/暂停转发设置保持现有逻辑。DiPlay 的 PAL SDK 仅用于麦克风诊断，不订阅物理方向盘按键；切歌不依赖 DiPlay 的 PAL SDK 是否加载成功。

通过 `diplay_debug.py --adb status` 查看 `steeringWheel.policy=COMPANION_RELAY`。适配日志 `companion command=NEXT/PREVIOUS bridgeCode=2 duplicate=false` 表示收到新的中转命令，`duplicate=true` 表示重复事件已确认但不再执行。控制器日志 `source=vehicle_companion` 用于继续追踪手机发送链路。

验收时连接 CarPlay，分别短按上一曲和下一曲，并核对 iPhone NowPlaying 变化；同一事件重复送达不得多切一首。断开 CarPlay 后不得向旧手机会话发送命令。注册成功或命令入队不等于物理按键已完成实车验收。
