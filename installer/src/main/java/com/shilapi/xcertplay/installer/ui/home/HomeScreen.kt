package com.shilapi.xcertplay.installer.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.installer.ui.components.BulletLine
import com.shilapi.xcertplay.installer.ui.components.InfoCard
import com.shilapi.xcertplay.installer.ui.components.LineProgressBar
import com.shilapi.xcertplay.installer.ui.components.RiskBanner
import com.shilapi.xcertplay.installer.ui.model.RiskLevel
import com.shilapi.xcertplay.installer.ui.theme.DiPlayInstallerTheme
import com.shilapi.xcertplay.installer.update.AppUpdate
import com.shilapi.xcertplay.installer.update.AppUpdateRepository
import com.shilapi.xcertplay.installer.update.installUpdateApk
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun HomeScreen(
    onInstall: () -> Unit,
    onRestore: () -> Unit,
    onAdbTest: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var update by remember { mutableStateOf<AppUpdate?>(null) }
    var updateFile by remember { mutableStateOf<File?>(null) }
    var updatePercent by remember { mutableStateOf<Int?>(null) }
    var updateFailed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        update = AppUpdateRepository.check(context)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { BrandHeader() }
            item {
                Text(
                    text = "帮你把 DiPlay 安装到凯美瑞 A 导航车机，全程跟着提示点“下一步”即可，不需要电脑。装好后，iPhone 就能在原车屏幕上使用 CarPlay。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            update?.let { found ->
                item {
                    AppUpdateCard(
                        update = found,
                        file = updateFile,
                        percent = updatePercent,
                        failed = updateFailed,
                        onUpdate = {
                            updateFailed = false
                            updatePercent = 0
                            scope.launch {
                                updateFile = AppUpdateRepository.download(context, found) { percent ->
                                    updatePercent = percent
                                }
                                updatePercent = null
                                if (updateFile == null) updateFailed = true
                            }
                        },
                        onInstall = { file ->
                            installUpdateApk(context, file)
                        },
                    )
                }
            }
            item { PreparationCard() }
            item {
                ModeCard(
                    title = "安装 / 升级",
                    description = "备份原应用并安装 DiPlay",
                    icon = Icons.Default.PlayArrow,
                    onClick = onInstall,
                    highlighted = true,
                )
            }
            item {
                ModeCard(
                    title = "恢复备份",
                    description = "把之前备份的原应用装回车机",
                    icon = Icons.Default.Refresh,
                    onClick = onRestore,
                    highlighted = false,
                )
            }
            item {
                RiskBanner(
                    level = RiskLevel.Warning,
                    title = "操作前请注意",
                    message = "安装会替换车机上的部分应用。请在驻车、拉好手刹后操作，过程中不要拔线或熄火断电。",
                )
            }
            item {
                OutlinedButton(
                    onClick = onAdbTest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("设备连接测试（开发）")
                }
            }
            item {
                Text(
                    text = "非丰田/广汽丰田官方工具 · v0.1.0",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(52.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = "DiPlay 装车助手",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "为凯美瑞 A 导航安装 DiPlay",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PreparationCard() {
    InfoCard(title = "开始前准备") {
        BulletLine("安卓手机，并确认支持 OTG")
        BulletLine("OTG 转接头和连接数据线")
        BulletLine("车机通电，手机电量充足")
        BulletLine("可用的网络，用于下载安装包")
    }
}

@Composable
private fun AppUpdateCard(
    update: AppUpdate,
    file: File?,
    percent: Int?,
    failed: Boolean,
    onUpdate: () -> Unit,
    onInstall: (File) -> Unit,
) {
    InfoCard(title = "发现新版本 ${update.versionName}") {
        when {
            file != null -> {
                Text(
                    text = "更新包已下载，点击安装。安装时会询问是否允许，请选择允许。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { onInstall(file) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("安装")
                }
            }
            percent != null -> {
                LineProgressBar(progress = percent / 100f)
                Text(
                    text = "正在下载更新… $percent%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                if (failed) {
                    Text(
                        text = "下载失败，请重试。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("立即更新")
                }
            }
        }
    }
}

@Composable
private fun ModeCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    highlighted: Boolean,
) {
    val shape = RoundedCornerShape(18.dp)
    val container = if (highlighted) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val iconContainer = if (highlighted) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val iconTint = if (highlighted) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = iconContainer,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    DiPlayInstallerTheme {
        HomeScreen(onInstall = {}, onRestore = {}, onAdbTest = {})
    }
}
