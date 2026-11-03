package com.demonlab.lune.core.format

import kotlin.math.pow

/**
 * Platform-agnostic random-access byte reader so [NativePcmContainerDecoder] runs in pure Kotlin
 * `commonMain` on Android, Windows, Linux, and JVM unit tests with zero external dependencies.
 */
interface SeekableByteReader {
    val length: Long
    var position: Long
    fun read(buffer: ByteArray, offset: Int, count: Int): Int
    fun close()
}

/**
 * In-memory implementation of [SeekableByteReader] for tests and small buffers.
 */
class ByteArraySeekableReader(private val data: ByteArray) : SeekableByteReader {
    override val length: Long get() = data.size.toLong()
    override var position: Long = 0L

    override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
        if (position >= data.size || count <= 0) return -1
        val avail = (data.size - position).toInt()
        val toCopy = minOf(avail, count)
        data.copyInto(buffer, offset, position.toInt(), position.toInt() + toCopy)
        position += toCopy
        return toCopy
    }

    override fun close() = Unit
}

/**
 * Decoded stereo 16-bit Little-Endian PCM stream produced by [NativePcmContainerDecoder].
 */
interface DecodedS16leStream {
    val outputSampleRate: Int
    val sourceSampleRate: Int
    val sourceChannels: Int
    val durationMs: Long
    val containerName: String
    fun seekToMs(targetMs: Long)
    fun readStereoS16le(outBuffer: ByteArray, offset: Int = 0, maxBytes: Int = outBuffer.size - offset): Int
    fun close()
}

/**
 * Pure-Kotlin, zero-AVX, ultra-low-RAM container & audiophile PCM/PDM decoder for Flow.
 *
 * Guarantees cross-platform native playback on BOTH Android (even API 24 devices without vendor codecs)
 * and Desktop (Windows/Linux even when `ffmpeg` is not installed) for:
 *  - AIFF / AIF / AIFC (8/16/24/32-bit Big-Endian, Little-Endian `sowt`, 32-bit float `fl32`, u-law, A-law)
 *  - CAF (Apple Core Audio Format `caff` uncompressed `lpcm` 16/24/32-bit int & float, BE/LE)
 *  - DSF (Sony DSD64 / DSD128 / DSD256 1-bit PDM -> 88.2 kHz 16-bit LE Stereo PCM)
 *  - DFF / DSDIFF (Philips `FRM8....DSD ` 1-bit PDM -> 88.2 kHz 16-bit LE Stereo PCM)
 *  - AU / SND (NeXT/Sun 8/16/24/32-bit BE PCM, float, u-law, A-law)
 */
object NativePcmContainerDecoder {

    private val POPCOUNT_8 = IntArray(256) { b -> b.countOneBits() }

    private val NATIVE_DIRECT_EXTENSIONS = setOf(
        "aiff", "aif", "aifc", "caf", "dsf", "dff", "au", "snd"
    )

    fun isNativelyDecodableExtension(extension: String): Boolean {
        return extension.lowercase().trim().trimStart('.') in NATIVE_DIRECT_EXTENSIONS
    }

    fun open(reader: SeekableByteReader): DecodedS16leStream? {
        if (reader.length < 16L) return null
        reader.position = 0L
        val magic = ByteArray(12)
        if (readFully(reader, magic, 0, 12) < 12) return null

        val m4 = ascii(magic, 0, 4)
        return when (m4) {
            "FORM" -> {
                val formType = ascii(magic, 8, 4)
                if (formType == "AIFF" || formType == "AIFC") parseAiff(reader, formType == "AIFC") else null
            }
            "caff" -> parseCaf(reader)
            "DSD " -> parseDsf(reader)
            "FRM8" -> parseDff(reader)
            ".snd" -> parseAu(reader)
            else -> null
        }
    }

    // =========================================================================
    // 1. AIFF / AIFC
    // =========================================================================
    private enum class PcmSampleEncoding {
        INT_BE, INT_LE, FLOAT32_BE, FLOAT32_LE, FLOAT64_BE, FLOAT64_LE, ULAW, ALAW
    }

