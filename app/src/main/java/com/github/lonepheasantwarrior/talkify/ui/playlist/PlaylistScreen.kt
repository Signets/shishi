package com.github.lonepheasantwarrior.talkify.ui.playlist

import android.content.Intent
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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

/**
 * 播放列表界面。
 *
 * - 不再包含独立的 TopAppBar 和倍速 FAB（已迁移到 MainScreen 底部导航栏）。
 * - [speed] 由父级 MainScreen 管理并传入。
 * - 暂停使用 ACTION_PAUSE（保留服务），恢复使用 ACTION_RESUME。
 */
@Composable
fun PlaylistScreen(
    modifier: Modifier = Modifier,
    currentEngineId: String = "",
    speed: Float = 1.0f,
    onPlayAllClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val playlist by PlaylistManager.playlist.collectAsState()

    /** 启动服务播放指定条目 */
    fun playItem(item: PlaylistItem) {
        val intent = Intent(context, BackgroundPlaybackService::class.java).apply {
            action = BackgroundPlaybackService.ACTION_PLAY_ITEM
            putExtra(BackgroundPlaybackService.EXTRA_ITEM_ID, item.id)
            putExtra(BackgroundPlaybackService.EXTRA_ENGINE_ID, currentEngineId)
            putExtra(BackgroundPlaybackService.EXTRA_SPEED, speed)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /** 暂停当前播放（保留服务，允许恢复） */
    fun pausePlayback() {
        val intent = Intent(context, BackgroundPlaybackService::class.java).apply {
            action = BackgroundPlaybackService.ACTION_PAUSE
        }
        context.startService(intent)
    }

    /** 恢复当前播放 */
    fun resumePlayback() {
        val intent = Intent(context, BackgroundPlaybackService::class.java).apply {
            action = BackgroundPlaybackService.ACTION_RESUME
        }
        context.startService(intent)
    }

    if (playlist.isEmpty()) {
        // ---------- 空列表占位 ----------
        Box(
            modifier = modifier.fillMaxSize(),
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
        // ---------- 播放列表 ----------
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(playlist, key = { it.id }) { item ->
                PlaylistItemCard(
                    item = item,
                    onPlayClick = {
                        // PAUSED 条目点击 ▶ 时恢复播放，其他状态则重新开始播放
                        if (item.status == PlaylistItemStatus.PAUSED) {
                            resumePlayback()
                        } else {
                            playItem(item)
                        }
                    },
                    onPauseClick = { pausePlayback() },
                    onDeleteClick = { PlaylistManager.removeItem(item.id) }
                )
            }
        }
    }
}

/**
 * 单条播放列表卡片。
 *
 * 交互规则：
 * - PLAYING → 显示 ‖ 图标，点击触发暂停
 * - PAUSED / IDLE → 显示 ▶ 图标，点击触发恢复 / 播放
 *
 * 视觉规则：
 * - PLAYING → primaryContainer（蓝色调高亮）
 * - PAUSED  → tertiaryContainer（琥珀色调）
 * - COMPLETED → 半透明 surfaceVariant
 * - IDLE / ERROR → surfaceVariant
 */
@Composable
fun PlaylistItemCard(
    item: PlaylistItem,
    onPlayClick: () -> Unit,
    onPauseClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val isActive = item.status == PlaylistItemStatus.PLAYING ||
                   item.status == PlaylistItemStatus.PAUSED

    // ---------- 卡片背景色（带过渡动画） ----------
    val containerColor by animateColorAsState(
        targetValue = when (item.status) {
            PlaylistItemStatus.PLAYING   -> MaterialTheme.colorScheme.primaryContainer
            PlaylistItemStatus.PAUSED    -> MaterialTheme.colorScheme.tertiaryContainer
            PlaylistItemStatus.COMPLETED -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else                         -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "cardColor"
    )

    // ---------- 文本颜色 ----------
    val textColor = when (item.status) {
        PlaylistItemStatus.PLAYING   -> MaterialTheme.colorScheme.onPrimaryContainer
        PlaylistItemStatus.PAUSED    -> MaterialTheme.colorScheme.onTertiaryContainer
        PlaylistItemStatus.COMPLETED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        else                         -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // ---------- 卡片阴影（带过渡动画） ----------
    val elevation by animateDpAsState(
        targetValue = if (isActive) 4.dp else 0.dp,
        label = "cardElevation"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：播放 / 暂停按钮
            IconButton(
                onClick = if (item.status == PlaylistItemStatus.PLAYING) onPauseClick else onPlayClick
            ) {
                Icon(
                    imageVector = if (item.status == PlaylistItemStatus.PLAYING)
                        Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (item.status == PlaylistItemStatus.PLAYING) "暂停" else "播放",
                    tint = textColor,
                    modifier = Modifier.size(28.dp)
                )
            }

            // 中间：内容文本
            Column(modifier = Modifier.weight(1f)) {
                // 标题或正文预览（URL 条目优先显示标题）
                val displayText = if (!item.title.isNullOrBlank() && item.isUrl) item.title
                                  else item.content
                Text(
                    text = displayText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                    color = textColor
                )
                // URL 条目显示链接域名
                if (item.isUrl) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = try {
                            java.net.URL(item.content).host
                        } catch (_: Exception) {
                            item.content.take(40)
                        },
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
