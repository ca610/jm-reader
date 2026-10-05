package com.jm.reader.data.history

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-device browsing history: every album opened in the detail screen, newest first.
 *
 * History lives in SharedPreferences so it works without an account and offline. Logged-in
 * readers still report their reads to the server's `watch_list` as before - this is the local
 * copy that the Library screen shows.
 */
class HistoryManager(context: Context) {

    data class Entry(
        val albumId: String,
        val name: String,
        val author: String?,
        val updateAt: Long,
        val viewedAt: Long,
    )

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("jm_history", Context.MODE_PRIVATE)

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** Records an album (or moves it back to the top). Safe to call from the main thread. */
    fun record(albumId: String, name: String, author: String?, updateAt: Long) {
        if (albumId.isBlank()) return
        val entry = Entry(
            albumId = albumId,
            name = name,
            author = author?.takeIf { it.isNotBlank() },
            updateAt = updateAt,
            viewedAt = System.currentTimeMillis(),
        )
        _entries.update { current ->
            (listOf(entry) + current.filterNot { it.albumId == albumId }).take(MAX_ENTRIES)
        }
        persist()
    }

    fun remove(albumId: String) {
        _entries.update { current -> current.filterNot { it.albumId == albumId } }
        persist()
    }

    fun clear() {
        _entries.value = emptyList()
        prefs.edit().remove(KEY).apply()
    }

    private fun persist() {
        val arr = JSONArray()
        _entries.value.forEach { e ->
            arr.put(
                JSONObject()
                    .put("albumId", e.albumId)
                    .put("name", e.name)
                    .put("author", e.author ?: "")
                    .put("updateAt", e.updateAt)
                    .put("viewedAt", e.viewedAt)
            )
        }
        // apply() is asynchronous, so recording never blocks the UI thread.
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun load(): List<Entry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("albumId")
                if (id.isBlank()) return@mapNotNull null
                Entry(
                    albumId = id,
                    name = o.optString("name"),
                    author = o.optString("author").takeIf { it.isNotBlank() },
                    updateAt = o.optLong("updateAt"),
                    viewedAt = o.optLong("viewedAt"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val KEY = "entries"
        const val MAX_ENTRIES = 200
    }
}
