package com.demonlab.lune.core.ai

import com.demonlab.lune.core.model.CoreAudioProfile
import kotlin.math.abs

/**
 * Platform-independent DJ & Automix transition scorer shared across Android, Windows, and Linux.
 * Evaluates harmonic/mood compatibility, smooth energy progression, Markov transition probability,
 * and artist-clustering penalties.
 */
object HarmonicAutomixScorer {

    /**
     * Scores a candidate next track in [0f..1.5f] for Smart Automix transition from [currentProfile].
     */
    fun scoreCandidate(
        currentArtistNormalized: String,
        currentProfile: CoreAudioProfile,
        candidateArtistNormalized: String,
        candidateProfile: CoreAudioProfile,
        recentArtistsNormalized: List<String>,
        baseAcousticSimilarity: Float,
        markovTransitionProb: Float,
        userAffinityNormalized: Float
    ): Float {
        if (candidateProfile.isSpokenWord && !currentProfile.isSpokenWord) {
            return 0.02f
        }

        // 1. Gradual energy arc: reward smooth +/- 0.25 energy steps, penalize jarring > 0.55 jumps
        val energyDelta = abs(currentProfile.energy - candidateProfile.energy)
        val valenceDelta = abs(currentProfile.valence - candidateProfile.valence)
        val energyFlowScore = when {
            energyDelta <= 0.18f -> 1.0f
            energyDelta <= 0.35f -> 0.82f
            energyDelta <= 0.55f -> 0.50f
            else -> 0.18f
        }
        val moodBridgeScore = (1f - valenceDelta * 0.7f).coerceIn(0.15f, 1f)

        // 2. Artist repeat penalty across the last 3 played tracks
        val sameAsCurrent = currentArtistNormalized.isNotEmpty() &&
            currentArtistNormalized == candidateArtistNormalized
        val recentIndex = if (candidateArtistNormalized.isNotEmpty()) {
            recentArtistsNormalized.takeLast(3).lastIndexOf(candidateArtistNormalized)
        } else {
            -1
        }
        val artistVarietyMultiplier = when {
            sameAsCurrent -> 0.28f
            recentIndex >= 0 -> 0.45f
            else -> 1.0f
        }

        // 3. Weighted studio Automix blend
        val rawScore = (baseAcousticSimilarity.coerceIn(0f, 1f) * 0.38f) +
            (energyFlowScore * 0.24f) +
            (moodBridgeScore * 0.12f) +
            (markovTransitionProb.coerceIn(0f, 1f) * 0.16f) +
            (userAffinityNormalized.coerceIn(0f, 1f) * 0.10f)

        return (rawScore * artistVarietyMultiplier).coerceIn(0.001f, 1.5f)
    }
}
