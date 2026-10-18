# DiPlay

## 凯美瑞车机适配版（本仓库重点）

> **本仓库重点维护丰田凯美瑞先锋车机的 CarPlay 适配。** 在 DiPlay 上游功能的基础上，针对原车的连接方式、声音通道、方向盘按键和日夜模式做专门处理，并与原车爱趣听（WeCarFlow）协同工作。

本分支应用名为 **DiPlay-凯美瑞A导航**，包名为 `com.gtmc.app.fengyunyx.b819`，目前已完整合入上游 **v0.2.21**。目标是让 CarPlay 融入原车的操作习惯：可以用原车按键切歌、跟随车机切换白天和黑夜，并减少两个音乐应用争抢声音的情况。

### 凯美瑞适配做了什么

| 适配内容 | 对日常使用的作用与当前进度 |
| --- | --- |
| 先锋车机连接 | 保留先锋固件所需的有线连接与数据传输处理，接入上游连接超时和重试修复；升级后的稳定性仍需实车回归。 |
| 原车声音通道 | 分别处理 CarPlay 音乐、导航提示和通话的声音需求，配合车机自己的音源管理；MODE 切换后回到爱趣听无声的问题仍待闭环验证。 |
| 方向盘按键与爱趣听协同 | 通过 `wecarflow/` 中的独立桥接服务处理前后曲按键和播放冲突。DiPlay 启动时只拉起所需桥接逻辑，避免同时启动原播放器业务；重启车机后的效果仍需回归。 |
| CarPlay 音乐使用提醒 | 保护策略生效时，打开爱趣听会提示优先使用 CarPlay 音乐。关闭提示不解除保护；勾选“本次不再提示”后，爱趣听主进程重启前不再弹出。 |
| 白天／黑夜同步 | 已在真实车机上走通，CarPlay 可以跟随原车昼夜状态切换；日夜测试悬浮窗和设置入口已删除。 |
| 页面切换与交互 | 已缩短切到后台时等待画面资源释放的时间，减少界面被拖住的机会；流畅度、画面恢复及长时间运行仍需实车确认。 |

### 两个应用怎样配合

**DiPlay** 负责连接 iPhone、显示 CarPlay、播放声音和接收触控；**WeCarFlow 桥接**负责对接原车按键、播放状态和冲突保护。CarPlay 连接并启用保护后，原车控制应交给 CarPlay，避免爱趣听与 iPhone 同时播放。只关闭提醒窗口，不会改变这套保护规则。

本分支保留 Android 6.0（API23）兼容处理及 DiPlay／WeCarFlow 共用本地签名。已完成的适配针对当前凯美瑞先锋车机，不代表所有凯美瑞年款、车机固件或其他丰田车型都已验证。**电话对方听不见、MODE 回切无声和交互流畅度，仍是后续实车验证重点。**

详细记录见[本轮调试进度与后续计划](docs/vehicle-adaptation/2026-10-18-0753-车机调试进度与v0.2.21合入计划.md)、[DiPlay 与 WeCarFlow 协同说明](docs/vehicle-adaptation/2026-10-16-2238-DiPlay与WeCarFlow冲突处理及弹窗说明.md)及 [WeCarFlow 项目说明](wecarflow/README.md)。

**下方保留上游项目介绍。** 其中比亚迪支持范围、`com.shihab.diplay` 包名、官方下载及问题反馈入口均属于上游；凯美瑞适配包与上游原版有区别，请结合本节及适配记录阅读。

[English](README.md) · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-TW.md)

**CarPlay for compatible BYD Android head units.** Wired and wireless, with the familiar DiAuto interface. Independent app: `com.shihab.diplay`.

> **BYD support scope:** These projects focus on BYD cars. They may work on other brands, but other brands are unsupported and there are no plans to add support or fix brand-specific incompatibilities.

> **Disclaimer:** DiPlay is not affiliated with, endorsed by, or authorized by Apple and is not an MFi-certified product. The software is provided **as-is** with no warranty (GPL-3.0). Driving-safety, regulatory, privacy and intellectual-property risks from use are your own responsibility — read [DISCLAIMER.md](DISCLAIMER.md) before installing or distributing.

