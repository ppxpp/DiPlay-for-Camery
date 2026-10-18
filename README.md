# DiPlay

## 凯美瑞车机适配版（本仓库重点）

> **本仓库面向丰田凯美瑞先锋车机，提供与原车操作相结合的 CarPlay 体验。** 基于 DiPlay，针对车机连接、声音通道、方向盘按键和日夜模式进行适配，并与原车爱趣听（WeCarFlow）协同工作。

应用名为 **DiPlay-凯美瑞A导航**，包名为 `com.gtmc.app.fengyunyx.b819`，基于上游 **v0.2.21**。通过原车按键控制音乐、跟随车机切换白天和黑夜，并在使用 CarPlay 时协调爱趣听的播放行为，减少两个应用争抢声音的情况。

### 凯美瑞适配功能

| 适配内容 | 使用说明 |
| --- | --- |
| 先锋车机连接 | 针对先锋固件处理有线连接与数据传输，支持连接超时处理和重试。 |
| 原车声音通道 | 对接车机音源管理，分别处理 CarPlay 音乐与导航提示的播放通道。 |
| 方向盘按键 | 通过 WeCarFlow 桥接，将原车前后曲按键交给 CarPlay 使用。 |
| 爱趣听播放保护 | DiPlay 启动时只拉起所需桥接服务，不启动爱趣听的播放器业务；CarPlay 连接后启用相应的播放冲突保护。 |
| 音乐使用提醒 | 保护生效时，打开爱趣听会提示优先使用 CarPlay 音乐。提示支持关闭，也可勾选“本次不再提示”。 |
| 白天／黑夜同步 | CarPlay 跟随原车昼夜状态切换，配合车机的日间与夜间使用场景。 |

### DiPlay 与爱趣听怎样配合

**DiPlay** 负责连接 iPhone、显示 CarPlay、播放声音和接收触控；**WeCarFlow 桥接**负责对接原车按键、播放状态和冲突保护。使用 CarPlay 音乐时，建议通过 CarPlay 选择和播放歌曲，避免同时在爱趣听中播放音乐。

**关闭提醒窗口不会解除播放保护。** 勾选“本次不再提示”后，爱趣听主进程重启前不再弹出；只退到后台或断开后重新连接 CarPlay，不会清除这项选择。

本分支最低支持 Android 6.0（API23），DiPlay 与配套 WeCarFlow 使用共用签名。适配范围为凯美瑞先锋车机，具体兼容性取决于车型、车机固件和 iPhone 系统版本。配套项目说明见 [WeCarFlow](wecarflow/README.md)。

**下方为上游项目介绍。** 其中比亚迪支持范围、`com.shihab.diplay` 包名、官方下载及问题反馈入口均属于上游；凯美瑞适配版请以本节说明为准。

[English](README.md) · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-TW.md)

**CarPlay for compatible BYD Android head units.** Wired and wireless, with the familiar DiAuto interface. Independent app: `com.shihab.diplay`.

> **BYD support scope:** These projects focus on BYD cars. They may work on other brands, but other brands are unsupported and there are no plans to add support or fix brand-specific incompatibilities.

> **Disclaimer:** DiPlay is not affiliated with, endorsed by, or authorized by Apple and is not an MFi-certified product. The software is provided **as-is** with no warranty (GPL-3.0). Driving-safety, regulatory, privacy and intellectual-property risks from use are your own responsibility — read [DISCLAIMER.md](DISCLAIMER.md) before installing or distributing.

