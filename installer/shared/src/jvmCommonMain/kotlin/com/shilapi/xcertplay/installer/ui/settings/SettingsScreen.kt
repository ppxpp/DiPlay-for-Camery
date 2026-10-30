package com.shilapi.xcertplay.installer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.installer.platform.LocalPlatform
import com.shilapi.xcertplay.installer.platform.LocalSettings
import com.shilapi.xcertplay.installer.platform.LocalUpdateManager
import com.shilapi.xcertplay.installer.platform.PlatformBackHandler
import com.shilapi.xcertplay.installer.settings.BackupDirectory
import com.shilapi.xcertplay.installer.ui.components.InfoCard
import com.shilapi.xcertplay.installer.ui.components.InfoRow
import com.shilapi.xcertplay.installer.ui.components.LineProgressBar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onExit: () -> Unit,
    onAdbTest: () -> Unit,
) {
    val platform = LocalPlatform.current
    val settings = LocalSettings.current
    val updateManager = LocalUpdateManager.current
    val scope = rememberCoroutineScope()
    var backupDirError by remember { mutableStateOf<String?>(null) }

    PlatformBackHandler(onBack = onExit)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (platform.isDesktop) {
                InfoCard(title = "应用升级") {
                    Text(
                        text = "桌面版不提供自动更新。请从官方渠道下载新版本，解压后替换当前程序目录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
            InfoCard(title = "应用升级") {
                when {
                    updateManager.checking -> CheckingRow()

                    updateManager.readyFile != null -> {
                        Text(
                            text = if (platform.isDesktop) {
                                "更新包已下载。Windows 便携版请关闭本程序后用新包替换当前目录；安装版会启动安装程序。"
                            } else {
                                "更新包已下载，点击安装。安装时会询问是否允许，请选择允许。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        updateManager.installError?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Button(
                            onClick = { scope.launch { updateManager.installReady() } },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (platform.isDesktop) "打开更新包位置" else "安装")
                        }
                    }

                    updateManager.downloading -> {
                        val percent = updateManager.downloadPercent ?: 0
                        LineProgressBar(progress = percent / 100f)
                        Text(
                            text = "正在下载更新… $percent%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> {
                        if (updateManager.checkFailed) {
                            Text(
                                text = "检查失败，请确认网络后重试。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (updateManager.downloadFailed) {
                            Text(
                                text = "下载失败，请重试。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        updateManager.available?.let { found ->
                            Text(
                                text = "发现新版本 ${found.versionName}，可更新到最新版。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (updateManager.checkedOnce &&
                            !updateManager.checkFailed &&
                            updateManager.available == null
                        ) {
                            Text(
                                text = "已是最新版本。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Button(
                            onClick = {
                                scope.launch {
                                    if (updateManager.available == null) {
                                        updateManager.check()
                                    } else {
                                        updateManager.download()
                                    }
                                }
                            },
                            enabled = !updateManager.downloading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (updateManager.available == null) "检查更新" else "下载更新")
                        }
                    }
                }
            }

            }

            InfoCard(title = "备份位置") {
                InfoRow(label = "当前目录", value = settings.backupDir.absolutePath)
                Text(
                    text = if (platform.isDesktop) {
                        "备份只保存应用安装包，不包含应用数据；更换目录不会删除旧备份。"
                    } else {
                        "Android 端备份保存在应用私有目录；卸载应用会同时删除备份。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (platform.supportsBackupDirectoryPicker) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                platform.pickDirectory(settings.backupDir)?.let { dir ->
                                    BackupDirectory.validate(dir)
                                        .onSuccess {
                                            settings.setBackupDir(dir)
                                            backupDirError = null
                                        }
                                        .onFailure {
                                            backupDirError = it.message
                                        }
                                }
                            },
                        ) {
                            Text("更改…")
                        }
                        OutlinedButton(
                            onClick = {
                                BackupDirectory.validate(settings.backupDir)
                                    .onFailure { backupDirError = it.message }
                                platform.openDirectory(settings.backupDir)
                            },
                        ) {
                            Text("打开目录")
                        }
                        TextButton(onClick = { settings.setBackupDir(null) }) {
                            Text("恢复默认")
                        }
                    }
                }
                backupDirError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            InfoCard(title = "关于") {
                InfoRow(
                    label = "版本",
                    value = platform.appVersionName.ifBlank { "未知" },
                )
                Text(
                    text = if (platform.isDesktop) {
                        "帮你把 DiPlay 安装到凯美瑞 A 导航车机，全程按提示操作即可，无需安装 Java 或 adb。"
                    } else {
                        "帮你把 DiPlay 安装到凯美瑞 A 导航车机，全程按提示操作即可，不需要电脑。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "非丰田/广汽丰田官方工具，仅供测试使用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            InfoCard(title = "开发") {
                OutlinedButton(
                    onClick = onAdbTest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("设备连接测试（开发）")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CheckingRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "正在检查更新…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
