package com.github.lonepheasantwarrior.talkify.domain.playlist

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object PlaylistManager {

    private val _playlist = MutableStateFlow<List<PlaylistItem>>(emptyList())
    val playlist: StateFlow<List<PlaylistItem>> = _playlist.asStateFlow()

    fun addItem(item: PlaylistItem) {
        _playlist.update { current ->
            current + item
        }
    }

    fun addItems(items: List<PlaylistItem>) {
        _playlist.update { current ->
            current + items
        }
    }

    fun removeItem(id: String) {
        _playlist.update { current ->
            current.filter { it.id != id }
        }
    }

    fun clear() {
        _playlist.value = emptyList()
    }

    fun updateItemStatus(id: String, status: PlaylistItemStatus) {
        _playlist.update { current ->
            current.map {
                if (it.id == id) it.copy(status = status) else it
            }
        }
    }

    fun getNextIdleItem(): PlaylistItem? {
        return _playlist.value.firstOrNull { it.status == PlaylistItemStatus.IDLE }
    }
    
    fun getNextItemAfter(id: String): PlaylistItem? {
        val currentList = _playlist.value
        val index = currentList.indexOfFirst { it.id == id }
        if (index != -1 && index + 1 < currentList.size) {
            return currentList[index + 1]
        }
        return null
    }

    fun markAllAs(status: PlaylistItemStatus) {
        _playlist.update { current ->
            current.map { it.copy(status = status) }
        }
    }
}
