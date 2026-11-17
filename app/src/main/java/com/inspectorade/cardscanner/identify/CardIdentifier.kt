package com.inspectorade.cardscanner.identify

import com.inspectorade.cardscanner.grading.CardLayout

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
     * [confident] means name + number left a single printing, or one printing's
     * printed number clearly matches a misread ("3897" for 38/97). Otherwise [matches]
     * lists the possibilities (e.g. "Porygon 103" is Aquapolis 103a and 103b, and
     * Burning Shadows 103) and the user chooses.
     */
    data class Result(val matches: List<Match>, val confident: Boolean)

    private const val MAX_MATCHES = 30

    suspend fun identify(reading: CardTextReader.Reading): Result {
        val (matches, byNumber) = search(reading.nameCandidates, reading.numberCandidates)
        if (byNumber && matches.size == 1) return Result(matches, confident = true)
        // Either no number read cleanly, or several printings share it (Porygon
        // 103a/103b/103): the full printed number, even misread, can settle it.
        return closestPrintedNumber(matches, reading.bottomLines)
            ?.let { best -> Result(listOf(best) + (matches - best), confident = true) }
            ?: Result(matches, confident = false)
    }

    // A printed number counts as read if OCR's text is within this many edits of
    // it: "38/97" read as "3897" (slash dropped) is 1, as "3R97" is 2.
    private const val MAX_NUMBER_EDITS = 2

    /**
     * When no number read cleanly, compares each printing's printed number
     * ("38/97") against the bottom band's text, tolerating OCR slips. Returns the
     * printing that matches clearly better than all others, or null.
     */
    private fun closestPrintedNumber(matches: List<Match>, bottomLines: List<String>): Match? {
        val tokens = bottomLines.flatMap { line -> line.split(Regex("""\s+""")) }
            .map { it.lowercase().filter { c -> c.isLetterOrDigit() || c == '/' || c == '|' } }
            .filter { token -> token.length >= 3 && token.any { it.isDigit() } }
        if (tokens.isEmpty()) return null
        val scored = matches.filter { it.setOfficial > 0 }.map { match ->
            val printed = "${match.localId}/${match.setOfficial}".lowercase()
            match to tokens.minOf { editDistance(it, printed) }
        }.sortedBy { it.second }
        val best = scored.firstOrNull() ?: return null
        val runnerUp = scored.getOrNull(1)?.second ?: Int.MAX_VALUE
        return best.first.takeIf { best.second <= MAX_NUMBER_EDITS && best.second < runnerUp }
    }

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
            }
            previous = current
        }
        return previous[b.length]
    }

    /**
     * Name + number first — the first candidate number that exists for the name
     * wins, so a stray code read off the bottom band just finds nothing — then
     * name alone (number misread, or none printed, as on many promos). TCGdex's
     * number filter is "contains" ("25" also returns 225 and 259), so hits are
     * narrowed to the same digits, zero-padded or with a letter suffix: "103"
     * keeps 103a/103b, "17" keeps 017. Also returns whether a number matched.
     */
    private suspend fun search(names: List<String>, numbers: List<String>): Pair<List<Match>, Boolean> {
        val sets = TcgdexClient.sets().associateBy { it.id }
        fun toMatches(cards: List<TcgdexClient.CardBrief>, name: String) = exactNamesFirst(
            cards.mapNotNull { card ->
                val set = sets[card.setId] ?: return@mapNotNull null
                Match(card.id, card.name, card.localId, set.id, set.name, set.official, card.imageBase)
            }.distinctBy { it.id },
            name,
        ).take(MAX_MATCHES)

        for (number in numbers) {
            val bare = number.trimStart('0').ifEmpty { "0" }
            val sameNumber = Regex("""0*$bare(\D.*)?""")
            for (name in names) {
                val cards = TcgdexClient.searchCards(name = name, localId = bare)
                    .filter { sameNumber.matches(it.localId) }
                val hits = toMatches(cards, name)
                if (hits.isNotEmpty()) return hits to true
            }
        }
        for (name in names) {
            val hits = toMatches(TcgdexClient.searchCards(name = name), name)
            if (hits.isNotEmpty()) return hits to false
        }
        return emptyList<Match>() to false
    }

    /** TCGdex name search matches substrings — "Porygon" also finds Porygon2 and Porygon-Z. */
    private fun exactNamesFirst(matches: List<Match>, name: String): List<Match> =
        matches.sortedByDescending { it.name.equals(name, ignoreCase = true) }

    /**
     * Manual search typed by the user: a name, optionally followed by the
     * collector number ("Hitmonchan ex 98" or "Ninjask 38/97").
     */
    suspend fun search(query: String): List<Match> {
        val parts = MANUAL_QUERY.matchEntire(query.trim())
        val name = parts?.groupValues?.get(1) ?: query.trim()
        val typed = parts?.groupValues?.get(2)
        val matches = search(listOf(name), listOfNotNull(typed?.takeWhile { it.isDigit() })).first
        // Unlike OCR, a typed letter suffix ("103a") is reliable: narrow to it.
        if (typed == null || typed.none { it.isLetter() }) return matches
        val exact = matches.filter { it.localId.trimStart('0').equals(typed.trimStart('0'), ignoreCase = true) }
        return exact.ifEmpty { matches }
    }

    // A name, then the number: leading digits plus an optional letter suffix;
    // any "/total" after it is ignored.
    private val MANUAL_QUERY = Regex("""(.+?)\s+(\d{1,3}[a-zA-Z]?)(?![\da-zA-Z]).*""")

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
}
