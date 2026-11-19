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
        // 103a/103b/103): the full printed number, even misread, can settle it —
        // unless a reprint of the same card (same name and number, its set's
        // total the same or unknown) prints the original's number too (the 2026
        // Classic Collection Pikachu reads "58/102" like Base Set's, and its set
        // lists no total): then the user or the artwork decides. Kabutops 6/75
        // and 6/100 are different cards, told apart by the total.
        return closestPrintedNumber(matches, reading.bottomLines)
            ?.takeIf { best ->
                matches.none {
                    it.id != best.id && isReprintOf(it, best) && (
                        CLASSIC_COLLECTION.containsMatchIn(it.setName) ||
                            it.setOfficial == 0 || it.setOfficial == best.setOfficial
                        )
                }
            }
            ?.let { best -> Result(listOf(best) + (matches - best), confident = true) }
            ?: Result(matches, confident = false)
    }

    /**
     * Possibly the same card in another set: the same name and collector number,
     * or the same name with either in a "Classic Collection" set — those reprint
     * old cards with the original's printed number ("58/102") but list them by
     * their place in the collection (TCGdex has the 2026 Pikachu as #008).
     */
    fun isReprintOf(a: Match, b: Match): Boolean {
        fun letters(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        if (letters(a.name) != letters(b.name)) return false
        return a.localId.trimStart('0') == b.localId.trimStart('0') ||
            CLASSIC_COLLECTION.containsMatchIn(a.setName) || CLASSIC_COLLECTION.containsMatchIn(b.setName)
    }

    private val CLASSIC_COLLECTION = Regex("""classic\s+collection""", RegexOption.IGNORE_CASE)

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
     * Name + number first, then name alone (number misread, or none printed, as
     * on many promos), then number alone with a misread name. Both databases are
     * asked — TCGdex and the Pokémon TCG API ([PokemonTcgClient]) — and their
     * printings merged: each lists some the other doesn't (the 2026 Classic
     * Collection reprints are only in the latter), and either can be down or
     * rate-limiting. Number filters match substrings ("25" also finds 225), so
     * hits are narrowed to the same digits, zero-padded or with a letter suffix:
     * "103" keeps 103a/103b, "17" keeps 017. Also returns whether a number
     * settled it. Throws only if neither database answers.
     */
    private suspend fun search(readNames: List<String>, numbers: List<String>): Pair<List<Match>, Boolean> {
        // Each name as read, then its core: OCR garbles the prefixes and suffixes
        // printed in other fonts ("MEGA Gallade X" for M Gallade EX), and the
        // search matches substrings, so "Gallade" still finds the card.
        val names = readNames.flatMap { listOfNotNull(it, coreName(it)) }.distinct()
        val sources = Sources()

        // Every candidate number for the first name that finds any: the bottom band
        // has stray digits too ("2" found Gallade POP 7 #2 before "100" found M
        // Gallade EX), so if different numbers find different cards, it's open.
        for (name in names) {
            val byNumber = numbers.map { number ->
                sources.find(name = name, number = number) { it.name.contains(name, ignoreCase = true) }
            }.filter { it.isNotEmpty() }
            if (byNumber.isEmpty()) continue
            return exactNamesFirst(merged(byNumber.flatten()), name).take(MAX_MATCHES) to (byNumber.size == 1)
        }
        for (name in names) {
            val hits = sources.find(name = name, number = null) { it.name.contains(name, ignoreCase = true) }
            if (hits.isNotEmpty()) return exactNamesFirst(hits, name).take(MAX_MATCHES) to false
        }
        // The name misread ("M Galade X" for M Gallade EX, "Team Rocket Moltres"
        // for Team Rocket's Moltres ex), so no search by it found anything: look
        // the number up alone and keep the cards whose name is close to what was read.
        for (number in numbers) {
            if (number.trimStart('0').length < MIN_FUZZY_NUMBER_DIGITS) continue
            val close = sources.find(name = null, number = number) { card -> names.any { nameIsClose(it, card.name) } }
            if (close.isNotEmpty()) return close.take(MAX_MATCHES) to true
        }
        sources.failure?.let { throw it }
        return emptyList<Match>() to false
    }

    /**
     * Searches both databases, keeping the cards [keep] accepts, merged. A
     * database that fails is skipped; [failure] is set only once both have.
     */
    private class Sources {
        private var tcgdexError: Exception? = null
        private var pokemonTcgError: Exception? = null
        private var tcgdexSets: Map<String, TcgdexClient.SetBrief>? = null
        val failure: Exception? get() = if (tcgdexError != null && pokemonTcgError != null) tcgdexError else null

        suspend fun find(name: String?, number: String?, keep: (Match) -> Boolean): List<Match> {
            val bare = number?.trimStart('0')?.ifEmpty { "0" }
            val sameNumber = bare?.let { Regex("""0*$it(\D.*)?""") }
            fun numberMatches(match: Match) = sameNumber == null || sameNumber.matches(match.localId)

            val fromTcgdex = try {
                val sets = tcgdexSets ?: TcgdexClient.sets().associateBy { it.id }.also { tcgdexSets = it }
                TcgdexClient.searchCards(name = name, localId = bare).mapNotNull { card ->
                    val set = sets[card.setId] ?: return@mapNotNull null
                    Match(card.id, card.name, card.localId, set.id, set.name, set.official, card.imageBase)
                }
            } catch (exc: Exception) {
                tcgdexError = exc
                emptyList()
            }
            val fromPokemonTcg = try {
                PokemonTcgClient.searchCards(nameWord = name?.let(::searchWord), number = bare).map { card ->
                    Match(card.id, card.name, card.number, card.setId, card.setName, card.printedTotal, card.imageBase)
                }
            } catch (exc: Exception) {
                pokemonTcgError = exc
                emptyList()
            }
            return merged((fromTcgdex + fromPokemonTcg).filter { numberMatches(it) && keep(it) })
        }

        /** The longest word of [name]: the Pokémon TCG API's query matches single words. */
        private fun searchWord(name: String): String? =
            name.split(Regex("""[^\p{L}\d]+""")).maxByOrNull { it.length }?.takeIf { it.length >= 3 }
    }

    /**
     * One entry per printing: TCGdex's first, then the Pokémon TCG API's that
     * aren't the same card — same name and number, and the same set by its
     * printed total or name (the two databases name and number sets differently).
     * A printing TCGdex has no image for takes the other's.
     */
    private fun merged(matches: List<Match>): List<Match> {
        fun letters(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        fun same(a: Match, b: Match) = letters(a.name) == letters(b.name) &&
            a.localId.trimStart('0') == b.localId.trimStart('0') &&
            ((a.setOfficial > 0 && a.setOfficial == b.setOfficial) || letters(a.setName) == letters(b.setName))
        val result = mutableListOf<Match>()
        for (match in matches.distinctBy { it.id }) {
            val i = result.indexOfFirst { same(it, match) }
            if (i < 0) result += match
            else if (result[i].imageBase == null && match.imageBase != null) result[i] = result[i].copy(imageBase = match.imageBase)
        }
        return result
    }

    // A number this short matches too many cards to search on alone.
    private const val MIN_FUZZY_NUMBER_DIGITS = 2

    /**
     * Whether [read] is a misreading of [actual]: compared as letters only, at
     * most a quarter of them different (and at least 2 allowed), counting the
     * suffixes OCR tends to drop or garble ("ex" → "x").
     */
    private fun nameIsClose(read: String, actual: String): Boolean {
        fun letters(s: String) = (coreName(s) ?: s).lowercase().filter { it.isLetter() }
        val a = letters(read)
        val b = letters(actual)
        if (a.length < 3) return false
        return editDistance(a, b) <= maxOf(2, b.length / 4)
    }

    // Prefixes and suffixes around a Pokémon's name, in a different font from it.
    private val NAME_AFFIXES = setOf("mega", "m", "ex", "x", "gx", "v", "vmax", "vstar", "break", "lv", "prime", "star")

    /** [name] without [NAME_AFFIXES] ("MEGA Gallade X" → "Gallade"); null if that changes nothing or leaves too little. */
    private fun coreName(name: String): String? {
        val words = name.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val core = words.filter { it.lowercase().trim('.', '-') !in NAME_AFFIXES }.joinToString(" ")
        return core.takeIf { it != name && core.count { c -> c.isLetter() } >= 3 }
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