    private fun parseAiff(reader: SeekableByteReader, isAifc: Boolean): DecodedS16leStream? {
        reader.position = 12L
        var channels = 0
        var bitsPerSample = 16
        var sampleRate = 44100
        var encoding = PcmSampleEncoding.INT_BE
        var ssndDataStart = -1L
        var ssndDataBytes = 0L
        val hdr = ByteArray(8)

        while (reader.position + 8 <= reader.length) {
            if (readFully(reader, hdr, 0, 8) < 8) break
            val chunkId = ascii(hdr, 0, 4)
            val chunkSize = readU32Be(hdr, 4)
            val chunkBodyStart = reader.position

            if (chunkId == "COMM" && chunkSize >= 18L) {
                val comm = ByteArray(chunkSize.coerceAtMost(64L).toInt())
                val readComm = readFully(reader, comm, 0, comm.size)
                if (readComm >= 18) {
                    channels = readU16Be(comm, 0).coerceIn(1, 8)
                    bitsPerSample = readU16Be(comm, 6).coerceIn(8, 64)
                    sampleRate = parseIeee80Extended(comm, 8).toInt().coerceIn(8000, 384000)
                    if (isAifc && readComm >= 22) {
                        val comp = ascii(comm, 18, 4)
                        encoding = when (comp) {
                            "NONE", "twos" -> PcmSampleEncoding.INT_BE
                            "sowt" -> PcmSampleEncoding.INT_LE
                            "fl32", "FL32" -> PcmSampleEncoding.FLOAT32_BE
                            "fl64", "FL64" -> PcmSampleEncoding.FLOAT64_BE
                            "ulaw", "ULAW" -> {
                                bitsPerSample = 8
                                PcmSampleEncoding.ULAW
                            }
                            "alaw", "ALAW" -> {
                                bitsPerSample = 8
                                PcmSampleEncoding.ALAW
                            }
                            else -> return null // Compressed AIFC (e.g. ima4) not linear PCM
                        }
                    }
                }
            } else if (chunkId == "SSND" && chunkSize >= 8L) {
                val ssndHdr = ByteArray(8)
                if (readFully(reader, ssndHdr, 0, 8) == 8) {
                    val dataOffset = readU32Be(ssndHdr, 0)
                    ssndDataStart = reader.position + dataOffset
                    ssndDataBytes = (chunkSize - 8L - dataOffset).coerceAtLeast(0L)
                }
            }

            val padded = if ((chunkSize and 1L) != 0L) chunkSize + 1L else chunkSize
            val nextPos = chunkBodyStart + padded
            if (nextPos <= chunkBodyStart || nextPos > reader.length) break
            reader.position = nextPos
        }

        if (channels <= 0 || ssndDataStart < 0L || ssndDataBytes <= 0L) return null
        val bytesPerSample = ((bitsPerSample + 7) / 8).coerceIn(1, 8)
        return LinearPcmDecodedStream(
            reader = reader,
            containerName = if (isAifc) "AIFC" else "AIFF",
            dataStartOffset = ssndDataStart,
            dataByteLength = ssndDataBytes.coerceAtMost(reader.length - ssndDataStart),
            sourceSampleRate = sampleRate,
            sourceChannels = channels,
            bytesPerSample = bytesPerSample,
            encoding = encoding
        )
    }

