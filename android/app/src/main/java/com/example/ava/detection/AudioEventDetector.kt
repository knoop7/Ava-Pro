package com.example.ava.detection

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max

private const val TAG = "AudioEventDetector"
private const val MODEL_FILE = "models/audio_events.tflite"

private const val SAMPLE_RATE = 16000
private const val FRAME_LENGTH = 320          // 0.02s @ 16kHz
private const val FRAME_STRIDE = 160          // 0.01s @ 16kHz
private const val FFT_SIZE = 512
private const val NUM_FFT_BINS = FFT_SIZE / 2 + 1  // rfft bins (257), matches speechpy/EI
private const val NUM_FILTERS = 40
private const val NUM_FRAMES = 99             // floor((16000-320)/160)+1
private const val FEATURE_SIZE = NUM_FRAMES * NUM_FILTERS  // 3960
private const val NUM_CLASSES = 7
/**
 * Mel filter low edge. Edge Impulse speechpy `mfe()` forces low_frequency = 300 Hz for
 * MFE block implementation_version < 4 (see feature.hpp: `if (version<4 && low_frequency==0) low_frequency=300`).
 * Our FFT-bin mapping uses the version<4 formula (NUM_FFT_BINS+1), so we must match the 300 Hz floor.
 */
private const val LOW_FREQUENCY_HZ = 300f
/** Edge Impulse MFE default noise floor (Studio block). */
private const val NOISE_FLOOR_DB = -52f
private const val NOISE_FLOOR_NORM = (-NOISE_FLOOR_DB) + 12f

val AUDIO_EVENT_LABELS = arrayOf(
    "alarm", "baby_cry", "cough", "doorbell",
    "glass_breaking", "siren", "speech"
)

data class AudioEventResult(
    val label: String,
    val score: Float,
    val index: Int,
    val allScores: FloatArray,
    /** Mean MFE feature value (0–1); training medians: doorbell ~0.09, alarm ~0.44, siren ~0.32. */
    val featureMean: Float = 0f,
    /** Fraction of MFE bins > 0; training reportable events p50 ≥ 0.62. */
    val featureNonZeroRatio: Float = 0f,
)

private var interpreter: Interpreter? = null
private var isInitialized = false
private var inputScale: Float = 1f
private var inputZeroPoint: Int = 0
private var outputScale: Float = 1f
private var outputZeroPoint: Int = 0

private val melFilters: Array<FloatArray> by lazy { buildMelFilters() }

private val fftRe = FloatArray(FFT_SIZE)
private val fftIm = FloatArray(FFT_SIZE)
private val pcmSamples = FloatArray(SAMPLE_RATE)
private val mfeFeatures = FloatArray(FEATURE_SIZE)
private var inputBuf: ByteBuffer? = null
private var outputBuf: ByteBuffer? = null

fun initAudioEventDetector(context: Context): Boolean {
    if (isInitialized) return true
    return try {
        val model = loadAudioEventModelFile(context)
        val options = Interpreter.Options().setNumThreads(1)
        interpreter = Interpreter(model, options)
        val iq = interpreter!!.getInputTensor(0).quantizationParams()
        val oq = interpreter!!.getOutputTensor(0).quantizationParams()
        inputScale = iq.getScale()
        inputZeroPoint = iq.getZeroPoint()
        outputScale = oq.getScale()
        outputZeroPoint = oq.getZeroPoint()
        inputBuf = ByteBuffer.allocateDirect(FEATURE_SIZE).order(ByteOrder.nativeOrder())
        outputBuf = ByteBuffer.allocateDirect(NUM_CLASSES).order(ByteOrder.nativeOrder())
        isInitialized = true
        Log.i(TAG, "initialized: inScale=$inputScale inZP=$inputZeroPoint outScale=$outputScale outZP=$outputZeroPoint")
        true
    } catch (e: Exception) {
        Log.e(TAG, "init failed: ${e.message}")
        false
    }
}

