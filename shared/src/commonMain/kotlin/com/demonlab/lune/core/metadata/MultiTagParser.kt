package com.demonlab.lune.core.metadata

import com.demonlab.lune.core.model.MultiTagConfig
import com.demonlab.lune.core.model.ParsedTrackTags

/**
 * Conflict-free, multi-pass Multi-Artist & Multi-Genre metadata engine for Flow.
 *
 * Pass 1: Shields whitelisted band/artist/genre names containing delimiters (e.g., "AC/DC",
 *         "Tyler, The Creator", "Earth, Wind & Fire", "Wisin & Yandel", "+44", "Chloe x Halle",
 *         "R&B", plus user-defined custom whitelist entries) using Unicode Private Use Area markers
 *         (\uE000..\uE001) immune to whitespace trimming and delimiter splitting.
 * Pass 2: Extracts featured/collaborating artists from track titles across multiple parenthesized,
 *         bracketed, or trailing clauses (e.g., "(ft. Daddy Yankee)", "[feat. Sia & Sean Paul]",
 *         "Song ft. Artist (Remix)", "- con Rosalía") while preserving edition tags like "(Remix)",
 *         "(Live)", "(Remastered)" and respecting the whitelist inside title features.
 * Pass 3: Splits native multi-value frames (\u0000) and configured separators (;, ,, /, \, |, &, +,
 *         feat., ft., featuring, con, with, vs., presents, x, ×) and deduplicates accent/case-insensitively
 *         so primary Artist tags and Title features never collide or duplicate.
 */
object MultiTagParser {

    val DEFAULT_PROTECTED_WHITELIST: Set<String> = setOf(
        // Classic & Modern Bands with '/', ',', '&', '+', 'x'
        "AC/DC",
        "Tyler, The Creator",
        "Earth, Wind & Fire",
        "Crosby, Stills & Nash",
        "Crosby, Stills, Nash & Young",
        "Simon & Garfunkel",
        "Hall & Oates",
        "Daryl Hall & John Oates",
        "Guns N' Roses",
        "Emerson, Lake & Palmer",
        "Blood, Sweat & Tears",
        "Kool & The Gang",
        "KC and the Sunshine Band",
        "Mumford & Sons",
        "Florence + the Machine",
        "Florence & The Machine",
        "Coheed and Cambria",
        "Echo & the Bunnymen",
        "Belle and Sebastian",
        "Belle & Sebastian",
        "Tom Petty and the Heartbreakers",
        "Prince and The Revolution",
        "Bob Marley & The Wailers",
        "Nick Cave & The Bad Seeds",
        "King Gizzard & The Lizard Wizard",
        "Huey Lewis & The News",
        "Martha and the Vandellas",
        "Gladys Knight & the Pips",
        "Sly & The Family Stone",
        "Derek and the Dominos",
        "Me First and the Gimme Gimmes",
        "Tegan and Sara",
        "Angus & Julia Stone",
        "She & Him",
        "Brooks & Dunn",
        "Dan + Shay",
        "Above & Beyond",
        "Chase & Status",
        "Calyx & TeeBee",
        "Camo & Krooked",
        "Aly & Fila",
        "Gabriel & Dresden",
        "Gorgon City",
        "Sofi Tukker",
        "Chloe x Halle",
        "Lil Nas X",
        "Malcolm X",
        "Symphony X",
        "Static-X",
        "Brand X",
        "Generation X",
        "X Japan",
        "+44",
        "Sum 41",
        "blink-182",
        "alt-J",
        // Latin / Spanish / Urban Duos & Groups
        "Wisin & Yandel",
        "Zion & Lennox",
        "Jowell & Randy",
        "Alexis & Fido",
        "Chino & Nacho",
        "Jesse & Joy",
        "Ha*Ash",
        "Mau y Ricky",
        "Cali y El Dandee",
        "Gente de Zona",
        "Servando & Florentino",
        "Andy & Lucas",
        "Estopa",
        "Pimpinela",
        "Sin Bandera",
        "Camila",
        "Khruangbin",
        "flight facilities",
        // Protected Compound Genres
        "R&B",
        "Contemporary R&B",
        "Alt R&B",
        "Rhythm & Blues",
        "Rock & Roll",
        "Drum & Bass",
        "Drum and Bass",
        "Country & Western",
        "Hip-Hop/Rap",
        "Hip Hop/Rap",
        "Singer/Songwriter",
        "Pop/Rock",
        "Folk/Rock",
        "Indie/Alternative",
        "J-Pop/Rock",
        "K-Pop"
    ).map { it.trim() }.toSet()

