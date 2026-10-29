package com.shilapi.xcertplay.installer.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.installer.ui.components.InfoCard
import com.shilapi.xcertplay.installer.ui.components.InfoRow
import com.shilapi.xcertplay.installer.ui.components.LineProgressBar
import com.shilapi.xcertplay.installer.update.AppUpdate
import com.shilapi.xcertplay.installer.update.AppUpdateRepository
import com.shilapi.xcertplay.installer.update.installUpdateApk
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onExit: () -> Unit,
    onAdbTest: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentVersion by remember {
        mutableStateOf(
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
            }.getOrDefault(""),
        )
    }
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    var updateFile by remember { mutableStateOf<File?>(null) }
    var updatePercent by remember { mutableStateOf<Int?>(null) }
    var updateFailed by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var checkedOnce by remember { mutableStateOf(false) }
    var checkToken by remember { mutableIntStateOf(1) }

    LaunchedEffect(checkToken) {
        checking = true
        updateFailed = false
        update = AppUpdateRepository.check(context)
        checking = false
        checkedOnce = true
    }

    BackHandler(onBack = onExit)

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
            InfoCard(title = "应用升级") {
                InfoRow(label = "当前版本", value = currentVersion.ifBlank { "未知" })
                when {
                    checking -> CheckingRow()
                    updateFile != null -> {
                        Text(
                            text = "更新包已下载，点击安装。安装时会询问是否允许，请选择允许。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = { updateFile?.let { installUpdateApk(context, it) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("安装")
                        }
                    }
                    updatePercent != null -> {
                        LineProgressBar(progress = (updatePercent ?: 0) / 100f)
                        Text(
                            text = "正在下载更新… $updatePercent%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        if (updateFailed) {
                            Text(
                                text = "下载失败，请重试。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        update?.let { found ->
                            Text(
                                text = "发现新版本 ${found.versionName}，可更新到最新版。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (checkedOnce && update == null) {
                            Text(
                                text = "已是最新版本。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Button(
                            onClick = {
                                val found = update
                                if (found == null) {
                                    checkToken++
                                } else {
                                    scope.launch {
                                        updatePercent = 0
                                        val file = AppUpdateRepository.download(context, found) { percent ->
                                            updatePercent = percent
                                        }
                                        updatePercent = null
                                        if (file == null) {
                                            updateFailed = true
                                        } else {
                                            updateFile = file
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (update == null) "检查更新" else "下载更新")
                        }
                    }
                }
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
