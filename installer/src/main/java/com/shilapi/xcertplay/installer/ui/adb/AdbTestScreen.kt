package com.shilapi.xcertplay.installer.ui.adb

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.installer.adb.rememberAdbConnectionController
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = rememberAdbConnectionController()

    fun runCommand(label: String, command: String) {
        val current = controller.session ?: return
        scope.launch {
            controller.updateStatus("$label…")
            val result = withContext(Dispatchers.IO) {
                runCatching { current.shell(command) }
            }
            result.onSuccess {
                val text = it.trim()
                controller.appendLog("$ $command\n$text")
                val failed = listOf("Failure", "Error", "Exception", "Can't open file")
                    .any { marker -> text.contains(marker, ignoreCase = true) }
                controller.updateStatus(if (failed) "$label 失败" else "$label 完成")
            }.onFailure {
                controller.appendLog("$ $command\n失败：${it.message}")
                controller.updateStatus("$label 失败")
            }
        }
    }

    BackHandler(onBack = onExit)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
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
                text = controller.status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            controller.activeTarget?.let { target ->
                Text(
                    text = "当前目标：${target.title}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (controller.connecting) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { controller.refresh() }) {
                    Text("重新扫描")
                }
                if (controller.session != null) {
                    OutlinedButton(onClick = { controller.disconnect() }) {
                        Text("断开")
                    }
                }
            }

            Text(
                text = "可用 ADB 目标",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            controller.targets.forEach { target ->
                AdbTargetCard(
                    target = target,
                    enabled = !controller.connecting,
                    connected = controller.session != null,
                    onConnect = { controller.connect(target) },
                )
            }

            if (controller.usbDevices.isEmpty()) {
                InfoCard(title = "USB 设备信息") {
                    Text(
                        text = "未发现带 ADB 接口的 USB 设备。以下是当前 USB 总线的原始信息：",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = controller.deviceDump.ifBlank { "正在读取 USB 设备信息…" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (controller.session != null) {
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
                            val current = controller.session ?: return@Button
                            scope.launch {
                                controller.updateStatus("流式安装测试包…")
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val local = File(context.cacheDir, "adbtest.apk")
                                        current.readRemoteFile(TEST_APK_PATH, local)
                                        current.uninstallPackage(TEST_PACKAGE)
                                        current.installApk(local)
                                    }
                                }
                                result.onSuccess {
                                    controller.appendLog("流式安装测试包：\n${it.trim()}")
                                    controller.updateStatus("流式安装完成")
                                }.onFailure {
                                    controller.appendLog("流式安装失败：\n${it.stackTraceToString()}")
                                    controller.updateStatus("流式安装失败")
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

            if (controller.log.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = controller.log,
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

