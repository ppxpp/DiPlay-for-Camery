# CarPlay 蓝牙协商测试

独立测试 APK，1280×720 横屏，默认包名 `com.tencent.wecarflow`（沿用车机已安装探针的身份）。依赖当前 DiPlay `shared`，最低 Android 6.0。

在项目根执行 `./gradlew :bluetooth-probe:assembleDebug`。输出 `bluetooth-probe/build/outputs/apk/debug/bluetooth-probe-debug.apk`。

研究结论与固件证据统一维护在 [先锋内置蓝牙接入路线](../docs/2026-10-26-0723-先锋车机蓝牙接入无线CarPlay技术路线与验证报告.md)。当前版本0.9.0先审计并按需补注册服务内部串口回调，再使用Blueforce原生收发，并复用DiPlay的Iap2Session、Iap2MfiAuthenticationClient、Iap2WirelessControlClient、CarPlayMediaEngine和Bonjour。完整测试覆盖CarPlay蓝牙控制工作并验证真实Wi-Fi接管；基础link测试仍保留用于定位故障。

默认targetSdk23用于Android7车机的私有库加载兼容实验。现代手机USB测试可用 `-PprobeTargetSdk=37`。车机入口只允许ARM32、已知指纹和精确匹配的libblueforce.so SHA-256；不携带厂商库。主动验证复用DiPlay现有的root服务调度，执行已知固件专用的回调审计与按需修复；不安装su、不刷写ROM。

### 构建与认证素材

认证使用DiPlay已有的 `LocalMfiAuthenticationClient`，从 `.private/runtime-assets/offline-mfi/identity.pk8` 和 `certificate.p7b` 装入。可用 `-PprobeAuthenticationAssetsDir=/实际素材根目录` 或 `DIPLAY_AUTH_ASSETS_DIR` 指定同一素材根目录。构建前检查文件存在、大小和目录内容；素材不进入Git。首次启动会复制到本应用noBackupFilesDir并验证密钥/证书匹配，不读取已安装DiPlay的私有数据。接收器配对身份另在本应用内持续保存。

本地网络默认值来自忽略的 `.private/probe-network-assets/probe-network.properties`，打包为 `probe-network.properties`；源码和报告不写入密码。

### 下次上车步骤

1. 使用 `/Users/zhouhao/work/project/carplay/DiPlay/.signing` 签名。确认 `com.tencent.wecarflow` 安装的是测试探针，再用 `adb install -r -d` 覆盖升级；不要覆盖真实QQ音乐或卸载原应用。
2. 车机和待测iPhone都连接现有Wi-Fi `Xiao`。本地测试包已预置用户提供的密码，页面可修改。`Xiao`应由另一台手机或路由器提供。程序复用ExistingWifiManager读取车机的实际接口、IP及信道，不要求车机开启热点，也不自动切换Wi-Fi。密码仅保存在忽略的本地部署素材和应用私有设置中，不写入导出报告。
3. 结束其他DiPlay会话。完整测试使用先锋放行的8721监听端口；如果此前临时将adbd切到8721，先恢复 `adb usb`，再运行测试。
4. 选择iPhone，先点“只读审计服务内部回调（root）”。本次现场若仍留有状态3/profile130，先点“清理目标残留并恢复原 profile（root）”，原掩码输入此前记录的2（若有本APP保存的回滚记录，优先使用该记录）。随后主动测试会自动审计、按需补注册缺失回调并核对空闲基线；目标已有串口配置位时点“完整蓝牙阶段：MFi → CarPlay → Wi-Fi 接管”。只有HFP/A2DP（mask3）时用“单设备临时配置 → 完整蓝牙阶段 → 恢复”。临时配置要求配对列表只有一台设备，程序不会删除其他配对。
5. 保持手机解锁，确认手机上的CarPlay提示，保持探针页面前台。身份识别/MFi及蓝牙控制窗口90秒，Wi-Fi接管总窗口120秒（不含预检和串口建连）。等待自动收尾；停止按钮会取消等待并清理本次连接。
6. 导出报告，检查原profile恢复和端口释放。若提示“需要人工恢复profile”，按报告原值从原车界面恢复；被杀进程或断电也应检查配置。

### 回调修复与等待边界

固件启动路径遗漏了 `CBlueforcePower+0x58` 串口回调注册。本版先读取运行中服务的真实回调列表，只有其它7个已知回调、对象/vtable、指纹、文件SHA-256及代码锚点均匹配时才允许修复。若回调已经存在则跳过。root helper通过ptrace在服务空闲的主Binder线程调用原有注册函数，使用服务原有对象；调用完成后恢复寄存器/VFP/临时栈并退出。不是在应用进程重新初始化硬件栈，也不把补注册当作连接成功。

该原生进程操作尚未实车验证。权限、布局或线程状态不满足时停止；调用开始后若超时/异常，终止本次蓝牙服务，避免恢复到可能持锁的半执行状态，正常服务重启由原车init管理。结果为 `SERVICE_RESTART_REQUIRED` 时本轮停止，检查原车服务状态后重新审计，不能继续连接。不会改写ROM或删除配对。服务重启后必须重新审计/补注册。

