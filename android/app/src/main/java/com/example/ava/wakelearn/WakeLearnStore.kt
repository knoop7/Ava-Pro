package com.example.ava.wakelearn

import com.example.ava.settings.WakeWordEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-disk home of on-device wake learning, per engine and wake word id:
 *
 *   <root>/<engine>/<id>.samples      labelled classifier windows from real wakes
 *   <root>/<engine>/<id>_verifier.bin the head trained from them (LinearVerifierHead)
 *
 * The head file takes precedence over imported and bundled heads in the providers, so a
 * downloaded wake word with no factory calibration gains a verifier the moment enough
 * of its wakes have been labelled, and the bundled ok_nabu head becomes the prior that
 * the household's own data refines.
 */
class WakeLearnStore(private val root: File) {
    class Sample(val positive: Boolean, val timestampMs: Long, val x: FloatArray)

    fun headFile(engine: WakeWordEngine, id: String): File =
        dir(engine).resolve("${safe(id)}_verifier.bin")

    fun samplesFile(engine: WakeWordEngine, id: String): File =
        dir(engine).resolve("${safe(id)}.samples")

    fun readHead(engine: WakeWordEngine, id: String, expectedDims: Int? = null): LinearVerifierHead? {
        val f = headFile(engine, id)
        if (!f.isFile) return null
        return LinearVerifierHead.parse(runCatching { f.readBytes() }.getOrNull(), expectedDims)
    }

    fun writeHead(engine: WakeWordEngine, id: String, head: LinearVerifierHead) {
        val f = headFile(engine, id)
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(head.toBytes())
        if (!tmp.renameTo(f)) {
            f.writeBytes(head.toBytes())
            tmp.delete()
        }
        notifyChanged()
    }

    @Synchronized
    fun append(engine: WakeWordEngine, id: String, sample: Sample) {
        val f = samplesFile(engine, id)
        f.parentFile?.mkdirs()
        DataOutputStream(FileOutputStream(f, true).buffered()).use { out ->
            out.writeInt(if (sample.positive) 1 else 0)
            out.writeLong(sample.timestampMs)
            out.writeInt(sample.x.size)
            val buf = ByteBuffer.allocate(sample.x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (v in sample.x) buf.putFloat(v)
            out.write(buf.array())
        }
        trimIfNeeded(engine, id)
        notifyChanged()
    }

    @Synchronized
    fun load(engine: WakeWordEngine, id: String): List<Sample> {
        val f = samplesFile(engine, id)
        if (!f.isFile) return emptyList()
        val out = ArrayList<Sample>()
        runCatching {
            DataInputStream(f.inputStream().buffered()).use { inp ->
                while (true) {
                    val label = try { inp.readInt() } catch (_: java.io.EOFException) { break }
                    val ts = inp.readLong()
                    val dims = inp.readInt()
                    if (dims <= 0 || dims > MAX_DIMS) break
                    val raw = ByteArray(dims * 4)
                    inp.readFully(raw)
                    val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                    out.add(Sample(label == 1, ts, FloatArray(dims) { buf.getFloat() }))
                }
            }
        }
        return out
    }

    @Synchronized
    fun clear(engine: WakeWordEngine, id: String) {
        samplesFile(engine, id).delete()
        headFile(engine, id).delete()
        notifyChanged()
    }

    fun clearAll() {
        root.deleteRecursively()
        notifyChanged()
    }

    /** Keep the newest [MAX_PER_CLASS] of each label so the file and the fit stay bounded. */
    private fun trimIfNeeded(engine: WakeWordEngine, id: String) {
        val all = load(engine, id)
        val pos = all.filter { it.positive }
        val neg = all.filterNot { it.positive }
        if (pos.size <= MAX_PER_CLASS && neg.size <= MAX_PER_CLASS) return
        val kept = (pos.takeLast(MAX_PER_CLASS) + neg.takeLast(MAX_PER_CLASS)).sortedBy { it.timestampMs }
        val f = samplesFile(engine, id)
        val tmp = File(f.path + ".tmp")
        DataOutputStream(FileOutputStream(tmp).buffered()).use { out ->
            for (s in kept) {
                out.writeInt(if (s.positive) 1 else 0)
                out.writeLong(s.timestampMs)
                out.writeInt(s.x.size)
                val buf = ByteBuffer.allocate(s.x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (v in s.x) buf.putFloat(v)
                out.write(buf.array())
            }
        }
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    private fun dir(engine: WakeWordEngine): File = root.resolve(
        when (engine) {
            WakeWordEngine.OPEN_WAKE_WORD -> "open"
            WakeWordEngine.MICRO_WAKE_WORD -> "micro"
        },
    )

    private fun safe(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object {
        const val MAX_PER_CLASS = 300
        private const val MAX_DIMS = 1 shl 20

        private val revisionState = MutableStateFlow(0L)
        val revision: StateFlow<Long> = revisionState.asStateFlow()

        fun notifyChanged() {
            revisionState.update { it + 1 }
        }

        fun forContext(context: android.content.Context): WakeLearnStore =
            WakeLearnStore(File(context.filesDir, "wake-learn"))
    }
}
