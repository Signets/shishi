package com.github.lonepheasantwarrior.talkify.domain.playlist

import android.content.Context
import android.content.SharedPreferences
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * 播放列表管理器（带 SharedPreferences 持久化）
 *
 * 使用轻量 JSON 自行序列化，避免引入 Room 等额外依赖。
 * 应用首次启动时调用 [init] 完成数据加载。
 */
object PlaylistManager {

    private const val TAG = "PlaylistManager"
    private const val PREFS_NAME = "shishi_playlist"
    private const val KEY_PLAYLIST = "playlist_json"

    private val _playlist = MutableStateFlow<List<PlaylistItem>>(emptyList())
    val playlist: StateFlow<List<PlaylistItem>> = _playlist.asStateFlow()

    private var prefs: SharedPreferences? = null

    // ────────────────────────────────────────────────────────
    // 初始化 & 持久化
    // ────────────────────────────────────────────────────────

    /** 在 Application.onCreate 中调用，加载历史列表 */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _playlist.value = load()
        TtsLogger.i(TAG) { "PlaylistManager 初始化完毕，共 ${_playlist.value.size} 条" }
    }

    private fun load(): List<PlaylistItem> {
        val json = prefs?.getString(KEY_PLAYLIST, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                PlaylistItem(
                    id = obj.getString("id"),
                    content = obj.getString("content"),
                    status = PlaylistItemStatus.IDLE, // 重启后所有条目重置为待播放
                    isUrl = obj.optBoolean("isUrl", false),
                    title = obj.optString("title").takeIf { it.isNotEmpty() }
                )
            }
        } catch (e: Exception) {
            TtsLogger.e("播放列表 JSON 解析失败", e, TAG)
            emptyList()
        }
    }

    private fun persist() {
        val array = JSONArray()
        _playlist.value.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("content", item.content)
                put("isUrl", item.isUrl)
                put("title", item.title ?: "")
            }
            array.put(obj)
        }
        prefs?.edit()?.putString(KEY_PLAYLIST, array.toString())?.apply()
    }

    // ────────────────────────────────────────────────────────
    // 公开操作 API
    // ────────────────────────────────────────────────────────

    fun addItem(item: PlaylistItem) {
        _playlist.update { it + item }
        persist()
    }

    fun addItems(items: List<PlaylistItem>) {
        _playlist.update { it + items }
        persist()
    }

    fun removeItem(id: String) {
        _playlist.update { it.filter { item -> item.id != id } }
        persist()
    }

    fun clear() {
        _playlist.value = emptyList()
        persist()
    }

    fun updateItemStatus(id: String, status: PlaylistItemStatus) {
        _playlist.update { current ->
            current.map { item ->
                when {
                    item.id == id -> item.copy(status = status)
                    // 当有新条目变为 PLAYING/PAUSED 时，将其他正在播放/暂停的条目重置为 IDLE
                    // 保证全局最多只有一个条目处于活跃状态
                    (status == PlaylistItemStatus.PLAYING || status == PlaylistItemStatus.PAUSED)
                        && (item.status == PlaylistItemStatus.PLAYING || item.status == PlaylistItemStatus.PAUSED)
                        -> item.copy(status = PlaylistItemStatus.IDLE)
                    else -> item
                }
            }
        }
        // 状态只是运行时数据，不需要持久化
    }

    fun getNextIdleItem(): PlaylistItem? =
        _playlist.value.firstOrNull { it.status == PlaylistItemStatus.IDLE }

    fun getNextItemAfter(id: String): PlaylistItem? {
        val list = _playlist.value
        val index = list.indexOfFirst { it.id == id }
        return if (index != -1 && index + 1 < list.size) list[index + 1] else null
    }

    fun markAllAs(status: PlaylistItemStatus) {
        _playlist.update { current -> current.map { it.copy(status = status) } }
    }
}
