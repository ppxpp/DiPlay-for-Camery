package com.shilapi.xcertplay.installer.ui.install

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.installer.adb.AdbController
import com.shilapi.xcertplay.installer.backup.AppBackupState
import com.shilapi.xcertplay.installer.backup.BackupCoordinator
import com.shilapi.xcertplay.installer.backup.BackupStatus
import com.shilapi.xcertplay.installer.backup.BackupUiState
import com.shilapi.xcertplay.installer.data.AppDownloadState
import com.shilapi.xcertplay.installer.data.DownloadCoordinator
import com.shilapi.xcertplay.installer.data.DownloadStatus
import com.shilapi.xcertplay.installer.data.DownloadUiState
import com.shilapi.xcertplay.installer.data.InstallKitRepository
import com.shilapi.xcertplay.installer.data.model.InstallApp
import com.shilapi.xcertplay.installer.data.model.InstallKit
import com.shilapi.xcertplay.installer.install.AppInstallState
import com.shilapi.xcertplay.installer.install.AppInstallStatus
import com.shilapi.xcertplay.installer.install.InstallCoordinator
import com.shilapi.xcertplay.installer.install.InstallUiState
import com.shilapi.xcertplay.installer.platform.LocalAdbController
import com.shilapi.xcertplay.installer.platform.LocalPlatform
import com.shilapi.xcertplay.installer.platform.LocalSettings
import com.shilapi.xcertplay.installer.platform.PlatformBackHandler
import com.shilapi.xcertplay.installer.settings.BackupDirectory
import com.shilapi.xcertplay.installer.ui.adb.AdbTargetCard
import com.shilapi.xcertplay.installer.ui.adb.WifiConnectField
import com.shilapi.xcertplay.installer.ui.components.AppRow
import com.shilapi.xcertplay.installer.ui.components.BulletLine
import com.shilapi.xcertplay.installer.ui.components.DestructiveConfirmDialog
import com.shilapi.xcertplay.installer.ui.components.InfoCard
import com.shilapi.xcertplay.installer.ui.components.InfoRow
import com.shilapi.xcertplay.installer.ui.components.LineProgressBar
import com.shilapi.xcertplay.installer.ui.components.WizardAction
import com.shilapi.xcertplay.installer.ui.components.WizardScaffold
import com.shilapi.xcertplay.installer.ui.model.RiskLevel
import com.shilapi.xcertplay.installer.ui.theme.DiPlaySuccess
import kotlinx.coroutines.launch
import java.io.File

private data class InstallStepInfo(
    val title: String,
    val subtitle: String,
    val riskLevel: RiskLevel = RiskLevel.None,
    val riskTitle: String = "",
    val riskMessage: String = "",
)

private val installSteps = listOf(
    InstallStepInfo(
        title = "下载安装文件",
        subtitle = "把要装到车机上的应用文件下载到本机；已下载过的不会重复下载。",
        riskLevel = RiskLevel.Info,
        riskTitle = "文件会自动校验",
        riskMessage = "下载完成后会自动检查文件是否完整，确认无误才会用来安装。",
    ),
    InstallStepInfo(
        title = "打开车机调试模式",
        subtitle = "在车机设置里打开“USB 调试”开关，本机才能把应用装到车机上。",
        riskLevel = RiskLevel.Warning,
        riskTitle = "装完后请关闭 USB 调试",
        riskMessage = "USB 调试开着会有安全风险，安装完成后建议回车机设置关闭。",
    ),
    InstallStepInfo(
        title = "连接本机和车机",
        subtitle = "用 USB 数据线连接本机和车机。",
    ),
    InstallStepInfo(
        title = "备份车机原应用",
        subtitle = "先把车机上要被替换的应用保存到本机，之后想还原时可以用。",
        riskLevel = RiskLevel.Warning,
        riskTitle = "应用数据不会被备份",
        riskMessage = "备份只保存应用本身；聊天记录、账号登录等数据不会保存。",
    ),
    InstallStepInfo(
        title = "确认安装内容",
        subtitle = "最后确认一遍：要装哪些应用，会卸载哪些应用。",
        riskLevel = RiskLevel.Danger,
        riskTitle = "卸载会清除应用内数据",
        riskMessage = "卸载后，这些应用里的设置、登录状态等数据将无法恢复。",
    ),
    InstallStepInfo(
        title = "安装到车机",
        subtitle = "安装过程中请不要拔线、断电或退出。",
        riskLevel = RiskLevel.Danger,
        riskTitle = "安装过程中请不要拔线",
        riskMessage = "不要拔掉 OTG 线、关闭车机电源或退出 App。",
    ),
    InstallStepInfo(
        title = "安装完成",
        subtitle = "请重启车机，让 DiPlay 生效。",
    ),
)

