package com.example.ava.esphome.voicesatellite

import com.example.ava.audio.AudioEnergy
import com.google.protobuf.ByteString

/** Bounded, immutable PCM frames. Onset decisions never destroy earlier speech. */
internal class WakePreRollBuffer(private val maxBytes: Int = 6_000 * 32) {
    init { require(maxBytes > 0 && maxBytes % 2 == 0) }
    private val frames = ArrayDeque<ByteString>()
    private var bytes = 0
    private var legacyStart: Int? = null
    private var minimumRms = Float.MAX_VALUE
    private var speechRunMs = 0

    fun append(audio: ByteString) {
        if (audio.size() < 2 || audio.size() % 2 != 0) return
        frames.addLast(audio)
        bytes += audio.size()
        while (bytes > maxBytes && frames.size > 1) {
            val dropped = frames.removeFirst().size()
            bytes -= dropped
            legacyStart = legacyStart?.let { (it - dropped).coerceAtLeast(0) }
        }
        if (bytes > maxBytes) {
            val dropped = bytes - maxBytes
            frames.addFirst(frames.removeFirst().substring(dropped))
            bytes = maxBytes
            legacyStart = legacyStart?.let { (it - dropped).coerceAtLeast(0) }
        }
        if (legacyStart != null) return
        val rms = AudioEnergy.pcm16LeFloatRms(audio.asReadOnlyByteBuffer())
        minimumRms = minOf(minimumRms, rms)
        val speech = rms >= 0.02f || (rms >= 0.008f && rms >= minimumRms * 3f)
        speechRunMs = if (speech) speechRunMs + audio.size() / 32 else 0
        if (speechRunMs < 180) return
        val keep = (speechRunMs + 300) * 32
        var skip = 0
        for (frame in frames) {
            if (bytes - skip - frame.size() < keep) break
            skip += frame.size()
        }
        legacyStart = skip
    }

    fun snapshot() = Snapshot(frames.toList(), bytes, legacyStart)

    fun clear() {
        frames.clear()
        bytes = 0
        legacyStart = null
        minimumRms = Float.MAX_VALUE
        speechRunMs = 0
    }

    data class Snapshot(val frames: List<ByteString>, val bytes: Int, val legacyStart: Int?) {
        val needsSpeechRescue: Boolean get() = bytes > 0 && legacyStart != 0

        fun pcm(): ByteArray {
            val out = ByteArray(bytes)
            var offset = 0
            for (frame in frames) {
                frame.copyTo(out, 0, offset, frame.size())
                offset += frame.size()
            }
            return out
        }

        fun replay(speechStartByte: Int? = null): List<ByteString> {
            val start = listOfNotNull(legacyStart, speechStartByte).minOrNull() ?: return emptyList()
            require(start in 0..bytes && start % 2 == 0)
            var skip = start
            return buildList {
                for (frame in frames) {
                    if (skip >= frame.size()) {
                        skip -= frame.size()
                    } else {
                        add(if (skip == 0) frame else frame.substring(skip))
                        skip = 0
                    }
                }
            }
        }
    }
}

/** Accessed under the pending-audio lock; prevents RUN_START from overtaking pre-roll. */
internal class MicAudioDeliveryGate {
    var generation: Long = 0; private set
    private var ready = false
    private var preRollPending = false
    private var closed = false
    val canBuffer: Boolean get() = !closed
    val canFlush: Boolean get() = !closed && ready && !preRollPending

    fun onRunStart() { if (!closed) ready = true }
    fun isCurrent(token: Long): Boolean = token == generation && !closed
    fun beginPreRoll(): Long { preRollPending = true; return generation }
    fun finishPreRoll(token: Long): Boolean {
        if (token != generation) return false
        preRollPending = false
        return true
    }
    fun close() { generation++; ready = false; preRollPending = false; closed = true }
    fun reset() { generation++; ready = false; preRollPending = false; closed = false }
}

/**
 * Which wake cues may use VAD-based pre-roll rescue. A cue that itself contains
 * speech (a spoken "yes?") would be replayed to HA as the user's command, so a cue is
 * only eligible once an offline scan ([WakeCueSpeechScanner]) has found it speech-free.
 * The bundled non-verbal assets were verified against the same VAD ahead of time.
 */
internal object WakePreRollCuePolicy {
    private val speechFree = java.util.concurrent.ConcurrentHashMap<String, Boolean>().apply {
        put("asset:///sounds/wake_word_triggered.wav", true)
        put("asset:///sounds/continuous_prompt.wav", true)
    }
    private val scanning = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun supportsSpeechRescue(uri: String?): Boolean = uri != null && speechFree[uri] == true

    fun isKnown(uri: String): Boolean = speechFree.containsKey(uri)

    /** Claim the scan for [uri]; false when already known or another scan is running. */
    fun beginScan(uri: String): Boolean = !speechFree.containsKey(uri) && scanning.add(uri)

    /** Record a scan outcome. Null (undecodable / VAD unavailable) keeps the cue ineligible. */
    fun endScan(uri: String, isSpeechFree: Boolean?) {
        speechFree[uri] = isSpeechFree == true
        scanning.remove(uri)
    }

    /** Scan interrupted (scope cancelled): leave the cue unknown so a later wake retries. */
    fun abandonScan(uri: String) {
        speechFree.remove(uri)
        scanning.remove(uri)
    }
}
