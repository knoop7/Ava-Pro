package com.example.ava.widgets

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.ava.R
import com.example.ava.services.VoiceSatelliteService
import java.util.Locale

/** One recorded reading of the configured entity. */
data class AvaSensorSample(val at: Long, val value: Float)

/**
 * Everything one sensor widget instance knows.
 *
 * [label], [unit] and [value] are a persisted snapshot of the last push from Home
 * Assistant, so the card still renders correctly after a reboot or while the satellite
 * is down — the live caches are in-memory only.
 */
data class AvaSensorConfig(
    val entityId: String,
    val label: String,
    val unit: String,
    val deviceClass: String,
    val value: String,
    val samples: List<AvaSensorSample>,
) {
    val configured: Boolean get() = entityId.isNotEmpty()
}

/**
 * Per-instance store for the sensor widget.
 *
 * The wave is drawn from [samples], which this store accumulates itself. Home Assistant
 * reaches Ava over the ESPHome protocol, which only ever pushes *current* state — there
 * is no history call on that channel and no stored HA token to reach the REST API with.
 * So a fresh card starts with a single point and grows one sample per
 * [SAMPLE_INTERVAL_MS] until it holds [CAPACITY] of them.
 */
object AvaSensorWidgetStore {

    private const val PREFS = "ava_sensor_widgets"

    /** [CAPACITY] samples spaced [SAMPLE_INTERVAL_MS] apart cover about eight hours. */
    const val CAPACITY = 48
    const val SAMPLE_INTERVAL_MS = 10 * 60_000L

    private val ENTITY_ID = Regex("^[a-z][a-z0-9_]*\\.[a-z0-9_]+$")

    /** Accepts every domain — a sensor card is useful for switches and locks too. */
    fun normalizeEntityId(raw: String): String? {
        val id = raw.trim().lowercase(Locale.ROOT)
        return if (ENTITY_ID.matches(id)) id else null
    }

    fun read(context: Context, appWidgetId: Int): AvaSensorConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AvaSensorConfig(
            entityId = prefs.getString(key("entity", appWidgetId), "").orEmpty(),
            label = prefs.getString(key("label", appWidgetId), "").orEmpty(),
            unit = prefs.getString(key("unit", appWidgetId), "").orEmpty(),
            deviceClass = prefs.getString(key("dclass", appWidgetId), "").orEmpty(),
            value = prefs.getString(key("value", appWidgetId), "").orEmpty(),
            samples = decodeSamples(prefs.getString(key("samples", appWidgetId), "").orEmpty()),
        )
    }

    /** Picking a different entity invalidates the snapshot and the whole wave. */
    fun writeEntity(context: Context, appWidgetId: Int, entityId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key("entity", appWidgetId), entityId)
            .remove(key("label", appWidgetId))
            .remove(key("unit", appWidgetId))
            .remove(key("dclass", appWidgetId))
            .remove(key("value", appWidgetId))
            .remove(key("samples", appWidgetId))
            .apply()
    }

    /** Stores whichever pieces of the snapshot Home Assistant just pushed. */
    fun writeSnapshot(
        context: Context,
        appWidgetId: Int,
        value: String? = null,
        unit: String? = null,
        label: String? = null,
        deviceClass: String? = null,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            value?.let { putString(key("value", appWidgetId), it) }
            unit?.let { putString(key("unit", appWidgetId), it) }
            label?.let { putString(key("label", appWidgetId), it) }
            deviceClass?.let { putString(key("dclass", appWidgetId), it) }
        }.apply()
    }

    /**
     * Records [value] on the wave, dropping the oldest sample once full.
     *
     * Returns false when the previous sample is younger than [SAMPLE_INTERVAL_MS], so a
     * chatty entity cannot flood the buffer and collapse the visible window to minutes.
     * The very first sample always lands, otherwise a new card would stay empty.
     */
    fun appendSample(context: Context, appWidgetId: Int, value: Float): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = decodeSamples(prefs.getString(key("samples", appWidgetId), "").orEmpty())
        val now = System.currentTimeMillis()
        val last = existing.lastOrNull()
        if (last != null && now - last.at < SAMPLE_INTERVAL_MS) return false
        val next = (existing + AvaSensorSample(now, value)).takeLast(CAPACITY)
        prefs.edit()
            .putString(key("samples", appWidgetId), encodeSamples(next))
            .apply()
        return true
    }

    /**
     * Replaces the sample buffer with [samples] when the existing buffer is shorter.
     * Used to pre-populate the sparkline from HA history on first subscription.
     * Returns true when the buffer was actually updated.
     */
    fun backfillSamples(context: Context, appWidgetId: Int, samples: List<AvaSensorSample>): Boolean {
        if (samples.isEmpty()) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = decodeSamples(prefs.getString(key("samples", appWidgetId), "").orEmpty())
        if (existing.size >= samples.size) return false
        val trimmed = samples.takeLast(CAPACITY)
        prefs.edit()
            .putString(key("samples", appWidgetId), encodeSamples(trimmed))
            .apply()
        return true
    }

    fun delete(context: Context, appWidgetId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(key("entity", appWidgetId))
            .remove(key("label", appWidgetId))
            .remove(key("unit", appWidgetId))
            .remove(key("dclass", appWidgetId))
            .remove(key("value", appWidgetId))
            .remove(key("samples", appWidgetId))
            .apply()
    }

    /** Live widget ids, so a stale pref entry can never resurrect a subscription. */
    fun widgetIds(context: Context): IntArray = runCatching {
        AppWidgetManager.getInstance(context).getAppWidgetIds(
            ComponentName(context, AvaSensorWidgetProvider::class.java),
        )
    }.getOrNull() ?: IntArray(0)

    /** Every entity at least one placed card is showing. */
    fun entityIds(context: Context): Set<String> =
        widgetIds(context)
            .asSequence()
            .mapNotNull { read(context, it).entityId.ifEmpty { null } }
            .toSet()

    fun widgetIdsFor(context: Context, entityId: String): List<Int> =
        widgetIds(context).filter { read(context, it).entityId == entityId }

    private fun key(name: String, appWidgetId: Int) = "${name}_$appWidgetId"

    private fun encodeSamples(samples: List<AvaSensorSample>): String =
        samples.joinToString(",") { "${it.at}:${it.value}" }

    private fun decodeSamples(raw: String): List<AvaSensorSample> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(',').mapNotNull { part ->
            val at = part.substringBefore(':').toLongOrNull() ?: return@mapNotNull null
            val value = part.substringAfter(':', "").toFloatOrNull() ?: return@mapNotNull null
            AvaSensorSample(at, value)
        }
    }
}

