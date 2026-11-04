# DiPlay 一键反馈 Implementation Plan

> **Execution status (2026-11-04):** Implemented in the feature worktree. Android feedback tests + `:mobile:assembleDebug`, backend tests/typecheck, admin site build and Android lint passed. No commit created; changes are left in the worktree for review.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 DiPlay 车机上提供匿名一键反馈：用户选分类、可选填描述，客户端整理脱敏日志并可靠上传；后端归档并提供管理台查看。

**Architecture:** Android `common` 模块新增 `com.shilapi.xcertplay.feedback` 包，负责反馈包生成、本地待发队列、WorkManager 上传、反馈页和反馈记录页。入口复用现有 Diagnostics 设置卡和 CarPlay 连接失败面板。后端在现有 `update-service` 的 Cloudflare Pages Functions + R2/KV 上新增匿名上传接口和管理接口，Astro/Vue 管理台增加“用户反馈”标签页。

**Tech Stack:** Kotlin/Android Views、WorkManager、`HttpURLConnection`、`org.json`、JUnit/Robolectric、Cloudflare Pages Functions、R2、KV、Vue 3、Astro、Vitest、`fflate`。

**Base:** `origin/main` (7b81e486)，worktree `/Users/zhouhao/work/project/carplay/DiPlay-one-click-feedback`，branch `feat/diplay-one-click-feedback`。

**Design:** `docs/2026-11-04-DiPlay一键反馈功能设计.md`

**Commit policy:** 本计划不自动提交。每个任务完成后运行验证命令并保留工作区改动，最后由用户决定是否提交。

---

## File Map

### Android

Create:

- `common/src/main/java/com/shilapi/xcertplay/feedback/FeedbackModels.kt` — 分类、入口、状态、元数据、记录类型。
- `common/src/main/java/com/shilapi/xcertplay/feedback/FeedbackConnectivity.kt` — 经过验证的互联网网络选择。
- `common/src/main/java/com/shilapi/xcertplay/feedback/FeedbackReport.kt` — 精简、脱敏的诊断摘要。
- `common/src/main/java/com/shilapi/xcertplay/feedback/FeedbackLogcat.kt` — 有权限时的短 logcat 采集。
- `common/src/main/java/com/shilapi/xcertplay/feedback/FeedbackBundle.kt` — 生成 ≤5MB 反馈 zip。
- `common/src/main/java/com/shilapi/xcertplay/feedback/FeedbackOutbox.kt` — 待发队列、永久失败、已发送历史。
- `common/src/main/java/com/shilapi/xcertplay/feedback/FeedbackUploader.kt` — multipart 上传。
- `common/src/main/java/com/shilapi/xcertplay/feedback/FeedbackUploadWorker.kt` — WorkManager worker 和调度器。
- `common/src/main/java/com/shilapi/xcertplay/feedback/FeedbackActivity.kt` — 单页反馈表单。
- `common/src/main/java/com/shilapi/xcertplay/feedback/FeedbackRecordsActivity.kt` — 待处理/已发送列表。
- `common/src/test/java/com/shilapi/xcertplay/feedback/FeedbackOutboxTest.kt`
- `common/src/test/java/com/shilapi/xcertplay/feedback/FeedbackBundleTest.kt`
- `common/src/test/java/com/shilapi/xcertplay/feedback/FeedbackUploaderTest.kt`
- `common/src/test/java/com/shilapi/xcertplay/feedback/FeedbackConnectivityTest.kt`

Modify:

- `common/src/main/java/com/shilapi/xcertplay/DiagnosticBundle.kt` — `DiagnosticArchive` 支持可配置大小上限。
- `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt` — Diagnostics 按钮、待处理角标、打开反馈/记录页。
- `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt` — 连接失败面板“反馈此问题”按钮。
- `common/src/main/AndroidManifest.xml` — 注册两个 Activity。
- `common/src/main/res/values/strings.xml` 和所有 `values-xx/strings.xml` — 新增 `feedback_*` 字符串。

### Backend

Create:

- `update-service/functions/api/v1/apps/[appId]/feedback.ts` — 匿名上传。
- `update-service/functions/api/v1/admin/feedback/index.ts` — 管理列表。
- `update-service/functions/api/v1/admin/feedback/[id].ts` — 详情和删除。
- `update-service/functions/api/v1/admin/feedback/[id]/bundle.ts` — 下载。
- `update-service/functions/_shared/feedback.ts` — schema、校验、存储 key、zip 解析。
- `update-service/schema/feedback-v1.schema.json` — meta/index 契约。
- `update-service/test/feedback.test.ts` — 上传/管理接口测试。
- `update-service/site/src/components/FeedbackPanel.vue` — 管理台反馈列表。

Modify:

- `update-service/package.json` / `package-lock.json` — 增加 `fflate`。
- `update-service/functions/_shared/management.ts` — `Env` 如无需新增绑定则保持不动；复用 `META`/`RATE_KV`。
- `update-service/site/src/components/AdminConsole.vue` — 增加“版本管理 / 用户反馈”标签。
- `update-service/site/src/pages/admin.astro` — 页面标题说明。
- `update-service/README.md`、`update-service/SECURITY.md` — API、存储、限流、保留期。
- `update-service/scripts/apply-edge-security.mjs` — 反馈上传 WAF 限流规则。

---

### Task 1: 反馈模型与本地队列

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/feedback/FeedbackModels.kt`
- Create: `common/src/main/java/com/shilapi/xcertplay/feedback/FeedbackOutbox.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/feedback/FeedbackOutboxTest.kt`

- [ ] **Step 1: 定义模型**

```kotlin
internal enum class FeedbackCategory(val wireName: String) {
    CONNECTION("connection"), DISPLAY("display"), AUDIO("audio"),
    NAVIGATION("navigation"), VEHICLE("vehicle"), OTHER("other");

    companion object {
        fun fromWire(value: String?): FeedbackCategory? = entries.firstOrNull { it.wireName == value }
    }
}

internal enum class FeedbackEntry(val wireName: String) {
    SETTINGS("settings"), CONNECTION_FAILURE("connection_failure");
}

internal enum class FeedbackState {
    PENDING, UPLOADING, FAILED_RETRYABLE, FAILED_PERMANENT,
}

internal data class FeedbackMeta(
    val id: String,
    val category: FeedbackCategory,
    val description: String,
    val entry: FeedbackEntry,
    val createdAt: Long,
    val failureStage: String? = null,
    val appVersionName: String = "unknown",
    val appVersionCode: Long = 0,
    val updateChannel: String = "unknown",
    val deviceManufacturer: String = "unknown",
    val deviceModel: String = "unknown",
    val deviceBoard: String = "unknown",
    val androidRelease: String = "unknown",
    val androidApi: Int = 0,
    val sessionMode: String = "unknown",
    val sessionState: String = "unknown",
    val bundleBytes: Long = 0,
    val bundleSha256: String = "",
)

internal data class FeedbackRecord(
    val meta: FeedbackMeta,
    val state: FeedbackState,
    val attempts: Int,
    val lastError: String?,
    val lastAttemptAt: Long,
    val userInitiated: Boolean,
)

internal data class SentFeedback(
    val id: String,
    val category: FeedbackCategory,
    val createdAt: Long,
    val sentAt: Long,
)
```

- [ ] **Step 2: 实现队列**

`FeedbackOutbox` 使用 `filesDir/feedback-outbox/{id}/`，每个目录含 `bundle.zip`、`meta.json`、`state.json`。`filesDir/feedback-sent.json` 保存已发送轻量记录。要求：

- `enqueue(context, meta, bundleFile)`：检查待处理数量（PENDING/UPLOADING/FAILED_RETRYABLE）< 5，否则抛 `QueueFullException`；写入临时文件后 rename。
- `pending(context): List<FeedbackRecord>`：读取并排序。
- `record(context, id): FeedbackRecord?`
- `markUploading/markRetryable/markPermanent/markSent/delete`
- `pendingCount(context): Int` = 待处理 + 永久失败。
- `sentHistory(context): List<SentFeedback>` 最多 10 条。
- `clearSent(context)`、`deleteSent(context, id)`。
- `cleanup(context)`：删除临时目录；永久失败超过 7 天或超过 5 条时清理。
- JSON 使用 `org.json`；写入使用 `FileOutputStream` + `.tmp` + `renameTo`。
- 所有方法允许在后台线程调用。

- [ ] **Step 3: 写测试**

覆盖：

```kotlin
@Test fun enqueueRejectsSixthPendingRecord()
@Test fun completedUploadMovesRecordToSentHistoryWithoutDescription()
@Test fun failedPermanentDoesNotBlockPendingQuota()
@Test fun sentHistoryKeepsTenAndThirtyDays()
@Test fun cleanupDeletesExpiredPermanentFailures()
```

测试使用 `Robolectric` + `ApplicationProvider.getApplicationContext()`，为每个测试清空 `filesDir/feedback-outbox` 和 `feedback-sent.json`。

- [ ] **Step 4: 运行**

```sh
./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.feedback.FeedbackOutboxTest"
```

Expected: PASS。

---

### Task 2: 网络状态、反馈摘要与反馈包

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/feedback/FeedbackConnectivity.kt`
- Create: `common/src/main/java/com/shilapi/xcertplay/feedback/FeedbackReport.kt`
- Create: `common/src/main/java/com/shilapi/xcertplay/feedback/FeedbackLogcat.kt`
- Create: `common/src/main/java/com/shilapi/xcertplay/feedback/FeedbackBundle.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiagnosticBundle.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/feedback/FeedbackBundleTest.kt`