[Download & website](https://shihabal3amri.github.io/DiPlay/) · [Release](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.21) · [Report a problem](https://github.com/shihabal3amri/DiPlay/issues/new/choose)

![DiPlay home](site/assets/home.png)

## 0.2.21 — public preview

Install on the **car**, not the iPhone. No jailbreak, dongle, Mac, account or authentication server is required for use. Core CarPlay does not require ADB; optional dashboard, battery, wheel-speed and parked-video features do. Your head unit must permit APK installation. The mobile APK targets Android 6.0+ (API 23); Compatibility depends on head-unit firmware and iOS; see [Android 6 compatibility](docs/ANDROID6_COMPATIBILITY.md). Wireless supports Wi-Fi Direct, the car’s existing hotspot or Existing Wi-Fi / Same LAN. Android 6.0–9 Wi-Fi Direct uses a firmware-dependent legacy path with generated group credentials and unverified requested frequency; see [Android 9 Wi-Fi Direct](docs/ANDROID9_WIFI_DIRECT.md). Android 10+ verifies its negotiated group frequency.

- Wired USB and wireless CarPlay with local authentication.
- BYD HUD navigation with arrows, distance and street names on verified firmware.
- Car hotspot support, improved audio buffering and saved receive diagnostics.
- Automatic address discovery, fixed-channel Wi-Fi fallbacks and successful-configuration memory.
- Icon/text size, resolution and frame rate; applying a display change reconnects CarPlay.
- Local diagnostic export. Reports are sent only if you choose to share them.
- Separate installation alongside DiAuto. Run one projection app at a time.

This is **not an Apple-certified product**. The upstream public APK uses an experimental accessory identity recovered from public Carlinkit firmware, not a newly provisioned MFi identity for DiPlay. This fork takes standalone authentication files from a private local directory; explicit source-only builds exclude them (see [build modes](docs/BUILD.md)). A bundled private key is extractable. Acceptance after future iOS updates, reliability across head units and suitability of that identity for general distribution are unresolved. This release invites community testing; it is not a guarantee of universal compatibility.

## What’s new in 0.2.21

- Music plays in step with the iPhone again: on 0.2.20, pause, skip and track changes could come seconds late, and the speed could wobble.
- Wired CarPlay recovers by itself when the iPhone never opens CarPlay, no longer waits forever after **Disconnect**, and keeps a connection that has already recovered.
- DiPlay no longer closes when the microphone is used on head units without Android's own Opus encoder, and music comes back after a call or a short interruption.
- Wireless CarPlay says why it cannot start; on BYD head units the dashboard is handed back to the car when CarPlay ends, and DiPlay no longer closes from the DiYou desktop card.
- DiPlay installs on Android 7.0 and is available in Italian.
- New options, off by default: temporary system bars, black rounded corners, a play/pause wheel key, double press for Siri, importing the car hotspot details, a home-screen bubble and the car's own music rhythm.

See [0.2.21 release notes](docs/RELEASE-NOTES-0.2.21.md) for features, compatibility limits and contribution credits.

If a problem remains, reproduce it on **0.2.21**, then use **Settings → Diagnostics → Save diagnostic report**. Android 10+ normally saves to **Downloads/DiPlay**; Android 7.0–9 asks for storage access and saves there too. If unavailable, use **View report** or **Share** from the confirmation, which identifies external/private fallback storage. Review the `.txt` and add it to a matching [existing issue](https://github.com/shihabal3amri/DiPlay/issues), or [create one](https://github.com/shihabal3amri/DiPlay/issues/new/choose). Include vehicle/head-unit model, exact firmware and Android/DiLink, phone/iOS, connection backend, relevant settings, steps and failure time. Reports are shared only when you choose; never post your hotspot password.

## Documentation

[Existing Wi-Fi / Same LAN](docs/EXISTING_WIFI.md) keeps the iPhone and head unit
on an external router. See the guide for setup and build requirements.

- [Install and connect](docs/INSTALL.md)
- [Compatibility and troubleshooting](docs/COMPATIBILITY.md)
- [Smooth wireless CarPlay](docs/SMOOTH_WIRELESS.md)
- [Privacy and diagnostic reports](docs/PRIVACY.md)
- [Build from source](docs/BUILD.md) — select `mobile` for the main DiPlay app; `maphost` is a map sample.
- [Release notes](CHANGELOG.md)
- [Credits and licenses](docs/THIRD_PARTY_NOTICES.md)

The app and the website source include English, Arabic, Russian, Ukrainian, Spanish, Simplified Chinese, Traditional Chinese (Taiwan), and Japanese. Traditional Chinese uses Taiwan wording; the app also recognizes Hong Kong/Macao and explicit Hant selections without claiming separate regional translations. Choose the app language in Settings; on Android 13+, it stays synchronized with Android’s per-app language setting. See the [Traditional Chinese guide](README.zh-TW.md) for installation, settings, troubleshooting, privacy and build instructions.

## Source and credits

Based on [xcertplay](https://github.com/shilapi/xcertplay), GPL-3.0. The home/settings UI and website adapt [DiAuto](https://github.com/shihabal3amri/DiAuto), AGPL-3.0; that license is included in `docs/licenses`. Preserve those notices when distributing modifications. CarPlay and its icon belong to Apple Inc.; no Apple or BYD affiliation or endorsement is implied.

This repository starts with a clean public source snapshot. Local research, tester reports and release-signing secrets are excluded. The complete source corresponding to the APK is provided with every release; experimental runtime identity assets are described separately in the build instructions and notices.

## Local release packaging

The mobile debug and release APKs bundle the experimental accessory identity from the project-local `.private/runtime-assets/offline-mfi/` directory by default. The directory is ignored by Git; fresh checkouts and CI must provision the two runtime files before building the mobile app. `DIPLAY_AUTH_ASSETS_DIR` overrides that directory. The Git repository and source archive exclude all accessory and Android signing keys; tests generate synthetic identities at runtime. Publishing an APK makes its bundled identity extractable; building locally does not preserve that identity's confidentiality.