fun closeAudioEventDetector() {
    interpreter?.close()
    interpreter = null
    inputBuf = null
    outputBuf = null
    isInitialized = false
}

/**
 * Run audio event detection on 1 second of 16kHz mono PCM16LE.
 * Returns sorted results (highest confidence first), or null on error.
 */
/** Window buffer from [AudioEventWindowAccumulator] is exclusive; no extra copy needed. */
fun detectAudioEvent(pcm16Le: ByteBuffer): List<AudioEventResult>? {
    val interp = interpreter ?: run {
        Log.e(TAG, "not initialized")
        return null
    }
    val pcm = pcm16Le.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    if (pcm.remaining() < SAMPLE_RATE * 2) {
        Log.w(TAG, "need ${SAMPLE_RATE * 2} bytes, got ${pcm.remaining()}")
        return null
    }

    val features = extractMfe(pcm) ?: return null
    var featSum = 0.0
    var featNonZero = 0
    for (v in features) {
        featSum += v
        if (v > 0f) featNonZero++
    }
    val featureMean = (featSum / features.size).toFloat()
    val featureNonZeroRatio = featNonZero.toFloat() / features.size

    val input = inputBuf ?: return null
    input.clear()
    for (i in 0 until FEATURE_SIZE) {
        val q = (features[i] / inputScale + inputZeroPoint).toInt()
            .coerceIn(-128, 127)
        input.put(q.toByte())
    }
    input.rewind()

    val output = outputBuf ?: return null
    output.clear()
    interp.run(input, output)
    output.rewind()

    // EI classifier head already applies softmax; dequantized values are probabilities (sum ≈ 1).
    // Do NOT softmax again — that collapses a 0.99 peak to ~0.31 on 7-class INT8 output.
    val scores = FloatArray(NUM_CLASSES)
    for (i in 0 until NUM_CLASSES) {
        val raw = output.get().toInt()
        scores[i] = (raw - outputZeroPoint) * outputScale
    }

    val results = AUDIO_EVENT_LABELS.mapIndexed { i, label ->
        AudioEventResult(
            label = label,
            score = scores[i],
            index = i,
            allScores = scores,
            featureMean = featureMean,
            featureNonZeroRatio = featureNonZeroRatio,
        )
    }.sortedByDescending { it.score }

    return results
}

/**
 * Extract Mel-Frequency Energy features matching the Edge Impulse MFE block (speechpy `mfe()`):
 * PCM16 normalized to [-1,1] -> rfft power spectrum (|X|^2 / fft_length) -> mel filterbank
 * (low edge 300 Hz) -> 10*log10 -> noise-floor normalize -> clip [0,1]. No preemphasis, no windowing.
 */
private fun extractMfe(pcm16Le: ByteBuffer): FloatArray? {
    val input = pcm16Le.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    val sampleCount = input.remaining() / 2
    if (sampleCount < SAMPLE_RATE) {
        Log.w(TAG, "need $SAMPLE_RATE samples, got $sampleCount")
        return null
    }

    val samples = pcmSamples
    for (i in 0 until SAMPLE_RATE) {
        samples[i] = input.short.toFloat() / 32768.0f
    }

    val features = mfeFeatures
    var featIdx = 0

    for (frame in 0 until NUM_FRAMES) {
        val start = frame * FRAME_STRIDE

        val re = fftRe
        val im = fftIm
        java.util.Arrays.fill(re, 0f)
        java.util.Arrays.fill(im, 0f)
        for (i in 0 until FRAME_LENGTH) {
            val sIdx = start + i
            val s = if (sIdx < SAMPLE_RATE) samples[sIdx] else 0f
            re[i] = s  // EI speechpy default: rectangular window (ones)
        }

        fft(re, im)

        for (m in 0 until NUM_FILTERS) {
            var energy = 0f
            val filter = melFilters[m]
            for (k in 0 until NUM_FFT_BINS) {
                // speechpy: power_spectrum = |rfft|^2 / fft_length
                val power = (re[k] * re[k] + im[k] * im[k]) / FFT_SIZE
                energy += power * filter[k]
            }
            energy = energy.coerceAtLeast(1e-30f)
            var mfe = 10f * log10(energy)
            mfe = (mfe - NOISE_FLOOR_DB) / NOISE_FLOOR_NORM
            features[featIdx++] = mfe.coerceIn(0f, 1f)
        }
    }

    // INT8 quantization to the model grid happens in detectAudioEvent() via the input
    // tensor's scale/zeroPoint — no extra 1/256 rounding here (that only adds noise).
    return features
}