- [ ] **Step 1: 网络检测**

```kotlin
internal object FeedbackConnectivity {
    fun select(context: Context): Network? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return runCatching { InternetNetworkSelector.select(manager) }.getOrNull()
    }
    fun isOnline(context: Context): Boolean = select(context) != null
    fun openConnection(context: Context, url: URL): URLConnection =
        select(context)?.openConnection(url) ?: url.openConnection()
}
```

注意：`InternetNetworkSelector` 在 `com.shilapi.xcertplay.update` 包，模块内 `internal` 可直接使用。

- [ ] **Step 2: 允许配置 zip 上限**

修改 `DiagnosticArchive` 构造：

```kotlin
internal class DiagnosticArchive(
    file: File,
    private val textLimit: Int = TEXT_LIMIT,
    private val componentLimit: Long = COMPONENT_LIMIT,
    private val totalLimit: Long = TOTAL_LIMIT,
) : Closeable
```

将内部对 `TEXT_LIMIT`、`COMPONENT_LIMIT`、`TOTAL_LIMIT` 的使用替换为实例字段，默认值保持现有完整诊断包行为。

- [ ] **Step 3: 反馈摘要**

`FeedbackReport.text(context, meta): String` 输出：

- DiPlay 版本、versionCode、渠道
- Android 版本/API、厂商/型号/board
- 连接模式与会话状态
- 关键显示/音频/连接设置
- `BluetoothDiagnosticSnapshot.report`
- `DisplayDiagnosticSnapshot.report`
- `StartupDiagnosticSnapshot.report`
- `WheelKeyLog.report`
- `UpdateAvailability.report`
- `ProcessExitDiagnostics.report`
- `CrashRecord.report`
- `UsbSessionDiagnostics.report`

不输出 StableClientId、手机名称、手机地址。最后统一逐行 `DiagnosticRedactor.redact`。

- [ ] **Step 4: 打包**

`FeedbackBundle.build(context, meta, output): File`：

- 读取 `DiPlayPreferences.phoneName` / `phoneAddress`，作为额外敏感字面量替换。
- `DiagnosticArchive(output, textLimit = 1_500_000, totalLimit = 5_000_000)`。
- 写入 `feedback.json`（≤64KB）。
- 写入 `report.txt`（≤1.5MB）。
- 写入最近 3 个 `SessionLogFile.REPORT_NAMES` 日志，每个 ≤512KB，总计 ≤1.5MB。
- 写入 `logs/wheel-keys.txt`（`WheelKeyLog.report`，≤128KB）。
- 写入 `crash/last-crash.txt`（≤256KB）。
- 写入 `logcat/logcat.txt`（`FeedbackLogcat.capture`，≤512KB）。
- 写入 `README.txt`。
- 关闭后重新打开 zip 校验 `manifest.json` 和必需条目，计算 SHA-256 写回 `feedback.json`/meta，返回文件。
- 超过 5MB 时按 `logcat → 最旧 session log → wheel-keys` 顺序丢弃，最后只保留 `feedback.json`、`report.txt`、`crash`、`manifest.json`、`README.txt`。

- [ ] **Step 5: 测试**

```kotlin
@Test fun bundleContainsRequiredEntries()
@Test fun bundleExcludesStableClientIdAndPhoneIdentity()
@Test fun bundleDropsLogcatBeforeSessionLogs()
@Test fun bundleNeverExceedsFiveMegabytes()
```

构造临时 `filesDir/logs/diplay.log` 和崩溃文件，调用 build，读取 zip 断言。

- [ ] **Step 6: 运行**

```sh
./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.feedback.FeedbackBundleTest"
```

Expected: PASS。

---

### Task 3: 上传器与 WorkManager

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/feedback/FeedbackUploader.kt`
- Create: `common/src/main/java/com/shilapi/xcertplay/feedback/FeedbackUploadWorker.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/feedback/FeedbackUploaderTest.kt`

- [ ] **Step 1: multipart 上传**

```kotlin
internal object FeedbackUploader {
    private const val ENDPOINT = "https://diplay.mgulu.com/api/v1/apps/diplay/feedback"
    internal const val MAX_BUNDLE_BYTES = 5L * 1024 * 1024

    sealed interface Result {
        data class Sent(val id: String) : Result
        data class Retryable(val reason: String) : Result
        data class Permanent(val reason: String) : Result
    }

