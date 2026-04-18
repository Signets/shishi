package com.github.lonepheasantwarrior.talkify.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.lonepheasantwarrior.talkify.domain.playlist.PlaylistItem
import com.github.lonepheasantwarrior.talkify.domain.playlist.PlaylistItemStatus
import com.github.lonepheasantwarrior.talkify.domain.playlist.PlaylistManager
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    modifier: Modifier = Modifier,
    onPlayAllClick: () -> Unit
) {
    val playlist by PlaylistManager.playlist.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("播放列表") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                )
            )
        },
        floatingActionButton = {
            if (playlist.isNotEmpty()) {
                FloatingActionButton(onClick = onPlayAllClick) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "全部播放")
                }
            }
        }
    ) { innerPadding ->
        if (playlist.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "列表为空。请复制文本打开 APP 加入朗读",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(playlist, key = { it.id }) { item ->
                    PlaylistItemCard(
                        item = item,
                        onDeleteClick = { PlaylistManager.removeItem(item.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun PlaylistItemCard(
    item: PlaylistItem,
    onDeleteClick: () -> Unit
) {
    val backgroundColor = when (item.status) {
        PlaylistItemStatus.PLAYING -> MaterialTheme.colorScheme.primaryContainer
        PlaylistItemStatus.COMPLETED -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        PlaylistItemStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    
    val textColor = when (item.status) {
        PlaylistItemStatus.PLAYING -> MaterialTheme.colorScheme.onPrimaryContainer
        PlaylistItemStatus.COMPLETED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        PlaylistItemStatus.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = item.status.name, // "IDLE", "PLAYING", "COMPLETED"
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = item.content,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor
                )
            }
            
            IconButton(onClick = onDeleteClick) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = textColor
                )
            }
        }
    }
}
