package com.adit.iptv

import org.json.JSONArray
import org.json.JSONObject

data class Channel(
    val id: String,
    val name: String,
    val group: String = "",
    val logo: String = "",
    val url: String,
    val featured: Boolean = false
)

/**
 * Accepts the same JSON shape used by app/src/main/assets/channels.json and by
 * the remote "channels.json" the user can host on GitHub for live updates:
 * [{ "id": "...", "name": "...", "group": "...", "logo": "emoji-or-url", "url": "...", "featured": false }, ...]
 */
fun parseChannelsJson(text: String): List<Channel> {
    val arr = JSONArray(text)
    val result = mutableListOf<Channel>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val url = o.optString("url").trim()
        if (url.isBlank()) continue
        val name = o.optString("name").ifBlank { "Channel ${i + 1}" }
        result += Channel(
            id = o.optString("id").ifBlank { url.hashCode().toString() },
            name = name,
            group = o.optString("group").ifBlank { o.optString("cat") },
            logo = o.optString("logo").ifBlank { o.optString("emoji") },
            url = url,
            featured = o.optBoolean("featured", false)
        )
    }
    return result
}

fun channelsToJson(channels: List<Channel>): String {
    val arr = JSONArray()
    channels.forEach { c ->
        val o = JSONObject()
        o.put("id", c.id)
        o.put("name", c.name)
        o.put("group", c.group)
        o.put("logo", c.logo)
        o.put("url", c.url)
        o.put("featured", c.featured)
        arr.put(o)
    }
    return arr.toString()
}

/** Parses a standard #EXTM3U playlist (tvg-id / tvg-logo / group-title supported). */
fun parseM3u(text: String): List<Channel> {
    val result = mutableListOf<Channel>()
    var name = "Unknown"
    var group = ""
    var logo = ""

    for (raw in text.lines()) {
        val line = raw.trim()
        if (line.isEmpty()) continue

        if (line.startsWith("#EXTINF", true)) {
            val comma = line.indexOf(',')
            name = if (comma >= 0) line.substring(comma + 1).trim() else "Unknown"
            group = Regex("group-title=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.getOrNull(1).orEmpty()
            logo = Regex("tvg-logo=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.getOrNull(1).orEmpty()
        } else if (line.startsWith("#")) {
            continue
        } else if (line.startsWith("http://") || line.startsWith("https://")) {
            result += Channel(
                id = line.hashCode().toString(),
                name = name,
                group = group,
                logo = logo,
                url = line
            )
            name = "Unknown"
            group = ""
            logo = ""
        }
    }
    return result
}