    fun upload(context: Context, meta: FeedbackMeta, bundle: File): Result
}
```

实现：

- 校验 `bundle.length() <= MAX_BUNDLE_BYTES`。
- 生成 boundary；`HttpURLConnection` 使用 `FeedbackConnectivity.openConnection`。
- `requestMethod = "POST"`，`doOutput = true`，固定长度流式写 multipart：
  - `meta` 字段：JSON，UTF-8。
  - `bundle` 字段：`application/zip`，文件名 `diplay-feedback-{id}.zip`。
- 连接 10s、读取 120s。
- 201/200 → `Sent`。
- 429/408/5xx/IO → `Retryable`。
- 其他 4xx → `Permanent`。
- 响应体按 `org.json` 解析 `ok` 和 `id`；解析失败按 Retryable。

- [ ] **Step 2: Worker**

`FeedbackUploadWorker` 继承 `androidx.work.Worker`：

```kotlin
override fun doWork(): Result {
    val context = applicationContext
    if (FeedbackConnectivity.select(context) == null) return Result.retry()
    val record = FeedbackOutbox.pending(context).firstOrNull() ?: return Result.success()
    if (!record.userInitiated && (CarPlayBackgroundSession.hasSession() || CarPlayBackgroundSession.active)) {
        return Result.retry()
    }
    FeedbackOutbox.markUploading(context, record.meta.id)
    return when (val result = FeedbackUploader.upload(context, record.meta, FeedbackOutbox.bundle(context, record.meta.id))) {
        is FeedbackUploader.Result.Sent -> {
            FeedbackOutbox.markSent(context, record.meta.id)
            Result.success()
        }
        is FeedbackUploader.Result.Retryable -> {
            FeedbackOutbox.markRetryable(context, record.meta.id, result.reason)
            Result.retry()
        }
        is FeedbackUploader.Result.Permanent -> {
            FeedbackOutbox.markPermanent(context, record.meta.id, result.reason)
            Result.success()
        }
    }
}
```

调度器：

```kotlin
internal object FeedbackScheduler {
    private const val WORK = "diplay-feedback-upload"
    fun schedule(context: Context, immediate: Boolean = false) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<FeedbackUploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request)
    }
}
```

`FeedbackActivity` 提交时调用 `FeedbackScheduler.schedule(context, immediate = true)`；App 启动时调用 `FeedbackScheduler.schedule(context)`。

- [ ] **Step 3: 测试**

`FeedbackUploaderTest` 使用 `com.sun.net.httpserver.HttpServer` 监听随机端口，注入 endpoint 或使用 `uploadTo(url)` 内部函数：

- 200 → Sent。
- 429 → Retryable。
- 400 → Permanent。
- 校验 multipart 中包含 `meta` 和 `bundle` 字段、文件名和 zip 字节。

- [ ] **Step 4: 运行**

```sh
./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.feedback.FeedbackUploaderTest"
```

Expected: PASS。

---

### Task 4: 反馈页 FeedbackActivity

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/feedback/FeedbackActivity.kt`
- Modify: `common/src/main/AndroidManifest.xml`
- Modify: string files

- [ ] **Step 1: Activity 入口**

```kotlin
class FeedbackActivity : ComponentActivity(), AppAppearanceOwner {
    companion object {
        private const val EXTRA_CATEGORY = "feedback_category"
        private const val EXTRA_FAILURE_STAGE = "feedback_failure_stage"
        fun intent(context: Context, category: FeedbackCategory? = null, failureStage: String? = null): Intent
    }
}
```

Manifest:

```xml
<activity
    android:name="com.shilapi.xcertplay.feedback.FeedbackActivity"
    android:exported="false"
    android:resizeableActivity="true"
    android:windowSoftInputMode="adjustResize"
    android:theme="@style/Theme.Xcertplay" />
```

- [ ] **Step 2: 布局**

根 `LinearLayout` 垂直：

- 顶栏：标题 + `发送` `DiPlayButton(PRIMARY)` + `关闭` `DiPlayButton(SECONDARY)`。
- 顶部交互区固定高度 = `min(screenHeight * 0.45, 420dp)`，包含：
  - 问题类型选择器（一行 `DiPlayButton(SECONDARY)`，点击弹 6 项 `AlertDialog`）。
  - 连接失败入口的只读故障阶段（单行省略）。
  - `EditText` 描述框，`layoutParams height=0 weight=1`，`minLines=3`，`maxLines=6`，`InputFilter.LengthFilter(2000)`，内部滚动。
- 底部说明区 `ScrollView` weight=1：
  - 网络状态行 + “检查网络”按钮。
  - 匿名说明。
  - 将包含内容摘要。
- 键盘占下半屏时，顶部交互区仍在可见范围，发送按钮在顶栏。

- [ ] **Step 3: 状态与网络**

