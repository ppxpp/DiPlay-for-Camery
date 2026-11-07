# USB 与防火墙失败诊断

此改动只补充错误证据，不改变 USB 请求大小、超时、重试、错误分类或防火墙规则。

## USB

Android 原生日志 `DiPlay-USB` 在取消/回收未完成请求之前记录一次 `VPN_USB_FAILURE`，防止清理阻塞时丢失首个失败。正常轮询超时和成功停止不产生失败记录。清理开始后停止收集失败，避免把取消 URB 产生的完成状态覆盖到原始错误上；仍需结合 `runningAtFailure` 与会话清理时间判断是否正在主动停止。

失败异常继续进入应用日志，保留原有 `VPN USBFS receiver/sender failed` 前缀，并增加：

- `operation`：提交 URB、收割 URB、内核完成状态、同步 BULK 回复，或处理输入/构建输出阶段。
- `rc`：原来用于结束运行的返回码，行为保持不变。
- `errno`、`urbStatus`：提交/收割 ioctl 的错误码或原始 URB 完成状态；完成状态原先可能被归一为 `-EIO`，现在可据此区分。没有确切错误码时显示 `not_recorded`，不把普通回调的负返回值硬解释为 errno。
- `slot`、`fd`、端点、`runningAtFailure`、`runningAfterCleanup`：失败所在请求槽、USB 文件描述符、传输端点及清理前后的运行状态。同步 BULK 或无法识别的请求槽记为 `-1`。

Java USBMUX 请求提交失败时，现有 API、端点、超时、尝试大小信息额外包含 `usbFd` 和 `sessionClosed`。Android `UsbRequest.queue` 的 false 返回本身不提供 errno，不能从此日志臆测底层错误码。

## 防火墙

读取链或修改规则的命令非零退出时，错误包含具体命令、退出码和合并的 stdout/stderr。错误输出限制为 1024 字符，控制字符转换为空格，超出部分标记 `[truncated]`，空输出标记 `[empty]`。不会输出成功读取的整张规则表，也不会重放失败的修改命令。

例如：锁占用、权限拒绝、链不存在等，应以命令实际输出为准；不能仅凭 `CompletionUnknown` 推断为命令超时。

## 诊断包

`system/logcat.txt` 增加 `DiPlay-USB` 和 Android USB 服务日志。`system/kernel_usb.txt` 通过现有共享 root 服务的只读诊断通道读取 `dmesg`，沿用已有执行时限、输出上限和脱敏处理，仅保留 USB/控制器相关行并排除设备序列号行。

`dmesg` 被拒绝、命令失败或输出截断会在 manifest 中保留相应状态，不能当作“内核没有 USB 错误”。内核和 logcat 都是导出时的有限缓冲区，复现后应尽快导出完整诊断包。