/** In-place radix-2 Cooley-Tukey FFT. */
private fun fft(re: FloatArray, im: FloatArray) {
    val n = re.size
    // Bit reversal
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j or bit
        if (i < j) {
            var tmp = re[i]; re[i] = re[j]; re[j] = tmp
            tmp = im[i]; im[i] = im[j]; im[j] = tmp
        }
    }

    // Cooley-Tukey
    var len = 2
    while (len <= n) {
        val halfLen = len shr 1
        val angle = -2.0 * PI / len
        val wRe = cos(angle).toFloat()
        val wIm = kotlin.math.sin(angle).toFloat()
        var i = 0
        while (i < n) {
            var curWRe = 1f
            var curWIm = 0f
            for (k in 0 until halfLen) {
                val idxEven = i + k
                val idxOdd = i + k + halfLen
                val tRe = curWRe * re[idxOdd] - curWIm * im[idxOdd]
                val tIm = curWRe * im[idxOdd] + curWIm * re[idxOdd]
                re[idxOdd] = re[idxEven] - tRe
                im[idxOdd] = im[idxEven] - tIm
                re[idxEven] = re[idxEven] + tRe
                im[idxEven] = im[idxEven] + tIm
                val nextWRe = curWRe * wRe - curWIm * wIm
                curWIm = curWRe * wIm + curWIm * wRe
                curWRe = nextWRe
            }
            i += len
        }
        len = len shl 1
    }
}

private fun buildMelFilters(): Array<FloatArray> {
    // EI speechpy use_old_mels=true (implementation_version <= 3): fftpoints = num_fft_bins
    val filters = Array(NUM_FILTERS) { FloatArray(NUM_FFT_BINS) }
    val lowMel = hzToMel(LOW_FREQUENCY_HZ)
    val highMel = hzToMel(SAMPLE_RATE / 2f)
    val melPoints = FloatArray(NUM_FILTERS + 2)
    for (i in melPoints.indices) {
        melPoints[i] = lowMel + (highMel - lowMel) * i / (NUM_FILTERS + 1)
    }
    val hzPoints = FloatArray(NUM_FILTERS + 2)
    for (i in hzPoints.indices) {
        hzPoints[i] = melToHz(melPoints[i])
    }
    hzPoints[hzPoints.lastIndex] -= 0.001f  // EI bucket edge fix

    val binPoints = IntArray(NUM_FILTERS + 2)
    for (i in binPoints.indices) {
        binPoints[i] = kotlin.math.floor((NUM_FFT_BINS + 1) * hzPoints[i] / SAMPLE_RATE).toInt()
    }

    for (m in 0 until NUM_FILTERS) {
        val left = binPoints[m]
        val center = binPoints[m + 1]
        val right = binPoints[m + 2]
        for (k in left..right) {
            if (k >= NUM_FFT_BINS) break
            filters[m][k] = when {
                k <= center -> (k - left).toFloat() / max(center - left, 1)
                else -> (right - k).toFloat() / max(right - center, 1)
            }
        }
    }
    return filters
}

private fun hzToMel(hz: Float): Float = 2595f * log10(1f + hz / 700f)

private fun melToHz(mel: Float): Float = 700f * (Math.pow(10.0, mel / 2595.0).toFloat() - 1f)

private fun loadAudioEventModelFile(context: Context): MappedByteBuffer {
    context.assets.openFd(MODEL_FILE).use { fd ->
        FileInputStream(fd.fileDescriptor).use { input ->
            val channel = input.channel
            return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }
}