残留清理先核对底层对象：目标确实连接时正常断开；所有底层串口连续两次空闲时，才允许调用原有setter清除上层残留2/3。不会将底层强置为已连接，清理后再次审计。配置恢复要求仅一台配对设备，避免connectProfile改变其它设备配置。

同UID宿主每次写入后最多等待3秒的event504发送结果，再允许下一块；Kotlin控制通道以poll限制单次回复5秒，直接Binder调用限制3秒。主协议成功仍由手机真实回包及后续认证证明。停止操作会执行有界清理，恢复失败时保留原配置记录并明确报告。

### 判据

| 输出 | 含义 |
| --- | --- |
| `iap2Verified=true` | 基础按钮的link验证，不包含MFi或无线控制。 |
| “蓝牙身份识别、MFi AA05、订阅均已完成” | 既有控制客户端已通过手机身份识别和认证成功应答。 |
| `bluetoothControlComplete=true` | 上述阶段及Wi-Fi配置、CarPlay StartSession、无线可用通知、transport通知和后续配置交付均有证据。 |
| `wifiRuntimeAuthenticated=true` | 真正的Wi-Fi type130 tunnel完成身份识别/MFi；TCP接入本身不满足。 |
| `bluetoothWorkVerified=true` | 蓝牙控制条件和实际Wi-Fi接管均满足；这是完整蓝牙阶段的验收标记。 |

仅发送0x4301、写入返回0或TCP接入均不会单独算成功。探针接收端用于验证交接，不做画面渲染、扬声器或触控验收。Wi-Fi接管失败会与蓝牙控制交付成功分开记录。程序仅对基础link/只读排查保留字节预览；完整测试不将Wi-Fi密码、证书/签名的原始载荷写入报告。

ADB完整动作使用明确目标，热点设置默认使用页面已保存的配置：

```sh
adb shell am start -f 0x24000000 -n com.tencent.wecarflow/com.shilapi.xcertplay.btprobe.ProbeActivity --es probe_action blueforce_carplay --es target_address AA:BB:CC:DD:EE:FF
adb shell am start -f 0x24000000 -n com.tencent.wecarflow/com.shilapi.xcertplay.btprobe.ProbeActivity --es probe_action blueforce_prepare_carplay --es target_address AA:BB:CC:DD:EE:FF
```

另有三个维护动作（均不代表CarPlay成功）：

```sh
adb shell am start -f 0x24000000 -n com.tencent.wecarflow/com.shilapi.xcertplay.btprobe.ProbeActivity --es probe_action blueforce_callback_audit
adb shell am start -f 0x24000000 -n com.tencent.wecarflow/com.shilapi.xcertplay.btprobe.ProbeActivity --es probe_action blueforce_callback_repair
adb shell am start -f 0x24000000 -n com.tencent.wecarflow/com.shilapi.xcertplay.btprobe.ProbeActivity --es probe_action blueforce_restore_profile --es target_address AA:BB:CC:DD:EE:FF --ei profile_mask 2
```

可选extras为 `hotspot_ssid`、`hotspot_password`（沿用原extras名称，表示现有Wi-Fi配置）。原 `bluetooth`（只读）、`blueforce_observe`、`blueforce_iap2` 和 `blueforce_prepare_iap2`（基础link）继续有效。报告保存在应用私有 `files/reports/bluetooth-negotiation.txt`，并尽可能复制到 `Android/data/com.tencent.wecarflow/files/bluetooth-negotiation.txt`。音源和USB验证入口保留。

## USB 蓝牙模块测试页（`UsbBtProbeActivity`）

验证 OTG 上的 USB 蓝牙适配器（HCI Rf Transport，USB 类 0xE0）能否由普通 App 直接驱动，用于 CarPlay 协商的前置判断。左侧按钮或 ADB 触发：

```sh
adb shell am start -f 0x24000000 -n com.tencent.wecarflow/com.shilapi.xcertplay.btprobe.UsbBtProbeActivity --es probe_action usb_bt
adb shell am start -f 0x24000000 -n com.tencent.wecarflow/com.shilapi.xcertplay.btprobe.UsbBtProbeActivity --es probe_action usb_bt_fast
```

分步取证：枚举并识别 0xE0/0x01/0x01 接口 → USB 权限（系统弹窗需点"确定"）→ `GET_CONFIGURATION`/占用全部无线接口 → `CLEAR_FEATURE(ENDPOINT_HALT)` → 发 HCI Reset / Read Local Version 并读回事件（中断 IN 与 bulk IN 都轮询，`bulkTransfer` 连续失败后改 `UsbRequest`+`requestWait`）。

`usb_bt_fast` 只做"占用全部接口 + 一条 HCI Reset"，3 秒窗口。**小米/MIUI 会在约 10 秒级回收探针进程**，长测试的尾部证据会丢，因此判定实验用快测跑。