    // =========================================================================
    // 2. Apple CAF (`caff`)
    // =========================================================================
    private fun parseCaf(reader: SeekableByteReader): DecodedS16leStream? {
        reader.position = 8L // Skip 'caff' (4) + mFileVersion (2) + mFileFlags (2)
        var sampleRate = 44100
        var channels = 0
        var bytesPerSample = 2
        var encoding = PcmSampleEncoding.INT_BE
        var dataStart = -1L
        var dataLen = 0L
        val chunkHdr = ByteArray(12)

        while (reader.position + 12 <= reader.length) {
            if (readFully(reader, chunkHdr, 0, 12) < 12) break
            val type = ascii(chunkHdr, 0, 4)
            val size = readS64Be(chunkHdr, 4)
            val bodyStart = reader.position

            if (type == "desc" && size >= 32L) {
                val desc = ByteArray(32)
                if (readFully(reader, desc, 0, 32) == 32) {
                    val srDouble = Double.fromBits(readS64Be(desc, 0))
                    sampleRate = srDouble.toInt().coerceIn(8000, 384000)
                    val fmtId = ascii(desc, 8, 4)
                    val fmtFlags = readU32Be(desc, 12).toInt()
                    val bytesPerPacket = readU32Be(desc, 16).toInt()
                    channels = readU32Be(desc, 24).toInt().coerceIn(1, 8)
                    val bitsPerCh = readU32Be(desc, 28).toInt()

                    when (fmtId) {
                        "lpcm" -> {
                            val isFloat = (fmtFlags and 1) != 0
                            val isLittleEndian = (fmtFlags and 2) != 0
                            bytesPerSample = when {
                                bitsPerCh > 0 -> ((bitsPerCh + 7) / 8).coerceIn(1, 8)
                                bytesPerPacket > 0 && channels > 0 -> (bytesPerPacket / channels).coerceIn(1, 8)
                                else -> 2
                            }
                            encoding = when {
                                isFloat && bytesPerSample == 4 -> if (isLittleEndian) PcmSampleEncoding.FLOAT32_LE else PcmSampleEncoding.FLOAT32_BE
                                isFloat && bytesPerSample == 8 -> if (isLittleEndian) PcmSampleEncoding.FLOAT64_LE else PcmSampleEncoding.FLOAT64_BE
                                isLittleEndian -> PcmSampleEncoding.INT_LE
                                else -> PcmSampleEncoding.INT_BE
                            }
                        }
                        "ulaw" -> {
                            bytesPerSample = 1
                            encoding = PcmSampleEncoding.ULAW
                        }
                        "alaw" -> {
                            bytesPerSample = 1
                            encoding = PcmSampleEncoding.ALAW
                        }
                        else -> return null
                    }
                }
            } else if (type == "data") {
                dataStart = bodyStart + 4L // Skip 4-byte mEditCount
                dataLen = if (size < 0L) {
                    (reader.length - dataStart).coerceAtLeast(0L)
                } else {
                    (size - 4L).coerceAtLeast(0L)
                }
                break
            }

            if (size < 0L) break
            val next = bodyStart + size
            if (next <= bodyStart || next > reader.length) break
            reader.position = next
        }

        if (channels <= 0 || dataStart < 0L || dataLen <= 0L) return null
        return LinearPcmDecodedStream(
            reader = reader,
            containerName = "CAF",
            dataStartOffset = dataStart,
            dataByteLength = dataLen.coerceAtMost(reader.length - dataStart),
            sourceSampleRate = sampleRate,
            sourceChannels = channels,
            bytesPerSample = bytesPerSample,
            encoding = encoding
        )
    }

    // =========================================================================
    // 3. Sun / NeXT AU (.au / .snd)
    // =========================================================================
    private fun parseAu(reader: SeekableByteReader): DecodedS16leStream? {
        reader.position = 0L
        val hdr = ByteArray(24)
        if (readFully(reader, hdr, 0, 24) < 24) return null
        val dataOffset = readU32Be(hdr, 4).coerceAtLeast(24L)
        val rawDataSize = readU32Be(hdr, 8)
        val auEncoding = readU32Be(hdr, 12).toInt()
        val sampleRate = readU32Be(hdr, 16).toInt().coerceIn(8000, 384000)
        val channels = readU32Be(hdr, 20).toInt().coerceIn(1, 8)

        val (bps, enc) = when (auEncoding) {
            1 -> 1 to PcmSampleEncoding.ULAW
            2 -> 1 to PcmSampleEncoding.INT_BE
            3 -> 2 to PcmSampleEncoding.INT_BE
            4 -> 3 to PcmSampleEncoding.INT_BE
            5 -> 4 to PcmSampleEncoding.INT_BE
            6 -> 4 to PcmSampleEncoding.FLOAT32_BE
            7 -> 8 to PcmSampleEncoding.FLOAT64_BE
            27 -> 1 to PcmSampleEncoding.ALAW
            else -> return null
        }
        val avail = (reader.length - dataOffset).coerceAtLeast(0L)
        val dataBytes = if (rawDataSize == 0xFFFFFFFFL || rawDataSize <= 0L) avail else minOf(rawDataSize, avail)
        if (dataBytes <= 0L) return null

        return LinearPcmDecodedStream(
            reader = reader,
            containerName = "AU",
            dataStartOffset = dataOffset,
            dataByteLength = dataBytes,
            sourceSampleRate = sampleRate,
            sourceChannels = channels,
            bytesPerSample = bps,
            encoding = enc
        )
    }

