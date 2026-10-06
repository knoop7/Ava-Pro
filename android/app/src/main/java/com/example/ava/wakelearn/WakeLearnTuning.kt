package com.example.ava.wakelearn

import android.content.Context
import android.content.SharedPreferences
import com.example.ava.settings.WakeWordEngine

/**
 * User-tunable knobs and status of on-device wake learning. Defaults are the measured
 * calibration; the settings page exposes them for advanced users and shows the last
 * training report per wake word. Values are held in volatile fields so the audio-thread
 * detectors read them without touching preferences.
 */
object WakeLearnTuning {
    private const val PREFS = "wake_learn_tuning"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_VETO_OFFSET = "veto_offset"
    private const val KEY_RETRAIN_EVERY = "retrain_every"
    private const val KEY_MIN_RETENTION = "min_positive_retention"
    private const val KEY_REPORT_PREFIX = "report_"
    private const val KEY_VETO_PREFIX = "vetoes_"
    private const val KEY_PUBLISHED_PREFIX = "published_"

    const val DEFAULT_VETO_OFFSET = 0f
    const val MIN_VETO_OFFSET = -0.25f
    const val MAX_VETO_OFFSET = 0.10f
    const val DEFAULT_RETRAIN_EVERY = 5
    const val MIN_RETRAIN_EVERY = 2
    const val MAX_RETRAIN_EVERY = 20
    const val DEFAULT_MIN_RETENTION = OnDeviceVerifierTrainer.MIN_POSITIVE_RETENTION
    const val MIN_MIN_RETENTION = 0.80f
    const val MAX_MIN_RETENTION = 0.98f

    @Volatile var enabled: Boolean = true; private set
    /** Added to every verifier veto threshold (negative = more permissive). */
    @Volatile var vetoOffset: Float = DEFAULT_VETO_OFFSET; private set
    @Volatile var retrainEvery: Int = DEFAULT_RETRAIN_EVERY; private set
    @Volatile var minPositiveRetention: Float = DEFAULT_MIN_RETENTION; private set

    private var prefs: SharedPreferences? = null

    fun load(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        enabled = p.getBoolean(KEY_ENABLED, true)
        vetoOffset = p.getFloat(KEY_VETO_OFFSET, DEFAULT_VETO_OFFSET).coerceIn(MIN_VETO_OFFSET, MAX_VETO_OFFSET)
        retrainEvery = p.getInt(KEY_RETRAIN_EVERY, DEFAULT_RETRAIN_EVERY).coerceIn(MIN_RETRAIN_EVERY, MAX_RETRAIN_EVERY)
        minPositiveRetention = p.getFloat(KEY_MIN_RETENTION, DEFAULT_MIN_RETENTION)
            .coerceIn(MIN_MIN_RETENTION, MAX_MIN_RETENTION)
    }

    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        edit(context) { putBoolean(KEY_ENABLED, value) }
    }

    fun setVetoOffset(context: Context, value: Float) {
        vetoOffset = value.coerceIn(MIN_VETO_OFFSET, MAX_VETO_OFFSET)
        edit(context) { putFloat(KEY_VETO_OFFSET, vetoOffset) }
    }

    fun setRetrainEvery(context: Context, value: Int) {
        retrainEvery = value.coerceIn(MIN_RETRAIN_EVERY, MAX_RETRAIN_EVERY)
        edit(context) { putInt(KEY_RETRAIN_EVERY, retrainEvery) }
    }

    fun setMinPositiveRetention(context: Context, value: Float) {
        minPositiveRetention = value.coerceIn(MIN_MIN_RETENTION, MAX_MIN_RETENTION)
        edit(context) { putFloat(KEY_MIN_RETENTION, minPositiveRetention) }
    }

    fun resetToDefaults(context: Context) {
        setEnabled(context, true)
        setVetoOffset(context, DEFAULT_VETO_OFFSET)
        setRetrainEvery(context, DEFAULT_RETRAIN_EVERY)
        setMinPositiveRetention(context, DEFAULT_MIN_RETENTION)
    }

    /** Level threshold plus the user's offset, kept inside the engine's valid range. */
    fun effectiveVetoThreshold(base: Float): Float = (base + vetoOffset).coerceIn(0.05f, 0.99f)

    /** Last training outcome for a wake word, kept for the settings page. */
    class TrainReport(
        val timestampMs: Long,
        val published: Boolean,
        val positives: Int,
        val negatives: Int,
        val cvPositivePass: Float,
        val cvNegativeVeto: Float,
        val priorNegativeVeto: Float,
    )

    fun recordReport(context: Context, engine: WakeWordEngine, id: String, report: TrainReport) {
        val p = prefs ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { prefs = it }
        val e = p.edit().putString(
            reportKey(engine, id),
            listOf(
                report.timestampMs, if (report.published) 1 else 0, report.positives, report.negatives,
                report.cvPositivePass, report.cvNegativeVeto, report.priorNegativeVeto,
            ).joinToString(","),
        )
        if (report.published) {
            val k = publishedKey(engine, id)
            e.putInt(k, p.getInt(k, 0) + 1)
        }
        e.apply()
        WakeLearnStore.notifyChanged()
    }

    /** How many personalized heads have been published for this wake word (its "version"). */
    fun publishedCount(context: Context, engine: WakeWordEngine, id: String): Int =
        (prefs ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
            .getInt(publishedKey(engine, id), 0)

    fun lastReport(context: Context, engine: WakeWordEngine, id: String): TrainReport? {
        val raw = (prefs ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
            .getString(reportKey(engine, id), null) ?: return null
        val f = raw.split(",")
        if (f.size != 7) return null
        return runCatching {
            TrainReport(
                f[0].toLong(), f[1] == "1", f[2].toInt(), f[3].toInt(),
                f[4].toFloat(), f[5].toFloat(), f[6].toFloat(),
            )
        }.getOrNull()
    }

    fun clearReports(context: Context) {
        val p = prefs ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val e = p.edit()
        for (k in p.all.keys) {
            if (k.startsWith(KEY_REPORT_PREFIX) || k.startsWith(KEY_VETO_PREFIX) || k.startsWith(KEY_PUBLISHED_PREFIX)) {
                e.remove(k)
            }
        }
        e.apply()
        WakeLearnStore.notifyChanged()
    }

    /**
     * A verifier head just stopped a false wake. Called from the detector thread after
     * [load]; before that (no satellite yet) there is nothing to veto, so it is a no-op.
     */
    fun recordVeto(engine: WakeWordEngine, id: String) {
        val p = prefs ?: return
        val key = vetoKey(engine, id)
        p.edit().putInt(key, p.getInt(key, 0) + 1).apply()
        WakeLearnStore.notifyChanged()
    }

    /** False wakes the verifier has vetoed for this wake word since the last reset. */
    fun vetoCount(context: Context, engine: WakeWordEngine, id: String): Int =
        (prefs ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
            .getInt(vetoKey(engine, id), 0)

    private fun reportKey(engine: WakeWordEngine, id: String) = "$KEY_REPORT_PREFIX${engine.name}_$id"
    private fun vetoKey(engine: WakeWordEngine, id: String) = "$KEY_VETO_PREFIX${engine.name}_$id"
    private fun publishedKey(engine: WakeWordEngine, id: String) = "$KEY_PUBLISHED_PREFIX${engine.name}_$id"

    private fun edit(context: Context, block: SharedPreferences.Editor.() -> Unit) {
        val p = prefs ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { prefs = it }
        p.edit().apply(block).apply()
    }
}
