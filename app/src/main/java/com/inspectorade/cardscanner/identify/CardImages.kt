package com.inspectorade.cardscanner.identify

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Official card images from either database, by the image base a match carries
 * ([CardIdentifier.Match.imageBase]): TCGdex serves "<base>/low.png" and
 * "<base>/high.png", the Pokémon TCG API "<base>.png" and "<base>_hires.png".
 */
object CardImages {

    private const val POKEMONTCG_IMAGES = "https://images.pokemontcg.io/"
    private const val TIMEOUT_MS = 10_000
    private const val RETRIES = 2
    private const val RETRY_DELAY_MS = 500L

    /** [quality] is "low" (thumbnail) or "high"; null if there's no image. Throws on network failure. */
    suspend fun load(imageBase: String, quality: String = "low"): Bitmap? {
        val url = if (imageBase.startsWith(POKEMONTCG_IMAGES)) imageBase + (if (quality == "high") "_hires" else "") + ".png"
        else "$imageBase/$quality.png"
        var attempt = 0
        while (true) {
            val (code, bitmap) = fetch(url)
            if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_NOT_FOUND) return bitmap
            // Busy or failing server: images are worth a short wait, a match is judged by them.
            if ((code == 429 || code >= 500) && attempt < RETRIES) delay(RETRY_DELAY_MS shl attempt++) else return null
        }
    }

    private suspend fun fetch(url: String): Pair<Int, Bitmap?> = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        try {
            val code = connection.responseCode
            code to if (code == HttpURLConnection.HTTP_OK) connection.inputStream.use { BitmapFactory.decodeStream(it) } else null
        } finally {
            connection.disconnect()
        }
    }
}
