package com.inspectorade.cardscanner.identify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal client for the TCGdex card database (https://tcgdex.dev, MIT-licensed,
 * free, no API key). Card search, card details (set, rarity, variants, and daily
 * TCGplayer/Cardmarket pricing). Its images load through [CardImages].
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

    // Responses already fetched this session, by URL: identification asks the same
    // searches again (a retake, Back and forth), and each one counts against the
    // server's rate limit. Null is a cached 404.
    private val responses = ConcurrentHashMap<String, Optional<String>>()

    /**
     * Body of a GET, or null on 404. A busy server (429, 5xx) is retried after a
     * short, growing pause — identification makes a burst of searches, and
     * TCGdex answers 503 when it's rate-limiting. Throws on network failure or
     * once retries run out.
     */
    private suspend fun get(url: String): String? {
        responses[url]?.let { return it.orElse(null) }
        var attempt = 0
        while (true) {
            val (code, body) = fetch(url)
            when {
                code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_NOT_FOUND -> {
                    responses[url] = Optional.ofNullable(body)
                    return body
                }
                (code == 429 || code >= 500) && attempt < RETRIES -> delay(RETRY_DELAY_MS shl attempt++)
                else -> error("TCGdex HTTP $code for $url")
            }
        }
    }

    /** Status code and, if 200, the body. */
    private suspend fun fetch(url: String): Pair<Int, String?> = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            code to if (code == HttpURLConnection.HTTP_OK) connection.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            connection.disconnect()
        }
    }

    private const val RETRIES = 3
    private const val RETRY_DELAY_MS = 500L

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null
}
