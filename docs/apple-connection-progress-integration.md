# 苹果风满屏连接进度界面 · 集成说明

把迭代出的纯 Apple / CarPlay 满屏设计（渐变壁纸 + 公路 + 轿车剪影 + 多色风火轮 + 分段进度轨道 + 当前步大号状态）落地到 **DiPlay-for-Camery** 工程。

> **硬约束：原界面的功能不能少。** 本方案不是「换张皮」，而是让新界面完整承接 `ConnectionProgressPanel` 的状态、文案、配色、自适应布局与无障碍语义。第五节是逐项功能对等清单，第六节是完整状态契约。

---

## 一、已核实的现状（决定方案的关键事实）

| 项 | 实际情况 |
|---|---|
| 目标界面 | `common/src/main/java/com/shilapi/xcertplay/connection/ConnectionProgressPanel.kt` |
| 实现方式 | **原生 `View`**（`LinearLayout` + `TextView` + `GradientDrawable`），**不是 Compose** |
| 状态来源 | `ConnectionProgress.snapshot()`：`steps / current / visited / durations / outcome / attempt / elapsedMillis / overallMillis / expectedMillis / remainingMillis / historySamples / waitingForUser / tasks / channel` |
| 文案 | `R.string.connect_progress_*`（已有多语言），R 类为 `com.shilapi.xcertplay.host.R` |
| 颜色 | `DiPlayPalette.of(night)`（`primaryText / secondaryText / accent / success / danger / surface`） |
| 主题 | `XcertplayTheme`（Compose Material3）—— 但本面板是 View，不经过 MaterialTheme |
| 模块 | `:common`；`settings.gradle.kts` 含 `:common/:mobile/:shared/:home/:installer/:pioneer-adapter/...` |
| 最低系统 | Android 6.0（API 23）/ 站点文档提到 7.0 |
| 工程约定 | `AGENTS.md`：**minSdk 以上的 `NewApi` 调用会让 build 失败**；要求报告「验证了什么 / 没验证什么」；新增 `settings_*` 字符串需全语言翻译 |

> 说明：仓库并非「只剩 APK」，公开树含完整多模块 Gradle 工程；被移出 Git 的只是专有 WeCarFlow 源码。

---

## 二、交付文件与放置路径

| 文件 | 放到工程 |
|---|---|
| `AppleStyleConnectionProgressPanel.kt` | `common/src/main/java/com/shilapi/xcertplay/connection/` |
| `carplay-progress.html` | `common/src/main/assets/`（目录不存在则新建） |
| `INTEGRATION.md` | `docs/apple-connection-progress-integration.md`（PR 中的映射路径） |

**没有新增任何 Gradle 依赖**（`WebView`、`org.json`、`NumberFormat` 全是 framework / 标准库），**不需要新权限**（只加载本地 asset），**不新增任何字符串资源**（全部沿用 `R.string.connect_progress_*`，因此不触发 `AGENTS.md` 的全语言翻译要求）。

---

## 三、集成步骤（只需改一处）

1. 把上面两个文件放好。
2. 全局搜索创建点：`grep -rn "ConnectionProgressPanel(" common/`，把构造
   `ConnectionProgressPanel(context)` 换成 `AppleStyleConnectionProgressPanel(context)`。
   > 新类**完整保留**了 `pendingTransport / begin / bind / retrying / failed / cancelled / setNight` 及所有生命周期回调，调用方无需改动。
3. 完成。字符串、颜色、连接状态、昼夜切换、ticker 全部沿用现有代码，无需改 Manifest 或资源。

---

## 四、为什么用 WebView 承载，而不是改原生 View 或 Compose

- 设计包含多层模糊壁纸、透视公路、轿车剪影、旋转风火轮、毛玻璃卡片。原生侧要在 **API 23** 上做模糊必须调 `RenderEffect` / `setRenderEffect`（API 31+），会直接触发 `NewApi` → **build 失败**（AGENTS.md 明确禁止）。用半透明渐变自绘虽可行，但视觉保真度和开发量都差。
- 改成 Compose 会牵动 `XcertplayTheme` 与既有的设置架构（`filteredSection` 等），风险高。
- **WebView + 本地 asset**：视觉 1:1 保真、零新增依赖、状态通过 `evaluateJavascript` **单向注入**，与传输层完全解耦，且不影响原有连接逻辑。
- 页面刻意**避开 `color-mix()` 等新 CSS**：状态底色用伪元素叠色 + CSS 变量实现，兼容车机旧版 WebView。
- 代价：首次 WebView 初始化有开销；若车机 ROM 精简掉 WebView，需保留原面板作降级（见第八节）。