- `onResume`：`refreshNetwork()`；更新发送按钮文案为“发送反馈”或“保存，联网后发送”。
- 分类未选时发送按钮 `isEnabled=false`；连接失败入口预选“连接”。
- 点击发送：
  1. 校验分类。
  2. 禁用发送/关闭，显示“正在整理诊断信息…”。
  3. 后台线程构建 `FeedbackBundle`。
  4. `FeedbackOutbox.enqueue`；`FeedbackScheduler.schedule`。
  5. 在线：显示“正在上传”；离线：显示“已保存，联网后自动上传”。
  6. 轮询 `FeedbackOutbox.record` 每 700ms，最多 2 分钟；`Sent` 显示编号 + “完成”，失败显示“查看记录/立即重试/完成”。
- “立即重试”：在线时 `FeedbackScheduler.schedule` 并重新轮询；离线时弹网络提示，按钮“打开网络设置/知道了”。
- 打开设置依次尝试 `Settings.ACTION_WIFI_SETTINGS`、`ACTION_WIRELESS_SETTINGS`、`ACTION_SETTINGS`，失败 toast。

- [ ] **Step 4: 测试**

Robolectric：

```kotlin
@Test fun sendDisabledUntilCategoryChosen()
@Test fun connectionFailureEntryPreselectsConnection()
@Test fun offlineSubmitQueuesAndShowsSavedMessage()
@Test fun onlineButtonLabelIsSend()
```

- [ ] **Step 5: 运行**

```sh
./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.feedback.FeedbackActivityTest"
./gradlew :common:compileDebugKotlin
```

Expected: PASS。

---

### Task 5: 反馈记录页 FeedbackRecordsActivity

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/feedback/FeedbackRecordsActivity.kt`
- Modify: `common/src/main/AndroidManifest.xml`
- Modify: string files

- [ ] **Step 1: 页面**

`FeedbackRecordsActivity` 使用 `TabLayout` 或两个 `DiPlayButton` 切换“待处理/已发送”。为避免新增依赖，使用两个按钮 + 一个 `LinearLayout` 内容区。

- 待处理：网络状态条、记录列表、立即重试、全部重试、删除、空状态。
- 已发送：最近 10 条编号/时间/类型/已发送、单条删除、清空。
- `onResume` 重新读取 `FeedbackOutbox` 和网络状态。
- 离线点重试弹网络提示。
- 删除二次确认。

- [ ] **Step 2: 测试**

Robolectric：

```kotlin
@Test fun pendingShowsRetryAndDelete()
@Test fun offlineRetryShowsNetworkDialog()
@Test fun sentTabShowsOnlyCodeTimeCategory()
@Test fun emptyStates()
```

- [ ] **Step 3: 运行**

```sh
./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.feedback.FeedbackRecordsActivityTest"
```

Expected: PASS。

---

### Task 6: Diagnostics 入口、角标与字符串

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`
- Modify: `common/src/main/res/values/strings.xml` 及所有 `values-xx/strings.xml`

- [ ] **Step 1: 角标视图**

在 `DiPlayActivity` 增加：

```kotlin
private val feedbackBadgeViews = mutableListOf<TextView>()
private var feedbackSummaryView: TextView? = null

private fun feedbackPendingCount(): Int = FeedbackOutbox.pendingCount(this)

private fun refreshFeedbackBadges() {
    val count = feedbackPendingCount()
    feedbackBadgeViews.forEach { badge ->
        badge.text = count.toString()
        badge.visibility = if (count > 0) View.VISIBLE else View.GONE
    }
    feedbackSummaryView?.text = if (count > 0) getString(R.string.feedback_pending_count, count) else ""
    feedbackSummaryView?.visibility = if (count > 0) View.VISIBLE else View.GONE
}
```

`onResume` 调用 `refreshFeedbackBadges()`。

- [ ] **Step 2: Overview 卡片和侧栏角标**

`settingsSummaryCard(category, summary)` 在 Diagnostics 标题右侧添加 badge，加入 `feedbackBadgeViews`。`settingsRailDestination(category)` 同理。badge 使用 `TextView`，背景 `rounded(ACCENT, ACCENT)`，文字 `ON_ACCENT`，尺寸 `dp(22)`。

- [ ] **Step 3: Diagnostics 卡片**

在 `filteredSection(SettingsSection.DIAGNOSTICS)` 内、导出按钮之前添加：

```kotlin
card.addView(button(getString(R.string.feedback_send), true) { openFeedback() }, matchButton(10, 60))
card.addView(label(getString(R.string.feedback_network_requirement), 14, MUTED))
feedbackSummaryView = label("", 14, ACCENT).apply {
    setOnClickListener { openFeedbackRecords() }
    addViewToCard(card)
}
```

实际追加方式使用 `card.addView(feedbackSummaryView)`；点击打开 `FeedbackRecordsActivity`。

- [ ] **Step 4: 打开页面**

```kotlin
private fun openFeedback() = startActivity(FeedbackActivity.intent(this))
private fun openFeedbackRecords() = startActivity(Intent(this, FeedbackRecordsActivity::class.java))
```

- [ ] **Step 5: 字符串**

