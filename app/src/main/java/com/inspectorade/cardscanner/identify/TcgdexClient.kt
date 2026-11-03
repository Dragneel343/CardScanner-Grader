package com.inspectorade.cardscanner.identify

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal client for the TCGdex card database (https://tcgdex.dev, MIT-licensed,
 * free, no API key). Card search, card details (set, rarity, variants, and daily
 * TCGplayer/Cardmarket pricing), and official card images.
 */
object TcgdexClient {

    private const val BASE = "https://api.tcgdex.net/v2/en"
    private const val TIMEOUT_MS = 10_000

    data class CardBrief(val id: String, val localId: String, val name: String, val imageBase: String?) {
        /** TCGdex card ids are "<setId>-<localId>". */
        val setId: String get() = id.substringBeforeLast('-')
    }

    data class SetBrief(val id: String, val name: String, val official: Int, val total: Int)

    data class CardDetail(
        val id: String,
        val localId: String,
        val name: String,
        val setId: String,
        val setName: String,
        val setOfficial: Int,
        val rarity: String?,
        val hasNormalVariant: Boolean,
        val hasHoloVariant: Boolean,
        val imageBase: String?,
    )

    @Volatile private var setsCache: List<SetBrief>? = null

    suspend fun searchCards(name: String? = null, localId: String? = null): List<CardBrief> {
        val params = buildList {
            name?.let { add("name=" + URLEncoder.encode(it, "UTF-8")) }
            localId?.let { add("localId=" + URLEncoder.encode(it, "UTF-8")) }
        }
        val array = JSONArray(get("$BASE/cards?" + params.joinToString("&")) ?: return emptyList())
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            CardBrief(o.getString("id"), o.getString("localId"), o.getString("name"), o.optStringOrNull("image"))
        }
    }

    /** Null if no card has this id (TCGdex answers 404). */
    suspend fun card(id: String): CardDetail? {
        val o = JSONObject(get("$BASE/cards/" + URLEncoder.encode(id, "UTF-8")) ?: return null)
        val set = o.getJSONObject("set")
        val variants = o.optJSONObject("variants")
        return CardDetail(
            id = o.getString("id"),
            localId = o.getString("localId"),
            name = o.getString("name"),
            setId = set.getString("id"),
            setName = set.getString("name"),
            setOfficial = set.optJSONObject("cardCount")?.optInt("official") ?: 0,
            rarity = o.optStringOrNull("rarity"),
            hasNormalVariant = variants?.optBoolean("normal") ?: true,
            hasHoloVariant = variants?.optBoolean("holo") ?: false,
            imageBase = o.optStringOrNull("image"),
        )
    }

    suspend fun sets(): List<SetBrief> {
        setsCache?.let { return it }
        val array = JSONArray(get("$BASE/sets") ?: return emptyList())
        val sets = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val count = o.optJSONObject("cardCount")
            SetBrief(o.getString("id"), o.getString("name"), count?.optInt("official") ?: 0, count?.optInt("total") ?: 0)
        }
        setsCache = sets
        return sets
    }

    /** Official card image; [quality] is "low" (thumbnail) or "high". */
    suspend fun image(imageBase: String, quality: String = "low"): Bitmap? = withContext(Dispatchers.IO) {
        val connection = (URL("$imageBase/$quality.png").openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) null
            else connection.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            connection.disconnect()
        }
    }

    /** Body of a GET, or null on 404. Throws on network failure or other errors. */
    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }
        try {
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> connection.inputStream.bufferedReader().use { it.readText() }
                HttpURLConnection.HTTP_NOT_FOUND -> null
                else -> error("TCGdex HTTP ${connection.responseCode} for $url")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null
}