    // =========================================================================
    // 4. Sony DSF (DSD64 / DSD128 / DSD256 -> 88.2 kHz 16-bit LE Stereo)
    // =========================================================================
    private fun parseDsf(reader: SeekableByteReader): DecodedS16leStream? {
        reader.position = 0L
        val dsdHdr = ByteArray(28)
        if (readFully(reader, dsdHdr, 0, 28) < 28) return null
        if (ascii(dsdHdr, 0, 4) != "DSD ") return null

        val fmtHdr = ByteArray(52)
        if (readFully(reader, fmtHdr, 0, 52) < 52) return null
        if (ascii(fmtHdr, 0, 4) != "fmt ") return null

        val channels = readS32Le(fmtHdr, 24).coerceIn(1, 6)
        val dsdSampleRate = readS32Le(fmtHdr, 28).coerceAtLeast(2822400)
        val bitsPerSampleField = readS32Le(fmtHdr, 32) // 1 = LSB first, 8 = MSB first
        val sampleCountPerCh = readS64Le(fmtHdr, 36)
        val blockSizePerCh = readS32Le(fmtHdr, 44).coerceIn(64, 65536)

        val fmtChunkSize = readS64Le(fmtHdr, 4)
        reader.position = 28L + fmtChunkSize
        val dataHdr = ByteArray(12)
        if (readFully(reader, dataHdr, 0, 12) < 12) return null
        if (ascii(dataHdr, 0, 4) != "data") return null
        val dataChunkSize = readS64Le(dataHdr, 4)
        val dataStart = reader.position
        val dataBytes = (dataChunkSize - 12L).coerceIn(0L, reader.length - dataStart)
        if (dataBytes <= 0L) return null

        val decimationBits = when {
            dsdSampleRate >= 11_289_600 -> 128 // DSD256 -> 88.2 / 96 kHz
            dsdSampleRate >= 5_644_800 -> 64   // DSD128 -> 88.2 / 96 kHz
            else -> 32                         // DSD64  -> 88.2 / 96 kHz
        }
        val outRate = (dsdSampleRate / decimationBits).coerceIn(44100, 96000)
        val durMs = if (sampleCountPerCh > 0L) {
            (sampleCountPerCh * 1000L) / dsdSampleRate
        } else {
            val totalBitsPerCh = (dataBytes / channels) * 8L
            (totalBitsPerCh * 1000L) / dsdSampleRate
        }

        return DsfPlanarDecodedStream(
            reader = reader,
            dataStartOffset = dataStart,
            dataByteLength = dataBytes,
            sourceSampleRate = dsdSampleRate,
            outputSampleRate = outRate,
            sourceChannels = channels,
            blockSizePerChannel = blockSizePerCh,
            bytesPerPcmSample = decimationBits / 8,
            durationMs = durMs,
            lsbFirst = bitsPerSampleField == 1
        )
    }

    // =========================================================================
    // 5. Philips DSDIFF / DFF (`FRM8....DSD `)
    // =========================================================================
    private fun parseDff(reader: SeekableByteReader): DecodedS16leStream? {
        reader.position = 0L
        val frm8 = ByteArray(16)
        if (readFully(reader, frm8, 0, 16) < 16) return null
        if (ascii(frm8, 0, 4) != "FRM8" || ascii(frm8, 12, 4) != "DSD ") return null

        var dsdSampleRate = 2_822_400
        var channels = 2
        var dsdDataStart = -1L
        var dsdDataBytes = 0L
        val chdr = ByteArray(12)

        while (reader.position + 12 <= reader.length) {
            if (readFully(reader, chdr, 0, 12) < 12) break
            val id = ascii(chdr, 0, 4)
            val size = readS64Be(chdr, 4)
            val bodyStart = reader.position

            if (id == "PROP" && size >= 4L) {
                val propType = ByteArray(4)
                if (readFully(reader, propType, 0, 4) == 4 && ascii(propType, 0, 4) == "SND ") {
                    val propEnd = (bodyStart + size).coerceAtMost(reader.length)
                    while (reader.position + 12 <= propEnd) {
                        if (readFully(reader, chdr, 0, 12) < 12) break
                        val subId = ascii(chdr, 0, 4)
                        val subSize = readS64Be(chdr, 4)
                        val subStart = reader.position
                        if (subId == "FS  " && subSize >= 4L) {
                            val b4 = ByteArray(4)
                            if (readFully(reader, b4, 0, 4) == 4) {
                                dsdSampleRate = readU32Be(b4, 0).toInt().coerceAtLeast(2_822_400)
                            }
                        } else if (subId == "CHNL" && subSize >= 2L) {
                            val b2 = ByteArray(2)
                            if (readFully(reader, b2, 0, 2) == 2) {
                                channels = readU16Be(b2, 0).coerceIn(1, 6)
                            }
                        }
                        val paddedSub = if ((subSize and 1L) != 0L) subSize + 1L else subSize
                        val nextSub = subStart + paddedSub
                        if (nextSub <= subStart || nextSub > propEnd) break
                        reader.position = nextSub
                    }
                }
            } else if (id == "DSD " && size > 0L) {
                dsdDataStart = bodyStart
                dsdDataBytes = size.coerceAtMost(reader.length - bodyStart)
                break
            }

            val padded = if ((size and 1L) != 0L) size + 1L else size
            val next = bodyStart + padded
            if (next <= bodyStart || next > reader.length) break
            reader.position = next
        }

        if (dsdDataStart < 0L || dsdDataBytes <= 0L) return null
        val decimationBits = when {
            dsdSampleRate >= 11_289_600 -> 128
            dsdSampleRate >= 5_644_800 -> 64
            else -> 32
        }
        val outRate = (dsdSampleRate / decimationBits).coerceIn(44100, 96000)
        val totalBitsPerCh = (dsdDataBytes / channels) * 8L
        val durMs = (totalBitsPerCh * 1000L) / dsdSampleRate

        return DffInterleavedDecodedStream(
            reader = reader,
            dataStartOffset = dsdDataStart,
            dataByteLength = dsdDataBytes,
            sourceSampleRate = dsdSampleRate,
            outputSampleRate = outRate,
            sourceChannels = channels,
            bytesPerPcmSample = decimationBits / 8,
            durationMs = durMs
        )
    }

