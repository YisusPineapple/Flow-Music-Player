package com.demonlab.lune.core.search

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.min

/**
 * Ultra-fast, zero-dependency phonetic & fuzzy search matcher shared across Android, Windows, and Linux.
 * Supports diacritic-insensitive substring matching, bilingual (EN/ES) phonetic equivalence
 * (e.g. "beetles" <-> "beatles", "baca" <-> "vaca", "xilofono" <-> "silofono"),
 * and bounded edit-distance typo tolerance.
 */
object PhoneticSearchEngine {

    private val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val WORD_SPLIT_REGEX = Regex("[^\\p{L}\\p{N}]+")

    fun normalize(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        return DIACRITICS_REGEX.replace(decomposed, "").lowercase().trim()
    }

    /**
     * Generates a compact phonetic code for a single normalized token so homophones and vowel-variant
     * artist/song names (e.g., "beatles" vs "beetles", "phish" vs "fish", "korn" vs "corn") collide cleanly.
     */
    fun phoneticToken(normalizedWord: String): String {
        if (normalizedWord.isEmpty()) return ""
        val sb = StringBuilder(normalizedWord.length)
        var i = 0
        val len = normalizedWord.length
        var prevChar = '\u0000'

        while (i < len) {
            val c = normalizedWord[i]
            val next = if (i + 1 < len) normalizedWord[i + 1] else '\u0000'

            // Multi-char phonetic clusters (EN/ES)
            val mapped: Char? = when {
                c == 'p' && next == 'h' -> {
                    i++
                    'f'
                }
                c == 'l' && next == 'l' -> {
                    i++
                    'y'
                }
                c == 'q' && next == 'u' -> {
                    i++
                    'k'
                }
                c == 'c' && (next == 'e' || next == 'i' || next == 'y') -> 's'
                c == 'c' && next == 'h' -> {
                    i++
                    'x'
                }
                c == 'g' && (next == 'e' || next == 'i' || next == 'y') -> 'j'
                c == 'e' && (next == 'a' || next == 'e') -> {
                    i++
                    'i'
                }
                c == 'i' && next == 'e' -> {
                    i++
                    'i'
                }
                c == 'o' && (next == 'o' || next == 'u') -> {
                    i++
                    'u'
                }
                c == 'h' -> null // silent H in ES and weak in EN matching
                c == 'v' -> 'b'
                c == 'z' -> 's'
                c == 'c' || c == 'q' -> 'k'
                c == 'y' -> if (i == len - 1) 'i' else 'y'
                c == 'w' -> 'u'
                else -> c
            }

            if (mapped != null) {
                val collapsed = when (mapped) {
                    'e' -> if (i > 0) 'i' else 'e'
                    'o' -> if (i > 0) 'u' else 'o'
                    else -> mapped
                }
                if (collapsed != prevChar) {
                    sb.append(collapsed)
                    prevChar = collapsed
                }
            }
            i++
        }
        return sb.toString()
    }

    /**
     * Checks whether [candidate] matches [rawQuery] using a 3-tier cascade:
     * 1. Fast normalized substring match (O(N), zero extra allocations on hit)
     * 2. Phonetic token prefix/equality match (catches "beetles" -> "beatles")
     * 3. Bounded Levenshtein distance <= 1 (for 4-6 char tokens) or <= 2 (for 7+ char tokens)
     */
    fun matches(candidate: String?, rawQuery: String): Boolean {
        if (candidate.isNullOrBlank()) return false
        val normQuery = normalize(rawQuery)
        if (normQuery.isEmpty()) return true

        val normCandidate = normalize(candidate)
        if (normCandidate.contains(normQuery)) return true

        // Only run phonetic / fuzzy fallback when query has at least 3 characters
        if (normQuery.length < 3) return false

        val queryTokens = normQuery.split(WORD_SPLIT_REGEX).filter { it.isNotEmpty() }
        if (queryTokens.isEmpty()) return false
        val candidateTokens = normCandidate.split(WORD_SPLIT_REGEX).filter { it.isNotEmpty() }
        if (candidateTokens.isEmpty()) return false

        val candidatePhonetics = Array(candidateTokens.size) { idx -> phoneticToken(candidateTokens[idx]) }

        return queryTokens.all { qToken ->
            if (normCandidate.contains(qToken)) return@all true
            if (qToken.length < 3) return@all false

            val qPhonetic = phoneticToken(qToken)
            val maxDist = when {
                qToken.length >= 7 -> 2
                qToken.length >= 4 -> 1
                else -> 0
            }

            for (idx in candidateTokens.indices) {
                val cToken = candidateTokens[idx]
                val cPhonetic = candidatePhonetics[idx]
                if (qPhonetic.length >= 3 && (cPhonetic.startsWith(qPhonetic) || cPhonetic == qPhonetic)) {
                    return@all true
                }
                if (maxDist > 0 && abs(cToken.length - qToken.length) <= maxDist) {
                    if (isWithinEditDistance(cToken, qToken, maxDist)) {
                        return@all true
                    }
                }
                // Prefix typo check for partial typing of longer words (e.g. "beetl" -> "beatles")
                if (maxDist > 0 && cToken.length > qToken.length && qToken.length >= 4) {
                    val cPrefix = cToken.substring(0, qToken.length)
                    if (isWithinEditDistance(cPrefix, qToken, 1)) {
                        return@all true
                    }
                }
            }
            false
        }
    }

    /**
     * Single-row bounded Levenshtein distance with early row abort when minimum exceeds [maxDist].
     */
    fun isWithinEditDistance(a: String, b: String, maxDist: Int): Boolean {
        val lenA = a.length
        val lenB = b.length
        if (abs(lenA - lenB) > maxDist) return false
        if (a == b) return true

        val prev = IntArray(lenB + 1) { it }
        val curr = IntArray(lenB + 1)

        for (i in 1..lenA) {
            curr[0] = i
            var rowMin = curr[0]
            val ca = a[i - 1]
            for (j in 1..lenB) {
                val cost = if (ca == b[j - 1]) 0 else 1
                val v = min(
                    min(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost
                )
                curr[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > maxDist) return false
            System.arraycopy(curr, 0, prev, 0, lenB + 1)
        }
        return prev[lenB] <= maxDist
    }
}
