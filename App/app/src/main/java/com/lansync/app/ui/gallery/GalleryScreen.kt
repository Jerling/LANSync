package com.lansync.app.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoLibrary
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
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
                        viewModel = viewModel,
                        onPhotoClick = { photo, allPhotos ->
                            viewModel.selectPhoto(photo, allPhotos)
                        }
                    )
                }
            }
        }

        // Photo preview dialog
        uiState.selectedPhoto?.let { selected ->
            PhotoPreviewDialog(
                selectedPhoto = selected,
                viewModel = viewModel,
                onDismiss = { viewModel.clearSelectedPhoto() }
            )
        }
    }
}

@Composable
private fun GalleryContent(
    groups: List<GalleryGroup>,
    viewModel: GalleryViewModel,
    onPhotoClick: (GalleryPhoto, List<Pair<String, String>>) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        groups.forEach { group ->
            item(key = group.date) {
                GalleryDateSection(
                    group = group,
                    viewModel = viewModel,
                    onPhotoClick = onPhotoClick
                )
            }
        }
    }
}

@Composable
private fun GalleryDateSection(
    group: GalleryGroup,
    viewModel: GalleryViewModel,
    onPhotoClick: (GalleryPhoto, List<Pair<String, String>>) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Date header
        Text(
            text = formatDateHeader(group.date),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // Photo grid
        val allPhotos: List<Pair<String, String>> = group.photos.map { it.id to it.path }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxWidth()
                .height(((group.photos.size / 3 + if (group.photos.size % 3 > 0) 1 else 0) * 130).dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            userScrollEnabled = false
        ) {
            items(group.photos, key = { it.id }) { photo ->
                PhotoGridItem(
                    photo = photo,
                    thumbUrl = viewModel.getThumbUrl(photo.path),
                    onClick = { onPhotoClick(photo, allPhotos) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun PhotoGridItem(
    photo: GalleryPhoto,
    thumbUrl: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.DarkGray)
            .clickable(onClick = onClick),
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
    }
}

@Composable
private fun PhotoPreviewDialog(
    selectedPhoto: SelectedPhoto,
    viewModel: GalleryViewModel,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Photo
            val photoUrl = viewModel.getPhotoUrl(selectedPhoto.path)
            AsyncImage(
                model = photoUrl,
                contentDescription = selectedPhoto.name,
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.Center),
                contentScale = ContentScale.Fit
            )

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
                Column {
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
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
                }
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

private fun formatDateHeader(dateStr: String): String {
    // "2026-04-27" -> "2026年4月27日"
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
