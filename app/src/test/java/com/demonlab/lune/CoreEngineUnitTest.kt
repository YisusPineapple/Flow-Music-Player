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

    @Test
    fun streamingLufsAnalyzerComputesRealTimeKWeightedLoudnessAndResolvesUntaggedGain() {
        val sampleRate = 48_000
        val analyzer = com.demonlab.lune.core.dsp.StreamingLufsAnalyzer(sampleRateHz = sampleRate, channels = 2)

        // Feed 1.2 seconds of a 1 kHz stereo sine wave at -12 dBFS peak (~0.2512 amplitude)
        val totalFrames = (sampleRate * 1.2).toInt()
        val chunkFrames = 2400
        val chunkBytes = ByteArray(chunkFrames * 4)
        var frameIdx = 0
        while (frameIdx < totalFrames) {
            for (i in 0 until chunkFrames) {
                val phase = 2.0 * Math.PI * 1000.0 * (frameIdx + i) / sampleRate.toDouble()
                val s = (sin(phase) * 8230.0).toInt()
                val b = i * 4
                chunkBytes[b] = (s and 0xFF).toByte()
                chunkBytes[b + 1] = ((s shr 8) and 0xFF).toByte()
                chunkBytes[b + 2] = (s and 0xFF).toByte()
                chunkBytes[b + 3] = ((s shr 8) and 0xFF).toByte()
            }
            analyzer.processPcm16le(chunkBytes, 0, chunkBytes.size)
            frameIdx += chunkFrames
        }

        val lufs = analyzer.currentIntegratedLufs()
        assertNotNull("Expected StreamingLufsAnalyzer to produce integrated LUFS after >1s audio", lufs)
        // 1 kHz stereo sine at ~0.251 peak has RMS ~0.1776 -> ~ -12.7 LUFS in BS.1770 stereo sum
        assertTrue("Expected integrated LUFS in realistic range [-16..-9], got $lufs", lufs!! in -16.0f..-9.0f)

        val gainAndPeak = analyzer.currentReplayGainDbAndPeak()
        assertNotNull(gainAndPeak)
        val (computedGainDb, samplePeak) = gainAndPeak!!
        assertTrue("Peak should match ~0.251 full-scale", samplePeak in 0.24f..0.27f)

        val untaggedMeta = ReplayGainEngine.fromRawTags(
            rgTrackGain = null,
            computedLoudnessDb = computedGainDb
        ).copy(trackPeak = samplePeak)

        val resolved = ReplayGainEngine.computeLinearMultiplier(
            metadata = untaggedMeta,
            config = ReplayGainConfig(mode = ReplayGainMode.TRACK, enableFallbackAnalysis = true, preventClipping = true)
        )
        assertEquals("Auto LUFS", resolved.sourceLabel)
        assertTrue("Untagged loud sine wave should be attenuated toward -18 LUFS", resolved.appliedGainDb < -2.0f)
    }

    @Test
    fun multiTagParserHandlesMultipleBracketsTrailingFeatConnectorsAndDiacriticsWithoutConflicts() {
        val cfg = MultiTagConfig(
            enableMultiArtist = true,
            enableMultiGenre = true,
            extractArtistsFromTitle = true,
            cleanExtractedFeatFromTitle = true,
            customWhitelist = setOf("Crosby, Stills, Nash & Young")
        )

        // 1. Multiple brackets + preserve (Remix) + unsplit custom 4-way protected band + diacritic dedup
        val p1 = MultiTagParser.parseTrackTags(
            rawTitle = "Bailando (Club Remix) [feat. Beyoncé & Sean Paul]",
            rawArtist = "Enrique Iglesias con Beyonce; Crosby, Stills, Nash & Young",
            rawGenre = "Latin Pop; Dance/Electronic | Tropical",
            config = cfg
        )
        assertEquals("Bailando (Club Remix)", p1.cleanTitle)
        assertEquals(
            listOf("Enrique Iglesias", "Beyonce", "Crosby, Stills, Nash & Young", "Sean Paul"),
            p1.artists
        )

        // 2. Unbracketed trailing 'ft.' before edition tag + built-in '+44' and 'Lil Nas X' protection
        val p2 = MultiTagParser.parseTrackTags(
            rawTitle = "Industry Baby ft. Jack Harlow (Extended Mix)",
            rawArtist = "Lil Nas X x +44",
            rawGenre = "Hip-Hop/Rap, Pop",
            config = cfg
        )
        assertEquals("Industry Baby (Extended Mix)", p2.cleanTitle)
        assertEquals(listOf("Lil Nas X", "+44", "Jack Harlow"), p2.artists)
        assertTrue(p2.genres.contains("Hip-Hop/Rap"))
    }

    @Test
    fun allTwentyThreeRequestedFormatsAndNativeCrossPlatformDecodersWorkEndToEnd() {
        val requiredExtensions = listOf(
            "alac", "aiff", "aif", "ape", "wv", "dsf", "dff",
            "m4a", "mp4", "mkv", "mka", "webm", "opus",
            "eac3", "ac3", "ac4", "dts", "wma", "caf",
            "flac", "wav", "ogg", "aac", "mp3"
        )
        for (ext in requiredExtensions) {
            assertTrue("Format '$ext' must be supported in AudioFormatRegistry", AudioFormatRegistry.isSupportedAudioExtension(ext))
        }

        // 1. Test synthetic AIFF (Big-Endian 16-bit 44.1 kHz Stereo) via NativePcmContainerDecoder
        val aiffBytes = buildSyntheticAiff16BeStereo(frames = 128, sampleValue = 12345)
        val aiffStream = com.demonlab.lune.core.format.NativePcmContainerDecoder.open(
            com.demonlab.lune.core.format.ByteArraySeekableReader(aiffBytes)
        )
        assertNotNull("NativePcmContainerDecoder must open AIFF container", aiffStream)
        assertEquals("AIFF", aiffStream!!.containerName)
        assertEquals(44100, aiffStream.outputSampleRate)
        val aiffOut = ByteArray(512)
        val aiffRead = aiffStream.readStereoS16le(aiffOut, 0, aiffOut.size)
        assertEquals(512, aiffRead)
        val decodedAiffSample = (aiffOut[1].toInt() shl 8) or (aiffOut[0].toInt() and 0xFF)
        assertEquals(12345, decodedAiffSample)
        aiffStream.close()

        // 2. Test synthetic Apple CAF (`caff` + `desc` + `data` 16-bit BE 48 kHz Stereo)
        val cafBytes = buildSyntheticCaf16BeStereo(frames = 64, sampleValue = -9876)
        val cafStream = com.demonlab.lune.core.format.NativePcmContainerDecoder.open(
            com.demonlab.lune.core.format.ByteArraySeekableReader(cafBytes)
        )
        assertNotNull("NativePcmContainerDecoder must open Apple CAF container", cafStream)
        assertEquals("CAF", cafStream!!.containerName)
        assertEquals(48000, cafStream.outputSampleRate)
        val cafOut = ByteArray(256)
        val cafRead = cafStream.readStereoS16le(cafOut, 0, cafOut.size)
        assertEquals(256, cafRead)
        val decodedCafSample = (cafOut[1].toInt() shl 8) or (cafOut[0].toInt() and 0xFF)
        assertEquals(-9876, decodedCafSample)
        cafStream.close()

        // 3. Test synthetic Sony DSF (DSD64 2.8224 MHz 1-bit PDM -> 88.2 kHz 16-bit LE Stereo)
        val dsfBytes = buildSyntheticDsfStereo()
        val dsfStream = com.demonlab.lune.core.format.NativePcmContainerDecoder.open(
            com.demonlab.lune.core.format.ByteArraySeekableReader(dsfBytes)
        )
        assertNotNull("NativePcmContainerDecoder must open Sony DSF (DSD64) container", dsfStream)
        assertEquals("DSD", dsfStream!!.containerName)
        assertEquals(88200, dsfStream.outputSampleRate)
        val dsfOut = ByteArray(1024)
        val dsfRead = dsfStream.readStereoS16le(dsfOut, 0, dsfOut.size)
        assertTrue("Expected positive PCM byte output from DSF PDM decimation, got $dsfRead", dsfRead > 0)
        dsfStream.close()
    }

    private fun buildSyntheticAiff16BeStereo(frames: Int, sampleValue: Int): ByteArray {
        val pcmBytes = frames * 4
        val ssndChunkSize = 8 + pcmBytes
        val commChunkSize = 18
        val formSize = 4 + (8 + commChunkSize) + (8 + ssndChunkSize)
        val out = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(out)
        dos.writeBytes("FORM")
        dos.writeInt(formSize)
        dos.writeBytes("AIFF")
        // COMM chunk
        dos.writeBytes("COMM")
        dos.writeInt(commChunkSize)
        dos.writeShort(2) // channels
        dos.writeInt(frames) // numSampleFrames
        dos.writeShort(16) // sampleSize
        // 80-bit IEEE extended for 44100.0 Hz: 0x400E AC44 0000 0000 0000
        dos.write(byteArrayOf(0x40, 0x0E, 0xAC.toByte(), 0x44, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        // SSND chunk
        dos.writeBytes("SSND")
        dos.writeInt(ssndChunkSize)
        dos.writeInt(0) // offset
        dos.writeInt(0) // blockSize
        repeat(frames * 2) {
            dos.writeShort(sampleValue)
        }
        dos.flush()
        return out.toByteArray()
    }

    private fun buildSyntheticCaf16BeStereo(frames: Int, sampleValue: Int): ByteArray {
        val pcmBytes = frames * 4
        val out = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(out)
        dos.writeBytes("caff")
        dos.writeShort(1) // mFileVersion
        dos.writeShort(0) // mFileFlags
        // desc chunk
        dos.writeBytes("desc")
        dos.writeLong(32L)
        dos.writeLong(java.lang.Double.doubleToRawLongBits(48000.0))
        dos.writeBytes("lpcm")
        dos.writeInt(0) // flags = 0 (int, big-endian)
        dos.writeInt(4) // bytesPerPacket
        dos.writeInt(1) // framesPerPacket
        dos.writeInt(2) // channelsPerFrame
        dos.writeInt(16) // bitsPerChannel
        // data chunk
        dos.writeBytes("data")
        dos.writeLong((4 + pcmBytes).toLong())
        dos.writeInt(0) // mEditCount
        repeat(frames * 2) {
            dos.writeShort(sampleValue)
        }
        dos.flush()
        return out.toByteArray()
    }

    private fun buildSyntheticDsfStereo(): ByteArray {
        val blockSizePerCh = 64
        val channels = 2
        val audioPayloadBytes = blockSizePerCh * channels * 2 // 2 superblocks
        val totalFileSize = 28L + 52L + 12L + audioPayloadBytes
        val buf = java.nio.ByteBuffer.allocate(totalFileSize.toInt()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        // 'DSD ' chunk (28 bytes)
        buf.put("DSD ".toByteArray(Charsets.US_ASCII))
        buf.putLong(28L)
        buf.putLong(totalFileSize)
        buf.putLong(0L)
        // 'fmt ' chunk (52 bytes)
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putLong(52L)
        buf.putInt(1) // format version
        buf.putInt(0) // format ID (DSD raw)
        buf.putInt(2) // channel type (stereo)
        buf.putInt(channels)
        buf.putInt(2_822_400) // DSD64
        buf.putInt(1) // bitsPerSample (LSB first)
        buf.putLong((blockSizePerCh * 2 * 8).toLong()) // sampleCount per channel
        buf.putInt(blockSizePerCh)
        buf.putInt(0) // reserved
        // 'data' chunk (12 + payload)
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putLong((12 + audioPayloadBytes).toLong())
        // Fill PDM bytes with 0xAA (50% ones = silence/zero DC) and 0xFF (positive)
        repeat(audioPayloadBytes) { idx ->
            buf.put(if (idx % 2 == 0) 0xCC.toByte() else 0xAA.toByte())
        }
        return buf.array()
    }

    @Test
    fun replayGainTagServiceReadsJaudiotaggerFilesLufsTagsAndComputesLinearPlaybackGain() {
        // 1. Direct LUFS tag conversion relative to -18.0 LUFS reference
        assertEquals(-4.0f, com.demonlab.lune.core.dsp.ReplayGainTagService.parseLufsTagToReplayGainDb("-14.0 LUFS") ?: 0f, 0.01f)
        assertEquals(3.5f, com.demonlab.lune.core.dsp.ReplayGainTagService.parseLufsTagToReplayGainDb("-21.5 LKFS") ?: 0f, 0.01f)

        // 2. Write real ID3v24 TXXX ReplayGain tags into a temporary WAV file and read via ReplayGainTagService
        val tempRgWav = java.io.File.createTempFile("flow_rg_test_", ".wav")
        val tempLufsWav = java.io.File.createTempFile("flow_lufs_test_", ".wav")
        try {
            tempRgWav.writeBytes(buildMinimalStereoWav16Le(frames = 64))
            val rgAudioFile = org.jaudiotagger.audio.AudioFileIO.read(tempRgWav)
            val id3Rg = org.jaudiotagger.tag.id3.ID3v24Tag()
            fun addTxxx(tag: org.jaudiotagger.tag.id3.ID3v24Tag, desc: String, value: String) {
                val frame = org.jaudiotagger.tag.id3.ID3v24Frame("TXXX")
                frame.body = org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX(0.toByte(), desc, value)
                tag.addFrame(frame)
            }
            addTxxx(id3Rg, "REPLAYGAIN_TRACK_GAIN", "-6.25 dB")
            addTxxx(id3Rg, "REPLAYGAIN_TRACK_PEAK", "0.94")
            addTxxx(id3Rg, "REPLAYGAIN_ALBUM_GAIN", "-4.50 dB")
            addTxxx(id3Rg, "REPLAYGAIN_ALBUM_PEAK", "0.98")
            val wavTagRg = (rgAudioFile.tagOrCreateAndSetDefault as org.jaudiotagger.tag.wav.WavTag)
            wavTagRg.iD3Tag = id3Rg
            rgAudioFile.commit()

            val parsedRg = com.demonlab.lune.core.dsp.ReplayGainTagService.readFromFile(tempRgWav)
            assertTrue("Expected embedded ReplayGain tags to be detected", parsedRg.hasEmbeddedTags)
            assertEquals(-6.25f, parsedRg.trackGainDb ?: 0f, 0.02f)
            assertEquals(0.94f, parsedRg.trackPeak ?: 0f, 0.01f)
            assertEquals(-4.50f, parsedRg.albumGainDb ?: 0f, 0.02f)
            assertEquals(0.98f, parsedRg.albumPeak ?: 0f, 0.01f)

            val linearTrack = com.demonlab.lune.core.dsp.ReplayGainTagService.computeTotalLinearGain(
                metadata = parsedRg,
                config = ReplayGainConfig(mode = ReplayGainMode.TRACK, preventClipping = true)
            )
            // 10^(-6.25 / 20) ~= 0.48697
            assertEquals(0.487f, linearTrack, 0.01f)

            // 3. Write real ID3v24 TXXX LUFS tag (without REPLAYGAIN_*) and verify LUFS -> ReplayGain conversion
            tempLufsWav.writeBytes(buildMinimalStereoWav16Le(frames = 64))
            val lufsAudioFile = org.jaudiotagger.audio.AudioFileIO.read(tempLufsWav)
            val id3Lufs = org.jaudiotagger.tag.id3.ID3v24Tag()
            addTxxx(id3Lufs, "LUFS", "-12.5 LUFS")
            val wavTagLufs = (lufsAudioFile.tagOrCreateAndSetDefault as org.jaudiotagger.tag.wav.WavTag)
            wavTagLufs.iD3Tag = id3Lufs
            lufsAudioFile.commit()

            val parsedLufs = com.demonlab.lune.core.dsp.ReplayGainTagService.readFromFile(tempLufsWav)
            assertEquals(-5.5f, parsedLufs.trackGainDb ?: 0f, 0.02f)

            val linearLufs = com.demonlab.lune.core.dsp.ReplayGainTagService.computeTotalLinearGain(
                metadata = parsedLufs,
                config = ReplayGainConfig(mode = ReplayGainMode.TRACK, preventClipping = true)
            )
            // 10^(-5.5 / 20) ~= 0.53088
            assertEquals(0.531f, linearLufs, 0.01f)

            // 4. Verify positive boost (> 1.0f) when peak headroom allows vs peak clamping
            val quietMeta = ReplayGainEngine.fromRawTags(rgTrackGain = "+6.0 dB", rgTrackPeak = "0.35")
            val boostLinear = com.demonlab.lune.core.dsp.ReplayGainTagService.computeTotalLinearGain(
                metadata = quietMeta,
                config = ReplayGainConfig(mode = ReplayGainMode.TRACK, preventClipping = true)
            )
            assertTrue("Expected quiet track with 0.35 peak to boost near 2.0x, got $boostLinear", boostLinear > 1.95f)
        } finally {
            tempRgWav.delete()
            tempLufsWav.delete()
        }
    }

    private fun buildMinimalStereoWav16Le(frames: Int): ByteArray {
        val dataBytes = frames * 4
        val buf = java.nio.ByteBuffer.allocate(44 + dataBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataBytes)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1) // PCM
        buf.putShort(2) // Stereo
        buf.putInt(44100)
        buf.putInt(44100 * 4)
        buf.putShort(4)
        buf.putShort(16)
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataBytes)
        repeat(frames * 2) { buf.putShort(1024) }
        return buf.array()
    }
}

