package com.demonlab.lune.core.metadata

import com.demonlab.lune.core.model.MultiTagConfig
import com.demonlab.lune.core.model.ParsedTrackTags

/**
 * Conflict-free, multi-pass Multi-Artist & Multi-Genre parser for Lune.
 *
 * Pass 1: Protects whitelisted band/genre names containing delimiters (e.g. "AC/DC", "Tyler, The Creator",
 *         "Earth, Wind & Fire", "Guns N' Roses", "R&B", plus user-added custom whitelist entries).
 * Pass 2: Extracts featured/collaborating artists from track titles (e.g. "(ft. Daddy Yankee)", "[feat. Sia & Sean Paul]",
 *         "- con Rosalía") while ignoring edition suffixes like "(Remix)", "(Live)", "(Remastered)".
 * Pass 3: Splits native multi-value frames (\u0000) and configured separators (;, ,, /, |, &, feat., ft., vs., x, etc.)
 *         and deduplicates case-insensitively so Artist tags and Title features never conflict or duplicate.
 */
object MultiTagParser {

    val DEFAULT_PROTECTED_WHITELIST: Set<String> = setOf(
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
        " Penn & Teller",
        "Above & Beyond",
        "Chase & Status",
        "Calyx & TeeBee",
        "Camo & Krooked",
        "Sigma & Rita",
        "Zeds Dead",
        "R&B",
        "Rhythm & Blues",
        "Rock & Roll",
        "Drum & Bass",
        "Drum and Bass",
        "Country & Western",
        "Hip-Hop/Rap",
        "Singer/Songwriter",
        "Pop/Rock"
    ).map { it.trim() }.toSet()

    val DEFAULT_PROTECTED_ENTRIES: Set<String>
        get() = DEFAULT_PROTECTED_WHITELIST

    // Non-artist edition/version keywords that can appear in parentheses/brackets
    private val EDITION_KEYWORDS = setOf(
        "remix", "remaster", "remastered", "live", "acoustic", "instrumental",
        "radio edit", "extended", "original mix", "club mix", "vip", "demo",
        "version", "versión", "edit", "mono", "stereo", "deluxe", "bonus",
        "explicit", "clean", "slowed", "reverb", "sped up", "karaoke", "cover",
        "ost", "soundtrack", "session", "en vivo", "acústico", "acustico"
    )

    // Matches bracketed/parenthesized feat clauses: (ft. Daddy Yankee), [feat. Bad Bunny & J Balvin], (con Rosalía)
    private val BRACKET_FEAT_REGEX = Regex(
        """[\(\[\{]\s*(?:featuring|feat\.?|ft\.?|con|with|pres\.?|presents)\s+([^\)\]\}]*)[\)\]\}]""",
        RegexOption.IGNORE_CASE
    )

    // Matches unbracketed trailing feat clauses: "Song Name - ft. Daddy Yankee" or "Song Name feat. Sia"
    private val TRAILING_FEAT_REGEX = Regex(
        """(?:\s+[-–—]\s+|\s+)(?:featuring|feat\.?|ft\.?|presents|pres\.?)\s+(.+)$""",
        RegexOption.IGNORE_CASE
    )