@Composable
fun InstallFlowScreen(
    onExit: () -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    var showUninstallConfirm by remember { mutableStateOf(false) }
    val current = installSteps[step]

    val platform = LocalPlatform.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val adb = LocalAdbController.current
    val adbSession by adb.session.collectAsState()
    val adbConnecting by adb.connecting.collectAsState()
    var backupDirError by remember { mutableStateOf<String?>(null) }

    var installKit by remember { mutableStateOf<InstallKit?>(null) }
    var kitLoading by remember { mutableStateOf(true) }
    var kitError by remember { mutableStateOf<String?>(null) }
    var kitReloadToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(kitReloadToken) {
        kitLoading = true
        kitError = null
        InstallKitRepository.load()
            .onSuccess { installKit = it }
            .onFailure { kitError = it.message ?: "无法获取安装包信息" }
        kitLoading = false
    }

    val downloader = remember(installKit, platform) {
        installKit?.let { DownloadCoordinator(platform.payloadDir, it) }
    }
    val downloadState = if (downloader != null) {
        downloader.state.collectAsState().value
    } else {
        DownloadUiState()
    }
    LaunchedEffect(downloader) {
        downloader?.verifyCachedFiles()
    }
    val requiredApps = installKit?.apps?.filter { it.required }.orEmpty()
    val allRequiredReady = requiredApps.isNotEmpty() &&
        requiredApps.all { downloadState.apps[it.id]?.status == DownloadStatus.Ready }
    val readyCount = installKit?.apps?.count {
        downloadState.apps[it.id]?.status == DownloadStatus.Ready
    } ?: 0
    val connectionReady = adbSession != null
    val kit = installKit
    val backupCoordinator = remember(adbSession, kit, settings.backupDir) {
        val session = adbSession
        if (session != null && kit != null) {
            BackupCoordinator(settings.backupDir, session, kit)
        } else {
            null
        }
    }
    val backupState = if (backupCoordinator != null) {
        backupCoordinator.state.collectAsState().value
    } else {
        BackupUiState()
    }
    var backupSkipped by remember(adbSession) { mutableStateOf(false) }
    val backupReady = backupSkipped || (
        backupState.apps.isNotEmpty() &&
            backupState.apps.values.all {
                it.status == BackupStatus.Done || it.status == BackupStatus.Missing
            }
        )
    val installCoordinator = remember(adbSession, kit, downloader) {
        val session = adbSession
        if (session != null && kit != null && downloader != null) {
            InstallCoordinator(session, kit, downloader)
        } else {
            null
        }
    }
    val installState = if (installCoordinator != null) {
        installCoordinator.state.collectAsState().value
    } else {
        InstallUiState()
    }
    val installAllDone = installState.finished &&
        installState.apps.isNotEmpty() &&
        installState.apps.values.all { it.status == AppInstallStatus.Done }

    PlatformBackHandler {
        if (step > 0) step-- else onExit()
    }

    WizardScaffold(
        step = step,
        totalSteps = installSteps.size,
        title = current.title,
        subtitle = current.subtitle,
        riskLevel = current.riskLevel,
        riskTitle = current.riskTitle,
        riskMessage = current.riskMessage,
        primaryAction = WizardAction(
            label = when {
                step == installSteps.lastIndex -> "完成"
                step == 0 && installKit == null -> if (kitLoading) "正在获取安装包信息…" else "重试"
                step == 0 && !allRequiredReady -> when {
                    downloadState.isRunning -> "正在下载…"
                    readyCount > 0 -> "继续下载"
                    else -> "开始下载"
                }
                step == 2 && !connectionReady -> if (adbConnecting) {
                    "正在连接..."
                } else {
                    "请先连接设备"
                }
                step == 3 && !backupReady -> when {
                    backupState.running -> "正在备份..."
                    !connectionReady -> "请先连接设备"
                    else -> "请先备份或跳过"
                }
                step == 5 && !installState.finished -> if (installState.running) {
                    "正在安装..."
                } else {
                    "开始安装"
                }
                step == 5 && !installAllDone -> "重试安装"
                else -> "下一步"
            },
            enabled = when {
                step == 0 && installKit == null -> !kitLoading
                step == 0 && !allRequiredReady -> downloader != null && !downloadState.isRunning
                step == 2 -> connectionReady
                step == 3 -> connectionReady && !backupState.running && backupReady
                step == 5 && !installState.finished ->
                    connectionReady && installCoordinator != null && !installState.running
                step == 5 && !installAllDone -> connectionReady && !installState.running
                else -> true
            },
            onClick = {
                when {
                    step == installSteps.lastIndex -> onExit()
                    step == 0 && installKit == null -> if (!kitLoading) kitReloadToken++
                    step == 0 && !allRequiredReady -> scope.launch { downloader?.downloadAll() }
                    step == 2 && !connectionReady -> Unit
                    step == 3 && !backupReady -> Unit
                    step == 5 && !installState.finished ->
                        scope.launch { installCoordinator?.install() }
                    step == 5 && !installAllDone ->
                        scope.launch { installCoordinator?.install() }
                    else -> step++
                }
            },
        ),
        secondaryAction = if (step > 0) {
            WizardAction(
                label = "上一步",
                onClick = { step-- },
            )
        } else {
            null
        },
        onBack = {
            if (step > 0) step-- else onExit()
        },
    ) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState > initialState
                val enter = slideInHorizontally(
                    animationSpec = tween(
                        durationMillis = 220,
                        easing = FastOutSlowInEasing,
                    ),
                    initialOffsetX = { width -> if (forward) width / 3 else -width / 3 },
                ) + fadeIn(animationSpec = tween(durationMillis = 160))
                val exit = slideOutHorizontally(
                    animationSpec = tween(
                        durationMillis = 220,
                        easing = FastOutSlowInEasing,
                    ),
                    targetOffsetX = { width -> if (forward) -width / 4 else width / 4 },
                ) + fadeOut(animationSpec = tween(durationMillis = 120))
                enter togetherWith exit
            },
            label = "install-step",
        ) { targetStep ->
            Column(modifier = Modifier.fillMaxWidth()) {
                when (targetStep) {
                    0 -> DownloadStepContent(
                        installKit = installKit,
                        loading = kitLoading,
                        error = kitError,
                        onRetry = { kitReloadToken++ },
                        downloadState = downloadState,
                    )
                    1 -> AdbGuideStepContent()
                    2 -> ConnectStepContent(adb)
                    3 -> BackupStepContent(
                        installKit = installKit,
                        sessionReady = connectionReady,
                        state = backupState,
                        skipped = backupSkipped,
                        backupDir = settings.backupDir,
                        canPickDirectory = platform.supportsBackupDirectoryPicker,
                        directoryError = backupDirError,
                        onChangeDirectory = {
                            platform.pickDirectory(settings.backupDir)?.let { dir ->
                                BackupDirectory.validate(dir)
                                    .onSuccess {
                                        settings.setBackupDir(dir)
                                        backupDirError = null
                                    }
                                    .onFailure { backupDirError = it.message }
                            }
                        },
                        onOpenDirectory = { platform.openDirectory(settings.backupDir) },
                        onStartBackup = {
                            backupSkipped = false
                            scope.launch { backupCoordinator?.backup() }
                        },
                        onSkip = { backupSkipped = true },
                    )
                    4 -> PreviewStepContent(
                        installKit = installKit,
                        onShowConfirm = { showUninstallConfirm = true },
                    )
                    5 -> InstallStepContent(
                        installKit = installKit,
                        state = installState,
                    )
                    else -> CompleteStepContent()
                }
            }
        }
    }

    if (showUninstallConfirm) {
        val uninstallLabels = installKit?.apps?.map { it.replacesLabel }
            ?: listOf("丰云悦享", "爱趣听", "微信")
        val appList = uninstallLabels.joinToString("\n") { "• $it" }
        DestructiveConfirmDialog(
            title = "即将卸载车机上的这些应用",
            message = "为了装上新版本，需要先卸载：\n$appList\n\n" +
                "卸载后，这些应用里的设置、登录状态等数据会一起清空，且无法找回" +
                "（应用本身已备份到本机，可以恢复）。",
            notice = installKit?.uninstallNotice
                ?: "卸载丰云悦享不会影响车联网功能（包括里程、油耗数据显示、远程空调等），" +
                "这些功能由车机系统提供。",
            confirmLabel = "确认卸载",
            checkboxLabel = "我已了解并确认卸载",
            onDismiss = { showUninstallConfirm = false },
            onConfirm = { showUninstallConfirm = false },
        )
    }
}