    val DEFAULT_PROTECTED_ENTRIES: Set<String>
        get() = DEFAULT_PROTECTED_WHITELIST

    // Non-artist edition/version keywords that appear in parentheses/brackets
    private val EDITION_KEYWORDS = setOf(
        "remix", "remaster", "remastered", "live", "acoustic", "instrumental",
        "radio edit", "extended", "extended mix", "original mix", "club mix", "dub mix",
        "vip", "demo", "version", "versión", "edit", "mono", "stereo", "deluxe",
        "bonus", "bonus track", "explicit", "clean", "slowed", "reverb", "sped up",
        "nightcore", "karaoke", "cover", "ost", "soundtrack", "session", "en vivo",
        "acústico", "acustico", "remasterizado", "directo", "unplugged", "mix",
        "intro", "outro", "interlude", "reprise", "432hz", "hi-res", "remake"
    )

    // Matches bracketed/parenthesized feat clauses:
    // (ft. Daddy Yankee), [feat. Bad Bunny & J Balvin], (con Rosalía), {with Sia}, (f/ Jay-Z)
    private val BRACKET_FEAT_REGEX = Regex(
        """[\(\[\{]\s*(?:featuring|feat\.?|ft\.?|f/|con|with|w/|pres\.?|presents|prod\.?\s+by)\s+([^\)\]\}]*)[\)\]\}]""",
        RegexOption.IGNORE_CASE
    )

    // Matches unbracketed trailing feat clauses:
    // "Song Name - ft. Daddy Yankee", "Song Name feat. Sia (Remix)", "Canción con Artista"
    private val TRAILING_FEAT_REGEX = Regex(
        """(?:\s+[-–—]\s+|\s+)(?:featuring|feat\.?|ft\.?|f/|presents|pres\.?|prod\.?\s+by)\s+(.+)$""",
        RegexOption.IGNORE_CASE
    )

    // Matches explicit hyphenated Spanish/English collaboration suffix: "Canción - con Rosalía" / "Track - with Bono"
    private val HYPHEN_WITH_FEAT_REGEX = Regex(
        """\s+[-–—]\s+(?:con|with)\s+(.+)$""",
        RegexOption.IGNORE_CASE
    )