在 `values/strings.xml` 添加中文，在 `values-*` 添加对应翻译（至少包含 ar/es/it/ja/ru/uk/zh-rCN/zh-rTW）。关键 key：

```xml
<string name="feedback_send">一键反馈</string>
<string name="feedback_title">发送反馈</string>
<string name="feedback_category">问题类型</string>
<string name="feedback_category_required">请选择问题类型</string>
<string name="feedback_description">问题描述（可选）</string>
<string name="feedback_description_hint">发生了什么？什么时候开始？能否复现？</string>
<string name="feedback_send_action">发送反馈</string>
<string name="feedback_save_offline">保存，联网后发送</string>
<string name="feedback_online">车机已联网，发送后会立即上传</string>
<string name="feedback_offline">车机当前没有可用网络，反馈会先保存在本机，联网后自动上传</string>
<string name="feedback_network_requirement">需要车机联网；无网络时会先保存</string>
<string name="feedback_network_help">反馈上传需要车机自身能访问互联网；仅连接 iPhone 的 CarPlay Wi-Fi 通常无法完成上传。</string>
<string name="feedback_records">反馈记录</string>
<string name="feedback_pending_count">待发送 %1$d 条 · 查看反馈记录</string>
<string name="feedback_crash_notice">崩溃记录会作为普通日志附带</string>
```

其余状态/按钮/网络提示字符串按实际 UI 补充。

- [ ] **Step 6: 运行**

```sh
./gradlew :common:testDebugUnitTest --tests "*Settings*" --tests "*AdaptiveSettingsUiTest*"
./gradlew :common:compileDebugKotlin
```

Expected: PASS。若新增 `settings_*` 字符串需补充所有 locale；`feedback_*` 同样全量补充。

---

### Task 7: 连接失败面板入口

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt`

- [ ] **Step 1: 字段**

```kotlin
private var feedbackButton: DiPlayButton? = null
```

- [ ] **Step 2: 构建按钮**

在 `buildContentView()` 的连接面板中，`help` 之后、`recovery` 之前添加：

```kotlin
val feedback = DiPlayButton(this).apply {
    text = getString(R.string.feedback_send_this_issue)
    visibility = View.GONE
    setOnClickListener {
        startActivity(FeedbackActivity.intent(
            this@CarPlayHostActivity,
            FeedbackCategory.CONNECTION,
            connectionHelpView?.text?.toString(),
        ))
    }
    feedbackButton = this
}
panel.addView(feedback, LinearLayout.LayoutParams(dp(300), dp(64)).apply { bottomMargin = dp(12) })
```

- [ ] **Step 3: 显示与样式**

`showConnectionHelp(failure)`：

```kotlin
feedbackButton?.visibility = if (failure == null) View.GONE else View.VISIBLE
```

`paintWaitingScreen` 中 `feedback.style(DiPlayButtonKind.SECONDARY, buttonPalette, overlay = false)`。

`updatePreparationLayout` 的按钮尺寸列表加入 `feedback`。

- [ ] **Step 4: 运行**

```sh
./gradlew :common:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL。

---

### Task 8: 后端匿名上传接口

**Files:**
- Create: `update-service/functions/api/v1/apps/[appId]/feedback.ts`
- Create: `update-service/functions/_shared/feedback.ts`
- Create: `update-service/test/feedback.test.ts`
- Modify: `update-service/package.json` (+ lock) — add `fflate`

- [ ] **Step 1: 安装依赖**

```sh
npm --prefix update-service install fflate
```

- [ ] **Step 2: 共享模块**

`functions/_shared/feedback.ts` 提供：

```ts
export const FEEDBACK_SCHEMA_VERSION = 1;
export const MAX_BUNDLE_BYTES = 5 * 1024 * 1024;
export const MAX_REQUEST_BYTES = 6 * 1024 * 1024;
export const MAX_DESCRIPTION_CHARS = 2000;

export type FeedbackCategory = "connection" | "display" | "audio" | "navigation" | "vehicle" | "other";
export interface FeedbackMeta { schemaVersion: 1; id: string; createdAt: number; category: FeedbackCategory; description: string; entry: "settings" | "connection_failure"; failureStage?: string; app: { versionName: string; versionCode: number; channel: string }; device: { manufacturer: string; model: string; board: string; androidRelease: string; api: number }; session: { mode: string; state: string }; bundleBytes: number; bundleSha256: string; }
export interface FeedbackIndex { schemaVersion: 1; id: string; receivedAt: number; category: FeedbackCategory; description: string; app: FeedbackMeta["app"]; device: FeedbackMeta["device"]; session: FeedbackMeta["session"]; bundleKey: string; bundleBytes: number; bundleSha256: string; reportPreview: string; previewTruncated: boolean; }

export function feedbackKeys(appId: string, id: string, receivedAt: number): { objectKey: string; indexKey: string; pointerKey: string }
export function parseMeta(value: unknown): FeedbackMeta | null
export function rateLimit(env: Env, request: Request): Promise<boolean>
export function parseBundle(bytes: Uint8Array): { metaJson: unknown; reportPreview: string; previewTruncated: boolean }
```