@Composable
private fun DownloadStepContent(
    installKit: InstallKit?,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    downloadState: DownloadUiState,
) {
    if (installKit == null) {
        if (loading) {
            InfoCard(title = "安装包") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "正在获取安装包信息…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            InfoCard(title = "安装包") {
                Text(
                    text = error ?: "暂时无法获取安装包信息。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        return
    }

    val requiredApps = installKit.apps.filter { it.required }
    val readyCount = installKit.apps.count {
        downloadState.apps[it.id]?.status == DownloadStatus.Ready
    }
    val allReady = requiredApps.isNotEmpty() && requiredApps.all {
        downloadState.apps[it.id]?.status == DownloadStatus.Ready
    }
    val totalBytes = installKit.totalSizeBytes
    val downloadedBytes = installKit.apps.sumOf { app ->
        downloadState.apps[app.id]?.downloadedBytes ?: 0L
    }
    val fraction = if (totalBytes > 0L) {
        downloadedBytes.toFloat() / totalBytes
    } else {
        0f
    }

    val profileName = installKit.deviceProfiles.firstOrNull()?.name ?: "凯美瑞 A 导航"
    InfoCard(title = "下载进度") {
        InfoRow(label = "目标车型", value = profileName)
        InfoRow(label = "应用数量", value = "${installKit.appCount} 个")
        InfoRow(label = "已下载", value = "$readyCount / ${installKit.appCount}")
        InfoRow(label = "总大小", value = formatSize(totalBytes))
    }

    if (downloadState.isRunning) {
        Spacer(Modifier.height(12.dp))
        LineProgressBar(progress = fraction)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "已下载 ${formatSize(downloadedBytes)} / ${formatSize(totalBytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(12.dp))
    InfoCard(title = "将安装的应用") {
        installKit.apps.forEachIndexed { index, app ->
            DownloadAppRow(app, downloadState.apps[app.id])
            if (index != installKit.apps.lastIndex) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
            }
        }
    }

    val error = downloadState.apps.values.firstOrNull { it.error != null }?.error
    if (error != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Spacer(Modifier.height(12.dp))
    if (allReady) {
        Text(
            text = "文件已下载完成，可以继续下一步。",
            style = MaterialTheme.typography.bodyMedium,
            color = DiPlaySuccess,
        )
    } else {
        Text(
            text = "点击下方“开始下载”，把文件下载到本机；中断后再次点击会接着下载。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DownloadAppRow(app: InstallApp, state: AppDownloadState?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "替换车机上的 ${app.replacesLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(
                modifier = Modifier.widthIn(max = 150.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = displayVersion(app),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = statusLabel(state),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor(state),
                )
            }
        }
        if (state != null && state.status == DownloadStatus.Downloading && state.totalBytes > 0L) {
            LineProgressBar(
                progress = state.downloadedBytes.toFloat() / state.totalBytes,
            )
        }
        if (state != null && state.error != null) {
            Text(
                text = state.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun statusLabel(state: AppDownloadState?): String {
    val status = state?.status ?: DownloadStatus.Pending
    return when (status) {
        DownloadStatus.Pending -> "待下载"
        DownloadStatus.Verifying -> "校验中"
        DownloadStatus.Downloading -> {
            val downloaded = state?.downloadedBytes ?: 0L
            val total = state?.totalBytes ?: 0L
            val percent = if (total > 0L) {
                (downloaded * 100L / total).toInt()
            } else {
                0
            }
            "下载中 $percent%"
        }
        DownloadStatus.Ready -> "已下载"
        DownloadStatus.Failed -> "下载失败，可重试"
        DownloadStatus.Invalid -> "下载地址暂不可用"
    }
}

@Composable
private fun statusColor(state: AppDownloadState?): Color = when (state?.status) {
    DownloadStatus.Ready -> DiPlaySuccess
    DownloadStatus.Failed, DownloadStatus.Invalid -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun AdbGuideStepContent() {
    InfoCard(title = "在车机上这样操作") {
        BulletLine("打开车机“设置”，进入“开发者选项”")
        BulletLine("打开“USB 调试”开关")
    }
    Spacer(Modifier.height(12.dp))
    InfoCard(title = "找不到“开发者选项”？") {
        Text(
            text = "部分车机需要在“设置 → 关于”里连点版本号数次才会出现；也可以插上数据线后直接到下一步试试。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConnectStepContent(adb: AdbController) {
    val platform = LocalPlatform.current
    val session by adb.session.collectAsState()
    val active by adb.activeDevice.collectAsState()
    val status by adb.status.collectAsState()
    val connecting by adb.connecting.collectAsState()
    val devices by adb.devices.collectAsState()

    InfoCard(title = "连接状态") {
        InfoRow(
            label = "状态",
            value = when {
                session != null -> "已连接"
                connecting -> "正在连接…"
                else -> "未连接"
            },
        )
        if (session != null) {
            InfoRow(label = "当前设备", value = active?.title ?: "已选择")
        } else {
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { adb.refresh() }) {
            Text("重新查找设备")
        }
        if (session != null) {
            OutlinedButton(onClick = { adb.disconnect() }) {
                Text("断开")
            }
        }
    }
    if (platform.isDesktop) {
        Spacer(Modifier.height(12.dp))
        WifiConnectField(adb)
    }
    Spacer(Modifier.height(12.dp))
    Text(
        text = "可用设备",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    devices.forEach { target ->
        AdbTargetCard(
            target = target,
            enabled = !connecting,
            connected = session != null,
            onConnect = { adb.connect(target) },
        )
    }
    if (devices.isEmpty() && session == null) {
        InfoCard(title = "还没有发现车机") {
            Text(
                text = if (platform.isDesktop) {
                    "请检查 USB 数据线和 ADB 驱动，或使用上方无线连接。"
                } else {
                    "请用 OTG 数据线将手机连接到车机。"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (platform.isDesktop) {
                Text(
                    text = if (platform.supportsUsbDriverHelp) {
                        "如果设备管理器中显示未知设备，请安装车机的 ADB 驱动。"
                    } else {
                        "macOS 无需安装 ADB 驱动；请确认车机已允许 USB 调试。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (platform.supportsUsbDriverHelp) {
                    OutlinedButton(onClick = { platform.openUsbDriverHelp() }) {
                        Text("打开设备管理器")
                    }
                }
            }
            Text(
                text = "如果已经连接，请点“重新查找设备”。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = if (session != null) {
            "已连接，可以继续下一步。"
        } else {
            "请选择设备并连接，连接成功后才能继续。"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun BackupStepContent(
    installKit: InstallKit?,
    sessionReady: Boolean,
    state: BackupUiState,
    skipped: Boolean,
    backupDir: File,
    canPickDirectory: Boolean,
    directoryError: String?,
    onChangeDirectory: () -> Unit,
    onOpenDirectory: () -> Unit,
    onStartBackup: () -> Unit,
    onSkip: () -> Unit,
) {
    val platform = LocalPlatform.current
    if (installKit == null) {
        InfoCard(title = "备份信息不可用") {
            Text(
                text = "暂时无法读取要备份的应用，请稍后重试。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    if (!sessionReady) {
        InfoCard(title = "请先连接设备") {
            Text(
                text = "返回上一步连接车机后再备份。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    val doneCount = state.apps.values.count { it.status == BackupStatus.Done }
    val missingCount = state.apps.values.count { it.status == BackupStatus.Missing }
    val allDone = state.apps.isNotEmpty() && state.apps.values.all {
        it.status == BackupStatus.Done || it.status == BackupStatus.Missing
    }

    InfoCard(title = "备份位置") {
        Text(
            text = backupDir.absolutePath,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (canPickDirectory) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onChangeDirectory) {
                    Text("更改…")
                }
                OutlinedButton(onClick = onOpenDirectory) {
                    Text("打开目录")
                }
            }
        }
        directoryError?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    InfoCard(title = "备份进度") {
        installKit.apps.forEachIndexed { index, app ->
            val appState = state.apps[app.id] ?: AppBackupState(
                appId = app.id,
                label = app.replacesLabel,
            )
            BackupAppRow(appState)
            if (index != installKit.apps.lastIndex) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
        }
    }

    when {
        state.running -> {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "正在保存到${if (platform.isDesktop) "电脑" else "手机"}，应用越大耗时越久，请稍候…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        allDone -> {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "已保存 $doneCount 个应用到${if (platform.isDesktop) "电脑" else "手机"}" +
                    if (missingCount > 0) "；$missingCount 个车机上本来就没有" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = DiPlaySuccess,
            )
        }
        skipped -> {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "已选择跳过备份；之后想还原车机原应用时将没有备份可用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onStartBackup) {
                Text("现在备份")
            }
        }
        else -> {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onStartBackup,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("开始备份")
            }
            TextButton(onClick = onSkip) {
                Text("跳过备份")
            }
        }
    }

    state.error?.let { error ->
        Spacer(Modifier.height(8.dp))
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun BackupAppRow(state: AppBackupState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = state.label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = when (state.status) {
                BackupStatus.Pending -> "等待"
                BackupStatus.Scanning -> "检查中"
                BackupStatus.BackingUp -> "保存中"
                BackupStatus.Done -> "已备份"
                BackupStatus.Missing -> "未安装"
                BackupStatus.Failed -> "失败"
            },
            style = MaterialTheme.typography.labelSmall,
            color = when (state.status) {
                BackupStatus.Done -> DiPlaySuccess
                BackupStatus.Failed -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
    state.message?.takeIf { state.status == BackupStatus.Failed }?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun PreviewStepContent(
    installKit: InstallKit?,
    onShowConfirm: () -> Unit,
) {
    InfoCard(title = "将要执行的操作") {
        if (installKit == null) {
            Text("安装包信息未加载。")
        } else {
            installKit.apps.forEach { app ->
                AppRow(
                    name = app.label,
                    subtitle = "替换车机上的 ${app.replacesLabel}",
                    version = displayVersion(app),
                    badge = actionLabel(app.installMode),
                )
            }
        }
    }
    val uninstallLabels = installKit?.apps?.map { it.replacesLabel }.orEmpty()
    if (uninstallLabels.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "同时会卸载车机上的：${uninstallLabels.joinToString("、")}。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = onShowConfirm,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        Text("查看卸载说明")
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = "确认没问题后，点“下一步”开始安装。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun actionLabel(installMode: String): String = when (installMode) {
    "replace" -> "先卸载旧版，再安装"
    "upgrade" -> "覆盖更新"
    else -> "直接安装"
}

private fun displayVersion(app: InstallApp): String =
    app.versionName.substringBefore("_").ifBlank { app.versionName }

private fun formatSize(bytes: Long): String =
    if (bytes <= 0L) "待补充" else "${bytes / 1024L / 1024L} MB"

@Composable
private fun InstallStepContent(
    installKit: InstallKit?,
    state: InstallUiState,
) {
    if (installKit == null) {
        InfoCard(title = "安装信息不可用") {
            Text(
                text = "暂时无法读取安装信息，请稍后重试。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    val allDone = state.finished &&
        state.apps.isNotEmpty() &&
        state.apps.values.all { it.status == AppInstallStatus.Done }

    InfoCard(title = "安装进度") {
        installKit.apps.forEachIndexed { index, app ->
            val appState = state.apps[app.id] ?: AppInstallState(
                appId = app.id,
                label = app.label,
            )
            InstallAppRow(appState)
            if (index != installKit.apps.lastIndex) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    when {
        allDone -> Text(
            text = "全部安装完成。",
            style = MaterialTheme.typography.bodyMedium,
            color = DiPlaySuccess,
        )
        state.finished -> Text(
            text = "有应用没装上，可以点下方按钮重试。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        state.running -> Text(
            text = "正在安装，请保持本机运行，不要拔线或退出。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Text(
            text = "确认后点击下方“开始安装”。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InstallAppRow(state: AppInstallState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = state.label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = when (state.status) {
                AppInstallStatus.Pending -> "等待"
                AppInstallStatus.Checking -> "检查中"
                AppInstallStatus.Uninstalling -> "卸载旧版本"
                AppInstallStatus.Installing -> "安装中"
                AppInstallStatus.Done -> "完成"
                AppInstallStatus.Failed -> "失败"
            },
            style = MaterialTheme.typography.labelSmall,
            color = when (state.status) {
                AppInstallStatus.Done -> DiPlaySuccess
                AppInstallStatus.Failed -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
    state.message?.takeIf { state.status == AppInstallStatus.Failed }?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun CompleteStepContent() {
    InfoCard(title = "安装完成") {
        Text(
            text = "DiPlay 已经装到车机上了。",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        BulletLine("重启车机，让 DiPlay 生效")
        BulletLine("重启后打开 DiPlay，连接 iPhone 使用 CarPlay")
        BulletLine("记得回车机设置关闭“USB 调试”")
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = "如果有应用没装上，先不要重启车机；点“上一步”重试，或回首页用“恢复备份”还原。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