    // Word-boundary collaboration connectors inside artist strings.
    // Note: 'x' / '×' only matches when followed by a name and NOT as single trailing initial like "Lil Nas X" at end-of-string.
    private val FEAT_CONNECTOR_REGEX = Regex(
        """\s+(?:featuring|feat\.?|ft\.?|f/|vs\.?|versus|presents|pres\.?|with|w/|con|×)\s+|\s+[xX]\s+(?=\S{2,})""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Full conflict-free parse for a track's title, artist, and genre strings.
     */
    fun parseTrackTags(
        rawTitle: String,
        rawArtist: String,
        rawGenre: String?,
        config: MultiTagConfig = MultiTagConfig()
    ): ParsedTrackTags {
        val effectiveWhitelist = buildSortedWhitelist(config.customWhitelist)

        // 1. Parse title for featured artists (if enabled)
        var cleanedTitle = rawTitle.trim()
        val titleExtractedArtists = ArrayList<String>(4)

        if (config.enableMultiArtist && config.extractArtistsFromTitle && cleanedTitle.isNotEmpty()) {
            // Pass A: Bracketed (feat. X) / [ft. Y] / (con Z) — supports multiple brackets in a single title
            val bracketMatches = BRACKET_FEAT_REGEX.findAll(cleanedTitle).toList()
            if (bracketMatches.isNotEmpty()) {
                var workingTitle = cleanedTitle
                for (match in bracketMatches) {
                    val candidatePayload = match.groupValues.getOrNull(1)?.trim().orEmpty()
                    if (candidatePayload.isNotEmpty() && !isPureEditionTag(candidatePayload)) {
                        val splitFromTitle = splitValueWithShield(
                            raw = candidatePayload,
                            whitelist = effectiveWhitelist,
                            splitSemicolon = true,
                            splitComma = config.splitComma,
                            splitSlash = config.splitSlash,
                            splitAmpersand = true,
                            splitFeatKeywords = true
                        )
                        var addedAny = false
                        for (extracted in splitFromTitle) {
                            val sanitized = stripTrailingEditionFromArtist(extracted)
                            if (sanitized.isNotEmpty() && !isPureEditionTag(sanitized)) {
                                titleExtractedArtists.add(sanitized)
                                addedAny = true
                            }
                        }
                        if (addedAny && config.cleanExtractedFeatFromTitle) {
                            workingTitle = workingTitle.replaceFirst(match.value, "")
                        }
                    }
                }
                if (config.cleanExtractedFeatFromTitle) {
                    cleanedTitle = workingTitle.replace(Regex("\\s{2,}"), " ").trim()
                }
            }

            // Pass B: Unbracketed trailing "Song ft. Artist (Remix)" or "Song - con Artist"
            val trailingMatch = TRAILING_FEAT_REGEX.find(cleanedTitle) ?: HYPHEN_WITH_FEAT_REGEX.find(cleanedTitle)
            if (trailingMatch != null) {
                val candidatePayload = trailingMatch.groupValues.getOrNull(1)?.trim().orEmpty()
                if (candidatePayload.isNotEmpty() && !isPureEditionTag(candidatePayload)) {
                    // Separate trailing edition brackets like "(Remix)" or "[Remastered]" from the featured artist list
                    val (artistSegment, preservedEditionSuffix) = splitTrailingEditionBrackets(candidatePayload)
                    if (artistSegment.isNotEmpty() && !isPureEditionTag(artistSegment)) {
                        val splitTrailing = splitValueWithShield(
                            raw = artistSegment,
                            whitelist = effectiveWhitelist,
                            splitSemicolon = true,
                            splitComma = config.splitComma,
                            splitSlash = config.splitSlash,
                            splitAmpersand = true,
                            splitFeatKeywords = true
                        )
                        var addedAny = false
                        for (extracted in splitTrailing) {
                            val sanitized = stripTrailingEditionFromArtist(extracted)
                            if (sanitized.isNotEmpty() && !isPureEditionTag(sanitized)) {
                                titleExtractedArtists.add(sanitized)
                                addedAny = true
                            }
                        }
                        if (addedAny && config.cleanExtractedFeatFromTitle) {
                            val prefix = cleanedTitle.substring(0, trailingMatch.range.first).trim()
                                .trimEnd('-', '–', '—', ' ')
                            cleanedTitle = if (preservedEditionSuffix.isNotEmpty()) {
                                "$prefix $preservedEditionSuffix".replace(Regex("\\s{2,}"), " ").trim()
                            } else {
                                prefix
                            }
                        }
                    }
                }
            }
        }

        // 2. Parse primary & collaborating artists from rawArtist
        val baseArtists = if (!config.enableMultiArtist) {
            val single = rawArtist.replace('\u0000', ';').trim()
            if (single.isNotEmpty()) listOf(single) else listOf("Unknown Artist")
        } else {
            splitValueWithShield(
                raw = rawArtist,
                whitelist = effectiveWhitelist,
                splitSemicolon = config.splitSemicolon,
                splitComma = config.splitComma,
                splitSlash = config.splitSlash,
                splitAmpersand = config.splitAmpersand,
                splitFeatKeywords = config.splitFeatKeywords
            )
        }

        // 3. Merge & deduplicate accent- and case-insensitively without losing original display casing
        val mergedArtists = LinkedHashMap<String, String>(baseArtists.size + titleExtractedArtists.size + 1)
        for (a in baseArtists) {
            val trimmed = a.trim()
            if (trimmed.isNotEmpty() && !isUnknownPlaceholder(trimmed)) {
                val normKey = normalizeComparisonKey(trimmed)
                if (normKey.isNotEmpty() && !mergedArtists.containsKey(normKey)) {
                    mergedArtists[normKey] = trimmed
                }
            }
        }
        for (a in titleExtractedArtists) {
            val trimmed = a.trim()
            if (trimmed.isNotEmpty() && !isUnknownPlaceholder(trimmed)) {
                val normKey = normalizeComparisonKey(trimmed)
                if (normKey.isNotEmpty() && !mergedArtists.containsKey(normKey)) {
                    mergedArtists[normKey] = trimmed
                }
            }
        }

        val finalArtists = if (mergedArtists.isEmpty()) {
            val fallback = rawArtist.trim()
            if (fallback.isEmpty() || isUnknownPlaceholder(fallback)) listOf("Unknown Artist") else listOf(fallback)
        } else {
            mergedArtists.values.toList()
        }

        // 4. Parse multi-genres
        val finalGenres = parseGenresOnly(rawGenre, config, effectiveWhitelist)

        return ParsedTrackTags(
            artists = finalArtists,
            genres = finalGenres,
            cleanTitle = cleanedTitle.ifBlank { rawTitle },
            primaryArtist = finalArtists.first()
        )
    }

    fun parseGenresOnly(
        rawGenre: String?,
        config: MultiTagConfig = MultiTagConfig(),
        prebuiltWhitelist: List<String>? = null
    ): List<String> {
        val g = rawGenre?.trim()
        if (g.isNullOrEmpty() || isUnknownPlaceholder(g)) {
            return emptyList()
        }
        if (!config.enableMultiGenre) {
            return listOf(g.replace('\u0000', ';'))
        }
        val whitelist = prebuiltWhitelist ?: buildSortedWhitelist(config.customWhitelist)
        val rawTokens = splitValueWithShield(
            raw = g,
            whitelist = whitelist,
            splitSemicolon = config.splitSemicolon,
            splitComma = config.splitComma,
            splitSlash = config.splitSlash,
            splitAmpersand = false, // Keep compound genres like "R&B", "Drum & Bass" intact
            splitFeatKeywords = false
        )
        val dedup = LinkedHashMap<String, String>(rawTokens.size)
        for (token in rawTokens) {
            val cleaned = token.trim()
            if (cleaned.isNotEmpty() && !isUnknownPlaceholder(cleaned)) {
                val key = normalizeComparisonKey(cleaned)
                if (key.isNotEmpty() && !dedup.containsKey(key)) {
                    dedup[key] = cleaned
                }
            }
        }
        return dedup.values.toList()
    }

    private fun buildSortedWhitelist(custom: Set<String>): List<String> {
        val combined = LinkedHashMap<String, String>(DEFAULT_PROTECTED_WHITELIST.size + custom.size)
        // Custom entries take display-casing precedence over defaults
        for (item in custom) {
            val t = item.trim()
            if (t.isNotEmpty()) combined[t.lowercase()] = t
        }
        for (item in DEFAULT_PROTECTED_WHITELIST) {
            val key = item.lowercase()
            if (!combined.containsKey(key)) combined[key] = item
        }
        // Longest entries first so "Crosby, Stills, Nash & Young" shields before "Crosby, Stills & Nash"
        return combined.values.sortedByDescending { it.length }
    }

    private fun splitValueWithShield(
        raw: String,
        whitelist: List<String>,
        splitSemicolon: Boolean,
        splitComma: Boolean,
        splitSlash: Boolean,
        splitAmpersand: Boolean,
        splitFeatKeywords: Boolean
    ): List<String> {
        if (raw.isBlank()) return emptyList()

        // Native multi-value ID3v2.4 / Vorbis null byte separator always splits first
        var working = raw.replace('\u0000', '\u001F')

        // Shield protected whitelist names case-insensitively using Unicode Private Use Area markers
        val shieldMap = ArrayList<String>(4)
        for (protectedName in whitelist) {
            if (protectedName.length < 2) continue
            val idx = working.indexOf(protectedName, ignoreCase = true)
            if (idx >= 0) {
                val startsAlnum = protectedName.first().isLetterOrDigit()
                val endsAlnum = protectedName.last().isLetterOrDigit()
                val prefixGuard = if (startsAlnum) """(?<![\p{L}\p{N}])""" else ""
                val suffixGuard = if (endsAlnum) """(?![\p{L}\p{N}])""" else ""
                val regex = Regex(prefixGuard + Regex.escape(protectedName) + suffixGuard, RegexOption.IGNORE_CASE)
                if (regex.containsMatchIn(working)) {
                    val tokenIndex = shieldMap.size
                    shieldMap.add(protectedName)
                    working = regex.replace(working, "\uE000$tokenIndex\uE001")
                }
            }
        }

        // Unwrap bracketed feat clauses inside the artist field: "Artist A (ft. Artist B)" -> "Artist A \u001F Artist B"
        if (splitFeatKeywords) {
            working = BRACKET_FEAT_REGEX.replace(working) { m ->
                " \u001F ${m.groupValues[1]} "
            }
            working = FEAT_CONNECTOR_REGEX.replace(working, "\u001F")
        }

        if (splitSemicolon) {
            working = working
                .replace(';', '\u001F')
                .replace('؛', '\u001F')
                .replace('；', '\u001F')
        }
        if (splitComma) {
            working = working
                .replace(',', '\u001F')
                .replace('،', '\u001F')
                .replace('、', '\u001F')
        }
        if (splitSlash) {
            working = working
                .replace('/', '\u001F')
                .replace('\\', '\u001F')
                .replace('|', '\u001F')
                .replace('•', '\u001F')
                .replace('·', '\u001F')
        }
        if (splitAmpersand) {
            working = working
                .replace(" & ", "\u001F")
                .replace("&", "\u001F")
                .replace(" + ", "\u001F")
                .replace(" ＋ ", "\u001F")
        }

        val rawParts = working.split('\u001F')
        val result = ArrayList<String>(rawParts.size)
        for (part in rawParts) {
            var restored = part
            var wasShieldedExact = false
            for (i in shieldMap.indices) {
                val marker = "\uE000$i\uE001"
                if (restored.contains(marker)) {
                    if (restored.trim() == marker) wasShieldedExact = true
                    restored = restored.replace(marker, shieldMap[i])
                }
            }
            restored = if (wasShieldedExact) {
                restored.trim()
            } else {
                restored.trim().trim(' ', '-', '–', '—', '(', ')', '[', ']', '{', '}')
            }
            if (restored.isNotEmpty()) {
                result.add(restored)
            }
        }
        return result
    }

    private fun splitTrailingEditionBrackets(payload: String): Pair<String, String> {
        val openParen = payload.indexOf('(')
        val openBracket = payload.indexOf('[')
        val firstOpen = when {
            openParen >= 0 && openBracket >= 0 -> minOf(openParen, openBracket)
            openParen >= 0 -> openParen
            openBracket >= 0 -> openBracket
            else -> -1
        }
        if (firstOpen < 0) return payload.trim() to ""
        val artistPart = payload.substring(0, firstOpen).trim()
        val bracketPart = payload.substring(firstOpen).trim()
        return artistPart to bracketPart
    }

    private fun isUnknownPlaceholder(value: String): Boolean {
        val lower = value.trim().lowercase()
        return lower == "<unknown>" || lower == "unknown" || lower == "unknown artist" || lower == "desconocido"
    }

    private fun isPureEditionTag(candidate: String): Boolean {
        val lower = candidate.lowercase().trim().trim('(', ')', '[', ']', '{', '}')
        if (lower.isEmpty()) return true
        return EDITION_KEYWORDS.any { kw ->
            lower == kw || (lower.endsWith(" $kw") && lower.length <= kw.length + 10)
        }
    }

    private fun stripTrailingEditionFromArtist(artist: String): String {
        return artist
            .substringBefore("(")
            .substringBefore("[")
            .replace(
                Regex("\\s+[-–—]\\s+(?:remix|live|remaster(?:ed)?|acoustic|radio edit|version|versión|edit).*$", RegexOption.IGNORE_CASE),
                ""
            )
            .trim()
    }

    private fun normalizeComparisonKey(name: String): String {
        val sb = StringBuilder(name.length)
        var prevSpace = true
        for (ch in name.trim().lowercase()) {
            val folded = foldDiacritic(ch)
            if (folded.isWhitespace()) {
                if (!prevSpace) {
                    sb.append(' ')
                    prevSpace = true
                }
            } else {
                sb.append(folded)
                prevSpace = false
            }
        }
        return sb.toString().trim()
    }

    private fun foldDiacritic(c: Char): Char = when (c) {
        'á', 'à', 'ä', 'â', 'ã', 'å' -> 'a'
        'é', 'è', 'ë', 'ê' -> 'e'
        'í', 'ì', 'ï', 'î' -> 'i'
        'ó', 'ò', 'ö', 'ô', 'õ' -> 'o'
        'ú', 'ù', 'ü', 'û' -> 'u'
        'ñ' -> 'n'
        'ç' -> 'c'
        else -> c
    }
}
