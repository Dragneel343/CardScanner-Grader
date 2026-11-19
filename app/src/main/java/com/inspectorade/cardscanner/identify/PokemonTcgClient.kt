package com.inspectorade.cardscanner.identify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal client for the Pokémon TCG API (https://pokemontcg.io), a second card
 * database beside [TcgdexClient]: it lists some printings TCGdex doesn't (the
 * 2026 "30th Celebration: Classic Collection" reprints), has images where TCGdex
 * has none, and keeps identification working while TCGdex is down or
 * rate-limiting. Used without a key, at the lower rate limit: the API is
 * deprecated (no new keys; existing ones end March 1, 2027, its successor
 * Scrydex is paid), so expect it to stop answering — identification then
 * carries on with TCGdex alone (see [CardIdentifier]).
 */
object PokemonTcgClient {

    private const val BASE = "https://api.pokemontcg.io/v2"
    private const val TIMEOUT_MS = 15_000
    private const val RETRIES = 3
    private const val RETRY_DELAY_MS = 500L
    private const val PAGE_SIZE = 60

    data class Card(
        val id: String,
        val name: String,
        val number: String,
        val setId: String,
        val setName: String,
        /** The number printed after the slash ("58/102"); 0 if none. */
        val printedTotal: Int,
        /** For [CardImages]: the small image URL without ".png". */
        val imageBase: String?,
    )

    /**
     * Cards with [number] whose name contains [nameWord] (one word: the query
     * language matches words, and a multi-word name with an apostrophe or a
     * misread letter would find nothing); either may be null. Callers narrow
     * the names further.
     */
    suspend fun searchCards(nameWord: String? = null, number: String? = null): List<Card> {
        val terms = buildList {
            nameWord?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() }?.let { add("name:*$it*") }
            number?.let { add("number:$it") }
        }
        if (terms.isEmpty()) return emptyList()
        val query = URLEncoder.encode(terms.joinToString(" "), "UTF-8")
        val body = get("$BASE/cards?pageSize=$PAGE_SIZE&select=id,name,number,set,images&q=$query") ?: return emptyList()
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { i ->
            val o = data.getJSONObject(i)
            val set = o.getJSONObject("set")
            Card(
                id = o.getString("id"),
                name = o.getString("name"),
                number = o.getString("number"),
                setId = set.getString("id"),
                setName = set.getString("name"),
                printedTotal = set.optInt("printedTotal"),
                imageBase = o.optJSONObject("images")?.optString("small")?.takeIf { it.endsWith(".png") }?.removeSuffix(".png"),
            )
        }
    }

    private val responses = ConcurrentHashMap<String, Optional<String>>()

    /** Body of a GET, or null on 404; busy-server answers are retried (see [TcgdexClient]). */
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
                else -> error("Pokémon TCG API HTTP $code for $url")
            }
        }
    }

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
}