---

## 五、功能对等清单（「原来界面的功能不能少」逐项核对）

| 原 `ConnectionProgressPanel` 功能 | 新实现对应点 |
|---|---|
| 公开 API：`pendingTransport / begin / bind / retrying / failed / cancelled / setNight` | 签名逐个保留，drop-in 替换 |
| `ConnectionProgressHistory.create(config, previous)` 复用历史样本 | 原样保留 `history.create(config, model)` |
| 500ms ticker，且**仅** CONNECTING / RETRYING 继续 tick | `tick` 内同一判断条件，失败/取消/完成后停表 |
| `onAttachedToWindow / onDetachedFromWindow / onVisibilityChanged / onWindowVisibilityChanged / onSizeChanged` | 全部保留；宽度变化重新 `render()` → 触发列数重算 |
| `snapshot()` 全字段 | `render()` 逐字段消费（见第六节契约） |
| 步骤 marker `● ✓ ○` + state 颜色 + 耗时 | 分段 `done/active/failed/pending` + SVG 图标 + `.tm` 耗时 |
| 时间列宽度预测量（防抖动） | `.tm{min-width:46px;font-variant-numeric:tabular-nums;text-align:right}` |
| **自适应列数**：普通通道宽屏 6 / 窄屏 3；车机无线宽屏 5 / 窄屏 3 | `columnCount(channel)` + CSS Grid；`onSizeChanged` 重注入即重算 |
| `estimate()` 行全部 outcome / 预估分支（learning / user_wait / overrun / estimate / retrying / failed / cancelled / complete） | Kotlin 完整计算，`estimate` + `estimateTone` 注入，含 `waitingForUser` 强调 |
| `summary` 行（attempt / elapsed / overall） | `R.string.connect_progress_summary` 注入 |
| PIONEER_WIRELESS 专属步骤标题（车机热点 / 蓝牙 / 认证 / 会话） | `title()` 保留原映射；`ordinaryTitle()` 保留全部 10 个 `ConnectionStep` —— 凯美瑞适配文案不丢失 |
| `ConnectionTask` hints：车机蓝牙等待 / 未连接、车机 Wi-Fi 开启 / 等待 | `steps[].hint` 逐段渲染 |
| 「蓝牙未连接」danger 红字 | `steps[].hintDanger` → `.hint.danger`（用注入的 `palette.danger`） |
| `setNight()` 整套 `DiPlayPalette` 昼夜配色 | `palette` 六色写入 CSS 变量 + `body.light` 明暗切换 + 壁纸减淡 |
| COMPLETE 态停止 spinner、percent = 100 | `running=false` 停转 + `wheel.is-complete` + `percent=100` |
| FAILED / CANCELLED / RETRYING 视觉区分 | `now` / `wheel` / `estimate` 状态类；CANCELLED 用独立 `labels.cancelled` |
| 多语言：所有可见文案走 `R.string` | 业务文案（标题、摘要、预估、步骤名、耗时、hint）全部注入；**HTML 静态 DOM 无中文硬编码**（装饰性英文徽标不属本地化文案） |
| `importantForAccessibility` | Kotlin 保留 + 页面 `aria-live="polite"` / `role="status"` |

> 页面里 `CARPLAY · CONNECTION`、`CURRENT STEP`、`Wireless / Vehicle Wireless / Wired` 属于 HMI 装饰性英文徽标，不是业务文案；真正的连接语义全部由 `R.string` 注入的 `steps[].title`、`estimate`、`summary`、`labels` 承载。

---

## 六、状态契约（`window.applyConnectionState(json)`）

`render()` 把 `snapshot()` 组装成单个 JSON，通过 `evaluateJavascript` 一次性推给页面。

| JSON 字段 | 来源 / 用途 |
|---|---|
| `night` | `setNight()` → `body.light` + 壁纸透明度 |
| `palette` | `{text, dim, accent, success, danger, surface}` = `DiPlayPalette.of(night)` → `--text/--dim/--accent/--success/--danger/--surface` |
| `labels` | `{title, waiting, complete, failed, cancelled}` → 字标与随结果切换的大标题（`carplay / connect_progress_waiting / carplay_connected / connect_progress_failed / connect_progress_cancelled`） |
| `outcome` | `CONNECTING / RETRYING / FAILED / CANCELLED / COMPLETE` |
| `running` | CONNECTING / RETRYING 为 `true` → 风火轮与脉冲转动；终态停转 |
| `channel` | `model.channel ?: pendingChannel` → 列数分支 + 顶部装饰短标 |
| `attempt / elapsed / overall` | 原始数值（兜底；页面优先用 `summary`） |
| `summary` | `R.string.connect_progress_summary` |
| `estimate` | 原 `estimate()` 的完整分支文案 |
| `estimateTone` | `normal / success / danger / dim` → 预估行左边框与文字色 |
| `waitingForUser` | 预估行加粗强调 |
| `steps[]` | `{title, state, time, hint, hintDanger}`：`title()` 本地化 + `current/visited/durations` 推导状态 + `connect_progress_seconds/pending/skipped` 耗时 + `ConnectionTask` hint |
| `currentTitle / currentTime` | 当前步大号状态条 |
| `percent` | `(已完成 + 当前×0.5) / 总步数`，COMPLETE 强制 100 |

