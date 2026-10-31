package com.shilapi.xcertplay.installer.ui.restore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.installer.adb.AdbController
import com.shilapi.xcertplay.installer.backup.BackupAppEntry
import com.shilapi.xcertplay.installer.backup.BackupRepository
import com.shilapi.xcertplay.installer.backup.BackupSession
import com.shilapi.xcertplay.installer.restore.AppRestoreState
import com.shilapi.xcertplay.installer.restore.AppRestoreStatus
import com.shilapi.xcertplay.installer.platform.LocalAdbController
import com.shilapi.xcertplay.installer.platform.LocalPlatform
import com.shilapi.xcertplay.installer.platform.LocalSettings
import com.shilapi.xcertplay.installer.platform.PlatformBackHandler
import com.shilapi.xcertplay.installer.restore.RestoreCoordinator
import com.shilapi.xcertplay.installer.restore.RestoreUiState
import com.shilapi.xcertplay.installer.ui.adb.AdbTargetCard
import com.shilapi.xcertplay.installer.ui.adb.WifiConnectField
import com.shilapi.xcertplay.installer.ui.components.DestructiveConfirmDialog
import com.shilapi.xcertplay.installer.ui.components.InfoCard
import com.shilapi.xcertplay.installer.ui.components.InfoRow
import com.shilapi.xcertplay.installer.ui.components.RiskBanner
import com.shilapi.xcertplay.installer.ui.model.RiskLevel
import com.shilapi.xcertplay.installer.ui.theme.DiPlaySuccess
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreScreen(onExit: () -> Unit) {
    val platform = LocalPlatform.current
    val settings = LocalSettings.current
    val adb = LocalAdbController.current
    val scope = rememberCoroutineScope()
    val adbSession by adb.session.collectAsState()
    val adbConnecting by adb.connecting.collectAsState()
    val adbStatus by adb.status.collectAsState()
    val adbActive by adb.activeDevice.collectAsState()

    var overrideRoot by remember { mutableStateOf<File?>(null) }
    val root = overrideRoot ?: settings.backupDir
    val sessions = remember(root) { BackupRepository.listSessions(root) }
    var selectedId by remember(root) { mutableStateOf(sessions.firstOrNull()?.id) }
    val selectedSession = sessions.firstOrNull { it.id == selectedId }
    var checked by remember(root) {
        mutableStateOf(selectedSession?.backedUpApps?.map { it.packageName }?.toSet() ?: emptySet())
    }

    var coordinator by remember { mutableStateOf<RestoreCoordinator?>(null) }
    val restoreState = coordinator?.state?.collectAsState()?.value ?: RestoreUiState()
    var showConfirm by remember { mutableStateOf(false) }

    val restoreAllDone = restoreState.finished &&
        restoreState.apps.isNotEmpty() &&
        restoreState.apps.values.all { it.status == AppRestoreStatus.Done }

    PlatformBackHandler(onBack = onExit)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("恢复备份") },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val canRestore = selectedSession != null &&
                        checked.isNotEmpty() &&
                        adbSession != null &&
                        !restoreState.running
                    Button(
                        onClick = {
                            if (restoreState.finished && restoreAllDone) {
                                onExit()
                            } else {
                                showConfirm = true
                            }
                        },
                        enabled = if (restoreState.running) false else canRestore,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                    ) {
                        Text(
                            text = when {
                                restoreState.running -> "正在恢复…"
                                restoreState.finished && restoreAllDone -> "完成"
                                else -> "开始恢复"
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (sessions.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                InfoCard(
                    title = if (root == settings.backupDir) "还没有备份" else "这个目录没有备份",
                ) {
                    Text(
                        text = "请先完成一次安装，并在安装前备份车机原应用。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "当前目录：${root.absolutePath}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (platform.supportsBackupDirectoryPicker) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                platform.pickDirectory(root)?.let { overrideRoot = it }
                            },
                        ) {
                            Text("选择其他备份目录")
                        }
                        if (overrideRoot != null) {
                            TextButton(onClick = { overrideRoot = null }) {
                                Text("恢复默认目录")
                            }
                        }
                    }
                }
                return@Column
            }

            Text(
                text = "选择要恢复的备份，再选择要装回的应用。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = "备份目录：${root.absolutePath}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (platform.supportsBackupDirectoryPicker) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            platform.pickDirectory(root)?.let { overrideRoot = it }
                        },
                    ) {
                        Text("选择其他备份目录")
                    }
                    if (overrideRoot != null) {
                        TextButton(onClick = { overrideRoot = null }) {
                            Text("恢复默认目录")
                        }
                    }
                }
            }

            RiskBanner(
                level = RiskLevel.Warning,
                title = "恢复不包含应用数据",
                message = "只还原应用安装包，不恢复设置、登录状态等数据。",
            )

            sessions.forEach { session ->
                SessionCard(
                    session = session,
                    selected = session.id == selectedId,
                    onSelect = {
                        if (selectedId != session.id) {
                            selectedId = session.id
                            checked = session.backedUpApps.map { it.packageName }.toSet()
                            coordinator = null
                        }
                    },
                )
            }

            selectedSession?.let { session ->
                val selectable = session.backedUpApps
                if (selectable.isNotEmpty()) {
                    InfoCard(title = "选择要恢复的应用") {
                        selectable.forEachIndexed { index, app ->
                            RestoreAppRow(
                                app = app,
                                checked = app.packageName in checked,
                                enabled = !restoreState.running,
                                onToggle = { isChecked ->
                                    checked = if (isChecked) {
                                        checked + app.packageName
                                    } else {
                                        checked - app.packageName
                                    }
                                },
                            )
                            if (index != selectable.lastIndex) {
                                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            }
                        }
                    }
                }
            }

            ConnectCard(adb)

            if (restoreState.apps.isNotEmpty()) {
                InfoCard(title = "恢复进度") {
                    restoreState.apps.forEach { (packageName, state) ->
                        RestoreStatusRow(state)
                        if (packageName != restoreState.apps.keys.lastOrNull()) {
                            HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        }
                    }
                }
                when {
                    restoreAllDone -> Text(
                        text = "恢复完成。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DiPlaySuccess,
                    )
                    restoreState.finished -> Text(
                        text = "有应用恢复失败，可以重试或联系支持。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showConfirm) {
        val labels = selectedSession?.apps
            ?.filter { it.packageName in checked }
            ?.joinToString("\n") { "• ${it.label}" }
            .orEmpty()
        DestructiveConfirmDialog(
            title = "确认恢复这些应用",
            message = "将把下面这些备份的应用装回车机，当前版本会被替换：\n$labels",
            notice = "恢复使用的是之前备份的安装包，可能是较早的版本。",
            confirmLabel = "确认恢复",
            checkboxLabel = "我已了解并确认恢复",
            onDismiss = { showConfirm = false },
            onConfirm = {
                showConfirm = false
                val session = selectedSession ?: return@DestructiveConfirmDialog
                val packages = session.apps.filter { it.packageName in checked }.map { it.packageName }
                val currentSession = adbSession ?: return@DestructiveConfirmDialog
                val runner = RestoreCoordinator(currentSession, session, packages)
                coordinator = runner
                scope.launch { runner.restore() }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionCard(
    session: BackupSession,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = formatCreatedAt(session.createdAt),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${session.deviceModel.ifBlank { "未知设备" }} · ${session.apps.size} 个应用 · ${formatSize(session.totalSizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RestoreAppRow(
    app: BackupAppEntry,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onToggle,
            enabled = enabled,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = if (app.isSystemApp) "系统应用 · 恢复为车机自带版本" else "恢复为备份时车机上的版本",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RestoreStatusRow(state: AppRestoreState) {
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
                AppRestoreStatus.Pending -> "等待"
                AppRestoreStatus.Uninstalling -> "准备中"
                AppRestoreStatus.Installing -> "安装中"
                AppRestoreStatus.Enabling -> "启用中"
                AppRestoreStatus.Verifying -> "检查中"
                AppRestoreStatus.Done -> "完成"
                AppRestoreStatus.Failed -> "失败"
            },
            style = MaterialTheme.typography.labelSmall,
            color = when (state.status) {
                AppRestoreStatus.Done -> DiPlaySuccess
                AppRestoreStatus.Failed -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
    state.message?.takeIf { state.status == AppRestoreStatus.Failed }?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ConnectCard(adb: AdbController) {
    val session by adb.session.collectAsState()
    val deviceName by adb.activeDevice.collectAsState()
    val status by adb.status.collectAsState()
    val connecting by adb.connecting.collectAsState()
    val devices by adb.devices.collectAsState()
    val platform = LocalPlatform.current

    InfoCard(title = "连接车机") {
        InfoRow(
            label = "状态",
            value = when {
                session != null -> "已连接"
                connecting -> "正在连接…"
                else -> "未连接"
            },
        )
        if (session != null && deviceName != null) {
            InfoRow(label = "当前设备", value = deviceName!!.title)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { adb.refresh() }) {
                Text("重新查找设备")
            }
            if (session != null) {
                OutlinedButton(onClick = { adb.disconnect() }) {
                    Text("断开")
                }
            }
        }
        if (platform.isDesktop) {
            Spacer(Modifier.height(8.dp))
            WifiConnectField(adb)
        }
        devices.forEach { target ->
            AdbTargetCard(
                target = target,
                enabled = !connecting,
                connected = session != null,
                onConnect = { adb.connect(target) },
            )
        }
        if (platform.isDesktop && devices.isEmpty() && session == null) {
            Text(
                text = if (platform.supportsUsbDriverHelp) {
                    "USB 连接需要 ADB 驱动；也可以使用上方无线连接。"
                } else {
                    "macOS 无需安装 ADB 驱动；也可以使用上方无线连接。"
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
        if (session == null) {
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatCreatedAt(raw: String): String {
    // raw like 20261029-165254
    if (raw.length >= 15 && raw[8] == '-') {
        val date = raw.substring(0, 8)
        val time = raw.substring(9, minOf(13, raw.length))
        val formattedDate = "${date.substring(0, 4)}-${date.substring(4, 6)}-${date.substring(6, 8)}"
        val formattedTime = if (time.length >= 4) "${time.substring(0, 2)}:${time.substring(2, 4)}" else time
        return "$formattedDate $formattedTime"
    }
    return raw
}

private fun formatSize(bytes: Long): String =
    if (bytes <= 0L) "—" else "${bytes / 1024L / 1024L} MB"
