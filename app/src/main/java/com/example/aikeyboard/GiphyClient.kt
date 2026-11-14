package com.example.aikeyboard

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** GIFs and stickers from GIPHY (free API key from developers.giphy.com). */
object GiphyClient {
    class Item(val thumbUrl: String, val gifUrl: String)

    fun fetch(apiKey: String, sticker: Boolean, query: String): List<Item> {
        val base = "https://api.giphy.com/v1/" + (if (sticker) "stickers" else "gifs")
        val url = if (query.isBlank()) "$base/trending?api_key=$apiKey&limit=24&rating=pg-13"
        else "$base/search?api_key=$apiKey&q=${URLEncoder.encode(query, "UTF-8")}&limit=24&rating=pg-13"
        val root = JSONObject(String(download(url), Charsets.UTF_8))
        val data = root.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Item>()
        for (i in 0 until data.length()) {
            val img = data.getJSONObject(i).optJSONObject("images") ?: continue
            val thumb = img.optJSONObject("fixed_width_still")?.optString("url").orEmpty()
            val gif = (img.optJSONObject("fixed_height") ?: img.optJSONObject("original"))?.optString("url").orEmpty()
            if (thumb.isNotEmpty() && gif.isNotEmpty()) out.add(Item(thumb, gif))
        }
        return out
    }

    fun download(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 30000
            if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
}
