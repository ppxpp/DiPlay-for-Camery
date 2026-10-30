package com.shilapi.xcertplay.installer.ui.adb

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.installer.platform.LocalAdbController
import com.shilapi.xcertplay.installer.platform.LocalPlatform
import com.shilapi.xcertplay.installer.platform.PlatformBackHandler
import com.shilapi.xcertplay.installer.ui.components.InfoCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TEST_APK_PATH = "/data/local/tmp/adbtest.apk"
private const val TEST_PACKAGE = "com.shilapi.xcertplay.adbtest"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdbTestScreen(onExit: () -> Unit) {
    val platform = LocalPlatform.current
    val adb = LocalAdbController.current
    val scope = rememberCoroutineScope()

    val status by adb.status.collectAsState()
    val active by adb.activeDevice.collectAsState()
    val connecting by adb.connecting.collectAsState()
    val devices by adb.devices.collectAsState()
    val diagnostics by adb.diagnostics.collectAsState()
    val session by adb.session.collectAsState()
    val log by adb.log.collectAsState()

    fun runCommand(label: String, command: String) {
        val current = adb.session.value ?: return
        scope.launch {
            adb.updateStatus("$label…")
            val result = withContext(Dispatchers.IO) {
                runCatching { current.shell(command) }
            }
            result.onSuccess {
                val text = it.trim()
                adb.appendLog("$ $command\n$text")
                val failed = listOf("Failure", "Error", "Exception", "Can't open file")
                    .any { marker -> text.contains(marker, ignoreCase = true) }
                adb.updateStatus(if (failed) "$label 失败" else "$label 完成")
            }.onFailure {
                adb.appendLog("$ $command\n失败：${it.message}")
                adb.updateStatus("$label 失败")
            }
        }
    }

    PlatformBackHandler(onBack = onExit)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("ADB 连接测试") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "选择 ADB 设备",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            active?.let { target ->
                Text(
                    text = "当前目标：${target.title}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (connecting) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { adb.refresh() }) {
                    Text("重新扫描")
                }
                if (session != null) {
                    OutlinedButton(onClick = { adb.disconnect() }) {
                        Text("断开")
                    }
                }
            }

            Text(
                text = "可用 ADB 目标",
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

            if (devices.isEmpty()) {
                InfoCard(title = "USB 设备信息") {
                    Text(
                        text = "未发现带 ADB 接口的 USB 设备。以下是当前 ADB 诊断信息：",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = diagnostics.ifBlank { "正在读取设备信息…" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (session != null) {
                InfoCard(title = "测试操作") {
                    Button(
                        onClick = {
                            runCommand(
                                "读取设备信息",
                                "getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk",
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("读取设备信息")
                    }
                    Button(
                        onClick = { runCommand("列出应用", "pm list packages -3") },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("列出第三方应用")
                    }
                    Button(
                        onClick = { runCommand("安装测试包", "pm install -r $TEST_APK_PATH") },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("安装测试包")
                    }
                    Button(
                        onClick = {
                            val current = adb.session.value ?: return@Button
                            scope.launch {
                                adb.updateStatus("流式安装测试包…")
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val local = File(platform.cacheDir, "adbtest.apk")
                                        current.readRemoteFile(TEST_APK_PATH, local)
                                        current.uninstallPackage(TEST_PACKAGE)
                                        current.installApk(local)
                                    }
                                }
                                result.onSuccess {
                                    adb.appendLog("流式安装测试包：\n${it.trim()}")
                                    adb.updateStatus("流式安装完成")
                                }.onFailure {
                                    adb.appendLog("流式安装失败：\n${it.stackTraceToString()}")
                                    adb.updateStatus("流式安装失败")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("流式安装测试包")
                    }
                    Button(
                        onClick = { runCommand("卸载测试包", "pm uninstall $TEST_PACKAGE") },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("卸载测试包")
                    }
                    Text(
                        text = "测试 APK 需先放到目标设备的 $TEST_APK_PATH；包名 $TEST_PACKAGE。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (log.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = log,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}