/**
 * Per-instance entity picker. Used both by the system add-widget flow and by tapping a
 * placed card, which is the only way in — the card carries no other affordance.
 *
 * The ESPHome channel cannot enumerate Home Assistant's entity registry, so the entity id
 * is typed. Anything Ava already subscribes to is offered as a one-tap suggestion.
 */
class AvaSensorWidgetConfigureActivity : Activity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val current = AvaSensorWidgetStore.read(this, appWidgetId)

        val entityInput = EditText(this).apply {
            hint = getString(R.string.ava_widget_sensor_entity_hint)
            setText(current.entityId)
            setTextColor(Color.WHITE)
            setHintTextColor(HINT)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            textSize = 16f
        }
        val error = TextView(this).apply {
            setTextColor(0xFFF87171.toInt())
            textSize = 12f
            visibility = android.view.View.GONE
        }

        val save = Button(this).apply {
            text = getString(R.string.ava_widget_config_save)
            isAllCaps = false
            setOnClickListener {
                val entityId = AvaSensorWidgetStore
                    .normalizeEntityId(entityInput.text?.toString().orEmpty())
                if (entityId == null) {
                    error.text = getString(R.string.ava_widget_sensor_entity_invalid)
                    error.visibility = android.view.View.VISIBLE
                    return@setOnClickListener
                }
                if (entityId != current.entityId) {
                    AvaSensorWidgetStore.writeEntity(this@AvaSensorWidgetConfigureActivity, appWidgetId, entityId)
                }
                AvaSensorWidgets.onEntityChanged(this@AvaSensorWidgetConfigureActivity)
                AvaSensorWidgets.render(
                    this@AvaSensorWidgetConfigureActivity,
                    AppWidgetManager.getInstance(this@AvaSensorWidgetConfigureActivity),
                    appWidgetId,
                )
                setResult(
                    RESULT_OK,
                    Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                )
                finish()
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(24.dp, 24.dp, 24.dp, 24.dp)
            addView(
                TextView(this@AvaSensorWidgetConfigureActivity).apply {
                    text = getString(R.string.ava_widget_sensor_config_heading)
                    setTextColor(Color.WHITE)
                    textSize = 22f
                },
                row().apply { bottomMargin = 6.dp },
            )
            addView(
                TextView(this@AvaSensorWidgetConfigureActivity).apply {
                    text = getString(R.string.ava_widget_sensor_config_hint)
                    setTextColor(HINT)
                    textSize = 13f
                },
                row().apply { bottomMargin = 18.dp },
            )
            addView(entityInput, row())
            addView(error, row().apply { topMargin = 6.dp })
            suggestions().takeIf { it.isNotEmpty() }?.let { known ->
                addView(
                    TextView(this@AvaSensorWidgetConfigureActivity).apply {
                        text = getString(R.string.ava_widget_sensor_known_entities)
                        setTextColor(HINT)
                        textSize = 13f
                    },
                    row().apply { topMargin = 22.dp; bottomMargin = 4.dp },
                )
                known.forEach { entityId ->
                    addView(
                        TextView(this@AvaSensorWidgetConfigureActivity).apply {
                            text = entityId
                            setTextColor(0xFF7DD3FC.toInt())
                            textSize = 14f
                            setPadding(0, 9.dp, 0, 9.dp)
                            setOnClickListener {
                                entityInput.setText(entityId)
                                entityInput.setSelection(entityId.length)
                                error.visibility = android.view.View.GONE
                            }
                        },
                        row(),
                    )
                }
            }
            addView(save, row().apply { topMargin = 24.dp })
        }

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(0xFF111827.toInt())
                addView(
                    content,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
        )
    }

    /**
     * Entities Ava is already subscribed to. Not Home Assistant's full registry — the
     * ESPHome channel has no call for that — just whatever has pushed state so far.
     */
    private fun suggestions(): List<String> {
        val service = VoiceSatelliteService.getInstance() ?: return emptyList()
        return (service.getQuickEntityStates().keys + service.getSceneEntityStates().keys)
            .distinct()
            .sorted()
            .take(40)
    }

    private fun row() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        const val HINT = 0xFF94A3B8.toInt()
    }
}