    // =========================================================================
    // Stream Implementations
    // =========================================================================

    private class LinearPcmDecodedStream(
        private val reader: SeekableByteReader,
        override val containerName: String,
        private val dataStartOffset: Long,
        private val dataByteLength: Long,
        override val sourceSampleRate: Int,
        override val sourceChannels: Int,
        private val bytesPerSample: Int,
        private val encoding: PcmSampleEncoding
    ) : DecodedS16leStream {

        override val outputSampleRate: Int = sourceSampleRate
        private val inFrameSize: Int = sourceChannels * bytesPerSample
        private val totalFrames: Long = dataByteLength / inFrameSize
        override val durationMs: Long = if (sourceSampleRate > 0) (totalFrames * 1000L) / sourceSampleRate else 0L

        private var currentFrame: Long = 0L
        private val rawScratch = ByteArray(16384)
        private val multiChS16Scratch = ByteArray(16384)

        init {
            reader.position = dataStartOffset
        }

        override fun seekToMs(targetMs: Long) {
            val targetFrame = ((targetMs.coerceAtLeast(0L) * sourceSampleRate) / 1000L).coerceIn(0L, totalFrames)
            currentFrame = targetFrame
            reader.position = dataStartOffset + targetFrame * inFrameSize
        }

        override fun readStereoS16le(outBuffer: ByteArray, offset: Int, maxBytes: Int): Int {
            val maxOutFrames = (maxBytes / 4).coerceAtLeast(0)
            if (maxOutFrames == 0 || currentFrame >= totalFrames) return -1

            val maxChunkFrames = minOf(
                maxOutFrames.toLong(),
                totalFrames - currentFrame,
                (rawScratch.size / inFrameSize).toLong(),
                (multiChS16Scratch.size / (sourceChannels * 2)).toLong()
            ).toInt()
            if (maxChunkFrames <= 0) return -1

            val bytesToRead = maxChunkFrames * inFrameSize
            val got = readFully(reader, rawScratch, 0, bytesToRead)
            val framesGot = got / inFrameSize
            if (framesGot <= 0) return -1
            currentFrame += framesGot

            // Convert each sample to 16-bit signed LE in multiChS16Scratch
            val totalSamples = framesGot * sourceChannels
            var s = 0
            while (s < totalSamples) {
                val srcOff = s * bytesPerSample
                val s16 = decodeSampleToS16(rawScratch, srcOff, bytesPerSample, encoding)
                val dstOff = s * 2
                multiChS16Scratch[dstOff] = (s16 and 0xFF).toByte()
                multiChS16Scratch[dstOff + 1] = ((s16 shr 8) and 0xFF).toByte()
                s++
            }

            val s16ValidBytes = totalSamples * 2
            return if (sourceChannels == 2 && offset == 0) {
                multiChS16Scratch.copyInto(outBuffer, 0, 0, s16ValidBytes)
                s16ValidBytes
            } else {
                val tempOut = if (offset == 0) outBuffer else ByteArray(framesGot * 4)
                val written = AudioFormatRegistry.downmixPcm16leToStereo(
                    inBuffer = multiChS16Scratch,
                    inValidBytes = s16ValidBytes,
                    inChannels = sourceChannels,
                    outStereoBuffer = tempOut
                )
                if (offset != 0 && written > 0) {
                    tempOut.copyInto(outBuffer, offset, 0, written)
                }
                written
            }
        }

        override fun close() = reader.close()
    }

