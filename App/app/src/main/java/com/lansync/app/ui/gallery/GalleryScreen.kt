package com.lansync.app.ui.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.lansync.app.domain.model.GalleryGroup
import com.lansync.app.domain.model.GalleryPhoto

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<GalleryPhoto?>(null) }

    // Rename dialog
    if (showRenameDialog && renameTarget != null) {
            RenameDialog(
                originalName = renameTarget!!.name,
                onConfirm = { newName ->
                    viewModel.renamePhoto(renameTarget!!.path, newName)
                    showRenameDialog = false
                    renameTarget = null
                },
                onDismiss = {
                    showRenameDialog = false
                    renameTarget = null
                }
            )
        }

        // 删除云相册时本地文件确认 dialog
        // 注意："只删云端" 是删除操作，不是取消，不应放在 dismissButton 中。
        // 用自定义 Dialog 布局，将两个删除选项并排，"取消"单独放在下面，避免与 dismissButton 混淆。
        if (uiState.showDeleteLocalDialog && uiState.pendingDeleteInfo != null) {
            val info = uiState.pendingDeleteInfo!!
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { viewModel.dismissDeleteDialog() }
            ) {
                androidx.compose.material3.Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    tonalElevation = 6.dp
                ) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            "删除照片",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        Text(
                            "确定删除这 ${info.totalCount} 张照片？" +
                            if (info.hasLocalCount > 0) {
                                "\n\n其中 ${info.hasLocalCount} 张在本地也有副本，\n是否一并删除本地文件？"
                            } else "",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(24.dp))
                        // 第一行：两个删除选项（都是 destructive）
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            androidx.compose.material3.TextButton(
                                onClick = { viewModel.confirmDelete(alsoDeleteLocal = false) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("只删云端")
                            }
                            androidx.compose.material3.TextButton(
                                onClick = { viewModel.confirmDelete(alsoDeleteLocal = true) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("删除云端和本地", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        // 第二行：取消
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            androidx.compose.material3.TextButton(onClick = { viewModel.dismissDeleteDialog() }) {
                                Text("取消")
                            }
                        }
                    }
                }
            }
        }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.operationMessage) {
        uiState.operationMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearOperationMessage()
        }
    }
    // 批量下载完成 Snackbar
    LaunchedEffect(uiState.batchDownloadMessage) {
        uiState.batchDownloadMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearBatchDownloadMessage()
        }
    }

    Scaffold(
        topBar = {
            if (uiState.isSelecting) {
                // 多选模式 top bar
                TopAppBar(
                    title = { Text("${uiState.selectedIds.size} 已选中") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "取消")
                        }
                    },
                    actions = {
                        // 全选（对所有照片全选）
                        IconButton(onClick = {
                            uiState.groups.forEach { viewModel.selectAllInGroup(it) }
                        }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "全选")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            } else {
                // 普通模式 top bar
                TopAppBar(
                    title = {
                        Column {
                            Text("云相册")
                            if (uiState.totalCount > 0) {
                                Text(
                                    text = "${uiState.totalCount} 张照片",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.loadGallery() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (uiState.isSelecting && uiState.selectedIds.isNotEmpty()) {
                BottomAppBar(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // 下载按钮
                        val context = androidx.compose.ui.platform.LocalContext.current
                        TextButton(
                            onClick = { viewModel.downloadSelected(context) },
                            enabled = !uiState.isBatchDownloading
                        ) {
                            if (uiState.isBatchDownloading) {
                                CircularProgressIndicator(
                                    progress = uiState.batchDownloadProgress / 100f,
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("${uiState.batchDownloadCurrent}/${uiState.batchDownloadTotal}")
                            } else {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("保存本地 (${uiState.selectedIds.size})")
                            }
                        }

                        // 重命名按钮
                        TextButton(
                            onClick = {
                                // 只选中1张时才能重命名
                                if (uiState.selectedIds.size == 1) {
                                    val target = uiState.groups
                                        .flatMap { it.photos }
                                        .first { it.id in uiState.selectedIds }
                                    renameTarget = target
                                    showRenameDialog = true
                                }
                            },
                            enabled = uiState.selectedIds.size == 1 && !uiState.isBatchDownloading
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("重命名")
                        }

                        // 删除按钮
                        TextButton(
                            onClick = { viewModel.prepareDelete() },
                            enabled = !uiState.isBatchDownloading,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("删除")
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                uiState.error != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = uiState.error!!,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.loadGallery() }) {
                            Text("重试")
                        }
                    }
                }
                uiState.groups.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Image,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "暂无照片",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    GalleryContent(
                        groups = uiState.groups,
                        selectedIds = uiState.selectedIds,
                        isSelecting = uiState.isSelecting,
                        viewModel = viewModel,
                        onPhotoClick = { photo, allPhotos ->
                            viewModel.onPhotoClick(photo, allPhotos)
                        },
                        onPhotoLongPress = { photo ->
                            viewModel.onPhotoLongPress(photo)
                        }
                    )
                }
            }
        }

        // Photo preview dialog (非多选模式时才显示)
        uiState.selectedPhoto?.let { selected ->
            PhotoPreviewDialog(
                selectedPhoto = selected,
                viewModel = viewModel,
                uiState = uiState,
                onDismiss = { viewModel.clearSelectedPhoto() }
            )
        }

        // 操作中 loading
        if (uiState.isOperationInProgress) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryContent(
    groups: List<GalleryGroup>,
    selectedIds: Set<String>,
    isSelecting: Boolean,
    viewModel: GalleryViewModel,
    onPhotoClick: (GalleryPhoto, List<PhotoNavigationItem>) -> Unit,
    onPhotoLongPress: (GalleryPhoto) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        groups.forEach { group ->
            item(key = group.date) {
                GalleryDateSection(
                    group = group,
                    selectedIds = selectedIds,
                    isSelecting = isSelecting,
                    viewModel = viewModel,
                    onPhotoClick = onPhotoClick,
                    onPhotoLongPress = onPhotoLongPress
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryDateSection(
    group: GalleryGroup,
    selectedIds: Set<String>,
    isSelecting: Boolean,
    viewModel: GalleryViewModel,
    onPhotoClick: (GalleryPhoto, List<PhotoNavigationItem>) -> Unit,
    onPhotoLongPress: (GalleryPhoto) -> Unit
) {
    val allPhotos: List<PhotoNavigationItem> = group.photos.map { PhotoNavigationItem(it.id, it.path, it.name, it.size) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Date header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatDateHeader(group.date),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            if (isSelecting) {
                // 组内全选按钮
                val allSelected = group.photos.all { it.id in selectedIds }
                Checkbox(
                    checked = allSelected,
                    onCheckedChange = {
                        if (allSelected) {
                            // 取消全组选中 → 从全局 selectedIds 移除这些
                            // 简单处理：直接清空选择再重新加入
                        } else {
                            // 全选
                            viewModel.selectAllInGroup(group)
                        }
                    }
                )
            }
        }

        // Photo grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxWidth()
                .height(((group.photos.size / 3 + if (group.photos.size % 3 > 0) 1 else 0) * 118).dp)
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            userScrollEnabled = false
        ) {
            items(group.photos, key = { it.id }) { photo ->
                PhotoGridItem(
                    photo = photo,
                    thumbUrl = viewModel.getThumbUrl(photo.path),
                    isSelected = photo.id in selectedIds,
                    isSelecting = isSelecting,
                    onClick = { onPhotoClick(photo, allPhotos) },
                    onLongClick = { onPhotoLongPress(photo) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoGridItem(
    photo: GalleryPhoto,
    thumbUrl: String,
    isSelected: Boolean,
    isSelecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.DarkGray)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = thumbUrl,
            contentDescription = photo.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Video indicator
        if (photo.type == "video") {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f))
            )
            Icon(
                Icons.Default.VideoLibrary,
                contentDescription = "视频",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }

        // Selection overlay
        if (isSelecting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (isSelected) Color.Blue.copy(alpha = 0.3f)
                        else Color.Transparent
                    )
            )
            // Checkbox
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClick() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp),
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    uncheckedColor = Color.White
                )
            )
        }
    }
}

@Composable
private fun PhotoPreviewDialog(
    selectedPhoto: SelectedPhoto,
    viewModel: GalleryViewModel,
    uiState: GalleryUiState,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // 预览下载完成后自动消失
    LaunchedEffect(uiState.previewDownloadMessage) {
        uiState.previewDownloadMessage?.let {
            if (it.startsWith("已保存")) {
                kotlinx.coroutines.delay(1500)
                viewModel.clearPreviewDownloadMessage()
            }
        }
    }

    // 本地优先：hasLocal=true 时直接用系统图库打开本地文件，秒开无等待
    LaunchedEffect(selectedPhoto.hasLocal, selectedPhoto.localUri) {
        if (selectedPhoto.hasLocal && !selectedPhoto.localUri.isNullOrEmpty()) {
            val uri = try {
                android.net.Uri.parse(selectedPhoto.localUri)
            } catch (_: Exception) {
                null
            }
            uri?.let {
                try {
                    val mimeType = when {
                        selectedPhoto.type == "video" -> "video/*"
                        selectedPhoto.name.endsWith(".mp4") -> "video/mp4"
                        selectedPhoto.name.endsWith(".mov") -> "video/quicktime"
                        selectedPhoto.name.endsWith(".png") -> "image/png"
                        selectedPhoto.name.endsWith(".gif") -> "image/gif"
                        else -> "image/*"
                    }
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        setDataAndType(it, mimeType)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(intent)
                    // 启动外部 App 后直接关闭预览，返回缩略图列表
                    onDismiss()
                } catch (_: Exception) {
                    // 打开失败，静默降级到网络图片
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Photo（本地模式显示 loading 状态提示；网络模式正常加载）
            val photoUrl = viewModel.getPhotoUrl(selectedPhoto.path)
            AsyncImage(
                model = photoUrl,
                contentDescription = selectedPhoto.name,
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.Center),
                contentScale = ContentScale.Fit
            )

            // 本地文件指示
            if (selectedPhoto.hasLocal) {
                Text(
                    text = "📱 本地",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            // Top bar with close button and info
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(8.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedPhoto.name,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatFileSize(selectedPhoto.size),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // 下载按钮（未在下载且本地没有时才显示）
                if (!uiState.isPreviewDownloading && !selectedPhoto.hasLocal) {
                    IconButton(onClick = {
                        val photo = com.lansync.app.domain.model.GalleryPhoto(
                            id = selectedPhoto.id,
                            name = selectedPhoto.name,
                            path = selectedPhoto.path,
                            size = selectedPhoto.size,
                            type = selectedPhoto.type
                        )
                        viewModel.downloadPreviewPhoto(photo, context)
                    }) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = "保存到本地",
                            tint = Color.White
                        )
                    }
                }

                // 下载进度指示
                if (uiState.isPreviewDownloading) {
                    CircularProgressIndicator(
                        progress = uiState.previewDownloadProgress / 100f,
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
                }
            }

            // 下载结果提示
            uiState.previewDownloadMessage?.let { msg ->
                Text(
                    text = msg,
                    color = if (msg.startsWith("已保存")) Color.Green else Color.Yellow,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 72.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            // Navigation arrows
            if (selectedPhoto.allPhotos.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.Center)
                ) {
                    // Left arrow
                    IconButton(
                        onClick = { viewModel.navigatePhoto(-1) },
                        modifier = Modifier.align(Alignment.CenterVertically)
                    ) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = "上一张",
                            tint = if (selectedPhoto.index > 0) Color.White else Color.Gray,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Right arrow
                    IconButton(
                        onClick = { viewModel.navigatePhoto(1) },
                        modifier = Modifier.align(Alignment.CenterVertically)
                    ) {
                        Icon(
                            Icons.Filled.ArrowForward,
                            contentDescription = "下一张",
                            tint = if (selectedPhoto.index < selectedPhoto.allPhotos.size - 1) Color.White else Color.Gray,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                // Photo counter
                Text(
                    text = "${selectedPhoto.index + 1} / ${selectedPhoto.allPhotos.size}",
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(
    originalName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(originalName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("文件名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank() && name != originalName
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private fun formatDateHeader(dateStr: String): String {
    val parts = dateStr.split("-")
    if (parts.size != 3) return dateStr
    return "${parts[0]}年${parts[1].toInt()}月${parts[2].toInt()}日"
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
