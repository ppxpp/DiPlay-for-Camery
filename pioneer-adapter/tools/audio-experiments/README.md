# 一次安装，通过 TCP 对照录音与通话策略

参数切换只允许调试版；正式版本使用已验证的默认参数。无需为每组参数重新安装 APK，但切换前必须停止 CarPlay、等待音频线程退出，设置完成后重新连接。2026-10-24最后一轮，用户确认微信语音、普通电话、Siri均正常，成功配置已固化为00-baseline。其他对照未必通过，不能一概当作可用方案。

先在仓库根目录执行（IP以车机实际显示为准）：

```sh
python3 pioneer-adapter/tools/diplay_debug.py --host 172.20.10.5 audio-experiments
python3 pioneer-adapter/tools/diplay_debug.py --host 172.20.10.5 stop
```

继续查 `status`，等 controllerRunning/backgroundSession/有线会话均停止；再查 `audio-experiments`，确认 `activeWorkers=0`。不要把“stop已排队”当成已经停止。

设置一整组预设，再启动连接：

```sh
python3 pioneer-adapter/tools/diplay_debug.py --host 172.20.10.5 audio-experiments --action SET --config-file pioneer-adapter/tools/audio-experiments/00-baseline.json
python3 pioneer-adapter/tools/diplay_debug.py --host 172.20.10.5 start-wired
```

电话由用户发起。每轮记录铃声、接通后双方收音、音乐是否停止、回声和音调。`configuration` 是选定的配置，`host.microphoneUplink` 与播放/采集日志才说明实际发生了什么。

## 已固化的基准与对照

00-baseline现在与实测通过的21-rollback-call-pcm10一致：通话400、通信模式、DiPlay麦克风身份、关闭持续归属恢复、保留媒体选源顺序、超限PCM包缩为10毫秒。默认参数也使用这一组，重启无需TCP再设。

22—26是相对新基准的单项对照：旧分包、开启归属恢复、保留电话音源、保持模式、402输出。01—20保留之前探索时的完整参数，属于历史对照，不再保证只比新基准改变一项；切换前应查看完整JSON。

原对照清单（01—20历史配置）：

每个JSON文件都包含完整参数，不会继承上一组遗漏的参数。`testId`对应文件名，日志也记录配置修订号。建议先单项对照，再测第19组组合，不要盲跑全部排列组合。

| 文件前缀 | 与基准相比改变什么 |
| --- | --- |
| 00 | 已验证基准：应用申请麦克风、400通话、通信模式、关闭归属恢复、保留音乐保护、超出动态包预算的PCM自动分档，默认缩短上限10毫秒 |
| 01 | 关闭麦克风归属恢复 |
| 02 / 03 | 强制通信模式 / 强制普通模式 |
| 04 / 05 | 输入改成语音通信源 / 语音识别源；不支持会失败，不悄悄换入口 |
| 06 | 先申请麦克风、再切媒体源；用于验证旧顺序的问题，可能直接失败 |
| 07 | 麦克风申请身份和通话音轨身份均使用原生微信身份 |
| 08 | 通话音轨显式使用DiPlay身份 |
| 09 | 关闭持续归属恢复，改为录音开始1秒后重新申请一次 |
| 10 | 关闭软件回声消除与可控制的系统音效 |
| 11 | 强制软件回声消除，关闭系统音效；不可用则失败 |
| 12 | 关闭软件回声消除，强制系统音效；不可用则失败 |
| 13 / 14 | 通话输出改成400 / 401，作为对照；用户已确认正常基准402走电话通道 |
| 15 / 16 | 通话请求允许混音的临时焦点 / 不请求通话焦点 |
| 17 | 同时关闭伴随服务PLAY拦截和本地通话音乐静音，作负向对照 |
| 18 | 不指定内置麦克风，交给系统选输入设备 |
| 19 | 应用音轨身份 + 通信模式 + 开始录音1秒后重新申请的组合 |

`WECHAT`指用`com.tencent.mm`身份调用车机接口，不会启动微信应用，也不证明该身份在这台固件上可用。音轨身份接口失败会记录失败；不能按请求值宣称已生效。

## 还可通过JSON自由组合的参数

