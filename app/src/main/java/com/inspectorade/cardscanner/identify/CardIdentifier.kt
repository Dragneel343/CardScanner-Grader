package com.inspectorade.cardscanner.identify

import com.inspectorade.cardscanner.grading.CardLayout
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Turns what [CardTextReader] read off a card into a ranked list of matching
 * cards from TCGdex, best first. The user confirms or picks from the list.
 */
object CardIdentifier {

    data class Match(
        val id: String,
        val name: String,
        val localId: String,
        val setId: String,
        val setName: String,
        val setOfficial: Int,
        val imageBase: String?,
    ) {
        /** "Ninjask · Dragon 38/97" */
        val label: String get() = "$name · $setName $localId" + if (setOfficial > 0) "/$setOfficial" else ""
    }

    /**
     * [confident] means the evidence pins down one specific printing (name and
     * number agree, or the number + set total leave one strong name match).
     * Otherwise — typically a name with no readable number — [matches] lists the
     * possibilities and the user should choose.
     */
    data class Result(val matches: List<Match>, val confident: Boolean)

    private const val MAX_MATCHES = 8
    private const val MAX_NAME_ONLY_MATCHES = 30
    private const val CONFIDENT_NAME_SIMILARITY = 0.8

    suspend fun identify(reading: CardTextReader.Reading): Result {
        val matches = rankedMatches(reading)
        val top = matches.firstOrNull() ?: return Result(emptyList(), false)
        val confident = when {
            reading.number == null -> matches.size == 1
            matches.size == 1 -> true
            else -> {
                // Several sets share this number; confident only if exactly one name fits.
                val strong = matches.count { m ->
                    reading.nameCandidates.any { similarity(it, m.name) >= CONFIDENT_NAME_SIMILARITY }
                }
                strong == 1 && reading.nameCandidates.any { similarity(it, top.name) >= CONFIDENT_NAME_SIMILARITY }
            }
        }
        return Result(matches, confident)
    }

    private suspend fun rankedMatches(reading: CardTextReader.Reading): List<Match> {
        val sets = TcgdexClient.sets().associateBy { it.id }
        val number = reading.number
        val total = reading.setTotal

        // The printed "/97" narrows the number to the few sets with that many cards.
        val setsWithTotal = total?.let { t -> sets.values.filter { it.official == t || it.total == t }.map { it.id }.toSet() }

        fun toMatch(card: TcgdexClient.CardBrief): Match? {
            val set = sets[card.setId] ?: return null
            return Match(card.id, card.name, card.localId, set.id, set.name, set.official, card.imageBase)
        }

        // 1. Name + number (+ set total) — usually a single exact hit.
        if (number != null) {
            for (name in reading.nameCandidates) {
                val hits = numberVariants(number).flatMap { TcgdexClient.searchCards(name = name, localId = it) }
                    .filter { setsWithTotal == null || it.setId in setsWithTotal }
                if (hits.isNotEmpty()) return hits.mapNotNull(::toMatch).distinctBy { it.id }.take(MAX_MATCHES)
            }
        }

        // 2. Number + set total alone (name misread): look up that number in each
        //    candidate set, then rank by how close the names are to what was read.
        if (number != null && setsWithTotal != null && setsWithTotal.size <= MAX_MATCHES) {
            val found = coroutineScope {
                setsWithTotal.flatMap { setId -> numberVariants(number).map { "$setId-$it" } }
                    .distinct()
                    .map { id -> async { runCatching { TcgdexClient.card(id) }.getOrNull() } }
                    .awaitAll()
                    .filterNotNull()
            }
            if (found.isNotEmpty()) {
                return found
                    .map { Match(it.id, it.name, it.localId, it.setId, it.setName, it.setOfficial, it.imageBase) }
                    .sortedByDescending { match -> reading.nameCandidates.maxOfOrNull { similarity(it, match.name) } ?: 0.0 }
            }
        }

        // 3. Name alone (no readable number, e.g. many promos): every printing of it,
        //    for the user to choose from — so don't cut the list short.
        for (name in reading.nameCandidates) {
            val hits = exactNamesFirst(TcgdexClient.searchCards(name = name).mapNotNull(::toMatch), name)
            if (hits.isNotEmpty()) return hits.take(MAX_NAME_ONLY_MATCHES)
        }
        return emptyList()
    }

    /** TCGdex name search matches substrings — "Porygon" also finds Porygon2 and Porygon-Z. */
    private fun exactNamesFirst(matches: List<Match>, name: String): List<Match> =
        matches.sortedByDescending { it.name.equals(name, ignoreCase = true) }

    /**
     * Manual search typed by the user: a name, optionally followed by the
     * collector number ("Hitmonchan ex 98" or "Ninjask 38/97").
     */
    suspend fun search(query: String): List<Match> {
        val sets = TcgdexClient.sets().associateBy { it.id }
        val parts = MANUAL_QUERY.matchEntire(query.trim())
        val name = parts?.groupValues?.get(1) ?: query.trim()
        val number = parts?.groupValues?.get(2)
        val cards = if (number == null) TcgdexClient.searchCards(name = name)
        else numberVariants(number).flatMap { TcgdexClient.searchCards(name = name, localId = it) }
        val matches = cards.mapNotNull { card ->
            val set = sets[card.setId] ?: return@mapNotNull null
            Match(card.id, card.name, card.localId, set.id, set.name, set.official, card.imageBase)
        }.distinctBy { it.id }
        return exactNamesFirst(matches, name).take(MAX_NAME_ONLY_MATCHES)
    }

    private val MANUAL_QUERY = Regex("""(.+?)\s+(\d{1,3}[ab]?)(?:\s*/\s*\d{1,3})?""")

    /** Layout for centering, from the card's set and whether it's a holo. */
    suspend fun layoutFor(match: Match): CardLayout {
        if (match.setId in E_CARD_SETS) return CardLayout.E_READER
        if (match.setId !in EX_DOT_CODE_SETS) return CardLayout.STANDARD
        // In the EX sets only non-holos carried dot codes.
        val detail = runCatching { TcgdexClient.card(match.id) }.getOrNull() ?: return CardLayout.EX_DOT_CODE
        val isHolo = detail.rarity?.contains("holo", ignoreCase = true) == true ||
            (detail.hasHoloVariant && !detail.hasNormalVariant)
        return if (isHolo) CardLayout.STANDARD else CardLayout.EX_DOT_CODE
    }

    // TCGdex set ids: Expedition, Aquapolis, Skyridge.
    private val E_CARD_SETS = setOf("ecard1", "ecard2", "ecard3")

    // TCGdex set ids: EX Ruby & Sapphire, Sandstorm, Dragon.
    private val EX_DOT_CODE_SETS = setOf("ex1", "ex2", "ex3")

    /** Printed numbers may be zero-padded ("017") or not ("17"), and TCGdex varies by set. */
    private fun numberVariants(number: String): List<String> {
        val bare = number.trimStart('0').ifEmpty { "0" }
        return listOf(number, bare, bare.padStart(3, '0')).distinct()
    }

    /** Fraction of the longer name's letters that match, position-insensitive (cheap, OCR-tolerant). */
    private fun similarity(a: String, b: String): Double {
        val x = a.lowercase().filter { it.isLetter() }
        val y = b.lowercase().filter { it.isLetter() }
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val counts = y.groupingBy { it }.eachCount().toMutableMap()
        var common = 0
        for (c in x) {
            val n = counts[c] ?: 0
            if (n > 0) {
                common++
                counts[c] = n - 1
            }
        }
        return common.toDouble() / maxOf(x.length, y.length)
    }
}