    // Word-boundary collaboration connectors inside artist strings
    private val FEAT_CONNECTOR_REGEX = Regex(
        """\s+(?:featuring|feat\.?|ft\.?|vs\.?|versus|presents|pres\.?|with|con|x|×)\s+""",
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
            // Check bracketed (feat. X) / [ft. Y]
            val bracketMatches = BRACKET_FEAT_REGEX.findAll(cleanedTitle).toList()
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
                    for (extracted in splitFromTitle) {
                        val sanitized = stripTrailingEditionFromArtist(extracted)
                        if (sanitized.isNotEmpty() && !isPureEditionTag(sanitized)) {
                            titleExtractedArtists.add(sanitized)
                        }
                    }
                    if (config.cleanExtractedFeatFromTitle) {
                        cleanedTitle = cleanedTitle.replace(match.value, "").replace(Regex("\\s{2,}"), " ").trim()
                    }
                }
            }

            // Check unbracketed trailing "Song ft. Artist"
            val trailingMatch = TRAILING_FEAT_REGEX.find(cleanedTitle)
            if (trailingMatch != null) {
                val candidatePayload = trailingMatch.groupValues.getOrNull(1)?.trim().orEmpty()
                if (candidatePayload.isNotEmpty() && !isPureEditionTag(candidatePayload)) {
                    // Stop before any trailing [Remix] or (Live) bracket
                    val beforeEditionBracket = candidatePayload.substringBefore("(").substringBefore("[").trim()
                    val remainderBracket = candidatePayload.substring(beforeEditionBracket.length).trim()
                    if (beforeEditionBracket.isNotEmpty() && !isPureEditionTag(beforeEditionBracket)) {
                        val splitTrailing = splitValueWithShield(
                            raw = beforeEditionBracket,
                            whitelist = effectiveWhitelist,
                            splitSemicolon = true,
                            splitComma = config.splitComma,
                            splitSlash = config.splitSlash,
                            splitAmpersand = true,
                            splitFeatKeywords = true
                        )
                        for (extracted in splitTrailing) {
                            val sanitized = stripTrailingEditionFromArtist(extracted)
                            if (sanitized.isNotEmpty() && !isPureEditionTag(sanitized)) {
                                titleExtractedArtists.add(sanitized)
                            }
                        }
                        if (config.cleanExtractedFeatFromTitle) {
                            val prefix = cleanedTitle.substring(0, trailingMatch.range.first).trim()
                            cleanedTitle = if (remainderBracket.isNotEmpty()) "$prefix $remainderBracket" else prefix
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

        // 3. Merge & deduplicate case-insensitively while preserving display casing and order
        val mergedArtists = LinkedHashMap<String, String>(baseArtists.size + titleExtractedArtists.size + 1)
        for (a in baseArtists) {
            val trimmed = a.trim()
            if (trimmed.isNotEmpty() && !trimmed.equals("<unknown>", ignoreCase = true)) {
                val normKey = normalizeArtistKey(trimmed)
                if (!mergedArtists.containsKey(normKey)) {
                    mergedArtists[normKey] = trimmed
                }
            }
        }
        for (a in titleExtractedArtists) {
            val trimmed = a.trim()
            if (trimmed.isNotEmpty() && !trimmed.equals("<unknown>", ignoreCase = true)) {
                val normKey = normalizeArtistKey(trimmed)
                // Avoid conflicts: also check that no existing artist already contains or equals this token cleanly
                if (!mergedArtists.containsKey(normKey)) {
                    mergedArtists[normKey] = trimmed
                }
            }
        }

        val finalArtists = if (mergedArtists.isEmpty()) {
            listOf(rawArtist.trim().ifEmpty { "Unknown Artist" })
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
        if (g.isNullOrEmpty() || g.equals("<unknown>", ignoreCase = true) || g.equals("unknown", ignoreCase = true)) {
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
            splitAmpersand = false, // Keep genres like "R&B", "Drum & Bass" intact even if custom
            splitFeatKeywords = false
        )
        val dedup = LinkedHashMap<String, String>(rawTokens.size)
        for (token in rawTokens) {
            val cleaned = token.trim()
            if (cleaned.isNotEmpty() && !cleaned.equals("unknown", ignoreCase = true) && !cleaned.equals("<unknown>", ignoreCase = true)) {
                val key = cleaned.lowercase()
                if (!dedup.containsKey(key)) {
                    dedup[key] = cleaned
                }
            }
        }
        return dedup.values.toList()
    }

    private fun buildSortedWhitelist(custom: Set<String>): List<String> {
        val combined = LinkedHashSet<String>(DEFAULT_PROTECTED_WHITELIST.size + custom.size)
        for (item in custom) {
            val t = item.trim()
            if (t.isNotEmpty()) combined.add(t)
        }
        combined.addAll(DEFAULT_PROTECTED_WHITELIST)
        // Sort longest first so "Crosby, Stills, Nash & Young" shields before "Crosby, Stills & Nash"
        return combined.sortedByDescending { it.length }
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

        // Native multi-value ID3v2.4 / Vorbis null byte separator always splits first into chunks
        var working = raw.replace('\u0000', '\u001F')

        // Shield protected whitelist names case-insensitively
        val shieldMap = ArrayList<String>(4)
        for (protectedName in whitelist) {
            if (protectedName.length < 2) continue
            val idx = working.indexOf(protectedName, ignoreCase = true)
            if (idx >= 0) {
                val regex = Regex(
                    """(?<![\p{L}\p{N}])""" + Regex.escape(protectedName) + """(?![\p{L}\p{N}])""",
                    RegexOption.IGNORE_CASE
                )
                if (regex.containsMatchIn(working)) {
                    val tokenIndex = shieldMap.size
                    // Preserve original matched casing or canonical whitelist casing
                    shieldMap.add(protectedName)
                    working = regex.replace(working, "\uE000$tokenIndex\uE001")
                }
            }
        }

        // Strip enclosing brackets on feat clauses inside the artist field itself: "Artist A (ft. Artist B)" -> "Artist A \u001F Artist B"
        if (splitFeatKeywords) {
            working = BRACKET_FEAT_REGEX.replace(working) { m ->
                " \u001F ${m.groupValues[1]} "
            }
            working = FEAT_CONNECTOR_REGEX.replace(working, "\u001F")
        }

        if (splitSemicolon) {
            working = working.replace(';', '\u001F').replace('؛', '\u001F')
        }
        if (splitComma) {
            working = working.replace(',', '\u001F')
        }
        if (splitSlash) {
            working = working.replace('/', '\u001F').replace('\\', '\u001F').replace('|', '\u001F').replace('•', '\u001F')
        }
        if (splitAmpersand) {
            working = working.replace(" & ", "\u001F").replace("&", "\u001F")
        }

        val rawParts = working.split('\u001F')
        val result = ArrayList<String>(rawParts.size)
        for (part in rawParts) {
            var restored = part
            for (i in shieldMap.indices) {
                val marker = "\uE000$i\uE001"
                if (restored.contains(marker)) {
                    restored = restored.replace(marker, shieldMap[i])
                }
            }
            restored = restored.trim().trim(' ', '-', '–', '—', '(', ')', '[', ']')
            if (restored.isNotEmpty()) {
                result.add(restored)
            }
        }
        return result
    }

    private fun isPureEditionTag(candidate: String): Boolean {
        val lower = candidate.lowercase().trim()
        if (lower.isEmpty()) return true
        return EDITION_KEYWORDS.any { kw -> lower == kw || lower.endsWith(" $kw") && lower.length <= kw.length + 6 }
    }

    private fun stripTrailingEditionFromArtist(artist: String): String {
        return artist
            .substringBefore("(")
            .substringBefore("[")
            .replace(Regex("\\s+-\\s+(?:remix|live|remastered|acoustic|radio edit).*$", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun normalizeArtistKey(name: String): String {
        return name.trim().lowercase().replace(Regex("\\s+"), " ")
    }
}
