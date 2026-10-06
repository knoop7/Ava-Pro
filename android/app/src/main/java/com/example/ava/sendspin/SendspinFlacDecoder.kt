package com.example.ava.sendspin

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * FLAC decoder backed by Android's built-in [MediaCodec] `audio/flac` decoder
 * (available on API 21+).
 *
 * Sendspin's [`stream/start`](https://github.com/Sendspin/spec#server--client-streamstart-player-object)
 * delivers FLAC by sending a `codec_header` (base64-encoded STREAMINFO) on the
 * `player` object and then a sequence of binary FLAC frames on
 * [type-4 audio chunks](https://github.com/Sendspin/spec#server--client-audio-chunks-binary).
 *
 * Design notes:
 *  - The decoder is **strictly best-effort**. Any configure/decode failure is
 *    logged and surfaces as an empty `ByteArray`. The caller (SendspinClient)
 *    treats empty output as a soft error and continues — the user gets a brief
 *    audio dropout, not a crash.
 *  - When `codec_header` is omitted on `stream/start` (valid per spec), we
 *    synthesize a minimal STREAMINFO block from `sample_rate`, `channels`, and
 *    `bit_depth` — matching [sendspin-cli](https://github.com/Sendspin/sendspin-cli).
 *  - If a `codec_header` is provided it is coerced into the canonical 42-byte
 *    `"fLaC" + STREAMINFO` shape Android's decoder expects. Configure failures
 *    still surface to the caller for optional `stream/request-format` fallback.
 *  - Output is interleaved 16-bit little-endian PCM (matching the rest of the
 *    Sendspin audio pipeline). MediaCodec emits raw PCM in the device's native
 *    endianness; we don't byte-swap here because Android is little-endian on
 *    every supported architecture (arm64, armeabi-v7a, x86, x86_64).
 */
class SendspinFlacDecoder(
    private val sampleRate: Int,
    private val channels: Int,
    bitDepth: Int,
    codecHeaderBase64: String? = null,
    embeddedStreamHeader: ByteArray? = null,
) {
    private var codec: MediaCodec? = null
    private val codecLock = Any()
    private val outputInfo = MediaCodec.BufferInfo()
    private var released = false
    private var lastDecodeErrorLogMs = 0L
    private var lastNativeFallbackLogMs = 0L

    @Volatile
    private var jflacFallback = false

    @Volatile
    private var nativeProducedOutput = false

    /** True once native decode returned empty PCM — stop feeding swcodec to avoid log spam. */
    private var nativeDecodeUnreliable = false
    private var nativeProbePackets = 0

    /**
     * Per-input sequence carried through MediaCodec as `presentationTimeUs`, so
     * each output can be matched to the frame that produced it. Without this
     * the pipeline silently lagged: a 5ms output poll missed, the next call
     * collected the *previous* frame's PCM and the caller wrote it under the
     * *current* chunk's timestamp. Every miss added one FLAC frame (~93ms at
     * 44.1k/4096) of content lag while the DAC lock still read "on time" —
     * the standing ~0.5s Ava sat behind AirPlay / web players.
     */
    private var nextInputSeq = 1L
    private var lastStaleDropLogMs = 0L

    /** PCM bit depth the decoder will emit (defaults to 16 until format is known). */
    @Volatile
    var outputPcmBitDepth: Int = 16
        private set

    /** [android.media.AudioFormat] encoding from MediaCodec output (e.g. PCM_16BIT, PCM_FLOAT). */
    @Volatile
    var outputPcmEncoding: Int = AudioFormat.ENCODING_PCM_16BIT
        private set

    private val csd0 = resolveCsd0(sampleRate, channels, bitDepth, codecHeaderBase64, embeddedStreamHeader)

    init {
        try {
            codec = startMediaCodec()
        } catch (t: Throwable) {
            Log.w(TAG, "Native MediaCodec flac unavailable; falling back to jflac: ${t.message}")
            releaseMediaCodecForFallback()
        }
    }

    private fun startMediaCodec(): MediaCodec {
        val mc = MediaCodec.createDecoderByType(MIME)
        mc.configure(buildInputFormat(), null, null, 0)
        mc.start()
        refreshOutputFormat(mc.outputFormat)
        return mc
    }

    private fun buildInputFormat(): MediaFormat =
        MediaFormat.createAudioFormat(MIME, sampleRate, channels).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
            // Synthetic STREAMINFO leaves max-frame-size unset, so the decoder may
            // default to a small input buffer and drop large FLAC frames
            // (e.g. 48kHz/24-bit). Request a generous buffer to cover the worst case.
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        }

    /**
     * Feed an encoded FLAC frame and pump out any available decoded PCM.
     *
     * Note: FLAC's frame boundary varies (typically ~85 ms at 48 kHz with
     * 4096-sample blocks). One input may produce zero or many output buffers
     * depending on codec internal state, so we drain the output side until
     * `INFO_TRY_AGAIN_LATER`.
     */
    fun decode(input: ByteArray): ByteArray = synchronized(codecLock) {
        if (released || input.isEmpty()) return EMPTY
        if (!isLikelyFlacAccessUnit(input)) return EMPTY
        if (jflacFallback) return decodeWithJFlac(input)

        val mc = codec
        if (mc == null || nativeDecodeUnreliable) {
            return decodeWithJFlac(input)
        }

        // Native already failed once this session — don't keep feeding swcodec.
        if (!nativeProducedOutput && nativeProbePackets > 0) {
            val jflacPcm = decodeWithJFlac(input)
            nativeProbePackets++
            if (jflacPcm.isNotEmpty() || nativeProbePackets >= NATIVE_PROBE_PACKETS) {
                switchToJFlacFallback(
                    reason = if (jflacPcm.isNotEmpty()) {
                        "native FLAC missing STREAMINFO"
                    } else {
                        "native FLAC produced no PCM after $nativeProbePackets frames"
                    },
                )
            }
            return jflacPcm
        }

        try {
            val seq = nextInputSeq++
            val pcm = if (queueInputFrame(mc, input, seq)) {
                collectDecodedPcm(mc, seq)
            } else {
                EMPTY
            }
            if (pcm.isNotEmpty()) {
                nativeProducedOutput = true
                nativeProbePackets = 0
                return pcm
            }

            // Native swcodec accepted the frame but has no STREAMINFO (flush/start race or bad
            // csd-0). Stop feeding it — each frame spams "no streaminfo metadata block".
            if (!nativeProducedOutput) {
                nativeProbePackets++
                val jflacPcm = decodeWithJFlac(input)
                if (jflacPcm.isNotEmpty() || nativeProbePackets >= NATIVE_PROBE_PACKETS) {
                    switchToJFlacFallback(
                        reason = if (jflacPcm.isNotEmpty()) {
                            "native FLAC missing STREAMINFO"
                        } else {
                            "native FLAC produced no PCM after $nativeProbePackets frames"
                        },
                    )
                }
                return jflacPcm
            }
            return EMPTY
        } catch (e: Throwable) {
            switchToJFlacFallback(reason = "native FLAC decode failed: ${e.message}")
            return decodeWithJFlac(input)
        }
    }

    /** @return true when [input] was handed to the codec tagged with [seq]. */
    private fun queueInputFrame(mc: MediaCodec, input: ByteArray, seq: Long): Boolean {
        val inIdx = mc.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (inIdx < 0) return false
        val inBuf = mc.getInputBuffer(inIdx) ?: return false
        inBuf.clear()
        if (inBuf.capacity() < input.size) {
            Log.w(
                TAG,
                "FLAC input buffer too small (${inBuf.capacity()} < ${input.size}); dropping frame",
            )
            mc.queueInputBuffer(inIdx, 0, 0, 0, 0)
            return false
        }
        inBuf.put(input)
        mc.queueInputBuffer(inIdx, 0, input.size, seq, 0)
        return true
    }

    private fun switchToJFlacFallback(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastNativeFallbackLogMs >= 5_000L) {
            lastNativeFallbackLogMs = now
            Log.i(TAG, "Switching to jflac ($reason)")
        }
        nativeDecodeUnreliable = true
        releaseMediaCodecForFallback()
    }

    private fun releaseMediaCodecForFallback() {
        synchronized(codecLock) {
            codec?.let { mc ->
                runCatching { mc.stop() }
                runCatching { mc.release() }
            }
            codec = null
            jflacFallback = true
        }
    }

    private fun decodeWithJFlac(input: ByteArray): ByteArray {
        try {
            // Concatenate csd0 and the single frame block to feed to jflac
            val fullStreamBytes = ByteArray(csd0.size + input.size)
            System.arraycopy(csd0, 0, fullStreamBytes, 0, csd0.size)
            System.arraycopy(input, 0, fullStreamBytes, csd0.size, input.size)

            val stream = java.io.ByteArrayInputStream(fullStreamBytes)
            val decoder = org.jflac.FLACDecoder(stream)
            val out = ByteArrayOutputStream()

            decoder.addPCMProcessor(object : org.jflac.PCMProcessor {
                override fun processStreamInfo(info: org.jflac.metadata.StreamInfo) {
                    refreshOutputFormatJFlac(info.bitsPerSample)
                }

                override fun processPCM(pcm: org.jflac.util.ByteData) {
                    out.write(pcm.data, 0, pcm.len)
                }
            })

            decoder.decode()
            return out.toByteArray()
        } catch (e: Exception) {
            val now = System.currentTimeMillis()
            if (now - lastDecodeErrorLogMs >= 2_000L) {
                lastDecodeErrorLogMs = now
                Log.w(TAG, "jflac decode exception (corrupt frame): ${e.message}")
            }
            return EMPTY
        }
    }

    private fun refreshOutputFormatJFlac(bitsPerSample: Int) {
        val encoding = when (bitsPerSample) {
            16 -> AudioFormat.ENCODING_PCM_16BIT
            24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            32 -> AudioFormat.ENCODING_PCM_32BIT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }
        outputPcmEncoding = encoding
        outputPcmBitDepth = bitsPerSample
    }

    /**
     * Reset decoder state after `stream/clear` or track restart.
     *
     * Do **not** call [MediaCodec.flush] for FLAC: Android's swcodec drops STREAMINFO on
     * flush and logs `decodeOneFrame: no streaminfo metadata block` on every subsequent
     * frame until csd-0 is resubmitted. Reconfigure instead.
     */
    fun reset() {
        synchronized(codecLock) {
            if (released) return
            if (jflacFallback) return
            try {
                codec?.let { mc ->
                    drainPendingOutput(mc)
                    mc.stop()
                    mc.release()
                }
                codec = startMediaCodec()
                nativeProducedOutput = false
                nativeDecodeUnreliable = false
                nativeProbePackets = 0
            } catch (e: Throwable) {
                Log.w(TAG, "FLAC reset failed; switching to jflac", e)
                switchToJFlacFallback(reason = "reset failed")
            }
        }
    }

    fun release() {
        synchronized(codecLock) {
            if (released) return
            released = true
            try {
                codec?.let { mc ->
                    drainPendingOutput(mc)
                    mc.stop()
                    mc.release()
                }
                codec = null
            } catch (e: Throwable) {
                Log.w(TAG, "FLAC release failed", e)
            }
        }
    }

    /**
     * Wait for the PCM of input [seq] and return only that.
     *
     * One FLAC frame decodes in well under a millisecond; the wait is for the
     * codec thread hand-off, so a real deadline (not a 5ms poll) is what keeps
     * output paired with its input. Output tagged with an older seq belongs to
     * a frame whose call already timed out and was skipped by the caller — it
     * is dropped here rather than written late under the current timestamp.
     */
    private fun collectDecodedPcm(mc: MediaCodec, seq: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val deadlineNs = System.nanoTime() + OUTPUT_WAIT_TOTAL_US * 1_000L
        while (true) {
            val remainingUs = (deadlineNs - System.nanoTime()) / 1_000L
            if (remainingUs <= 0L) break
            val outIdx = mc.dequeueOutputBuffer(
                outputInfo,
                remainingUs.coerceAtMost(OUTPUT_WAIT_STEP_US),
            )
            when {
                outIdx >= 0 -> {
                    val outSeq = outputInfo.presentationTimeUs
                    val outBuf = mc.getOutputBuffer(outIdx)
                    if (outBuf != null && outputInfo.size > 0) {
                        if (outSeq >= seq) {
                            val chunk = ByteArray(outputInfo.size)
                            outBuf.position(outputInfo.offset)
                            outBuf.get(chunk, 0, outputInfo.size)
                            out.write(chunk)
                        } else {
                            noteStaleOutputDropped(seq - outSeq)
                        }
                    }
                    mc.releaseOutputBuffer(outIdx, false)
                    if (outputInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                    if (outSeq >= seq) break
                }
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* wait until deadline */ }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    refreshOutputFormat(mc.outputFormat)
                }
                outIdx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> { /* keep draining */ }
                else -> break
            }
        }
        return out.toByteArray()
    }

    private fun noteStaleOutputDropped(framesBehind: Long) {
        val now = System.currentTimeMillis()
        if (now - lastStaleDropLogMs < 5_000L) return
        lastStaleDropLogMs = now
        Log.w(TAG, "FLAC output $framesBehind frame(s) behind its input; dropped stale PCM")
    }

    /** Drain all decoded output still queued inside MediaCodec. */
    private fun drainPendingOutput(mc: MediaCodec) {
        while (true) {
            val outIdx = mc.dequeueOutputBuffer(outputInfo, 0L)
            when {
                outIdx >= 0 -> {
                    mc.releaseOutputBuffer(outIdx, false)
                    if (outputInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                }
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    refreshOutputFormat(mc.outputFormat)
                }
                else -> break
            }
        }
    }

    private fun isLikelyFlacAccessUnit(data: ByteArray): Boolean {
        if (hasFlacStreamMarker(data)) return true
        return data.size >= 2 &&
            data[0] == 0xFF.toByte() &&
            (data[1].toInt() and 0xFE) == 0xF8
    }

    private fun refreshOutputFormat(format: MediaFormat) {
        if (!format.containsKey(MediaFormat.KEY_PCM_ENCODING)) return
        val encoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        outputPcmEncoding = encoding
        outputPcmBitDepth = pcmEncodingToBitDepth(encoding)
        Log.d(TAG, "FLAC decoder output encoding=$encoding bitDepth=$outputPcmBitDepth")
    }

    companion object {
        private const val TAG = "SendspinFlacDecoder"
        private const val MIME = "audio/flac"
        private const val INPUT_TIMEOUT_US = 20_000L
        /** Per-dequeue slice; loop until [OUTPUT_WAIT_TOTAL_US] for this frame's PCM. */
        private const val OUTPUT_WAIT_STEP_US = 10_000L
        /** Well above sw FLAC decode (<1ms) + thread hand-off, well under one frame (~93ms). */
        private const val OUTPUT_WAIT_TOTAL_US = 60_000L
        // Worst-case FLAC frame (48kHz/24-bit stereo, 4096-sample block) is well
        // under 64KB. Covers servers that send large frames the synthetic
        // STREAMINFO can't size the input buffer for.
        private const val MAX_INPUT_SIZE = 65_536
        // Packets to feed the native decoder before concluding it will never produce PCM.
        private const val NATIVE_PROBE_PACKETS = 2
        private val EMPTY = ByteArray(0)
        private val FLAC_MAGIC = byteArrayOf(
            'f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte()
        )

        private const val STREAM_HEADER_BYTES = 42

        /**
         * Probe whether this device can instantiate an `audio/flac` decoder.
         * Call before advertising FLAC in `client/hello.supported_formats`.
         */
        fun isDecodeAvailable(): Boolean = runCatching {
            val probe = MediaCodec.createDecoderByType(MIME)
            probe.release()
            true
        }.getOrDefault(false)

        /** True when [data] begins with the FLAC stream marker (`fLaC`). */
        fun hasFlacStreamMarker(data: ByteArray): Boolean =
            hasFlacStreamMarker(data, 0, data.size)

        fun hasFlacStreamMarker(data: ByteArray, offset: Int, length: Int): Boolean =
            length >= 4 &&
                data[offset] == 'f'.code.toByte() &&
                data[offset + 1] == 'L'.code.toByte() &&
                data[offset + 2] == 'a'.code.toByte() &&
                data[offset + 3] == 'C'.code.toByte()

        /**
         * Strip a leading 42-byte FLAC stream header when the server embeds it in
         * the first binary audio chunk instead of `codec_header`.
         */
        fun stripLeadingStreamHeaderIfPresent(data: ByteArray): ByteArray {
            if (data.size <= STREAM_HEADER_BYTES || !hasFlacStreamMarker(data)) return data
            return data.copyOfRange(STREAM_HEADER_BYTES, data.size)
        }

        /**
         * Build csd-0 for MediaCodec: prefer explicit `codec_header`, then an
         * embedded `fLaC` prefix, else synthesize STREAMINFO from stream/start.
         */
        fun resolveCsd0(
            sampleRate: Int,
            channels: Int,
            bitDepth: Int,
            codecHeaderBase64: String?,
            embeddedStreamHeader: ByteArray? = null
        ): ByteArray {
            if (!codecHeaderBase64.isNullOrBlank()) {
                return canonicaliseStreamInfo(Base64.decode(codecHeaderBase64, Base64.DEFAULT))
            }
            if (embeddedStreamHeader != null && embeddedStreamHeader.size >= STREAM_HEADER_BYTES &&
                hasFlacStreamMarker(embeddedStreamHeader)
            ) {
                Log.i(TAG, "Using FLAC stream header embedded in first audio chunk")
                return embeddedStreamHeader.copyOf(STREAM_HEADER_BYTES)
            }
            Log.i(
                TAG,
                "No codec_header on stream/start; synthesizing FLAC STREAMINFO " +
                    "($sampleRate Hz, $channels ch, $bitDepth bit)"
            )
            return buildSyntheticStreamInfo(sampleRate, channels, bitDepth)
        }

        /**
         * Minimal FLAC STREAMINFO (RFC 9639) from `stream/start` parameters.
         * Matches sendspin-cli `_generate_streaminfo`.
         */
        fun buildSyntheticStreamInfo(
            sampleRate: Int,
            channels: Int,
            bitDepth: Int,
            blockSize: Int = 4096
        ): ByteArray {
            val streamInfo = ByteArray(34)
            streamInfo[0] = (blockSize shr 8).toByte()
            streamInfo[1] = blockSize.toByte()
            streamInfo[2] = (blockSize shr 8).toByte()
            streamInfo[3] = blockSize.toByte()
            val chMinus1 = (channels - 1).coerceIn(0, 7)
            val bpsMinus1 = (bitDepth - 1).coerceIn(0, 31)
            val packed = (sampleRate.toLong() shl 12) or
                (chMinus1.toLong() shl 9) or
                (bpsMinus1.toLong() shl 4)
            streamInfo[10] = ((packed shr 24) and 0xFF).toByte()
            streamInfo[11] = ((packed shr 16) and 0xFF).toByte()
            streamInfo[12] = ((packed shr 8) and 0xFF).toByte()
            streamInfo[13] = (packed and 0xFF).toByte()
            return FLAC_MAGIC + byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x22) + streamInfo
        }

        /**
         * Coerce arbitrary STREAMINFO shapes into the 42-byte form Android wants
         * for csd-0: `"fLaC"` magic + 4-byte metadata-block header (last=1, type=0,
         * size=34) + 34-byte STREAMINFO body.
         */
        private fun canonicaliseStreamInfo(raw: ByteArray): ByteArray {
            if (raw.size == STREAM_HEADER_BYTES && hasFlacStreamMarker(raw)) {
                return raw
            }
            if (raw.size == 38) {
                return FLAC_MAGIC + raw
            }
            if (raw.size == 34) {
                return FLAC_MAGIC + byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x22) + raw
            }
            Log.w(TAG, "Unexpected FLAC codec_header size=${raw.size}; passing through")
            return raw
        }

        private fun pcmEncodingToBitDepth(encoding: Int): Int = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> 16
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
            AudioFormat.ENCODING_PCM_32BIT -> 32
            AudioFormat.ENCODING_PCM_FLOAT -> 32
            else -> 16
        }
    }
}