    private class DsfPlanarDecodedStream(
        private val reader: SeekableByteReader,
        private val dataStartOffset: Long,
        private val dataByteLength: Long,
        override val sourceSampleRate: Int,
        override val outputSampleRate: Int,
        override val sourceChannels: Int,
        private val blockSizePerChannel: Int,
        private val bytesPerPcmSample: Int,
        override val durationMs: Long,
        private val lsbFirst: Boolean
    ) : DecodedS16leStream {
        override val containerName: String = "DSD"
        private val superBlockBytes: Int = blockSizePerChannel * sourceChannels
        private val totalSuperBlocks: Long = dataByteLength / superBlockBytes
        private var currentSuperBlock: Long = 0L
        private val blockBuf = ByteArray(superBlockBytes)

        // Simple 2-pole Butterworth-like Ultrasonic Low-Pass (~22 kHz @ 88.2 kHz) to remove PDM HF noise
        private var lpL1 = 0f; private var lpL2 = 0f
        private var lpR1 = 0f; private var lpR2 = 0f

        init {
            reader.position = dataStartOffset
            if (lsbFirst) { /* Popcount over a full byte is bit-order invariant */ }
        }

        override fun seekToMs(targetMs: Long) {
            if (durationMs <= 0L || totalSuperBlocks <= 0L) return
            val frac = (targetMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 0.999)
            currentSuperBlock = (totalSuperBlocks * frac).toLong().coerceIn(0L, totalSuperBlocks)
            reader.position = dataStartOffset + currentSuperBlock * superBlockBytes
            lpL1 = 0f; lpL2 = 0f; lpR1 = 0f; lpR2 = 0f
        }

        override fun readStereoS16le(outBuffer: ByteArray, offset: Int, maxBytes: Int): Int {
            val pcmFramesPerBlock = blockSizePerChannel / bytesPerPcmSample
            val bytesPerBlockOut = pcmFramesPerBlock * 4
            if (maxBytes < 4 || currentSuperBlock >= totalSuperBlocks) return -1

            var outWritten = 0
            val totalBits = (bytesPerPcmSample * 8).toFloat()
            val halfBits = totalBits * 0.5f
            val normScale = (28000f / halfBits)

            while (currentSuperBlock < totalSuperBlocks && outWritten + bytesPerBlockOut <= maxBytes) {
                val got = readFully(reader, blockBuf, 0, superBlockBytes)
                if (got < superBlockBytes) break
                currentSuperBlock++

                val ch1Offset = if (sourceChannels >= 2) blockSizePerChannel else 0
                var b = 0
                while (b < pcmFramesPerBlock) {
                    val byteOff = b * bytesPerPcmSample
                    var onesL = 0
                    var onesR = 0
                    var k = 0
                    while (k < bytesPerPcmSample) {
                        onesL += POPCOUNT_8[blockBuf[byteOff + k].toInt() and 0xFF]
                        onesR += POPCOUNT_8[blockBuf[ch1Offset + byteOff + k].toInt() and 0xFF]
                        k++
                    }
                    val rawL = (onesL - halfBits) * normScale
                    val rawR = (onesR - halfBits) * normScale

                    // 2-stage cascade 1-pole ultrasonic smoothing (fc ~ 24 kHz @ 88.2 kHz, zero ringing)
                    lpL1 += 0.52f * (rawL - lpL1)
                    lpL2 += 0.52f * (lpL1 - lpL2)
                    lpR1 += 0.52f * (rawR - lpR1)
                    lpR2 += 0.52f * (lpR1 - lpR2)

                    val sL = lpL2.toInt().coerceIn(-32768, 32767)
                    val sR = lpR2.toInt().coerceIn(-32768, 32767)

                    val dst = offset + outWritten
                    outBuffer[dst] = (sL and 0xFF).toByte()
                    outBuffer[dst + 1] = ((sL shr 8) and 0xFF).toByte()
                    outBuffer[dst + 2] = (sR and 0xFF).toByte()
                    outBuffer[dst + 3] = ((sR shr 8) and 0xFF).toByte()
                    outWritten += 4
                    b++
                }
            }

            return if (outWritten == 0) -1 else outWritten
        }

        override fun close() = reader.close()
    }

