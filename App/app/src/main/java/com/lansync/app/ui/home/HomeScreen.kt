package com.lansync.app.ui.home

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lansync.app.domain.model.SyncState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // 权限声明
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else {
        arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.POST_NOTIFICATIONS
        )
    }

    var hasPermissions by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermissions = results.values.all { it }
    }

    // 启动时检查权限
    LaunchedEffect(Unit) {
        hasPermissions = permissions.all {
            context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LANSync") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                    IconButton(onClick = {
                        viewModel.logout()
                        onLogout()
                    }) {
                        Icon(Icons.Default.Logout, contentDescription = "退出登录")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 用户信息卡片
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.AccountCircle,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = uiState.username.ifEmpty { "未登录" },
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "服务器: ${uiState.serverUrl.ifEmpty { "未设置" }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 同步控制按钮
            if (uiState.isServiceRunning) {
                OutlinedButton(
                    onClick = { viewModel.stopSyncService() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("停止同步服务")
                }
            } else {
                Button(
                    onClick = {
                        if (hasPermissions) {
                            viewModel.startSyncService()
                        } else {
                            permissionLauncher.launch(permissions)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (hasPermissions) "启动同步服务" else "启动同步服务（需授权）")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 同步状态
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "同步状态",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val (icon, color, text) = when (uiState.syncState) {
                        is SyncState.Idle -> Triple(
                            Icons.Default.Schedule,
                            MaterialTheme.colorScheme.onSurfaceVariant,
                            "空闲"
                        )
                        is SyncState.LoggingIn -> Triple(
                            Icons.Default.Login,
                            MaterialTheme.colorScheme.primary,
                            "登录中..."
                        )
                        is SyncState.Scanning -> Triple(
                            Icons.Default.Search,
                            MaterialTheme.colorScheme.primary,
                            "扫描照片中..."
                        )
                        is SyncState.Uploading -> Triple(
                            Icons.Default.CloudUpload,
                            MaterialTheme.colorScheme.primary,
                            "上传中..."
                        )
                        is SyncState.Progress -> Triple(
                            Icons.Default.CloudUpload,
                            MaterialTheme.colorScheme.primary,
                            "上传进度: ${(uiState.syncState as SyncState.Progress).current}/${(uiState.syncState as SyncState.Progress).total}"
                        )
                        is SyncState.Completed -> Triple(
                            Icons.Default.CheckCircle,
                            MaterialTheme.colorScheme.primary,
                            "同步完成"
                        )
                        is SyncState.AllSynced -> Triple(
                            Icons.Default.CheckCircle,
                            MaterialTheme.colorScheme.primary,
                            "全部已同步"
                        )
                        is SyncState.Error -> Triple(
                            Icons.Default.Error,
                            MaterialTheme.colorScheme.error,
                            (uiState.syncState as SyncState.Error).message
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(icon, contentDescription = null, tint = color)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = text, color = color)
                    }

                    // 显示失败文件名列表
                    if (uiState.syncState is SyncState.Error) {
                        val errorState = uiState.syncState as SyncState.Error
                        if (errorState.failedFileNames.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Divider()
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "失败文件 (${errorState.failedFileNames.size}):",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 120.dp)
                            ) {
                                items(errorState.failedFileNames) { fileName ->
                                    Text(
                                        text = fileName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (uiState.syncState is SyncState.Progress) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val progress = uiState.syncState as SyncState.Progress
                        val percentage = (progress.current.toFloat() / progress.total.toFloat() * 100).toInt()
                        LinearProgressIndicator(
                            progress = progress.current.toFloat() / progress.total.toFloat(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "$percentage% (${progress.current}/${progress.total})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
