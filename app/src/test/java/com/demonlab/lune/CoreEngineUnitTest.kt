package com.demonlab.lune

import com.demonlab.lune.core.ai.HarmonicAutomixScorer
import com.demonlab.lune.core.dsp.CrossfadeCurveEngine
import com.demonlab.lune.core.dsp.ReplayGainEngine
import com.demonlab.lune.core.format.AudioFormatRegistry
import com.demonlab.lune.core.metadata.MultiTagParser
import com.demonlab.lune.core.model.CoreAudioProfile
import com.demonlab.lune.core.model.CrossfadeConfig
import com.demonlab.lune.core.model.CrossfadeCurveMode
import com.demonlab.lune.core.model.MultiTagConfig
import com.demonlab.lune.core.model.ReplayGainConfig
import com.demonlab.lune.core.model.ReplayGainMode
import com.demonlab.lune.core.search.PhoneticSearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class CoreEngineUnitTest {

    @Test
    fun equalPowerAndHybridCurvesPreventMidpointVolumeDip() {
        val (eqOutMid, eqInMid) = CrossfadeCurveEngine.computeScalarGains(0.5f, CrossfadeCurveMode.EQUAL_POWER)
        val powerSum = eqOutMid * eqOutMid + eqInMid * eqInMid
        assertEquals(1.0f, powerSum, 0.02f)

        val (hybOutStart, hybInStart) = CrossfadeCurveEngine.computeScalarGains(0f, CrossfadeCurveMode.HYBRID_AUDIOPHILE)
        assertEquals(1.0f, hybOutStart, 0.001f)
        assertEquals(0.0f, hybInStart, 0.001f)

        val (hybOutEnd, hybInEnd) = CrossfadeCurveEngine.computeScalarGains(1f, CrossfadeCurveMode.HYBRID_AUDIOPHILE)
        assertEquals(0.0f, hybOutEnd, 0.001f)
        assertEquals(1.0f, hybInEnd, 0.001f)
    }

    @Test
    fun adaptiveCrossfadeDurationAdaptsByGenreEnergyAndSkipsSpokenWord() {
        val config = CrossfadeConfig(
            enabled = true,
            automixEnabled = true,
            adaptiveDuration = true,
            silenceDetectionEnabled = true,
            baseDurationSeconds = 6
        )

        val clubProfile = CoreAudioProfile(1L, energy = 0.88f, valence = 0.75f, danceability = 0.85f, acousticness = 0.12f, normalizedGenre = "electronic")
        val ambientProfile = CoreAudioProfile(2L, energy = 0.25f, valence = 0.45f, danceability = 0.20f, acousticness = 0.82f, normalizedGenre = "ambient")
        val podcastProfile = CoreAudioProfile(3L, energy = 0.35f, valence = 0.50f, danceability = 0.30f, acousticness = 0.60f, normalizedGenre = "podcast", isSpokenWord = true)

        val clubFadeMs = CrossfadeCurveEngine.computeAdaptiveDurationMs(config, 210_000L, 200_000L, clubProfile, clubProfile)
        val ambientFadeMs = CrossfadeCurveEngine.computeAdaptiveDurationMs(config, 240_000L, 240_000L, ambientProfile, ambientProfile)
        val podcastFadeMs = CrossfadeCurveEngine.computeAdaptiveDurationMs(config, 600_000L, 200_000L, podcastProfile, clubProfile)

        assertTrue("Electronic club crossfade ($clubFadeMs ms) should be tighter than ambient ($ambientFadeMs ms)", clubFadeMs < ambientFadeMs)
        assertEquals("Spoken word tracks should not crossfade", 0L, podcastFadeMs)
    }

    @Test
    fun tailSilenceDetectionTriggersEarlyFadeBeforeDeadAir() {
        val buckets = floatArrayOf(0.85f, 0.80f, 0.76f, 0.72f, 0.60f, 0.45f, 0.15f, 0.02f, 0.01f, 0.0f)
        val earlyOffsetMs = CrossfadeCurveEngine.detectNaturalFadeOutOffsetMs(buckets, 10_000L)
        assertTrue("Expected >= 3000ms early trigger for 3s trailing silence, got $earlyOffsetMs", earlyOffsetMs >= 3_000L)
    }

    @Test
    fun harmonicAutomixPrefersSmoothEnergyAndPenalizesSameArtist() {
        val current = CoreAudioProfile(10L, energy = 0.70f, valence = 0.65f, danceability = 0.72f, acousticness = 0.20f, normalizedGenre = "synthpop")
        val smoothDifferentArtist = CoreAudioProfile(11L, energy = 0.74f, valence = 0.68f, danceability = 0.75f, acousticness = 0.22f, normalizedGenre = "synthpop")
        val jarringDifferentArtist = CoreAudioProfile(12L, energy = 0.10f, valence = 0.12f, danceability = 0.15f, acousticness = 0.90f, normalizedGenre = "classical")

        val smoothScore = HarmonicAutomixScorer.scoreCandidate(
            currentArtistNormalized = "daft punk",
            currentProfile = current,
            candidateArtistNormalized = "justice",
            candidateProfile = smoothDifferentArtist,
            recentArtistsNormalized = listOf("kavinsky"),
            baseAcousticSimilarity = 0.88f,
            markovTransitionProb = 0.35f,
            userAffinityNormalized = 0.7f
        )

        val sameArtistScore = HarmonicAutomixScorer.scoreCandidate(
            currentArtistNormalized = "daft punk",
            currentProfile = current,
            candidateArtistNormalized = "daft punk",
            candidateProfile = smoothDifferentArtist,
            recentArtistsNormalized = listOf("kavinsky"),
            baseAcousticSimilarity = 0.88f,
            markovTransitionProb = 0.35f,
            userAffinityNormalized = 0.7f
        )

        val jarringScore = HarmonicAutomixScorer.scoreCandidate(
            currentArtistNormalized = "daft punk",
            currentProfile = current,
            candidateArtistNormalized = "chopin",
            candidateProfile = jarringDifferentArtist,
            recentArtistsNormalized = listOf("kavinsky"),
            baseAcousticSimilarity = 0.20f,
            markovTransitionProb = 0.0f,
            userAffinityNormalized = 0.5f
        )

        assertTrue("Smooth transition should beat jarring energy jump", smoothScore > jarringScore)
        assertTrue("Artist variety penalty should score new artist higher than consecutive repeat", smoothScore > sameArtistScore)
    }

    @Test
    fun phoneticAndFuzzySearchMatchesHomophonesAccentsAndTypos() {
        assertTrue(PhoneticSearchEngine.matches("The Beatles", "beetles"))
        assertTrue(PhoneticSearchEngine.matches("Música Ligera - Canción", "musica cancion"))
        assertTrue(PhoneticSearchEngine.matches("Phish", "fish"))
        assertTrue(PhoneticSearchEngine.matches("Radiohead", "radiahead"))
        assertFalse(PhoneticSearchEngine.matches("Metallica", "mozart"))
    }

    @Test
    fun replayGainParsesTagsR128SoundCheckAndAppliesPeakLimiter() {
        // Standard ReplayGain 2.0 & Opus R128 Q7.8 (-1536 / 256 = -6.0 dB + 5.0 dB RG2 offset = -1.0 dB)
        assertEquals(-6.45f, ReplayGainEngine.parseGainDb("-6.45 dB") ?: 0f, 0.01f)
        assertEquals(-1.0f, ReplayGainEngine.parseR128Q78GainDb("-1536") ?: 0f, 0.01f)
        assertNotNull(ReplayGainEngine.parseITunNormGainDb("00000A24 00000A24 00002510 00002510 00000000 00000000 00007FFF 00007FFF 00000000 00000000"))

        val meta = ReplayGainEngine.fromRawTags(
            rgTrackGain = "-7.50 dB",
            rgTrackPeak = "0.99",
            rgAlbumGain = "-5.00 dB",
            rgAlbumPeak = "0.99"
        )

        val smartAlbumSeq = ReplayGainEngine.computeLinearMultiplier(
            metadata = meta,
            config = ReplayGainConfig(mode = ReplayGainMode.SMART_ALBUM),
            isAlbumSequentialContext = true
        )
        val smartShuffle = ReplayGainEngine.computeLinearMultiplier(
            metadata = meta,
            config = ReplayGainConfig(mode = ReplayGainMode.SMART_ALBUM),
            isAlbumSequentialContext = false
        )
        assertEquals(-5.0f, smartAlbumSeq.appliedGainDb, 0.05f)
        assertEquals(-7.5f, smartShuffle.appliedGainDb, 0.05f)

        // Anti-clipping peak limiter when positive gain would exceed full-scale peak 1.0
        val positiveMeta = ReplayGainEngine.fromRawTags(rgTrackGain = "+8.0 dB", rgTrackPeak = "0.95")
        val limited = ReplayGainEngine.computeLinearMultiplier(
            metadata = positiveMeta,
            config = ReplayGainConfig(mode = ReplayGainMode.TRACK, preventClipping = true)
        )
        assertTrue("Expected anti-clipping peak limiter to engage", limited.isPeakLimited)
        assertTrue("Peak-limited gain (${limited.appliedGainDb} dB) must be way below +8.0 dB", limited.appliedGainDb < 1.0f)
    }

    @Test
    fun replayGainEstimatesLoudnessFromSparsePcm16leWithoutAvx() {
        // Generate 4096 stereo frames of a loud -3 dBFS sine wave
        val frames = 4096
        val pcm = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val s = (sin(i * 0.1) * 23170.0).toInt()
            val base = i * 4
            pcm[base] = (s and 0xFF).toByte()
            pcm[base + 1] = ((s shr 8) and 0xFF).toByte()
            pcm[base + 2] = (s and 0xFF).toByte()
            pcm[base + 3] = ((s shr 8) and 0xFF).toByte()
        }
        val estimated = ReplayGainEngine.estimateGainFromPcm16le(pcm, pcm.size, channels = 2)
        assertNotNull("Expected sparse RMS/LUFS estimator to return valid gain", estimated)
        // A -6 dBFS RMS sine wave is much louder than -18 LUFS, so gain adjustment must be negative
        assertTrue("Loud sine wave should yield negative ReplayGain attenuation, got ${estimated!!.first}", estimated.first < -6.0f)
    }

    @Test
    fun multiTagParserRespectsProtectedWhitelistExtractsTitleFeatWithoutConflicts() {
        val cfg = MultiTagConfig(
            enableMultiArtist = true,
            enableMultiGenre = true,
            extractArtistsFromTitle = true,
            cleanExtractedFeatFromTitle = true,
            customWhitelist = setOf("Wisin & Yandel")
        )

        // 1. Built-in & Custom Protected Whitelist + Multiple delimiters + Title feat extraction without duplicates
        val parsed1 = MultiTagParser.parseTrackTags(
            rawTitle = "Noche de Entierro (ft. Daddy Yankee & Tony Tun Tun) (Remastered)",
            rawArtist = "Wisin & Yandel; Héctor el Father, Daddy Yankee",
            rawGenre = "Reggaeton / Latin Urban; R&B",
            config = cfg
        )
        assertEquals(
            listOf("Wisin & Yandel", "Héctor el Father", "Daddy Yankee", "Tony Tun Tun"),
            parsed1.artists
        )
        assertEquals("Noche de Entierro (Remastered)", parsed1.cleanTitle)
        assertEquals(listOf("Reggaeton", "Latin Urban", "R&B"), parsed1.genres)

        // 2. AC/DC and Tyler, The Creator must NEVER be split by '/' or ','
        val parsed2 = MultiTagParser.parseTrackTags(
            rawTitle = "Thunderstruck (Live)",
            rawArtist = "AC/DC feat. Tyler, The Creator & Earth, Wind & Fire",
            rawGenre = "Hard Rock / Pop/Rock",
            config = cfg
        )
        assertEquals(
            listOf("AC/DC", "Tyler, The Creator", "Earth, Wind & Fire"),
            parsed2.artists
        )
        assertEquals("Thunderstruck (Live)", parsed2.cleanTitle)
        assertTrue("Pop/Rock should remain protected", parsed2.genres.contains("Pop/Rock"))
    }

    @Test
    fun extendedFormatsMultichannelDownmixAndSmartAlbumGaplessWorkCorrectly() {
        assertTrue(AudioFormatRegistry.isSupportedAudioExtension("alac"))
        assertTrue(AudioFormatRegistry.isSupportedAudioExtension("aiff"))
        assertTrue(AudioFormatRegistry.isSupportedAudioExtension("mkv"))
        assertTrue(AudioFormatRegistry.isSupportedAudioExtension("opus"))
        assertTrue(AudioFormatRegistry.isSupportedAudioExtension("eac3"))
        assertTrue(AudioFormatRegistry.isSupportedAudioExtension("ac4"))
        assertEquals("OPUS 5.1", AudioFormatRegistry.formatBadgeLabel("opus", "opus", 6))

        // Smart Album Gapless: consecutive tracks on same album bypass crossfade
        assertTrue(
            AudioFormatRegistry.shouldUseGaplessTransition(
                gaplessEnabled = true,
                crossfadeEnabled = true,
                automixEnabled = true,
                respectAlbumGapless = true,
                isShuffleActive = false,
                currentAlbum = "The Dark Side of the Moon",
                currentTrackNumber = 1,
                nextAlbum = "The Dark Side of the Moon",
                nextTrackNumber = 2
            )
        )
        assertFalse(
            AudioFormatRegistry.shouldUseGaplessTransition(
                gaplessEnabled = true,
                crossfadeEnabled = true,
                automixEnabled = false,
                respectAlbumGapless = true,
                isShuffleActive = false,
                currentAlbum = "The Dark Side of the Moon",
                currentTrackNumber = 1,
                nextAlbum = "Random Singles",
                nextTrackNumber = 2
            )
        )

        // 5.1 (6ch = 12 bytes per frame) -> Stereo (2ch = 4 bytes per frame) ITU downmix
        val sixChFrame = ByteArray(12)
        // Put +10000 in Front Center (offset 4..5)
        sixChFrame[4] = (10000 and 0xFF).toByte()
        sixChFrame[5] = ((10000 shr 8) and 0xFF).toByte()
        val stereoOut = ByteArray(4)
        val outBytes = AudioFormatRegistry.downmixPcm16leToStereo(sixChFrame, 12, 6, stereoOut)
        assertEquals(4, outBytes)
        val leftSample = ((stereoOut[1].toInt() shl 8) or (stereoOut[0].toInt() and 0xFF))
        val rightSample = ((stereoOut[3].toInt() shl 8) or (stereoOut[2].toInt() and 0xFF))
        assertEquals("Center channel must distribute equally to Left and Right", leftSample, rightSample)
        assertTrue("Downmixed center sample should be positive and attenuated cleanly", leftSample in 3500..4200)
    }
}