**演示态**：`carplay-progress.html` 无原生注入时，用与真机注入结构完全一致的 `DEMO` 对象自跑（含 `palette / labels / estimate / steps[].hint` 等全字段），并提供 Connecting / Retrying / Complete / Failed / Cancelled / Day-Night / Task-hints 切换按钮，便于浏览器直接预览所有状态。中文仅存在于 `<script>` 内的模拟数据里；一旦 `applyConnectionState` 被调用，立即停表、隐藏演示按钮，全部改由 `R.string` 驱动 —— 同一文件既能预览又能上机。

---

## 七、验证边界（遵循 `AGENTS.md`：报告验证 / 未验证）

**已验证**
- 逐文件核对真实源码：`ConnectionProgressPanel.kt`、`ConnectionProgressHistory.kt`、`ui/theme/Theme.kt`、`settings.gradle.kts`、`AGENTS.md`、仓库文件树。
- 已对齐：`history.create(config, previous)` 签名、包名、`host.R`、`ConnectionChannel / ConnectionStep / ConnectionOutcome` 枚举值、`R.string.connect_progress_*` 命名。本面板所有字段访问均镜像自现有 `render()`（那是已编译通过的代码），**未引入任何新的 `R.string` 引用**。
- `node --check`：内联 JS 语法通过。
- **Chromium headless DOM 冒烟：6 组全通过**（演示态 8 项 / FAILED·浅色·窄屏 16 项 / COMPLETE·深色·宽屏 8 项 / RETRYING·车机无线 5 项 / CANCELLED 5 项 / CONNECTING·深色·宽屏 6 项），覆盖：`palette` 六色注入、`labels` 标题切换、`estimate` 四种 tone、`hintDanger` 红字、控件隐藏、`aria-live`、COMPLETE 停转/100%、自适应列数（宽 6 / 窄 3 / 车机无线宽 5）、各 outcome 状态类。
- 静态检查：去掉 `<script>/<style>/注释` 后，HTML DOM **无中文硬编码**。

**未验证（本机条件限制）**
- 本机**无 Android SDK / `ANDROID_HOME`**，且源码包下载不完整，因此**未执行 Gradle 编译**，也**未做实车 / 设备 smoke**。
- 创建点的确切文件与行号未在本机工程内 grep（请在本地 `grep -rn "ConnectionProgressPanel("` 确认）。
- 未跑 `SettingsTranslationsTest` 等既有测试（本次未新增字符串，理论上不受影响）。

**建议的最小检查**（AGENTS.md 要求「最小可运行改动」）
```sh
./gradlew :common:lintDebug :common:assembleDebug
```
实车验收：无线连接一次 CarPlay → 进度随真实状态推进、昼夜切换正常、失败/重试/取消/完成四态呈现正确、车机蓝牙与 Wi-Fi hint 出现时机正确、宽窄屏列数自适应。

---

## 八、风险与后续

- **签名**：README 说明 DiPlay 与配套 WeCarFlow 使用**共用签名**。重新打包 APK 必须用同一 keystore，否则桥接失效。
- **降级**：若目标车机无 WebView，保留原 `ConnectionProgressPanel` 作为 fallback（按 `WebView` 可用性在工厂里二选一）。
- **列数阈值近似**：`columnCount()` 用 `window.innerWidth >= 560` 近似原面板的 `dp(560)`；WebView 在 `width=device-width` 下 CSS px ≈ dp，若目标 ROM 密度异常，可把阈值随 `density` 从原生注入。
- **R8 / 混淆**：未使用 `addJavascriptInterface`，无需额外 keep 规则。
- **纯原生备选**：若你坚持不用 WebView，我可以另出一版 `Canvas` 自绘 + `ValueAnimator` 的实现（无 `NewApi`，但视觉会简化）。