`parseBundle` 使用 `unzipSync`，过滤条目：

- 拒绝路径含 `..`、以 `/` 开头、条目数 >64。
- 拒绝 `originalSize` 总和 >20MB。
- 只解压 `feedback.json`、`report.txt`、`manifest.json`。
- `report.txt` 预览截断到 64KB。

- [ ] **Step 3: 上传 handler**

`functions/api/v1/apps/[appId]/feedback.ts`：

1. `appId` 必须匹配 `^[a-z0-9][a-z0-9-]{0,63}$`。
2. `content-length` > 6MB → 413。
3. `request.formData()`；`meta` 字符串、`bundle` File。
4. `parseMeta`；校验 category、id `/^[0-9a-f]{32}$/`、description length ≤2000、bundle size ≤5MB。
5. `parseBundle` 校验 zip；比较 `feedback.json` 的 id/category/version 与 meta。
6. 计算 bundle SHA-256，比较 `meta.bundleSha256`。
7. 限流：`rateLimit(env, request)`，超限 429 + `Retry-After: 3600`。
8. 幂等：`env.META.get(pointerKey)` 存在则返回 200 `{ok:true,id,duplicate:true}`。
9. 写 `bundleKey`，写 `indexKey`，写 `pointerKey`（包含 indexKey）。索引失败时 `delete(bundleKey)`。
10. 返回 `201 {ok:true,id,receivedAt}`。

- [ ] **Step 4: 测试**

`test/feedback.test.ts` 使用 `fflate.zipSync` 构造 zip，`FormData` + `File` 发送。覆盖：

- 正常上传 201，R2 中存在 bundle/index/pointer。
- 重复 ID 200 duplicate。
- 超大 bundle 413。
- 非法 meta/id/category 422。
- 缺失 report.txt/feedback.json 422。
- 路径穿越 422。
- 限流 429。
- `fixture` 中 `report.txt` 预览截断。

- [ ] **Step 5: 运行**

```sh
npm --prefix update-service test -- --run test/feedback.test.ts
npm --prefix update-service run typecheck
```

Expected: PASS。

---

### Task 9: 后端管理接口

**Files:**
- Create: `update-service/functions/api/v1/admin/feedback/index.ts`
- Create: `update-service/functions/api/v1/admin/feedback/[id].ts`
- Create: `update-service/functions/api/v1/admin/feedback/[id]/bundle.ts`
- Modify: `update-service/functions/_shared/feedback.ts`
- Test: `update-service/test/feedback.test.ts`

- [ ] **Step 1: 列表**

`GET /api/v1/admin/feedback?limit=50&cursor=`：

- `isAdmin` 失败 401。
- `env.META.list({ prefix: "feedback/{appId}/index/", cursor, limit: min(limit,100) })`。
- 读取每个 index JSON，返回 `{items, cursor, truncated}`；按 key 倒序（key 使用倒序时间戳）。
- 默认 appId `diplay`。

- [ ] **Step 2: 详情/删除**

`GET /api/v1/admin/feedback/{id}`：

- `isAdmin` 401。
- 读 pointer → index，返回索引。

`DELETE /api/v1/admin/feedback/{id}`：

- 读 pointer → index；删除 bundle、index、pointer。
- 返回 `{ok:true}`；不存在 404。

- [ ] **Step 3: 下载**

`GET /api/v1/admin/feedback/{id}/bundle`：

- `isAdmin` 401。
- 读 pointer → index → `env.META.get(bundleKey)`。
- 返回 R2 body 流，`content-type: application/zip`、`content-disposition: attachment; filename="diplay-feedback-{id}.zip"`、`x-content-type-options: nosniff`、`cache-control: no-store`。

- [ ] **Step 4: 测试**

覆盖未登录 401、列表、详情、下载字节、删除后 404、删除清理三个 key。

- [ ] **Step 5: 运行**

```sh
npm --prefix update-service test
npm --prefix update-service run typecheck
```

Expected: PASS。

---

### Task 10: 管理台“用户反馈”标签

**Files:**
- Create: `update-service/site/src/components/FeedbackPanel.vue`
- Modify: `update-service/site/src/components/AdminConsole.vue`
- Modify: `update-service/site/src/pages/admin.astro`

- [ ] **Step 1: 组件**

`FeedbackPanel.vue`：