| 参数 | 值 | 含义 |
| --- | --- | --- |
| testId | 1–64位字母、数字、点、横线或下划线 | 本轮编号，每次SET必填 |
| micSource | MIC / VOICE_RECOGNITION / VOICE_COMMUNICATION | 录音输入源；Pioneer的显式选择不做静默回退 |
| micIdentity | APP / WECHAT | 向车机申请麦克风时使用的应用身份 |
| playbackIdentity | DEFAULT / APP / WECHAT | 保持默认音轨身份，或显式设置通话音轨身份 |
| callMode | KEEP / NORMAL / COMMUNICATION | 保持现状，或在通话期间设置普通/通信模式；结束后恢复原模式 |
| micGrantOrder | SOURCE_FIRST / MIC_FIRST | 切源与初次申请麦克风的先后顺序 |
| grantDelayMs / sourceDelayMs | 0–1000 | 麦克风申请后 / 媒体源切换后的等待时间 |
| reassertAfterStartMs | 0–3000 | 0关闭；从录音采集线程开始计时，延迟重新申请一次，不是等待实际扬声器首帧 |
| ownershipRecovery | true / false | 是否在通话中检查并恢复麦克风归属 |
| ownershipCheckMs | 250–5000 | 检查间隔，恢复开启时生效 |
| ownershipMaxRestores | 1–10 | 一条采集流最多恢复次数，不含延迟申请的一次 |
| preferredBuiltin | true / false | 是否优先选择内置麦克风 |
| softwareAec | DEFAULT / ON / OFF | 跟随现有设置，或强制开启/关闭软件回声消除；只用于通话 |
| platformEffects | DEFAULT / ON / OFF | 跟随现有策略，或强制开启/关闭可控制的系统AEC/降噪；只用于通话 |
| callStream | 400 / 401 / 402 | 通话输出的对照路径编号 |
| callFocus | TRANSIENT / MIX / NONE | 临时焦点 / 允许混音的临时焦点 / 不申请通话焦点 |
| blockCompanionPlay | true / false | 通话期间是否阻止配套服务PLAY转发到iPhone |
| muteLocalMedia | true / false | 是否将DiPlay本地媒体在通话期间保持静音 |

新增参数：

- callSourcePolicy：MEDIA_300保留成功轮的选源策略（默认）；PRESERVE_PHONE用于对照，当前源为302时不切回300。
- oversizePcmPacketMs：0保留旧分包用于调试；5/10/20作为超限时分包时长的上限，按20→10→5毫秒选择可容纳的最长一档。原包能容纳则不改；无法容纳任何支持的档位会拒绝。采样率、声道和Opus保持原样。

MTU预算不再固定1500：通过相同绑定的UDP路由查询（不发数据包）读取实际出口接口MTU，显式绑定网络时还核对其LinkProperties。只知道本地接口不能证明整条路径可用，所以取接口限制与保守路径预算的较小值：IPv6为1280、IPv4为576；未知或回环出口使用保守预算。日志区分interfaceMtu和实际采用的mtu，不声称完成了PMTU探测。

这会改变普通电话的分包：32kHz单声道20毫秒约1364字节，在1280预算下也改成10毫秒。48kHz单声道为10毫秒，48kHz双声道可降到5毫秒。旧版实车通过记录不能直接当作新算法已通过，新包须复测。

用户确认最后一轮通过，说明该配置在这次实测有效；尚未直接抓包证明网络分片必然是此前无声的唯一根因。

软件与系统回声消除不能同时强制ON。强制关闭的系统效果如果被固件拒绝，测试失败；不会按“已关闭”继续。强制软件ON运行失败也不切换成另一种方案。DEFAULT保留原有回退并记录实际状态。

未知参数、错误类型、越界值和冲突组合会整体拒绝，不会只应用一半。SET是对当前参数做成组修改；若希望完全确定，使用完整预设或先RESET。

**不提供正式上行采样率强制开关**：遵循iPhone协商，避免没有重采样转换却发出错误格式。独立录音页仍可通过已有`car-mic-test`比较16/32/48kHz和增益等参数，但其结果不能代替正式通话。共享的车机麦克风申请顺序、身份和等待时间也会影响有线会话中的测试页；测试页自己的采样参数保持独立。

参数不保存到磁盘，应用进程重启后回到基准。只控制Pioneer的相关适配，不恢复已挂起的Siri长播报音乐专项。

## 保存本轮听感与恢复基准

FEEDBACK无需停止通话，测试编号必须匹配当前配置。反馈会连同完整配置、修订号、时间写入adapter日志；状态保留最近20条反馈。

```sh
python3 pioneer-adapter/tools/diplay_debug.py --host 172.20.10.5 audio-experiments --action FEEDBACK --test-id 00-baseline --feedback '{"peerHears":false,"phoneRoute":true,"musicStopped":true,"notes":"对方仍听不到"}'
```

还支持`echo`、`pitchWrong`布尔反馈。再次SET可以复用一组方案，但比较日志时应同时看配置修订号。切换前先保存反馈，防止记错组。

恢复基准也先stop并等待退出：

```sh
python3 pioneer-adapter/tools/diplay_debug.py --host 172.20.10.5 audio-experiments --action RESET
```

每轮保存session与adapter日志，关注：`Audio experiment configured`、`Audio experiment playback`、`Audio experiment microphone`、`Audio experiment feedback`，以及实际麦克风归属、模式、采样率、软件/系统效果状态、原始/发送前RMS与零值比例。发送无错误不等于对方听得见。
