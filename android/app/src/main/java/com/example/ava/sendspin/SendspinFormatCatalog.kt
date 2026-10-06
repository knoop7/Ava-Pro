package com.example.ava.sendspin

import org.json.JSONArray
import org.json.JSONObject

object SendspinFormatCatalog {
    data class FormatSpec(
        val key: String,
        val codec: String,
        val sampleRate: Int,
        val bitDepth: Int,
        val displayLabel: String,
    )

    private const val CHANNELS = 2
    private val SAMPLE_RATES = listOf(48_000, 44_100)
    private val PROBE_BIT_DEPTHS = listOf(16, 24, 32)

    private val flacFormats =
        listOf(
            FormatSpec("flac_44_16", "flac", 44_100, 16, "FLAC 44.1kHz 16bit"),
            FormatSpec("flac_48_16", "flac", 48_000, 16, "FLAC 48kHz 16bit"),
            FormatSpec("flac_44_24", "flac", 44_100, 24, "FLAC 44.1kHz 24bit"),
            FormatSpec("flac_48_24", "flac", 48_000, 24, "FLAC 48kHz 24bit"),
        )

    val opusFormat =
        FormatSpec("opus_48_16", "opus", 48_000, 16, "Opus 48kHz 16bit")

    @Volatile
    private var probedPcmSpecsCache: List<FormatSpec>? = null

    @Volatile
    private var flacDecodeAvailableCache: Boolean? = null

    fun invalidateProbeCache() {
        probedPcmSpecsCache = null
        flacDecodeAvailableCache = null
    }

    fun isFlacDecodeAvailable(): Boolean {
        flacDecodeAvailableCache?.let { return it }
        val available = SendspinFlacDecoder.isDecodeAvailable()
        flacDecodeAvailableCache = available
        return available
    }

    private fun isBitDepthSupported(sampleRate: Int, bitDepth: Int): Boolean =
        SendspinPcmFormatSupport.isPlaybackFormatSupported(sampleRate, CHANNELS, bitDepth)

    private fun probedBitDepthsForRate(sampleRate: Int): List<Int> {
        val supported = PROBE_BIT_DEPTHS.filter { isBitDepthSupported(sampleRate, it) }
        return if (supported.isEmpty()) listOf(16) else supported
    }

    private fun pcmKey(sampleRate: Int, bitDepth: Int): String {
        val ratePart = if (sampleRate == 44_100) "44" else (sampleRate / 1000).toString()
        return "pcm_${ratePart}_${bitDepth}"
    }

    private fun pcmDisplayLabel(sampleRate: Int, bitDepth: Int): String {
        val rateLabel = if (sampleRate == 44_100) "44.1kHz" else "${sampleRate / 1000}kHz"
        return "PCM $rateLabel ${bitDepth}bit"
    }

    fun probedPcmFormats(): List<FormatSpec> {
        probedPcmSpecsCache?.let { return it }
        val specs = mutableListOf<FormatSpec>()
        for (sampleRate in SAMPLE_RATES) {
            for (bitDepth in probedBitDepthsForRate(sampleRate)) {
                specs.add(
                    FormatSpec(
                        key = pcmKey(sampleRate, bitDepth),
                        codec = "pcm",
                        sampleRate = sampleRate,
                        bitDepth = bitDepth,
                        displayLabel = pcmDisplayLabel(sampleRate, bitDepth),
                    )
                )
            }
        }
        probedPcmSpecsCache = specs
        return specs
    }

    private fun formatSpecForKey(key: String): FormatSpec? {
        if (key == opusFormat.key) return opusFormat
        flacFormats.firstOrNull { it.key == key }?.let { return it }
        return probedPcmFormats().firstOrNull { it.key == key }
    }

    private fun helloFormats(preferredFormat: String): List<FormatSpec> {
        val normalized = normalizePreferredFormat(preferredFormat)
        if (normalized != "automatic") {
            return formatSpecForKey(normalized)?.let { listOf(it) } ?: emptyList()
        }
        val formats = mutableListOf<FormatSpec>()
        if (isFlacDecodeAvailable()) {
            formats.addAll(flacFormats)
        }
        formats.addAll(probedPcmFormats())
        return formats
    }

    fun isPcmPlaybackSupported(sampleRate: Int, bitDepth: Int): Boolean =
        probedPcmFormats().any { it.sampleRate == sampleRate && it.bitDepth == bitDepth }

    fun isFormatKeySupported(key: String): Boolean {
        if (key == "automatic" || key.isBlank()) return true
        return when (key) {
            opusFormat.key -> true
            else -> formatSpecForKey(key) != null
        }
    }

    fun normalizePreferredFormat(preferredFormat: String): String {
        if (preferredFormat == "automatic" || preferredFormat.isBlank()) return "automatic"
        return if (isFormatKeySupported(preferredFormat)) preferredFormat else "automatic"
    }

    fun isOpusManuallySelected(preferredFormat: String): Boolean =
        normalizePreferredFormat(preferredFormat) == opusFormat.key

    fun getPreferredSmoothFallbackSpec(): FormatSpec? =
        probedPcmFormats().firstOrNull { it.key == "pcm_48_16" }
            ?: probedPcmFormats().firstOrNull()

    fun getPreferredAutomaticFallbackSpec(): FormatSpec? =
        flacFormats.firstOrNull { isFlacDecodeAvailable() }
            ?: probedPcmFormats().firstOrNull { it.key == "pcm_48_16" }
            ?: probedPcmFormats().firstOrNull()

    fun buildStreamRequestFormatPayload(preferFlac: Boolean = false): JSONObject? {
        val spec = if (preferFlac) {
            getPreferredAutomaticFallbackSpec()
        } else {
            getPreferredSmoothFallbackSpec()
        } ?: return null
        return JSONObject().put("player", spec.toJson())
    }

    fun getSelectableFormatOptions(automaticLabel: String): List<Pair<String, String>> {
        val options = mutableListOf("automatic" to automaticLabel)
        if (isFlacDecodeAvailable()) {
            flacFormats.forEach { options.add(it.key to it.displayLabel) }
        }
        probedPcmFormats().forEach { options.add(it.key to it.displayLabel) }
        options.add(opusFormat.key to opusFormat.displayLabel)
        return options
    }

    fun buildSupportedFormatsJson(preferredFormat: String): JSONArray {
        val supportedFormats = JSONArray()
        helloFormats(preferredFormat).forEach { supportedFormats.put(it.toJson()) }
        return supportedFormats
    }

    fun buildPlayerSupportObject(bufferCapacity: Int, preferredFormat: String): JSONObject =
        JSONObject()
            .put("supported_formats", buildSupportedFormatsJson(preferredFormat))
            .put("buffer_capacity", bufferCapacity)
            .put("supported_commands", JSONArray().put("volume").put("mute"))

    private fun FormatSpec.toJson(): JSONObject =
        JSONObject()
            .put("codec", codec)
            .put("channels", CHANNELS)
            .put("sample_rate", sampleRate)
            .put("bit_depth", bitDepth)
}
