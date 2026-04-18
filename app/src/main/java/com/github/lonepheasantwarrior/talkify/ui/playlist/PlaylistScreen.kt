package com.github.lonepheasantwarrior.talkify.ui.playlist

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.lonepheasantwarrior.talkify.domain.playlist.PlaylistItem
import com.github.lonepheasantwarrior.talkify.domain.playlist.PlaylistItemStatus
import com.github.lonepheasantwarrior.talkify.domain.playlist.PlaylistManager
import com.github.lonepheasantwarrior.talkify.service.player.BackgroundPlaybackService

/** 支持的倍速列表，循环切换 */
private val SPEED_OPTIONS = listOf(1.0f, 1.5f, 2.0f)

/** 倍速对应的显示文字 */
private fun speedLabel(speed: Float): String = when (speed) {
    1.0f -> "1×"
    1.5f -> "1.5×"
    2.0f -> "2×"
    else -> "${speed}×"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    modifier: Modifier = Modifier,
    currentEngineId: String = "",
    onPlayAllClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val playlist by PlaylistManager.playlist.collectAsState()

    // 倍速状态（持久化到 SharedPreferences）
    val speedPrefs = remember { context.getSharedPreferences("shishi_playback", Context.MODE_PRIVATE) }
    var currentSpeed by remember { mutableStateOf(speedPrefs.getFloat("speed", 1.0f)) }

    fun saveSpeed(speed: Float) {
        currentSpeed = speed
        speedPrefs.edit().putFloat("speed", speed).apply()
    }

    /** 启动服务播放指定条目 */
    fun playItem(item: PlaylistItem) {
        val intent = Intent(context, BackgroundPlaybackService::class.java).apply {
            action = BackgroundPlaybackService.ACTION_PLAY_ITEM
            putExtra(BackgroundPlaybackService.EXTRA_ITEM_ID, item.id)
            putExtra(BackgroundPlaybackService.EXTRA_ENGINE_ID, currentEngineId)
            putExtra(BackgroundPlaybackService.EXTRA_SPEED, currentSpeed)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /** 停止（暂停）当前播放 */
    fun stopPlayback() {
        val intent = Intent(context, BackgroundPlaybackService::class.java).apply {
            action = BackgroundPlaybackService.ACTION_STOP
        }
        context.startService(intent)
    }

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
            // 右下角：倍速切换按钮
            FloatingActionButton(
                onClick = {
                    val nextIdx = (SPEED_OPTIONS.indexOf(currentSpeed) + 1) % SPEED_OPTIONS.size
                    saveSpeed(SPEED_OPTIONS[nextIdx])
                },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Icon(Icons.Default.Speed, contentDescription = "倍速", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(speedLabel(currentSpeed), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "列表为空",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "复制文本或链接后打开 APP，即可加入朗读",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(playlist, key = { it.id }) { item ->
                    PlaylistItemCard(
                        item = item,
                        onPlayClick = { playItem(item) },
                        onPauseClick = { stopPlayback() },
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
    onPlayClick: () -> Unit,
    onPauseClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val isPlaying = item.status == PlaylistItemStatus.PLAYING

    val containerColor by animateColorAsState(
        targetValue = when (item.status) {
            PlaylistItemStatus.PLAYING -> MaterialTheme.colorScheme.primaryContainer
            PlaylistItemStatus.COMPLETED -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            PlaylistItemStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "cardColor"
    )

    val textColor = when (item.status) {
        PlaylistItemStatus.PLAYING -> MaterialTheme.colorScheme.onPrimaryContainer
        PlaylistItemStatus.COMPLETED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        PlaylistItemStatus.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val statusLabel = when (item.status) {
        PlaylistItemStatus.IDLE -> "待播放"
        PlaylistItemStatus.PLAYING -> "正在播放"
        PlaylistItemStatus.COMPLETED -> "已播放"
        PlaylistItemStatus.ERROR -> "播放出错"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isPlaying) 4.dp else 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：播放 / 暂停 按钮
            IconButton(
                onClick = if (isPlaying) onPauseClick else onPlayClick
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    tint = textColor,
                    modifier = Modifier.size(28.dp)
                )
            }

            // 中间：内容文本
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(2.dp))
                // 标题或正文预览
                val displayText = if (!item.title.isNullOrBlank() && item.isUrl) item.title
                                  else item.content
                Text(
                    text = displayText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isPlaying) FontWeight.Medium else FontWeight.Normal,
                    color = textColor
                )
                // URL 条目显示链接域名
                if (item.isUrl) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = try { java.net.URL(item.content).host } catch (_: Exception) { item.content.take(40) },
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 右侧：删除按钮
            IconButton(onClick = onDeleteClick) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = textColor.copy(alpha = 0.6f)
                )
            }
        }
    }
}