[Download & website](https://shihabal3amri.github.io/DiPlay/) · [Release](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.21) · [Report a problem](https://github.com/shihabal3amri/DiPlay/issues/new/choose)

![DiPlay home](site/assets/home.png)

## 0.2.21 — public preview

Install on the **car**, not the iPhone. No jailbreak, dongle, Mac, account or authentication server is required for use. Core CarPlay does not require ADB; optional dashboard, battery, wheel-speed and parked-video features do. Your head unit must permit APK installation. The mobile APK targets Android 6.0+ (API 23); Android 6.0–8.1 CarPlay support is not yet confirmed on a vehicle. API 23 startup/settings checks pass; see [Android 6 compatibility](docs/ANDROID6_COMPATIBILITY.md). Wireless supports Wi-Fi Direct, the car’s existing hotspot or Existing Wi-Fi / Same LAN. Android 6.0–9 Wi-Fi Direct uses a firmware-dependent legacy path with generated group credentials and unverified requested frequency; see [Android 9 Wi-Fi Direct](docs/ANDROID9_WIFI_DIRECT.md). Android 10+ verifies its negotiated group frequency.

- Wired USB and wireless CarPlay with local authentication.
- BYD HUD navigation with arrows, distance and street names on verified firmware.
- Car hotspot support, improved audio buffering and saved receive diagnostics.
- Automatic address discovery, fixed-channel Wi-Fi fallbacks and successful-configuration memory.
- Icon/text size, resolution and frame rate; applying a display change reconnects CarPlay.
- Local diagnostic export. Reports are sent only if you choose to share them.
- Separate installation alongside DiAuto. Run one projection app at a time.

This is **not an Apple-certified product**. The upstream public APK uses an experimental accessory identity recovered from public Carlinkit firmware, not a newly provisioned MFi identity for DiPlay. This fork takes standalone authentication files from a private local directory; explicit source-only builds exclude them (see [build modes](docs/BUILD.md)). A bundled private key is extractable. Acceptance after future iOS updates, reliability across head units and suitability of that identity for general distribution are unresolved. This release invites community testing; it is not a guarantee of universal compatibility.

Earlier releases were tested on the development DiLink5.1 car: live windshield guidance and street names work, Car hotspot now starts CarPlay, and Wi-Fi Direct performance is substantially improved. Audio underrun recovery is improved in 0.2.15; remaining cutouts need current diagnostic reports. The 0.2.16 software Opus microphone fallback was accepted on a BOS Mini A1 head unit (Android 9) with an iPhone 12 on iOS 27. The floating-map test build was installed on the development DiLink 5.1 car; feedback led to the pinch corrections in 0.2.9. Earlier wheel-speed and video contributions were tested on a BYD Tang with DiLink 5.0 and an iPhone 15 Pro on iOS 27; wheel-speed dead reckoning in tunnels remains unverified. Broader head-unit and iOS compatibility is not guaranteed. The HUD firmware scope and cleanup limits are documented in [BYD navigation](docs/BYD_NAVIGATION.md).

## What’s new in 0.2.21

- Music plays in step with the iPhone again: on 0.2.20, pause, skip and track changes could come seconds late, and the speed could wobble.
- Wired CarPlay recovers by itself when the iPhone never opens CarPlay, no longer waits forever after **Disconnect**, and keeps a connection that has already recovered.
- DiPlay no longer closes when the microphone is used on head units without Android's own Opus encoder, and music comes back after a call or a short interruption.
- Wireless CarPlay says why it cannot start; on BYD head units the dashboard is handed back to the car when CarPlay ends, and DiPlay no longer closes from the DiYou desktop card.
- DiPlay installs on Android 7.0 and is available in Italian.
- New options, off by default: temporary system bars, black rounded corners, a play/pause wheel key, double press for Siri, importing the car hotspot details, a home-screen bubble and the car's own music rhythm.

See [0.2.21 release notes](docs/RELEASE-NOTES-0.2.21.md) and [validation](docs/VALIDATION.md) for contribution links and remaining physical tests. General stutter, calls/Siri, decoder and model-specific reports still need current-device evidence. [0.2.20 notes](docs/RELEASE-NOTES-0.2.20.md) remain available as historical guidance.

If a problem remains, reproduce it on **0.2.21**, then use **Settings → Diagnostics → Save diagnostic report**. Android 10+ normally saves to **Downloads/DiPlay**; Android 7.0–9 asks for storage access and saves there too. If unavailable, use **View report** or **Share** from the confirmation, which identifies external/private fallback storage. Review the `.txt` and add it to a matching [existing issue](https://github.com/shihabal3amri/DiPlay/issues), or [create one](https://github.com/shihabal3amri/DiPlay/issues/new/choose). Include vehicle/head-unit model, exact firmware and Android/DiLink, phone/iOS, connection backend, relevant settings, steps and failure time. Reports are shared only when you choose; never post your hotspot password.

## Documentation

[Existing Wi-Fi / Same LAN](docs/EXISTING_WIFI.md) keeps the iPhone and head unit
on an external router. See the guide for setup, build requirements and the
BYD DiLink 4.0 / Android 10 clean-install validation result.

- [Install and connect](docs/INSTALL.md)
- [Compatibility and troubleshooting](docs/COMPATIBILITY.md)
- [Smooth wireless CarPlay](docs/SMOOTH_WIRELESS.md)
- [Privacy and diagnostic reports](docs/PRIVACY.md)
- [Build from source](docs/BUILD.md) — select `mobile` for the main DiPlay app; `maphost` is a map sample.
- [Validation](docs/VALIDATION.md)
- [Release notes](CHANGELOG.md)
- [Credits and licenses](docs/THIRD_PARTY_NOTICES.md)

The app and the website source include English, Arabic, Russian, Ukrainian, Spanish, Simplified Chinese, Traditional Chinese (Taiwan), and Japanese. Traditional Chinese uses Taiwan wording; the app also recognizes Hong Kong/Macao and explicit Hant selections without claiming separate regional translations. Choose the app language in Settings; on Android 13+, it stays synchronized with Android’s per-app language setting. See the [Traditional Chinese guide](README.zh-TW.md) for installation, settings, troubleshooting, privacy and build instructions.

## Source and credits

Based on [xcertplay](https://github.com/shilapi/xcertplay), GPL-3.0. The home/settings UI and website adapt [DiAuto](https://github.com/shihabal3amri/DiAuto), AGPL-3.0; that license is included in `docs/licenses`. Preserve those notices when distributing modifications. CarPlay and its icon belong to Apple Inc.; no Apple or BYD affiliation or endorsement is implied.

This repository starts with a clean public source snapshot. Local research, tester reports and release-signing secrets are excluded. The complete source corresponding to the APK is provided with every release; experimental runtime identity assets are described separately in the build instructions and notices.

## Local release packaging

The mobile debug and release APKs bundle the experimental accessory identity from the project-local `.private/runtime-assets/offline-mfi/` directory by default. The directory is ignored by Git; fresh checkouts and CI must provision the two runtime files before building the mobile app. `DIPLAY_AUTH_ASSETS_DIR` overrides that directory. The Git repository and source archive exclude all accessory and Android signing keys; tests generate synthetic identities at runtime. Publishing an APK makes its bundled identity extractable; building locally does not preserve that identity's confidentiality.
