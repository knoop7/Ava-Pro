package com.example.ava.widgets

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import com.example.ava.R

data class AvaWidgetText(
    val title: String,
    val description: String,
    val textScale: Float,
)

object AvaWidgetTextStore {
    private const val PREFS = "ava_title_description_widgets"

    fun read(context: Context, appWidgetId: Int): AvaWidgetText {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val titleKey = "title_$appWidgetId"
        val descriptionKey = "description_$appWidgetId"
        return AvaWidgetText(
            title = if (prefs.contains(titleKey)) {
                prefs.getString(titleKey, "").orEmpty()
            } else {
                context.getString(R.string.ava_widget_default_title)
            },
            description = if (prefs.contains(descriptionKey)) {
                prefs.getString(descriptionKey, "").orEmpty()
            } else {
                context.getString(R.string.ava_widget_default_description)
            },
            textScale = prefs.getFloat("text_scale_$appWidgetId", 1f).coerceIn(0.7f, 1.8f),
        )
    }

    fun write(
        context: Context,
        appWidgetId: Int,
        title: String,
        description: String,
        textScale: Float,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("title_$appWidgetId", title)
            .putString("description_$appWidgetId", description)
            .putFloat("text_scale_$appWidgetId", textScale.coerceIn(0.7f, 1.8f))
            .apply()
    }

    fun delete(context: Context, appWidgetId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove("title_$appWidgetId")
            .remove("description_$appWidgetId")
            .remove("text_scale_$appWidgetId")
            .apply()
    }
}

/**
 * Per-instance editor. It is used both by the system add-widget flow and when
 * the user taps an existing Ava text widget.
 */
class AvaTitleDescriptionWidgetConfigureActivity : Activity() {

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

        val current = AvaWidgetTextStore.read(this, appWidgetId)
        val titleInput = EditText(this).apply {
            hint = getString(R.string.ava_widget_config_title_hint)
            setText(current.title)
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF94A3B8.toInt())
            setSingleLine(true)
            textSize = 16f
        }
        val descriptionInput = EditText(this).apply {
            hint = getString(R.string.ava_widget_config_description_hint)
            setText(current.description)
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF94A3B8.toInt())
            gravity = Gravity.START or Gravity.TOP
            minLines = 2
            maxLines = 4
            textSize = 16f
        }
        val scaleLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        val scaleSlider = SeekBar(this).apply {
            // 0...110 maps to 70%...180%; API 21 compatible (no min property).
            max = 110
            progress = ((current.textScale * 100f).toInt() - 70).coerceIn(0, max)
        }
        fun updateScaleLabel() {
            val percent = scaleSlider.progress + 70
            scaleLabel.text = getString(R.string.ava_widget_config_text_size, percent)
        }
        scaleSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateScaleLabel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        updateScaleLabel()
        val save = Button(this).apply {
            text = getString(R.string.ava_widget_config_save)
            isAllCaps = false
            setOnClickListener {
                AvaWidgetTextStore.write(
                    this@AvaTitleDescriptionWidgetConfigureActivity,
                    appWidgetId,
                    titleInput.text?.toString().orEmpty(),
                    descriptionInput.text?.toString().orEmpty(),
                    (scaleSlider.progress + 70) / 100f,
                )
                AvaTitleDescriptionWidgetProvider.render(
                    this@AvaTitleDescriptionWidgetConfigureActivity,
                    AppWidgetManager.getInstance(this@AvaTitleDescriptionWidgetConfigureActivity),
                    appWidgetId,
                )
                val result = Intent().putExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    appWidgetId,
                )
                setResult(RESULT_OK, result)
                finish()
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(24.dp, 24.dp, 24.dp, 24.dp)
            addView(
                TextView(this@AvaTitleDescriptionWidgetConfigureActivity).apply {
                    text = getString(R.string.ava_widget_config_heading)
                    setTextColor(Color.WHITE)
                    textSize = 22f
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = 20.dp },
            )
            addView(
                titleInput,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                descriptionInput,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 12.dp },
            )
            addView(
                scaleLabel,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 18.dp },
            )
            addView(
                scaleSlider,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                save,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 24.dp },
            )
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
            }
        )
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