    private class DffInterleavedDecodedStream(
        private val reader: SeekableByteReader,
        private val dataStartOffset: Long,
        private val dataByteLength: Long,
        override val sourceSampleRate: Int,
        override val outputSampleRate: Int,
        override val sourceChannels: Int,
        private val bytesPerPcmSample: Int,
        override val durationMs: Long
    ) : DecodedS16leStream {
        override val containerName: String = "DFF"
        private val inChunkUnit = sourceChannels * bytesPerPcmSample
        private val totalUnits = dataByteLength / inChunkUnit
        private var currentUnit = 0L
        private val rawBuf = ByteArray(inChunkUnit * 512)
        private var lpL1 = 0f; private var lpL2 = 0f
        private var lpR1 = 0f; private var lpR2 = 0f

        init {
            reader.position = dataStartOffset
        }

        override fun seekToMs(targetMs: Long) {
            if (durationMs <= 0L || totalUnits <= 0L) return
            val frac = (targetMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 0.999)
            currentUnit = (totalUnits * frac).toLong().coerceIn(0L, totalUnits)
            reader.position = dataStartOffset + currentUnit * inChunkUnit
            lpL1 = 0f; lpL2 = 0f; lpR1 = 0f; lpR2 = 0f
        }

        override fun readStereoS16le(outBuffer: ByteArray, offset: Int, maxBytes: Int): Int {
            val maxUnits = minOf(
                (maxBytes / 4).toLong(),
                totalUnits - currentUnit,
                512L
            ).toInt()
            if (maxUnits <= 0) return -1

            val got = readFully(reader, rawBuf, 0, maxUnits * inChunkUnit)
            val unitsGot = got / inChunkUnit
            if (unitsGot <= 0) return -1
            currentUnit += unitsGot

            val halfBits = (bytesPerPcmSample * 8) * 0.5f
            val normScale = 28000f / halfBits
            var outWritten = 0

            var u = 0
            while (u < unitsGot) {
                val base = u * inChunkUnit
                var onesL = 0
                var onesR = 0
                var k = 0
                while (k < bytesPerPcmSample) {
                    val step = base + k * sourceChannels
                    onesL += POPCOUNT_8[rawBuf[step].toInt() and 0xFF]
                    val rIdx = if (sourceChannels >= 2) step + 1 else step
                    onesR += POPCOUNT_8[rawBuf[rIdx].toInt() and 0xFF]
                    k++
                }
                val rawL = (onesL - halfBits) * normScale
                val rawR = (onesR - halfBits) * normScale
                lpL1 += 0.52f * (rawL - lpL1)
                lpL2 += 0.52f * (lpL1 - lpL2)
                lpR1 += 0.52f * (rawR - lpR1)
                lpR2 += 0.52f * (lpR1 - lpR2)

                val sL = lpL2.toInt().coerceIn(-32768, 32767)
                val sR = lpR2.toInt().coerceIn(-32768, 32767)
                val dst = offset + outWritten
                outBuffer[dst] = (sL and 0xFF).toByte()
                outBuffer[dst + 1] = ((sL shr 8) and 0xFF).toByte()
                outBuffer[dst + 2] = (sR and 0xFF).toByte()
                outBuffer[dst + 3] = ((sR shr 8) and 0xFF).toByte()
                outWritten += 4
                u++
            }
            return outWritten
        }

        override fun close() = reader.close()
    }

    // =========================================================================
    // Low-Level Sample & Binary Helpers
    // =========================================================================

