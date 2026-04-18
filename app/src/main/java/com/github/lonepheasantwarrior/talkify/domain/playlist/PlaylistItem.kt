package com.github.lonepheasantwarrior.talkify.domain.playlist

import java.util.UUID

enum class PlaylistItemStatus {
    IDLE,
    PLAYING,
    COMPLETED,
    ERROR
}

data class PlaylistItem(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    var status: PlaylistItemStatus = PlaylistItemStatus.IDLE,
    val isUrl: Boolean = false,
    val title: String? = null // Extracted title if URL, or cropped text
)