2026-10-16 小米 13（Android 15，无 root）+ Actions ATS2851（`10d7:b012`，绿联 CM591 方案）实测：枚举/权限/占用/配置/写 HCI 命令全部成功，中断 IN 与 bulk IN 在任何读取方式下都收不到 HCI 事件。已排除设备未配置、端点选错、残留 halt、模块需要固件（主线 `btusb.c` 对 `10d7:0xb012` 只有 `BTUSB_ACTIONS_SEMI` 的 `HCI_QUIRK_BROKEN_*` 标记，无 `.firmware`）。剩余歧义与决定性对照实验（换 CSR8510 `0a12:0001` 重跑本页）见报告判定行。该机型内核没有 `btusb`/`/sys/class/bluetooth`，所以 root 后走 BlueZ 也不可用。

## 原生 URB 归因实验（`src/main/cpp/usb_bt_hci.c`）

Java 的 `bulkTransfer` 只回 `-1`，分不清"设备没数据"和"内核拒绝"。原生实验借用同一个已授权 fd，把三件事分开测：

| 判据 | 含义 |
| --- | --- |
| `SYNC_BULK ret=4` | 同步 `USBDEVFS_BULK` 把 HCI Reset 送达设备（USB 层已 ACK），写通路可用 |
| `SYNC_IN ret=-1 errno=110` | 同步读 interrupt IN 返回 `ETIMEDOUT` —— 内核确实去取了，是设备没有数据；权限/参数问题会是 `EPERM/EINVAL/ENOENT` |
| `IN_ARMED / OUT_STATUS / IN_STATUS=none` | 异步 `SUBMITURB` 提交成功但从不完成（同 fd 上同步路径正常）——本 App 的 HCI 传输只能走同步阻塞读写 |

2026-10-16 小米 13（Android 15，无 root）+ Actions ATS2851（`10d7:b012`）两次运行结果一致：

```
GETDRIVER if=0 ret=0 errno=0 driver=usbfs | IN_ARMED=3 | SYNC_BULK ret=4 errno=0 |
IN_STATUS=none reap_errno=0 | IN_EVENTS=0 | OUT_STATUS=none reap_errno=0 | SYNC_IN ret=-1 errno=110
```

判定：**App 的 USB 读写通路可用，模块对 HCI Reset 不回任何事件 → 归因于模块，不是 Android 权限层。**
下一步归因实验：换无固件依赖、Linux 用普通 HCI 即可驱动的模块（CSR8510 `0a12:0001`）跑 `usb_bt_fast`。

报告：`/sdcard/Android/data/<实际包名>/files/bluetooth-negotiation.txt`。音源、MainToken/MixToken 和输出总线需同时由 ADB 保存 `dumpsys mediamanager.mediamanagerservice`、`dumpsys media.audio_flinger` 与 `dumpsys media.audio_policy`。

USB 蓝牙页报告：`run-as com.tencent.wecarflow cat files/reports/usb-bluetooth.txt`。

## 蓝牙排查策略

实机异常优先从匹配版本的固件分析实际调用、回调注册与分发、锁/等待及恢复逻辑，再做最小针对性修改。区分上层缓存、底层连接、真实协议响应和手机认证；固件不能替代本次手机响应。模块后续工作遵循 [蓝牙模块排查策略](AGENTS.md)。只运行当前变更必要的构建和验证，最后统一更新已有中文日期时间报告。

## 车机自身的5GHz热点验证

先启动厂商热点，再切换5GHz；切换后会核对并补回bcm1在Android local_network表中的连接网段路由，以恢复DHCP和本地通信。运行中的AP只改frequency并SELECT不会立即换频，诊断助手会停用并重新启用网络。手机业务前需要为专用AP接口安装通行规则。以下动作也有页面按钮：

```sh
adb shell am start -f 0x24000000 -n com.tencent.wecarflow/com.shilapi.xcertplay.btprobe.ProbeActivity --es probe_action wifi_hotspot_start
adb shell am start -f 0x24000000 -n com.tencent.wecarflow/com.shilapi.xcertplay.btprobe.ProbeActivity --es probe_action wifi_hotspot_5g
adb shell am start -f 0x24000000 -n com.tencent.wecarflow/com.shilapi.xcertplay.btprobe.ProbeActivity --es probe_action wifi_hotspot_firewall
adb shell am start -f 0x24000000 -n com.tencent.wecarflow/com.shilapi.xcertplay.btprobe.ProbeActivity --es probe_action wifi_hotspot_status
adb shell am start -f 0x24000000 -n com.tencent.wecarflow/com.shilapi.xcertplay.btprobe.ProbeActivity --es probe_action blueforce_prepare_carplay_ap --es target_address AA:BB:CC:DD:EE:FF
adb shell am start -f 0x24000000 -n com.tencent.wecarflow/com.shilapi.xcertplay.btprobe.ProbeActivity --es probe_action wifi_hotspot_stop
```

AP交接动作从系统读取热点配置并附着bcm1，不使用页面里的Xiao配置；不打印热点密码。独立内核审计工具为 `tools/wifi_nl_audit.c`，使用NDK ARM32编译后可从ADB运行，不需要root；以iftype=AP及实际frequency_mhz核对热点，不能将STA频率当作AP证据。supplicant与内核精确信道存在差异时须保留两份结果。