    private fun decodeSampleToS16(
        buf: ByteArray,
        off: Int,
        bps: Int,
        encoding: PcmSampleEncoding
    ): Int {
        return when (encoding) {
            PcmSampleEncoding.INT_BE -> when (bps) {
                1 -> (buf[off].toInt() shl 8) // Signed 8-bit AIFF/AU -> 16-bit
                2 -> (buf[off].toInt() shl 8) or (buf[off + 1].toInt() and 0xFF)
                3 -> (buf[off].toInt() shl 8) or (buf[off + 1].toInt() and 0xFF)
                else -> (buf[off].toInt() shl 8) or (buf[off + 1].toInt() and 0xFF)
            }
            PcmSampleEncoding.INT_LE -> when (bps) {
                1 -> ((buf[off].toInt() and 0xFF) - 128) shl 8
                2 -> (buf[off + 1].toInt() shl 8) or (buf[off].toInt() and 0xFF)
                3 -> (buf[off + 2].toInt() shl 8) or (buf[off + 1].toInt() and 0xFF)
                else -> (buf[off + 3].toInt() shl 8) or (buf[off + 2].toInt() and 0xFF)
            }
            PcmSampleEncoding.FLOAT32_BE -> {
                val bits = ((buf[off].toInt() and 0xFF) shl 24) or
                    ((buf[off + 1].toInt() and 0xFF) shl 16) or
                    ((buf[off + 2].toInt() and 0xFF) shl 8) or
                    (buf[off + 3].toInt() and 0xFF)
                val f = Float.fromBits(bits)
                if (!f.isFinite()) 0 else (f * 32767.0f).toInt().coerceIn(-32768, 32767)
            }
            PcmSampleEncoding.FLOAT32_LE -> {
                val bits = (buf[off].toInt() and 0xFF) or
                    ((buf[off + 1].toInt() and 0xFF) shl 8) or
                    ((buf[off + 2].toInt() and 0xFF) shl 16) or
                    ((buf[off + 3].toInt() and 0xFF) shl 24)
                val f = Float.fromBits(bits)
                if (!f.isFinite()) 0 else (f * 32767.0f).toInt().coerceIn(-32768, 32767)
            }
            PcmSampleEncoding.FLOAT64_BE -> {
                val bits = readS64Be(buf, off)
                val d = Double.fromBits(bits)
                if (!d.isFinite()) 0 else (d * 32767.0).toInt().coerceIn(-32768, 32767)
            }
            PcmSampleEncoding.FLOAT64_LE -> {
                val bits = readS64Le(buf, off)
                val d = Double.fromBits(bits)
                if (!d.isFinite()) 0 else (d * 32767.0).toInt().coerceIn(-32768, 32767)
            }
            PcmSampleEncoding.ULAW -> decodeUlaw(buf[off].toInt() and 0xFF)
            PcmSampleEncoding.ALAW -> decodeAlaw(buf[off].toInt() and 0xFF)
        }
    }

    private fun decodeUlaw(uVal: Int): Int {
        val u = uVal.inv() and 0xFF
        val sign = u and 0x80
        val exponent = (u shr 4) and 0x07
        val mantissa = u and 0x0F
        var sample = ((mantissa shl 3) + 0x84) shl exponent
        sample -= 0x84
        return (if (sign != 0) -sample else sample).coerceIn(-32768, 32767)
    }

    private fun decodeAlaw(aVal: Int): Int {
        val a = aVal xor 0x55
        var t = (a and 0x0F) shl 4
        val seg = (a and 0x70) shr 4
        t = when (seg) {
            0 -> t + 8
            1 -> t + 0x108
            else -> (t + 0x108) shl (seg - 1)
        }
        return (if ((a and 0x80) != 0) t else -t).coerceIn(-32768, 32767)
    }

    private fun parseIeee80Extended(buf: ByteArray, off: Int): Double {
        val expon = ((buf[off].toInt() and 0x7F) shl 8) or (buf[off + 1].toInt() and 0xFF)
        val hiMant = readU32Be(buf, off + 2)
        val loMant = readU32Be(buf, off + 6)
        if (expon == 0 && hiMant == 0L && loMant == 0L) return 0.0
        val f = hiMant.toDouble() * 2.0.pow(expon - 16383 - 31) +
            loMant.toDouble() * 2.0.pow(expon - 16383 - 63)
        return if ((buf[off].toInt() and 0x80) != 0) -f else f
    }

    private fun readFully(reader: SeekableByteReader, buf: ByteArray, offset: Int, count: Int): Int {
        var total = 0
        while (total < count) {
            val n = reader.read(buf, offset + total, count - total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    private fun ascii(buf: ByteArray, off: Int, len: Int): String {
        val chars = CharArray(len)
        for (i in 0 until len) {
            chars[i] = (buf[off + i].toInt() and 0xFF).toChar()
        }
        return chars.concatToString()
    }

    private fun readU16Be(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun readU32Be(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFFL) shl 24) or
            ((b[o + 1].toLong() and 0xFFL) shl 16) or
            ((b[o + 2].toLong() and 0xFFL) shl 8) or
            (b[o + 3].toLong() and 0xFFL)

    private fun readS32Le(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun readS64Be(b: ByteArray, o: Int): Long =
        (readU32Be(b, o) shl 32) or (readU32Be(b, o + 4) and 0xFFFFFFFFL)

    private fun readS64Le(b: ByteArray, o: Int): Long {
        val lo = readS32Le(b, o).toLong() and 0xFFFFFFFFL
        val hi = readS32Le(b, o + 4).toLong()
        return (hi shl 32) or lo
    }
}
