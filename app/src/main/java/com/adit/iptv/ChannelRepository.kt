package com.adit.iptv

import android.content.Context
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles where the channel list comes from:
 *  1) last-known-good cache saved on this device (instant startup, works offline)
 *  2) the JSON/M3U bundled inside the app as a fallback (app/src/main/assets/channels.json)
 *  3) a remote URL you control (e.g. a raw GitHub file) that can be refreshed any time
 *     WITHOUT reinstalling the app — edit the file on GitHub, tap refresh (or wait for
 *     the automatic background check), and the new list shows up.
 */
class ChannelRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)

    fun getRemoteUrl(): String = prefs.getString(KEY_REMOTE_URL, "") ?: ""

    fun setRemoteUrl(url: String) {
        prefs.edit().putString(KEY_REMOTE_URL, url.trim()).apply()
    }

    fun getLastUpdateLabel(): String = prefs.getString(KEY_LAST_UPDATE, "") ?: ""

    private fun markUpdated() {
        val time = SimpleDateFormat("HH:mm", Locale("id", "ID")).format(Date())
        prefs.edit().putString(KEY_LAST_UPDATE, time).apply()
    }

    /** Fast path used on app launch: cache if present, otherwise the bundled default list. */
    fun loadCachedOrBundled(): List<Channel> {
        val cached = prefs.getString(KEY_CHANNELS_CACHE, null)
        if (!cached.isNullOrBlank()) {
            try {
                val parsed = parseChannelsJson(cached)
                if (parsed.isNotEmpty()) return parsed
            } catch (_: Exception) {
                // fall through to bundled defaults
            }
        }
        return loadBundled()
    }

    fun loadBundled(): List<Channel> {
        return try {
            val text = appContext.assets.open("channels.json").bufferedReader().use { it.readText() }
            parseChannelsJson(text)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveCache(channels: List<Channel>) {
        if (channels.isEmpty()) return
        prefs.edit().putString(KEY_CHANNELS_CACHE, channelsToJson(channels)).apply()
        markUpdated()
    }

    /** Blocking network call — always run this off the main thread. */
    @Throws(Exception::class)
    fun fetchRemote(url: String): List<Channel> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Cache-Control", "no-cache")
        connection.setRequestProperty("User-Agent", "IPTVPlayer/2.0 (Android)")
        connection.connect()

        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw Exception("HTTP $code")
        }

        val text = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()

        val trimmed = text.trim()
        val parsed = if (trimmed.startsWith("#EXTM3U") || trimmed.startsWith("#EXTINF")) {
            parseM3u(trimmed)
        } else {
            parseChannelsJson(trimmed)
        }
        if (parsed.isEmpty()) throw Exception("Playlist kosong / format tidak dikenali")
        return parsed
    }

    // ---- Favorites (keyed by channel URL) ----

    fun getFavorites(): MutableSet<String> =
        (prefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()).toMutableSet()

    fun saveFavorites(set: Set<String>) {
        prefs.edit().putStringSet(KEY_FAVORITES, set).apply()
    }

    // ---- History (ordered list of URLs, most recent first) ----

    fun getHistory(): MutableList<String> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { arr.getString(it) }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun saveHistory(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    companion object {
        private const val KEY_REMOTE_URL = "remote_url"
        private const val KEY_LAST_UPDATE = "last_update"
        private const val KEY_CHANNELS_CACHE = "channels_cache"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_HISTORY = "history"
    }
}