- 接收 `request` 函数或自行封装 fetch（`credentials: same-origin`）。
- `load()` 调用 `GET /api/v1/admin/feedback?limit=50`。
- 列表列：接收时间、类型、版本、设备、大小、描述摘要。
- 筛选：类型、版本、关键词（客户端过滤）。
- 详情：弹层/卡片展示完整描述、设备/版本/会话、`reportPreview` `<pre>`。
- 下载：`window.location.href = /api/v1/admin/feedback/{id}/bundle`。
- 删除：`confirm` + `DELETE`，刷新。
- 空状态、加载中、错误提示。
- 所有文本使用 `{{ }}`，不使用 `v-html`。

- [ ] **Step 2: 标签切换**

`AdminConsole.vue`：

- 增加 `activeTab = ref<'releases'|'feedback'>('releases')`。
- 登录后在顶部渲染两个标签按钮。
- `releases` 保留现有内容；`feedback` 渲染 `<FeedbackPanel :request="request" />`。
- 退出登录时保留现有逻辑。

- [ ] **Step 3: 页面标题**

`admin.astro` 标题改为“管理后台”，说明增加“版本下发与用户反馈归档”。

- [ ] **Step 4: 构建**

```sh
npm --prefix update-service/site run build
```

Expected: build success。若站点构建需要联网获取 manifest，允许失败并用 `npm --prefix update-service run typecheck` 与 `npm --prefix update-service test` 作为后端验证。

---

### Task 11: 文档、契约与运维

**Files:**
- Create: `update-service/schema/feedback-v1.schema.json`
- Modify: `update-service/README.md`
- Modify: `update-service/SECURITY.md`
- Modify: `update-service/scripts/apply-edge-security.mjs`

- [ ] **Step 1: Schema**

定义 `FeedbackMeta`、`FeedbackIndex` 的 JSON Schema，`additionalProperties: false`，限制字符串长度、ID 正则、分类枚举。

- [ ] **Step 2: README**

API 列表增加：

```
POST /api/v1/apps/{appId}/feedback
GET  /api/v1/admin/feedback
GET  /api/v1/admin/feedback/{id}
GET  /api/v1/admin/feedback/{id}/bundle
DELETE /api/v1/admin/feedback/{id}
```

说明存储 key、90 天保留期、匿名与限流。

- [ ] **Step 3: SECURITY**

补充匿名上传滥用面、5MB/6MB 限制、每 IP 5/小时与 20/天、WAF 规则、IP 不落库、管理员下载权限。

- [ ] **Step 4: WAF**

在 `apply-edge-security.mjs` 的 `fullRateRules` 增加：

```js
{
  action: "block",
  description: `${TAG} feedback upload`,
  enabled: true,
  expression: 'http.request.method eq "POST" and starts_with(http.request.uri.path, "/api/v1/apps/") and ends_with(http.request.uri.path, "/feedback")',
  ratelimit: {
    characteristics: ["ip.src", "cf.colo.id"],
    period: 60,
    requests_per_period: 5,
    mitigation_timeout: 600,
  },
}
```

Free plan 只有一条规则时，保留现有 lite 规则并在 README 说明反馈依赖应用层限流。

- [ ] **Step 5: 运行**

```sh
node update-service/scripts/apply-edge-security.mjs
```

Expected: dry-run 输出包含 feedback upload 规则。

---

### Task 12: 全量验证

- [ ] **Step 1: Android 编译与测试**

```sh
./gradlew :common:compileDebugKotlin
./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.feedback.*"
```

- [ ] **Step 2: App 构建**

```sh
./gradlew :mobile:assembleDebug
```

Expected: BUILD SUCCESSFUL。若签名/私有资源缺失，记录未验证项，不修改共享签名。

- [ ] **Step 3: 后端测试与类型检查**

```sh
npm --prefix update-service test
npm --prefix update-service run typecheck
```

- [ ] **Step 4: 站点构建（可选）**

```sh
npm --prefix update-service/site run build
```

- [ ] **Step 5: 差异审查**

```sh
git status --short
git diff --stat
```

确认未加入 `.private`、`.signing`、`local.properties` 或生成的 build 产物。

---

## Self-Review

**Spec coverage:**

- 两个入口：Task 6、7。
- 单页表单/分类必选/描述可选/键盘上半部：Task 4。
- 网络状态和离线保存：Task 2、4、5。
- 待发队列/自动重试/失败记录/已发送记录：Task 1、3、5。
- 反馈包内容和脱敏：Task 2。
- 匿名上传、R2 归档、限流、幂等：Task 8。
- 管理接口和管理台：Task 9、10。
- 文档、schema、WAF、保留期：Task 11。
- 测试和验证：各任务 + Task 12。

**Known constraints:**

- 不自动提交；最后交由用户审查。
- 不在反馈包中包含 StableClientId、手机名称/地址、系统库/APK。
- 不实现崩溃后的引导弹窗；崩溃记录文件只作为日志附带。
- 不实现自动根因分析或 LLM 分析。
